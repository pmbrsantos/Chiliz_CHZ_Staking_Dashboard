package com.santoshi.chzstakingdashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

data class Wallet(val name: String, val address: String)

// Chili red, used in multiple places.
private val CHILI_RED = Color(0xFFE53935)

// Shared, process-wide engine so caches are shared across screens.
private val sharedEngine = CryptoEngine()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainAppScreen()
                }
            }
        }
    }
}

@Composable
fun MainAppScreen() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = { BottomNavigationBar(navController) }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            NavHost(navController = navController, startDestination = "dashboard") {
                composable("dashboard") { DashboardScreen() }
                composable("staking") { StakingScreen() }
                composable("about") { AboutScreen() }
            }
        }
    }
}

@Composable
fun BottomNavigationBar(navController: NavHostController) {
    val items = listOf(
        Pair("dashboard", Icons.Default.Home to "Dashboard"),
        Pair("staking", Icons.Default.Star to "Staking"),
        Pair("about", Icons.Default.Person to "About")
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar {
        items.forEach { (route, iconAndLabel) ->
            NavigationBarItem(
                icon = { Icon(iconAndLabel.first, contentDescription = iconAndLabel.second) },
                label = { Text(iconAndLabel.second) },
                selected = currentRoute == route,
                onClick = {
                    navController.navigate(route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}

// Helper to format numbers consistently regardless of device locale.
private fun Double.fmt(decimals: Int = 2): String =
    String.format(Locale.US, "%,.${decimals}f", this)

// ==========================================
// 1. STAKING SCREEN (Web3 WebView)
// ==========================================
@Composable
fun StakingScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext

    // Whitelisted schemes we are willing to hand off to external apps.
    val allowedExternalSchemes = remember {
        setOf("wc", "ethereum", "metamask", "trust", "rainbow", "coinbasewallet", "imtoken")
    }
    // Domains allowed inside the WebView itself.
    val allowedHosts = remember {
        setOf("app.chilizchain.com", "chilizchain.com", "www.chilizchain.com")
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                // Reduce attack surface.
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false

                webChromeClient = WebChromeClient()

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: return false
                        val scheme = uri.scheme?.lowercase() ?: return false

                        // Standard web navigation — only allow our whitelisted hosts in the WebView.
                        if (scheme == "http" || scheme == "https") {
                            val host = uri.host?.lowercase() ?: return true
                            return if (allowedHosts.any { host == it || host.endsWith(".$it") }) {
                                false // let WebView handle it
                            } else {
                                // External link — hand off to the system browser.
                                safeStartActivity(appContext, Intent(Intent.ACTION_VIEW, uri))
                                true
                            }
                        }

                        // Block the generic intent: scheme entirely to avoid intent injection.
                        if (scheme == "intent") {
                            Toast.makeText(appContext, "Blocked unsupported link.", Toast.LENGTH_SHORT).show()
                            return true
                        }

                        // Allow only whitelisted Web3 schemes out to external wallets.
                        if (scheme in allowedExternalSchemes) {
                            val opened = safeStartActivity(appContext, Intent(Intent.ACTION_VIEW, uri))
                            if (!opened) {
                                Toast.makeText(appContext, "No external wallet found to handle this request.", Toast.LENGTH_SHORT).show()
                            }
                            return true
                        }

                        // Unknown scheme — ignore.
                        return true
                    }
                }
                loadUrl("https://app.chilizchain.com/staking")
                webViewRef = this
            }
        },
        modifier = Modifier.fillMaxSize()
    )

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webViewRef = null
        }
    }
}

private fun safeStartActivity(context: Context, intent: Intent): Boolean {
    return try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w("CHZ", "startActivity failed: ${e.message}")
        false
    }
}

// ==========================================
// 2. ABOUT SCREEN (Donations)
// ==========================================
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val engine = remember { sharedEngine }

    var chzPriceUsd by remember { mutableStateOf(0.0) }
    var isLoadingPrice by remember { mutableStateOf(true) }
    var priceError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoadingPrice = true
        priceError = false
        val price = engine.getChzPrice("usd")
        chzPriceUsd = price
        priceError = price <= 0.0
        isLoadingPrice = false
    }

    fun triggerDonation(usdAmount: Double) {
        if (chzPriceUsd <= 0.0) {
            Toast.makeText(context, "CHZ price unavailable. Please try again later.", Toast.LENGTH_SHORT).show()
            return
        }

        // Use BigDecimal end-to-end to avoid float imprecision.
        val chzAmount = BigDecimal.valueOf(usdAmount)
            .divide(BigDecimal.valueOf(chzPriceUsd), 18, RoundingMode.HALF_UP)
        val weiAmount = chzAmount
            .multiply(BigDecimal.TEN.pow(18))
            .toBigInteger()
            .toString()

        val targetAddress = "0x850Ce7193b1B3e87f6bEc859eCDC6C321d24eb0E"
        val uriString = "ethereum:$targetAddress@88888?value=$weiAmount"

        val opened = safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(uriString)))
        if (!opened) {
            Toast.makeText(context, "No Web3 Wallet (like MetaMask) found on this device!", Toast.LENGTH_LONG).show()
        }
    }

    fun openLink(url: String) {
        val opened = safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        if (!opened) {
            Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("About the Author", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = CHILI_RED)
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Hi! Santoshi here! Early CHZ investor and member of the Chiliz Alliance since 2021, I built this dashboard to help the Chiliz community stake and track their CHZ staking rewards cleanly and natively. If you find it useful, consider supporting the project!",
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = Color.LightGray
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text("Contact me for bug reports, suggestions, etc.", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { openLink("mailto:santoshi.crypto@ethermail.io") }) { Text("Email") }
            OutlinedButton(onClick = { openLink("https://t.me/pmbrsantos") }) { Text("Telegram") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openLink("https://x.com/SantoshiOG") }) { Text("X (Twitter)") }
            OutlinedButton(onClick = { openLink("https://instagram.com/pmbrsantos") }) { Text("Instagram") }
            OutlinedButton(onClick = { openLink("https://tiktok.com/@pmbrsantos") }) { Text("TikTok") }
        }

        Spacer(modifier = Modifier.height(48.dp))
        Divider(color = Color.DarkGray, thickness = 1.dp)
        Spacer(modifier = Modifier.height(32.dp))

        Text("Support the Project", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("CHZ transfers via Web3 Wallet", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 24.dp))

        when {
            isLoadingPrice -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            priceError -> Text("CHZ price unavailable — check your connection.", color = Color.Red, fontSize = 14.sp)
            else -> {
                Button(
                    onClick = { triggerDonation(1.0) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("☕ Buy me a Coffee ($1 USD)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { triggerDonation(5.0) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("🍔 Buy me a Big Mac ($5 USD)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { triggerDonation(10.0) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
                ) {
                    Text("🍟 Buy me a McMenu ($10 USD)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ==========================================
// 3. DASHBOARD SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val gson = remember { Gson() }
    val prefs = remember { context.getSharedPreferences("NodePrefs", Context.MODE_PRIVATE) }

    val walletListType = remember { object : TypeToken<List<Wallet>>() {}.type }
    var walletList by remember {
        mutableStateOf(
            try {
                gson.fromJson<List<Wallet>>(prefs.getString("saved_wallets", "[]"), walletListType)
                    ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        )
    }

    var selectedWallet by remember { mutableStateOf(walletList.firstOrNull()) }
    var selectedCurrency by remember { mutableStateOf(prefs.getString("currency", "usd") ?: "usd") }
    val fiatSymbol = if (selectedCurrency == "usd") "$" else "€"

    var expanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showAllTimeYieldDialog by remember { mutableStateOf(false) }
    var showEstApyDialog by remember { mutableStateOf(false) }

    var newWalletName by remember { mutableStateOf("") }
    var newWalletAddress by remember { mutableStateOf("") }

    var availableBalance by remember { mutableStateOf(0.0) }
    var bondedBalance by remember { mutableStateOf(0.0) }
    var allTimeRewards by remember { mutableStateOf(0.0) }
    var apy by remember { mutableStateOf(0.0) }
    var chzPrice by remember { mutableStateOf(0.0) }
    var accountAgeYears by remember { mutableStateOf(1.0) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val engine = remember { sharedEngine }

    val totalBalanceChz = bondedBalance + availableBalance
    val totalBalanceFiat = totalBalanceChz * chzPrice

    // Single source of truth for loading wallet data.
    suspend fun loadForWallet(wallet: Wallet, currency: String) {
        isLoading = true
        loadError = null
        try {
            // Use snapshot to avoid two serial calls to the staking API.
            val snapshot = engine.getStakingSnapshot(wallet.address)
            availableBalance = engine.getAvailableBalance(wallet.address)
            bondedBalance = snapshot.staked
            allTimeRewards = snapshot.rewards
            chzPrice = engine.getChzPrice(currency)
            apy = engine.getLiveAPY()
            accountAgeYears = engine.getAccountAgeInYears(wallet.address)

            if (chzPrice <= 0.0) {
                loadError = "Could not fetch CHZ price. Fiat values may be inaccurate."
            }
        } catch (e: Exception) {
            loadError = "Failed to load wallet data: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(selectedWallet, selectedCurrency) {
        val wallet = selectedWallet
        if (wallet != null) {
            loadForWallet(wallet, selectedCurrency)
        } else {
            availableBalance = 0.0
            bondedBalance = 0.0
            allTimeRewards = 0.0
            apy = 0.0
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add New Wallet") },
            text = {
                Column {
                    OutlinedTextField(value = newWalletName, onValueChange = { newWalletName = it }, label = { Text("Wallet Name/Alias") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newWalletAddress, onValueChange = { newWalletAddress = it }, label = { Text("CHZ Address (0x...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    val trimmedName = newWalletName.trim()
                    val trimmedAddr = newWalletAddress.trim()
                    val looksLikeAddress = trimmedAddr.startsWith("0x") && trimmedAddr.length == 42
                    if (trimmedName.isNotBlank() && looksLikeAddress) {
                        val newWallet = Wallet(trimmedName, trimmedAddr)
                        val updatedList = walletList + newWallet
                        walletList = updatedList
                        selectedWallet = newWallet
                        prefs.edit().putString("saved_wallets", gson.toJson(updatedList)).apply()
                        newWalletName = ""
                        newWalletAddress = ""
                        showAddDialog = false
                    } else {
                        Toast.makeText(context, "Please enter a name and a valid 0x address.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }

    if (showAllTimeYieldDialog) {
        AlertDialog(
            onDismissRequest = { showAllTimeYieldDialog = false },
            title = { Text("About All-Time Yield") },
            text = {
                Text("Because Chiliz auto-compounds, this percentage is calculated by dividing your All-Time Rewards by your estimated Original Principal (Current Staked Balance minus All-Time Rewards).\n\nPlease note: If you have unstaked tokens in the past, this estimation may appear skewed.")
            },
            confirmButton = { TextButton(onClick = { showAllTimeYieldDialog = false }) { Text("Got It") } }
        )
    }

    if (showEstApyDialog) {
        AlertDialog(
            onDismissRequest = { showEstApyDialog = false },
            title = { Text("About Est. All-Time APY") },
            text = {
                Text("This is calculated by dividing your All-Time Yield by the number of years since your first network transaction.\n\nThis represents your personal historical annual performance, not the current live network rate.")
            },
            confirmButton = { TextButton(onClick = { showEstApyDialog = false }) { Text("Got It") } }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Chiliz (CHZ) Staking Dashboard",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Start).padding(top = 16.dp, bottom = 24.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = selectedWallet?.name ?: "Select a Wallet",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Active Wallet") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    walletList.forEach { wallet ->
                        DropdownMenuItem(
                            text = { Text("${wallet.name} (${wallet.address.take(8)}...)") },
                            onClick = { selectedWallet = wallet; expanded = false }
                        )
                    }
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text("➕ Add New Wallet", color = CHILI_RED, fontWeight = FontWeight.Bold) },
                        onClick = { expanded = false; showAddDialog = true }
                    )
                    if (selectedWallet != null) {
                        DropdownMenuItem(
                            text = { Text("🗑️ Delete Current Wallet", color = Color.Red, fontWeight = FontWeight.Bold) },
                            onClick = {
                                selectedWallet?.let { walletToRemove ->
                                    val updatedList = walletList.filter { it.address != walletToRemove.address }
                                    walletList = updatedList
                                    prefs.edit().putString("saved_wallets", gson.toJson(updatedList)).apply()
                                    selectedWallet = updatedList.firstOrNull()
                                    expanded = false
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = {
                    val newCurrency = if (selectedCurrency == "usd") "eur" else "usd"
                    selectedCurrency = newCurrency
                    prefs.edit().putString("currency", newCurrency).apply()
                    // The LaunchedEffect will pick up the currency change and refresh the price.
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CHILI_RED)
            ) {
                Text(if (selectedCurrency == "usd") "USD" else "EUR", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val wallet = selectedWallet ?: return@Button
                coroutineScope.launch { loadForWallet(wallet, selectedCurrency) }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedWallet != null && !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = CHILI_RED)
        ) { Text(if (isLoading) "Refreshing Data..." else "Refresh Dashboard") }

        loadError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = Color.Red, fontSize = 12.sp, textAlign = TextAlign.Center)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TOTAL BALANCE", color = Color.Gray, fontSize = 12.sp)
            Text("${totalBalanceChz.fmt()} CHZ", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "$fiatSymbol${totalBalanceFiat.fmt()} ${selectedCurrency.uppercase()}",
                color = CHILI_RED,
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                GridItem(
                    "AVAILABLE BALANCE",
                    "${availableBalance.fmt()} CHZ",
                    "$fiatSymbol${(availableBalance * chzPrice).fmt()}"
                )
                Spacer(modifier = Modifier.height(24.dp))
                GridItem(
                    "ESTIMATED APR",
                    if (apy > 0.0) "${apy.fmt()}%" else "—",
                    if (apy > 0.0) "Live Network Rate" else "Unavailable"
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                GridItem(
                    "STAKED BALANCE",
                    "${bondedBalance.fmt()} CHZ",
                    "$fiatSymbol${(bondedBalance * chzPrice).fmt()}"
                )
                Spacer(modifier = Modifier.height(24.dp))
                GridItem(
                    "ALL-TIME REWARDS",
                    "${allTimeRewards.fmt()} CHZ",
                    "$fiatSymbol${(allTimeRewards * chzPrice).fmt()}"
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Divider(color = Color.DarkGray, thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        val principal = bondedBalance - allTimeRewards
        val allTimeYield = when {
            principal > 0 -> (allTimeRewards / principal) * 100.0
            bondedBalance > 0 -> (allTimeRewards / bondedBalance) * 100.0
            else -> 0.0
        }
        val estAllTimeApy = if (accountAgeYears > 0.0) allTimeYield / accountAgeYears else 0.0

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ALL-TIME YIELD", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = Color.Gray,
                        modifier = Modifier.size(14.dp).clickable { showAllTimeYieldDialog = true }
                    )
                }
                Text("${allTimeYield.fmt()}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text("Total ROI", color = CHILI_RED, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("EST. ALL-TIME APY", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = Color.Gray,
                        modifier = Modifier.size(14.dp).clickable { showEstApyDialog = true }
                    )
                }
                Text("${estAllTimeApy.fmt()}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text("Personal Average", color = CHILI_RED, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun GridItem(title: String, mainText: String, subText: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(title, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(mainText, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        if (subText.isNotEmpty()) {
            Text(subText, color = CHILI_RED, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}