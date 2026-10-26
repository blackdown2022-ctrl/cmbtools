package ir.cmbtools.ledger.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ir.cmbtools.ledger.AppContainer
import ir.cmbtools.ledger.LedgerApp
import ir.cmbtools.ledger.Notifications
import ir.cmbtools.ledger.ui.screens.BankSmsScreen
import ir.cmbtools.ledger.ui.screens.CustomerDetailScreen
import ir.cmbtools.ledger.ui.screens.CustomersScreen
import ir.cmbtools.ledger.ui.screens.DashboardScreen
import ir.cmbtools.ledger.ui.screens.ExpensesScreen
import ir.cmbtools.ledger.ui.screens.InvoiceDetailScreen
import ir.cmbtools.ledger.ui.screens.InvoicesScreen
import ir.cmbtools.ledger.ui.screens.PlansScreen
import ir.cmbtools.ledger.ui.screens.ReportsScreen
import ir.cmbtools.ledger.ui.screens.SettingsScreen

data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon
)

/**
 * سربرگ‌های اصلی برنامه. «تنظیمات» حالا یک سربرگ مستقل است (قبلاً فقط از آیکون
 * داشبورد یا انتهای گزارش‌ها باز می‌شد).
 */
private val navItems = listOf(
    NavItem("dashboard", "داشبورد", Icons.Outlined.Dashboard, Icons.Filled.Dashboard),
    NavItem("customers", "مشتریان", Icons.Outlined.People, Icons.Filled.People),
    NavItem("invoices", "فاکتور", Icons.Outlined.Receipt, Icons.Filled.Receipt),
    NavItem("bank", "بانک", Icons.Outlined.AccountBalance, Icons.Filled.AccountBalance),
    NavItem("reports", "گزارش", Icons.Outlined.Assessment, Icons.Filled.Assessment),
    NavItem("settings", "تنظیمات", Icons.Outlined.Settings, Icons.Filled.Settings)
)

@Composable
fun AppRoot(container: AppContainer, notifications: Notifications) {
    val app = LocalContext.current.applicationContext as LedgerApp
    val vm: AppViewModel = viewModel(
        factory = viewModelFactory {
            initializer { AppViewModel(app) }
        }
    )

    val settings by vm.settings.collectAsStateWithLifecycle()
    var unlocked by remember { mutableStateOf(false) }

    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val event by vm.events.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    // درخواست مجوزها یک‌بار در شروع
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )
    }

    LaunchedEffect(event) {
        event?.let {
            snackbar.showSnackbar(it.message)
            vm.clearEvent()
        }
    }

    val navIntent by vm.navIntent.collectAsStateWithLifecycle()
    LaunchedEffect(navIntent) {
        navIntent?.let {
            navController.navigate(it) { launchSingleTop = true }
            vm.consumeNavIntent()
        }
    }

    val locked = settings.lockEnabled && settings.lockPin.isNotBlank() && !unlocked
    if (locked) {
        LockScreen(
            correctPin = settings.lockPin,
            businessName = settings.businessName,
            onUnlock = { unlocked = true }
        )
        return
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // ریسپانسیو: روی نمایشگر پهن (تبلت/دسکتاپ) نوار کنار، روی گوشی نوار پایین
            val wide = maxWidth >= 600.dp
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = { if (!wide) BottomBar(navController) }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (wide) SideRail(navController)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(modifier = Modifier.widthIn(max = 980.dp).fillMaxSize()) {
                            AppNavHost(navController, vm)
                            if (busy) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * NavHost مشترک بین چیدمان گوشی و تبلت.
 * انیمیشن ورود/خروج خاموش است: جابه‌جایی بین سربرگ‌ها روی گوشی‌های ضعیف
 * کند به‌نظر می‌رسید (کاربر: «در جابجایی بین سربرگ‌ها کندی وجود دارد»).
 */
@Composable
private fun AppNavHost(navController: NavHostController, vm: AppViewModel) {
    NavHost(
        navController = navController,
        startDestination = "dashboard",
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        composable("dashboard") { DashboardScreen(vm, navController) }
        composable("customers") { CustomersScreen(vm, navController) }
        composable("customer/{id}") { entry ->
            CustomerDetailScreen(
                vm = vm,
                customerId = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L,
                nav = navController
            )
        }
        composable("invoices") { InvoicesScreen(vm, navController) }
        composable("invoice/{id}") { entry ->
            InvoiceDetailScreen(
                vm = vm,
                invoiceId = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L,
                nav = navController
            )
        }
        composable("bank") { BankSmsScreen(vm, navController) }
        composable("reports") { ReportsScreen(vm, navController) }
        composable("settings") { SettingsScreen(vm, navController) }
        composable("plans") { PlansScreen(vm, navController) }
        composable("expenses") { ExpensesScreen(vm, navController) }
    }
}

@Composable
private fun LockScreen(correctPin: String, businessName: String, onUnlock: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("🔒", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(businessName, style = MaterialTheme.typography.titleLarge)
            Text(
                "رمز چهاررقمی را وارد کنید",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.OutlinedTextField(
                value = pin,
                onValueChange = { input ->
                    pin = input.filter { it.isDigit() }.take(4)
                    error = false
                    if (pin.length == 4) {
                        if (pin == correctPin) onUnlock() else error = true
                    }
                },
                singleLine = true,
                isError = error,
                textStyle = MaterialTheme.typography.headlineSmall,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                )
            )
            if (error) {
                Spacer(Modifier.height(6.dp))
                Text("رمز اشتباه است", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination?.route
    val mainRoutes = navItems.map { it.route }
    if (current !in mainRoutes) return

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        navItems.forEach { item ->
            val selected = current == item.route
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) navController.navigateToTab(item.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.height(24.dp)
                    )
                },
                label = {
                    Text(
                        item.label,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

@Composable
private fun SideRail(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination?.route
    val mainRoutes = navItems.map { it.route }
    if (current !in mainRoutes) return

    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Spacer(Modifier.height(12.dp))
        navItems.forEach { item ->
            val selected = current == item.route
            NavigationRailItem(
                selected = selected,
                onClick = { if (!selected) navController.navigateToTab(item.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.icon,
                        contentDescription = item.label
                    )
                },
                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                alwaysShowLabel = true
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

/**
 * جابه‌جایی بین سربرگ‌ها.
 * `saveState/restoreState` وضعیت اسکرول را نگه می‌دارد و `popUpTo` از انباشته‌شدن
 * صفحه‌ها جلوگیری می‌کند (عامل کندی تدریجی جابه‌جایی در نسخهٔ قبل).
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo("dashboard") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
