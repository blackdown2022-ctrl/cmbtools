package ir.cmbtools.ledger.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.BuildConfig
import ir.cmbtools.ledger.core.AppSettings
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.SmsGatewayProvider
import ir.cmbtools.ledger.core.SmsMode
import ir.cmbtools.ledger.export.ExportManager
import ir.cmbtools.ledger.panel.PanelUrl
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.AmountField
import ir.cmbtools.ledger.ui.common.DropdownField
import ir.cmbtools.ledger.ui.common.GradientHeader
import ir.cmbtools.ledger.ui.common.LabeledField
import ir.cmbtools.ledger.ui.common.SectionCard
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.PrimaryCyan
import ir.cmbtools.ledger.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: AppViewModel, nav: NavHostController) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config by vm.panelConfig.collectAsStateWithLifecycle()
    val panelStatus by vm.panelStatus.collectAsStateWithLifecycle()
    val panelChecks by vm.panelChecks.collectAsStateWithLifecycle()
    val accounts by vm.panelAccounts.collectAsStateWithLifecycle()
    val panelEndpoints by vm.panelEndpoints.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sendSmsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.updateSettings { it.copy(smsMode = SmsMode.SIM) }
        else vm.report("مجوز ارسال پیامک داده نشد؛ روش درگاه پیامکی فعال می‌ماند", isError = true)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setAutoSyncEnabled(true)
        if (!granted) vm.report("همگام‌سازی روشن شد؛ اما اعلان‌های آن در تنظیمات اندروید خاموش است")
    }

    // سه فیلد مستقل و صریح (بازطراحی ۰.۳.۱):
    //   آدرس کامل پنل  ← همان آدرس مرورگر (مسیر ادمین از آن استخراج می‌شود)
    //   کلید API      ← UUID ادمین
    //   پایهٔ لینک اشتراک ← آدرس جدا برای کانفیگ مشتری (خالی = خودکار از پنل)
    var panelUrl by remember { mutableStateOf(config?.panelUrl ?: "") }
    var apiKey by remember { mutableStateOf(config?.apiKey ?: "") }
    var subBase by remember { mutableStateOf(config?.subscriptionBase ?: "") }
    var panelName by remember { mutableStateOf(config?.name ?: "پنل اصلی") }
    var showEndpoints by remember { mutableStateOf(true) }

    var businessName by remember { mutableStateOf(settings.businessName) }
    var footer by remember { mutableStateOf(settings.invoiceFooter) }
    var template by remember { mutableStateOf(settings.invoiceTemplate) }
    var gatewayKey by remember { mutableStateOf(settings.gatewayApiKey) }
    var gatewaySender by remember { mutableStateOf(settings.gatewaySender) }
    var autoMatchMax by remember { mutableStateOf(settings.autoMatchMaxRial) }
    var cardNumber by remember { mutableStateOf(settings.paymentCardNumber) }
    var pricePerGb by remember { mutableStateOf(settings.pricePerGbRial) }
    var pin by remember { mutableStateOf("") }

    // هر گروه تنظیمات سربرگ (تب) جداگانه دارد تا صفحه شلوغ و تو‌در‌تو نباشد.
    var tab by remember { mutableStateOf(0) }

    LaunchedEffect(config) {
        config?.let {
            panelUrl = it.panelUrl
            apiKey = it.apiKey
            subBase = it.subscriptionBase
            panelName = it.name
            vm.refreshPanelEndpoints()
        }
    }

    val scope = rememberCoroutineScope()
    val saveBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            ExportManager(context, vm.appContainer()).saveBackup(uri) { result ->
                result.onSuccess { vm.report("فایل پشتیبان در دستگاه ذخیره شد") }
                    .onFailure { vm.report("ذخیرهٔ پشتیبان ناموفق: ${it.message}", isError = true) }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                ExportManager(context, vm.appContainer()).importBackup(uri)
                    .onSuccess {
                        vm.report(
                            "بازیابی کامل شد: " + Money.toPersian(it.customers.size.toString()) + " مشتری" +
                                (if (it.settings != null) " + تنظیمات" else " (فایل قدیمی، بدون تنظیمات)") +
                                (if (it.panelConfig != null) " + اتصال پنل" else "")
                        )
                    }
                    .onFailure { vm.report("بازیابی ناموفق: ${it.message}", isError = true) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(
            title = "تنظیمات",
            subtitle = "هر گروه تنظیمات، سربرگ جداگانه دارد"
        )

        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 6.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = PrimaryCyan
        ) {
            SettingsTab.entries.forEachIndexed { index, item ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ─────────────────────────────────────────────────────────────
            // اتصال پنل — بازطراحی کامل فیلدها (نسخهٔ ۰.۳.۱)
            // سه چیز که در هیدی‌فای از سه جای متفاوت می‌آید، اینجا سه فیلد جدا دارند:
            //   ۱) آدرس پنل (همان آدرس مرورگر) → مسیر ادمین و آدرس ساخت کاربر از آن ساخته می‌شود
            //   ۲) کلید API (UUID ادمین)
            //   ۳) پایهٔ لینک اشتراک (مستقل از مسیر ادمین)
            // ─────────────────────────────────────────────────────────────
            SectionCard(title = "اتصال به پنل هیدی‌فای", visible = tab == SettingsTab.PANEL.ordinal) {
                Text(
                    "برای اینکه ساخت کاربر و لینک اشتراک درست کار کند، این سه مورد را پر کنید. " +
                        "اپ با نسخه‌های ۱۱ تا ۱۴ پنل هیدی‌فای کار می‌کند و مسیر/سبک درست را خودش پیدا می‌کند؛ " +
                        "نتیجهٔ جستجو را پایین همین کارت می‌بینید.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(10.dp))

                LabeledField(
                    "۱) آدرس پنل (همان که در مرورگر باز می‌کنید)",
                    panelUrl,
                    { panelUrl = it },
                    placeholder = "https://panel.example.com/AbCdEf12"
                )
                Text(
                    "آدرس را از نوار آدرس مرورگر کپی کنید؛ اگر پنل را با /admin یا با لینک API باز کرده‌اید، " +
                        "همان را بچسبانید — اپ مسیر پروکسی ادمین را خودش جدا می‌کند.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )

                Spacer(Modifier.height(10.dp))
                LabeledField(
                    "۲) کلید API (UUID ادمین)",
                    apiKey,
                    { apiKey = it },
                    placeholder = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                )
                Text(
                    "در پنل: «ادمین‌ها» → ردیف حساب خودتان → کلید API. این کلید در هدر Hiddify-API-Key فرستاده می‌شود.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )

                Spacer(Modifier.height(10.dp))
                LabeledField(
                    "۳) پایهٔ لینک اشتراک مشتری (اختیاری)",
                    subBase,
                    { subBase = it },
                    placeholder = "https://panel.example.com/x7Yz12/"
                )
                Text(
                    "این آدرس با «آدرس پنل» فرق دارد و در هیدی‌فای از proxy_path_client ساخته می‌شود. " +
                        "اگر خالی بگذارید، بعد از تست اتصال خودکار از خود پنل خوانده می‌شود؛ " +
                        "لینک هر مشتری = همین آدرس + شناسهٔ کاربر + /",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { subBase = "" }) { Text("خالی کردن (خودکار از پنل)") }
                    if (subBase.isBlank()) {
                        TextButton(onClick = { vm.testPanel() }) { Text("خواندن از پنل الان") }
                    }
                }

                Spacer(Modifier.height(6.dp))
                LabeledField("نام پنل (برای خودتان)", panelName, { panelName = it })

                Spacer(Modifier.height(10.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val narrow = maxWidth < 360.dp
                    if (narrow) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.savePanelConfig(panelUrl, apiKey, subBase, panelName) { vm.testPanel() } },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("ذخیره و تست اتصال") }
                            OutlinedButton(onClick = { vm.probePanelCreate() }, modifier = Modifier.fillMaxWidth()) {
                                Text("تست ساخت کاربر در پنل")
                            }
                            OutlinedButton(onClick = { vm.scanPanel() }, modifier = Modifier.fillMaxWidth()) {
                                Text("جستجوی خودکار مسیر (نسخه ۱۱ تا ۱۴)")
                            }
                            OutlinedButton(onClick = { vm.diagnosePanel() }, modifier = Modifier.fillMaxWidth()) {
                                Text("عیب‌یابی کامل")
                            }
                            OutlinedButton(onClick = { vm.syncPanel() }, modifier = Modifier.fillMaxWidth()) {
                                Text("همگام‌سازی الان")
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { vm.savePanelConfig(panelUrl, apiKey, subBase, panelName) { vm.testPanel() } },
                                    modifier = Modifier.weight(1f)
                                ) { Text("ذخیره و تست") }
                                OutlinedButton(onClick = { vm.probePanelCreate() }, modifier = Modifier.weight(1f)) {
                                    Text("تست ساخت کاربر")
                                }
                            }
                            OutlinedButton(onClick = { vm.scanPanel() }, modifier = Modifier.fillMaxWidth()) {
                                Text("جستجوی خودکار مسیر و نسخه")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.diagnosePanel() }, modifier = Modifier.weight(1f)) {
                                    Text("عیب‌یابی")
                                }
                                OutlinedButton(onClick = { vm.syncPanel() }, modifier = Modifier.weight(1f)) {
                                    Text("همگام‌سازی")
                                }
                            }
                        }
                    }
                }

                if (panelStatus.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(panelStatus, color = PrimaryCyan, style = MaterialTheme.typography.labelMedium)
                }

                // ── آدرس‌های دقیق: چیزی پنهان نماند ──
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { showEndpoints = !showEndpoints }) {
                    Text(if (showEndpoints) "بستن آدرس‌های دقیق ▲" else "دیدن آدرس‌های دقیق ▼")
                }
                if (showEndpoints) {
                    // همین حالا و از همان چیزی که در فیلدهاست محاسبه می‌شود تا کاربر
                    // قبل از ذخیره هم ببیند دقیقاً چه آدرسی فرستاده می‌شود.
                    val parts = PanelUrl.normalize(panelUrl, "")
                    val api = if (parts.proxyPath.isBlank()) "" else parts.panelUrl + "/api/v2/"
                    val linkBase = PanelUrl.normalizeLinkBase(subBase)
                        .ifBlank { panelEndpoints.subscriptionBase }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        EndpointLine("آدرس پنل", if (parts.host.isBlank()) "—" else parts.panelUrl)
                        EndpointLine(
                            "مسیر پروکسی ادمین",
                            parts.proxyPath.ifBlank { "— (از آدرس پنل پیدا نشد)" }
                        )
                        EndpointLine("سبک API تشخیص‌داده‌شده", panelEndpoints.flavor.ifBlank { "خودکار" })
                        EndpointLine("نسخهٔ پنل", panelEndpoints.version.ifBlank { "?" })
                        EndpointLine("پایهٔ API", api.ifBlank { "—" })
                        // مسیر فعال همان است که اپ واقعاً استفاده می‌کند (بر اساس نسخهٔ تشخیص‌داده‌شده)
                        EndpointLine(
                            "ساخت کاربر (POST) — مسیر فعال",
                            panelEndpoints.createUserUrl.ifBlank {
                                if (api.isBlank()) "—" else api + "admin/users/"
                            }
                        )
                        EndpointLine(
                            "مسیرهای جایگزین (اگر نسخهٔ قدیمی باشد)",
                            if (api.isBlank()) "—" else api + "admin/user/  •  " + api + "v1/user/"
                        )
                        EndpointLine(
                            "پایهٔ لینک اشتراک",
                            linkBase.ifBlank { "— (بعد از تست، خودکار از پنل پر می‌شود)" }
                        )
                        if (linkBase.isNotBlank()) {
                            EndpointLine(
                                "نمونهٔ لینک مشتری",
                                linkBase + "00000000-0000-0000-0000-000000000000/"
                            )
                        }
                    }
                }

                if (panelChecks.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("نتیجهٔ بررسی:", style = MaterialTheme.typography.labelLarge)
                    panelChecks.forEach { check ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                when {
                                    check.ok -> "✅"
                                    check.note -> "▫️"   // «در این نسخه وجود ندارد» — خطا نیست
                                    else -> "❌"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(check.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    check.detail,
                                    color = if (check.ok || check.note) TextMuted else MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { vm.copyPanelReport() }, modifier = Modifier.fillMaxWidth()) {
                        Text("کپی گزارش برای ارسال")
                    }
                }

                if (config?.panelVersion?.isNotBlank() == true) {
                    Text(
                        "نسخهٔ پنل: " + Money.toPersian(config?.panelVersion ?: ""),
                        color = TextMuted, style = MaterialTheme.typography.labelMedium
                    )
                }
                Text(
                    "اکانت‌های همگام‌شده: " + Money.toPersian(accounts.size.toString()),
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            SectionCard(title = "همگام‌سازی و فاکتور خودکار", visible = tab == SettingsTab.SYNC.ordinal) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("همگام‌سازی خودکار با پنل")
                        Text(
                            "هر ${Money.toPersian(settings.autoSyncMinutes.toString())} دقیقه در سرویس فعال؛ پشتیبان اندروید هر ۱۵ دقیقه هم تلاش می‌کند",
                            color = TextMuted,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Switch(
                        checked = settings.autoSyncEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else vm.setAutoSyncEnabled(enabled)
                        }
                    )
                }
                if (settings.autoSyncEnabled && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    TextButton(onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("اجازهٔ نمایش اعلان همگام‌سازی را بده")
                    }
                }
                if ((config?.lastSyncAt ?: 0L) > 0L) {
                    Text(
                        "آخرین همگام‌سازی: " + Jalali.formatDateTime(config?.lastSyncAt ?: 0L),
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                vm.lastSync.collectAsStateWithLifecycle().value?.let { r ->
                    Text(r.message, color = PrimaryCyan, style = MaterialTheme.typography.labelMedium)
                }

                Spacer(Modifier.height(10.dp))
                SwitchLine(
                    title = "فاکتور خودکار برای کاربر جدید پنل",
                    hint = "کاربری که مستقیم در پنل ساخته می‌شود",
                    checked = settings.autoInvoiceOnNewAccount,
                    onChange = { v -> vm.updateSettings { it.copy(autoInvoiceOnNewAccount = v) } }
                )
                SwitchLine(
                    title = "فاکتور خودکار برای تمدید",
                    hint = "وقتی تاریخ انقضای اکانت جلو می‌رود",
                    checked = settings.autoInvoiceOnRenew,
                    onChange = { v -> vm.updateSettings { it.copy(autoInvoiceOnRenew = v) } }
                )
                SwitchLine(
                    title = "ارسال فوری پیامک فاکتور",
                    hint = "بلافاصله پس از صدور، به شمارهٔ ثبت‌شدهٔ مشتری",
                    checked = settings.autoSmsOnInvoice,
                    onChange = { v -> vm.updateSettings { it.copy(autoSmsOnInvoice = v) } }
                )
                SwitchLine(
                    title = "ساخت خودکار مشتری از نام کاربر پنل",
                    hint = "اگر مشتری متناظر پیدا نشد",
                    checked = settings.autoCreateCustomerFromPanel,
                    onChange = { v -> vm.updateSettings { it.copy(autoCreateCustomerFromPanel = v) } }
                )

                Spacer(Modifier.height(10.dp))
                LabeledField(
                    "شمارهٔ کارت برای درج در فاکتور",
                    cardNumber,
                    { cardNumber = it },
                    placeholder = "6037-9977-1234-5678"
                )
                Spacer(Modifier.height(8.dp))
                AmountField(
                    "قیمت هر گیگ (ریال) — برای بسته‌های بدون قیمت",
                    pricePerGb,
                    { pricePerGb = it }
                )
                Text(
                    "اگر حجم/روز اکانت با هیچ بسته‌ای در صفحهٔ «بسته‌ها» نخواند، فاکتور با این نرخ ساخته می‌شود. صفر = غیرفعال.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.updateSettings {
                                it.copy(
                                    paymentCardNumber = cardNumber.trim(),
                                    pricePerGbRial = pricePerGb
                                )
                            }
                            vm.report("تنظیمات فاکتور خودکار ذخیره شد")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("ذخیره") }
                    OutlinedButton(onClick = { vm.syncPanel() }, modifier = Modifier.weight(1f)) {
                        Text("همگام‌سازی الان")
                    }
                }
            }

            SectionCard(title = "پیامک", visible = tab == SettingsTab.SMS.ordinal) {
                DropdownField(
                    label = "روش ارسال",
                    items = listOf(SmsMode.GATEWAY, SmsMode.SIM),
                    selected = settings.smsMode,
                    itemLabel = { if (it == SmsMode.SIM) "سیم‌کارت گوشی (رایگان)" else "درگاه پیامکی (با گزارش تحویل)" },
                    onSelect = { mode ->
                        if (mode == SmsMode.SIM && ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                            sendSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                        } else vm.updateSettings { it.copy(smsMode = mode) }
                    }
                )
                if (settings.smsMode == SmsMode.GATEWAY) {
                    Spacer(Modifier.height(8.dp))
                    DropdownField(
                        label = "درگاه",
                        items = listOf(SmsGatewayProvider.KAVENEGAR, SmsGatewayProvider.SMS_IR),
                        selected = settings.gatewayProvider,
                        itemLabel = { SmsGatewayProvider.label(it) },
                        onSelect = { p -> vm.updateSettings { it.copy(gatewayProvider = p) } }
                    )
                    Spacer(Modifier.height(8.dp))
                    LabeledField("کلید API درگاه", gatewayKey, { gatewayKey = it })
                    Spacer(Modifier.height(8.dp))
                    LabeledField("شمارهٔ خط/فرستنده", gatewaySender, { gatewaySender = it })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "پیامک فاکتور با نام مشتری شروع می‌شود؛ نام فروشگاه و لینک اشتراک در آن درج نمی‌شود.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    "قالب پیامک فاکتور",
                    template,
                    { template = it },
                    singleLine = false,
                    maxLines = 12
                )
                Text(
                    "متغیرها: {customer} {invoiceNo} {datetime} {date} {package} {kind} " +
                        "{items} {total} {prevBalance} {totalDebt} {debt} {card} {footer}",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
                // پیش‌نمایش با همان متنی که الان در کادر است (قبل از ذخیره هم دیده می‌شود)
                Text("پیش‌نمایش پیامک:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        ir.cmbtools.ledger.sms.SmsTemplate.preview(settings, template, footer),
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(
                    onClick = {
                        template = AppSettings.DEFAULT_INVOICE_TEMPLATE
                        vm.updateSettings { it.copy(invoiceTemplate = AppSettings.DEFAULT_INVOICE_TEMPLATE) }
                        vm.report("قالب پیامک به حالت پیش‌فرض (ساختاریافته) بازگشت")
                    }
                ) { Text("بازگردانی قالب پیش‌فرض") }
                Spacer(Modifier.height(8.dp))
                LabeledField("امضای پیامک ({footer})", footer, { footer = it })
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.updateSettings {
                            it.copy(
                                gatewayApiKey = gatewayKey,
                                gatewaySender = gatewaySender,
                                invoiceTemplate = template,
                                invoiceFooter = footer
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ذخیرهٔ تنظیمات پیامک") }
            }

            SectionCard(title = "پیامک بانکی", visible = tab == SettingsTab.SMS.ordinal) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("تطبیق خودکار پرداخت")
                    Switch(
                        checked = settings.autoMatchBankSms,
                        onCheckedChange = { value -> vm.updateSettings { it.copy(autoMatchBankSms = value) } }
                    )
                }
                Spacer(Modifier.height(8.dp))
                AmountField("حداکثر مبلغ برای تطبیق خودکار", autoMatchMax, { autoMatchMax = it })
                Text(
                    "مبالغ بالاتر از این مقدار فقط با تأیید شما ثبت می‌شوند.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.updateSettings { it.copy(autoMatchMaxRial = autoMatchMax) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ذخیره") }
            }

            SectionCard(title = "کسب‌وکار و بسته‌ها", visible = tab == SettingsTab.BUSINESS.ordinal) {
                LabeledField("نام کسب‌وکار (عنوان داشبورد و صفحهٔ قفل)", businessName, { businessName = it })
                Text(
                    "این نام در پیامک فاکتور درج نمی‌شود؛ پیامک با نام مشتری شروع می‌شود.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.updateSettings { it.copy(businessName = businessName) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ذخیره") }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate("plans") }, modifier = Modifier.weight(1f)) {
                        Text("بسته‌ها")
                    }
                    OutlinedButton(onClick = { nav.navigate("expenses") }, modifier = Modifier.weight(1f)) {
                        Text("هزینه‌ها")
                    }
                }
            }

            SectionCard(title = "امنیت", visible = tab == SettingsTab.SECURITY.ordinal) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("قفل اپ با رمز")
                        Text(
                            "داده‌های مالی و کلید API روی دستگاه رمزنگاری‌شده‌اند",
                            color = TextMuted,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Switch(
                        checked = settings.lockEnabled,
                        onCheckedChange = { on ->
                            if (on && settings.lockPin.isBlank()) {
                                vm.updateSettings { it.copy(lockEnabled = true) }
                            } else {
                                vm.updateSettings { it.copy(lockEnabled = on) }
                            }
                        }
                    )
                }
                if (settings.lockEnabled) {
                    Spacer(Modifier.height(8.dp))
                    LabeledField(
                        if (settings.lockPin.isBlank()) "تعیین رمز (۴ رقم)" else "تغییر رمز (۴ رقم)",
                        pin,
                        { pin = it.filter { ch -> ch.isDigit() }.take(4) },
                        keyboardType = KeyboardType.NumberPassword
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.updateSettings { it.copy(lockPin = pin) } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = pin.length == 4
                    ) { Text("ذخیرهٔ رمز") }
                }
            }

            SectionCard(title = "پشتیبان‌گیری و بازیابی", visible = tab == SettingsTab.BACKUP.ordinal) {
                Text(
                    "این تنها جای پشتیبان‌گیری برنامه است. فایل JSON شامل همهٔ این‌ها است:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                listOf(
                    "مشتریان، بسته‌ها، فاکتورها و اقلام فاکتور",
                    "پرداخت‌ها، ثبت‌های دفتر، هزینه‌ها",
                    "تنظیمات اتصال پنل هیدی‌فای (دامنه، مسیر پروکسی، کلید API)",
                    "اکانت‌های همگام‌شدهٔ پنل",
                    "همهٔ تنظیمات برنامه: پیامک، قالب فاکتور، نام کسب‌وکار، شمارهٔ کارت، رقم‌بندی، همگام‌سازی خودکار، رمز قفل",
                    "پیامک‌های بانکی و تاریخچهٔ پیامک‌های ارسالی"
                ).forEach { line ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text("• ", color = PrimaryCyan, style = MaterialTheme.typography.bodyMedium)
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { saveBackupLauncher.launch("tiznet-backup.json") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ذخیرهٔ فایل پشتیبان در دستگاه") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        ExportManager(context, vm.appContainer()).exportBackup { r ->
                            r.onSuccess { vm.report("پشتیبان کامل آمادهٔ اشتراک‌گذاری است: " + it.name) }
                                .onFailure { vm.report("ساخت پشتیبان ناموفق: ${it.message}", isError = true) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("اشتراک‌گذاری فایل پشتیبان (JSON)") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("بازیابی از فایل پشتیبان") }
                Spacer(Modifier.height(6.dp))
                Text(
                    "بازیابی، داده‌های فعلی را با محتوای فایل جایگزین می‌کند (قبلش یک پشتیبان تازه بگیرید). " +
                        "فایل پشتیبان را در پوشه‌ای امن از دستگاه یا فضای شخصی نگه دارید؛ شامل اطلاعات مشتری و تنظیمات محرمانهٔ پنل است و آن را عمومی نفرستید.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            SectionCard(title = "درباره", visible = tab == SettingsTab.ABOUT.ordinal) {
                Text(
                    "تیزنت — نسخهٔ " + Money.toPersian(BuildConfig.VERSION_NAME),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "شمارهٔ ساخت: " + Money.toPersian(BuildConfig.VERSION_CODE.toString()) +
                        "  •  اندروید " + Money.toPersian(android.os.Build.VERSION.RELEASE ?: ""),
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    "اپ شخصی حسابداری + مدیریت پنل هیدی‌فای ۱۱ تا ۱۴. ساخته‌شده برای استفادهٔ تک‌کاربره روی گوشی خودتان.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://hiddify.com"))
                    context.startActivity(intent)
                }) { Text("مستندات هیدی‌فای", color = AccentGreen) }
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

/** سوییچ با عنوان و توضیح فارسی (در این صفحه چند بار لازم است) */
@Composable
private fun SwitchLine(
    title: String,
    hint: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(hint, color = TextMuted, style = MaterialTheme.typography.labelMedium)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}


/**
 * یک خط «برچسب + آدرس» برای نمایش آدرس‌های دقیق پنل.
 * آدرس‌ها لاتین‌اند و جدا از برچسب فارسی نمایش داده می‌شوند تا قاطی نشوند.
 */
@Composable
private fun EndpointLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = TextMuted, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}


/**
 * سربرگ‌های صفحهٔ تنظیمات.
 *
 * هر گروه تنظیمات یک سربرگ جداگانه دارد تا کاربر لازم نباشد برای رسیدن به
 * «پیامک» از وسط «پنل» عبور کند. ترتیب `ordinal` با `when`/`if`های بالا یکی است،
 * پس اگر سربرگی جابه‌جا شد فقط همین لیست را تغییر دهید.
 */
private enum class SettingsTab(val title: String) {
    PANEL("پنل هیدی‌فای"),
    SYNC("همگام‌سازی و فاکتور"),
    SMS("پیامک"),
    BUSINESS("کسب‌وکار"),
    SECURITY("امنیت"),
    BACKUP("پشتیبان"),
    ABOUT("درباره")
}
