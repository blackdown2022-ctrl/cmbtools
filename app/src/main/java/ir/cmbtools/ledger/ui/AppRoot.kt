package ir.cmbtools.ledger.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
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
import androidx.core.content.ContextCompat
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
import ir.cmbtools.ledger.core.BackgroundExecutionAccess
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
    NavItem("customers", "مشتری", Icons.Outlined.People, Icons.Filled.People),
    NavItem("invoices", "فاکتور", Icons.Outlined.Receipt, Icons.Filled.Receipt),
    NavItem("bank", "بانک", Icons.Outlined.AccountBalance, Icons.Filled.AccountBalance),
    NavItem("reports", "گزارش", Icons.Outlined.Assessment, Icons.Filled.Assessment),
    NavItem("settings", "تنظیمات", Icons.Outlined.Settings, Icons.Filled.Settings)
)

@Composable
private fun FirstLaunchPermissionsPrompt(vm: AppViewModel) {
    val context = LocalContext.current
    val promptPrefs = remember(context) { context.getSharedPreferences("first_run_permissions", Context.MODE_PRIVATE) }
    var showRationale by remember { mutableStateOf(false) }

    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val allowed = BackgroundExecutionAccess.isExempt(context)
        vm.report(
            if (allowed) "اجازهٔ اجرای پس‌زمینه فعال شد"
            else "اجازهٔ اجرای پس‌زمینه داده نشد؛ ممکن است همگام‌سازی خودکار با تأخیر انجام شود",
            isError = !allowed
        )
    }

    val sendSmsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.report(
            if (granted) "مجوز ارسال پیامک فعال شد"
            else "مجوز ارسال پیامک داده نشد؛ برای ارسال با سیم‌کارت از تنظیمات گوشی مجوز را فعال کنید",
            isError = !granted
        )
        val intent = BackgroundExecutionAccess.requestIntent(context)
        if (intent != null) {
            runCatching { backgroundLauncher.launch(intent) }.onFailure {
                runCatching { backgroundLauncher.launch(BackgroundExecutionAccess.settingsIntent()) }
                    .onFailure { vm.report("بازکردن تنظیمات اجرای پس‌زمینه ممکن نشد", isError = true) }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!promptPrefs.getBoolean("shown", false)) showRationale = true
    }

    fun requestBackgroundAccess() {
        val intent = BackgroundExecutionAccess.requestIntent(context)
        if (intent == null) {
            if (!BackgroundExecutionAccess.isExempt(context)) {
                runCatching { backgroundLauncher.launch(BackgroundExecutionAccess.settingsIntent()) }
                    .onFailure { vm.report("بازکردن تنظیمات اجرای پس‌زمینه ممکن نشد", isError = true) }
            }
            return
        }
        runCatching { backgroundLauncher.launch(intent) }.onFailure {
            runCatching { backgroundLauncher.launch(BackgroundExecutionAccess.settingsIntent()) }
                .onFailure { vm.report("بازکردن تنظیمات اجرای پس‌زمینه ممکن نشد", isError = true) }
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = {
                promptPrefs.edit().putBoolean("shown", true).apply()
                showRationale = false
            },
            title = { Text("اجازه‌های لازم برای پیامک و همگام‌سازی") },
            text = {
                Text(
                    "برای فرستادن فاکتور با سیم‌کارت، اجازهٔ ارسال پیامک لازم است. برای اینکه همگام‌سازی خودکار هنگام خاموش بودن صفحه کمتر متوقف شود، اندروید اجازهٔ جداگانهٔ فعالیت در پس‌زمینه را هم با تأیید شما می‌پرسد. برنامه نمی‌تواند این اجازه‌ها را خودش تأیید کند."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    promptPrefs.edit().putBoolean("shown", true).apply()
                    showRationale = false
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                    if (granted) requestBackgroundAccess()
                    else sendSmsLauncher.launch(Manifest.permission.SEND_SMS)
                }) { Text("درخواست اجازه‌ها") }
            },
            dismissButton = {
                TextButton(onClick = {
                    promptPrefs.edit().putBoolean("shown", true).apply()
                    showRationale = false
                }) { Text("بعداً") }
            }
        )
    }
}

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

    FirstLaunchPermissionsPrompt(vm)

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
 * جابه‌جایی بین سربرگ‌های اصلی. هر بار مقصد را از ریشه باز می‌کند؛ restoreState قبلی
 * می‌توانست stack ذخیره‌شدهٔ تنظیمات را هنگام انتخاب داشبورد دوباره برگرداند.
 */
private fun NavHostController.navigateToTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo("dashboard") { inclusive = false }
        launchSingleTop = true
    }
}
