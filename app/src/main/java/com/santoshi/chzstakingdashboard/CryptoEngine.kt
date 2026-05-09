package com.santoshi.chzstakingdashboard

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import kotlin.math.exp

/**
 * Result wrapper so the UI can distinguish "zero" from "network failure".
 */
sealed class EngineResult<out T> {
    data class Success<T>(val value: T) : EngineResult<T>()
    data class Failure(val reason: String) : EngineResult<Nothing>()
}

data class StakingSnapshot(
    val staked: Double,
    val rewards: Double
)

class CryptoEngine {

    companion object {
        private const val TAG = "CryptoEngine"

        // 10^18 as BigDecimal — exact, no float imprecision.
        private val WEI_PER_CHZ: BigDecimal = BigDecimal.TEN.pow(18)

        // ---- Chiliz Chain Tokenomics 2.0 constants ----
        // Source: https://docs.chiliz.com/learn/about-chiliz-chain/tokenomics
        // Inflation formula:  y = 9.24 * e^(-0.25 * x) + 1.60   (x = years since genesis)
        // Floor: y = 1.88% once x > 13.
        private const val INFLATION_COEFFICIENT = 9.24
        private const val INFLATION_DECAY_RATE = 0.25
        private const val INFLATION_ASYMPTOTE = 1.60
        private const val INFLATION_FLOOR_PCT = 1.88
        private const val INFLATION_FLOOR_YEAR = 13.0

        // Share of annual inflation distributed to validators + delegators.
        private const val DELEGATOR_SHARE = 0.65

        // Dragon8 hard fork — tokenomics 2.0 went live on June 17, 2024.
        // Unix millis for 2024-06-17 00:00:00 UTC.
        private const val CHAIN_GENESIS_MILLIS = 1_718_582_400_000L

        private const val SECONDS_PER_YEAR = 365.25 * 24 * 60 * 60

        // Chiliz Chain block time is ~3 seconds.
        private const val BLOCK_TIME_SECONDS = 3.0
        private const val BLOCKS_PER_YEAR = SECONDS_PER_YEAR / BLOCK_TIME_SECONDS

        // Fallback supply table from the Pepper8 roadmap (year index -> supply at start of year, in CHZ).
        // Used only if we cannot fetch current supply from the chain.
        private val SUPPLY_BY_YEAR = doubleArrayOf(
            8_888_888_888.0,   // Y1
            9_670_766_153.0,   // Y2
            10_367_481_346.0,  // Y3
            10_985_867_079.0,  // Y4
            11_535_073_210.0,  // Y5
            12_025_002_873.0,  // Y6
            12_465_325_130.0,  // Y7
            12_864_922_473.0,  // Y8
            13_231_636_833.0,  // Y9
            13_572_204_456.0,  // Y10
            13_892_300_200.0,  // Y11
            14_196_637_909.0,  // Y12
            14_489_093_265.0,  // Y13
            14_772_829_365.0   // Y14+
        )

        // Sanity bounds on the final APR. Tokenomics doc says minimum ~5.72% (100% supply staked)
        // and ~11.44% @ 50% staked. We allow a wider band for safety.
        private const val APR_MIN_SANE = 2.0
        private const val APR_MAX_SANE = 40.0

        // Cache TTLs
        private const val STAKING_CACHE_MS = 10_000L
        private const val PRICE_CACHE_MS = 60_000L
        private const val APR_CACHE_MS = 5 * 60_000L   // APR barely moves; 5 min is plenty
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val nodeUrl = "https://rpc.chiliz.com"
    private val chilizApiUrl = "https://cc-api.chiliz.com"
    private val stakingApiUrl = "https://staking-api.chiliz.com"

    // --- Caches, protected by a Mutex ---
    private val stakingMutex = Mutex()
    private var cachedSnapshot: StakingSnapshot = StakingSnapshot(0.0, 0.0)
    private var lastFetchedAddress = ""
    private var lastFetchTime = 0L

    private val priceMutex = Mutex()
    private var cachedChzPriceUsd = 0.0
    private var cachedChzPriceEur = 0.0
    private var lastPriceFetchTime = 0L

    private val ageMutex = Mutex()
    private var cachedStakingTimestamp = 0L
    private var lastAgeFetchedAddress = ""

    private val aprMutex = Mutex()
    private var cachedApr = 0.0
    private var lastAprFetchTime = 0L

    // --------------------------------------------------
    // HTTP helpers
    // --------------------------------------------------
    private fun fetchJson(url: String): JsonObject? {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "CHZStakingDashboard/1.0")
            .build()
        return try {
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    JsonParser.parseString(response.body?.string() ?: "").asJsonObject
                } else {
                    Log.w(TAG, "GET $url -> HTTP ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        }
    }

    private fun postJsonRpc(method: String, paramsJson: String): JsonObject? {
        val payload = """{"jsonrpc":"2.0","method":"$method","params":$paramsJson,"id":1}"""
        val body = payload.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(nodeUrl).post(body).build()
        return try {
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    JsonParser.parseString(response.body?.string() ?: "").asJsonObject
                } else {
                    Log.w(TAG, "RPC $method -> HTTP ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "RPC $method failed: ${e.message}")
            null
        }
    }

    /**
     * Convert a wei string (decimal or hex with 0x prefix) to a Double CHZ value
     * using BigDecimal for precision.
     */
    private fun weiStringToChz(wei: String): Double {
        return try {
            val bi = if (wei.startsWith("0x") || wei.startsWith("0X")) {
                BigInteger(wei.removePrefix("0x").removePrefix("0X"), 16)
            } else {
                BigInteger(wei)
            }
            BigDecimal(bi).divide(WEI_PER_CHZ, 8, RoundingMode.HALF_UP).toDouble()
        } catch (e: Exception) {
            Log.w(TAG, "Bad wei value: $wei")
            0.0
        }
    }

    // --------------------------------------------------
    // Staking data (v2 API)
    // --------------------------------------------------
    private suspend fun refreshStakingData(address: String): StakingSnapshot = withContext(Dispatchers.IO) {
        val cleanAddress = address.lowercase()
        stakingMutex.withLock {
            val now = System.currentTimeMillis()
            if (cleanAddress == lastFetchedAddress && (now - lastFetchTime) < STAKING_CACHE_MS) {
                return@withLock cachedSnapshot
            }

            val stakerJson = fetchJson("$stakingApiUrl/stats/v2/staker/$cleanAddress")
            val rewardsJson = fetchJson("$stakingApiUrl/stats/v2/rewards/$cleanAddress")

            val staked = stakerJson?.get("totalStaked")?.asString
                ?.let { weiStringToChz(it) } ?: cachedSnapshot.staked

            val rewards = rewardsJson?.get("totalRewards")?.asString
                ?.let { weiStringToChz(it) } ?: cachedSnapshot.rewards

            val snapshot = StakingSnapshot(staked, rewards)
            cachedSnapshot = snapshot
            lastFetchedAddress = cleanAddress
            lastFetchTime = now
            snapshot
        }
    }

    suspend fun getStakingSnapshot(address: String): StakingSnapshot = refreshStakingData(address)
    suspend fun getBondedBalance(address: String): Double = refreshStakingData(address).staked
    suspend fun getAllTimeRewards(address: String): Double = refreshStakingData(address).rewards

    // --------------------------------------------------
    // Available on-chain balance
    // --------------------------------------------------
    suspend fun getAvailableBalance(address: String): Double = withContext(Dispatchers.IO) {
        val params = """["$address", "latest"]"""
        val json = postJsonRpc("eth_getBalance", params) ?: return@withContext 0.0
        val hex = json.get("result")?.asString ?: "0x0"
        weiStringToChz(hex)
    }

    // --------------------------------------------------
    // Price (CoinGecko)
    // --------------------------------------------------
    suspend fun getChzPrice(currency: String): Double = withContext(Dispatchers.IO) {
        val curr = currency.lowercase()
        priceMutex.withLock {
            val now = System.currentTimeMillis()
            val cachedForCurrency = if (curr == "usd") cachedChzPriceUsd else cachedChzPriceEur

            if ((now - lastPriceFetchTime) < PRICE_CACHE_MS && cachedForCurrency > 0.0) {
                return@withLock cachedForCurrency
            }

            val json = fetchJson(
                "https://api.coingecko.com/api/v3/simple/price?ids=chiliz&vs_currencies=usd,eur"
            )
            val chilizNode = json?.getAsJsonObject("chiliz")
            if (chilizNode != null) {
                cachedChzPriceUsd = chilizNode.get("usd")?.asDouble ?: cachedChzPriceUsd
                cachedChzPriceEur = chilizNode.get("eur")?.asDouble ?: cachedChzPriceEur
                lastPriceFetchTime = now
            }
            if (curr == "usd") cachedChzPriceUsd else cachedChzPriceEur
        }
    }

    // --------------------------------------------------
    // Live APR — derived from Chiliz Chain Tokenomics 2.0
    //
    // Pipeline:
    //   1. Compute years since chain genesis.
    //   2. Apply the published inflation formula -> current annual inflation %.
    //   3. Fetch current total supply (fallback: projected supply from doc's table).
    //   4. Fetch current total staked from the validator contract (fallback: 50% of supply).
    //   5. APR = (totalSupply * inflation% * 0.65) / totalStaked
    //
    // This gives the network-wide theoretical APR before any individual validator
    // commission. Individual users may receive slightly less depending on which
    // validator they delegate to.
    // --------------------------------------------------

    /** Public so UI / tests can inspect the published inflation curve. */
    fun currentAnnualInflationPct(nowMillis: Long = System.currentTimeMillis()): Double {
        val yearsSinceGenesis = (nowMillis - CHAIN_GENESIS_MILLIS) / 1000.0 / SECONDS_PER_YEAR
        if (yearsSinceGenesis <= 0.0) return INFLATION_COEFFICIENT + INFLATION_ASYMPTOTE
        if (yearsSinceGenesis > INFLATION_FLOOR_YEAR) return INFLATION_FLOOR_PCT
        return INFLATION_COEFFICIENT * exp(-INFLATION_DECAY_RATE * yearsSinceGenesis) + INFLATION_ASYMPTOTE
    }

    /** Linearly interpolated supply estimate from the doc's year-by-year table. */
    private fun projectedSupplyFromTable(nowMillis: Long = System.currentTimeMillis()): Double {
        val yearsSinceGenesis = (nowMillis - CHAIN_GENESIS_MILLIS) / 1000.0 / SECONDS_PER_YEAR
        val clamped = yearsSinceGenesis.coerceIn(0.0, (SUPPLY_BY_YEAR.size - 1).toDouble())
        val lowerIdx = clamped.toInt().coerceAtMost(SUPPLY_BY_YEAR.size - 1)
        val upperIdx = (lowerIdx + 1).coerceAtMost(SUPPLY_BY_YEAR.size - 1)
        val frac = clamped - lowerIdx
        return SUPPLY_BY_YEAR[lowerIdx] + (SUPPLY_BY_YEAR[upperIdx] - SUPPLY_BY_YEAR[lowerIdx]) * frac
    }

    /**
     * Bundle of live chain data from cc-api.chiliz.com/supply.
     * All fields are in whole CHZ (not wei).
     */
    private data class ChainSupplyInfo(
        val totalSupply: Double,
        val blockInflationChz: Double,   // CHZ minted per block
        val blockDeflationChz: Double    // CHZ burned per block (EIP-1559)
    )

    /**
     * Read live supply + per-block mint/burn from the Chiliz API.
     * Response shape (confirmed):
     *   {
     *     "block": "33222302",
     *     "totalSupply": "10307599916.53...",       // whole CHZ as decimal string
     *     "blockDeflation": "0.445385",             // CHZ burned per block
     *     "blockInflation": "66.278...",            // CHZ minted per block
     *     "totalBurnedSupply": "...",
     *     "totalIntroducedSupply": "..."
     *   }
     */
    private fun fetchChainSupplyInfo(): ChainSupplyInfo? {
        val json = fetchJson("$chilizApiUrl/supply") ?: return null
        return try {
            val supply = json.get("totalSupply")?.asString?.toDoubleOrNull() ?: return null
            val inflation = json.get("blockInflation")?.asString?.toDoubleOrNull() ?: return null
            val deflation = json.get("blockDeflation")?.asString?.toDoubleOrNull() ?: 0.0
            ChainSupplyInfo(supply, inflation, deflation)
        } catch (e: Exception) {
            Log.w(TAG, "fetchChainSupplyInfo parse error: ${e.message}")
            null
        }
    }

    /** Read total staked CHZ from the validator/staking system contract at 0x...1000. */
    private fun fetchTotalStaked(totalSupply: Double): Double {
        val params = """[{"to":"0x0000000000000000000000000000000000001000","data":"0xc172e24d"}, "latest"]"""
        val rpcJson = postJsonRpc("eth_call", params)
        val hex = rpcJson?.get("result")?.asString
        if (hex.isNullOrBlank() || hex == "0x" || hex == "0x0") {
            // Fallback: doc's median scenario assumes 50% of supply staked.
            return totalSupply * 0.5
        }
        val staked = weiStringToChz(hex)
        return if (staked > 0.0) staked else totalSupply * 0.5
    }

    suspend fun getLiveAPY(): Double = withContext(Dispatchers.IO) {
        aprMutex.withLock {
            val now = System.currentTimeMillis()
            if ((now - lastAprFetchTime) < APR_CACHE_MS && cachedApr > 0.0) {
                return@withLock cachedApr
            }

            try {
                // Pull live chain data. The API gives us actual per-block mint, which is
                // more accurate than the theoretical formula.
                val chain = fetchChainSupplyInfo()

                val totalSupply: Double
                val annualInflationChz: Double

                if (chain != null && chain.totalSupply > 0.0 && chain.blockInflationChz > 0.0) {
                    totalSupply = chain.totalSupply
                    annualInflationChz = chain.blockInflationChz * BLOCKS_PER_YEAR
                    Log.d(TAG, "APR: using live chain data (blockInflation=${chain.blockInflationChz})")
                } else {
                    // Fallback: derive from the published formula + projected supply table.
                    totalSupply = projectedSupplyFromTable(now)
                    val inflationPct = currentAnnualInflationPct(now)
                    annualInflationChz = totalSupply * (inflationPct / 100.0)
                    Log.d(TAG, "APR: chain data unavailable, using formula + table")
                }

                val totalStaked = fetchTotalStaked(totalSupply)

                if (totalSupply <= 0.0 || totalStaked <= 0.0) {
                    Log.w(TAG, "getLiveAPY: bad supply/staked values $totalSupply / $totalStaked")
                    return@withLock 0.0
                }

                val delegatorRewardsChz = annualInflationChz * DELEGATOR_SHARE
                val apr = (delegatorRewardsChz / totalStaked) * 100.0

                Log.d(
                    TAG,
                    "APR calc: annualInflation=${"%.0f".format(annualInflationChz)} CHZ " +
                            "supply=${"%.0f".format(totalSupply)} staked=${"%.0f".format(totalStaked)} " +
                            "-> APR=${"%.2f".format(apr)}%"
                )

                val sane = if (apr in APR_MIN_SANE..APR_MAX_SANE) apr else 0.0
                if (sane > 0.0) {
                    cachedApr = sane
                    lastAprFetchTime = now
                }
                sane
            } catch (e: Exception) {
                Log.w(TAG, "getLiveAPY failed: ${e.message}")
                0.0
            }
        }
    }

    // --------------------------------------------------
    // Account age (for estimated historical APY)
    // --------------------------------------------------
    suspend fun getAccountAgeInYears(address: String): Double = withContext(Dispatchers.IO) {
        val cleanAddress = address.lowercase()
        ageMutex.withLock {
            if (cleanAddress == lastAgeFetchedAddress && cachedStakingTimestamp > 0L) {
                return@withLock yearsSince(cachedStakingTimestamp)
            }

            val url = "https://api.routescan.io/v2/network/mainnet/evm/88888/etherscan/api" +
                    "?module=account&action=txlist&address=$cleanAddress" +
                    "&startblock=0&endblock=99999999&page=1&offset=100&sort=asc"

            val json = fetchJson(url) ?: return@withLock 1.0

            try {
                val result = json.getAsJsonArray("result") ?: return@withLock 1.0
                if (result.size() == 0) return@withLock 1.0

                val stakingContracts = setOf(
                    "0x0000000000000000000000000000000000007001",
                    "0x0000000000000000000000000000000000001000"
                )

                var stakingTimestamp: Long? = null
                for (i in 0 until result.size()) {
                    val tx = result.get(i).asJsonObject
                    val toAddress = tx.get("to")?.asString?.lowercase() ?: ""
                    if (toAddress in stakingContracts) {
                        stakingTimestamp = tx.get("timeStamp").asString.toLong() * 1000L
                        break
                    }
                }

                if (stakingTimestamp == null) {
                    stakingTimestamp = result.get(0).asJsonObject
                        .get("timeStamp").asString.toLong() * 1000L
                }

                cachedStakingTimestamp = stakingTimestamp
                lastAgeFetchedAddress = cleanAddress
                yearsSince(stakingTimestamp)
            } catch (e: Exception) {
                Log.w(TAG, "getAccountAgeInYears parse failed: ${e.message}")
                1.0
            }
        }
    }

    private fun yearsSince(tsMillis: Long): Double {
        val diff = System.currentTimeMillis() - tsMillis
        val years = diff / (1000.0 * 60 * 60 * 24 * 365.25)
        return if (years > 0.01) years else 1.0
    }
}