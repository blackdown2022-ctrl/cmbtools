package ir.cmbtools.ledger.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.SmsGatewayProvider
import ir.cmbtools.ledger.core.SmsMode
import ir.cmbtools.ledger.export.ExportManager
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
    val accounts by vm.panelAccounts.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var host by remember { mutableStateOf(config?.host ?: "") }
    var path by remember { mutableStateOf(config?.adminProxyPath ?: "") }
    var apiKey by remember { mutableStateOf(config?.apiKey ?: "") }
    var useHttps by remember { mutableStateOf(config?.useHttps ?: true) }
    var panelName by remember { mutableStateOf(config?.name ?: "پنل اصلی") }

    var businessName by remember { mutableStateOf(settings.businessName) }
    var footer by remember { mutableStateOf(settings.invoiceFooter) }
    var template by remember { mutableStateOf(settings.invoiceTemplate) }
    var gatewayKey by remember { mutableStateOf(settings.gatewayApiKey) }
    var gatewaySender by remember { mutableStateOf(settings.gatewaySender) }
    var fallbackLink by remember { mutableStateOf(settings.fallbackSubscriptionBase) }
    var autoMatchMax by remember { mutableStateOf(settings.autoMatchMaxRial) }
    var cardNumber by remember { mutableStateOf(settings.paymentCardNumber) }
    var pricePerGbText by remember { mutableStateOf(settings.pricePerGbRial.toString()) }
    var pin by remember { mutableStateOf("") }

    LaunchedEffect(config) {
        config?.let {
            host = it.host
            path = it.adminProxyPath
            apiKey = it.apiKey
            useHttps = it.useHttps
            panelName = it.name
        }
    }

    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                ExportManager(context, vm.appContainer()).importBackup(uri)
                    .onSuccess { vm.report("بازیابی انجام شد: ${Money.toPersian(it.customers.size.toString())} مشتری") }
                    .onFailure { vm.report("بازیابی ناموفق: ${it.message}", isError = true) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(
            title = "تنظیمات",
            subtitle = "پنل، پیامک، امنیت و داده‌ها",
            action = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت", tint = TextMuted)
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionCard(title = "اتصال به پنل هیدی‌فای ۱۴") {
                LabeledField("نام (مثلاً سرور آلمان)", panelName, { panelName = it })
                Spacer(Modifier.height(8.dp))
                LabeledField("دامنهٔ پنل", host, { host = it }, placeholder = "panel.example.com")
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    "مسیر پروکسی ادمین",
                    path,
                    { path = it },
                    placeholder = "از تنظیمات ← تنظیمات خیلی پیشرفته"
                )
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    "کلید API (UUID ادمین)",
                    apiKey,
                    { apiKey = it },
                    placeholder = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("استفاده از HTTPS")
                    Switch(checked = useHttps, onCheckedChange = { useHttps = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.savePanelConfig(host, path, apiKey, useHttps, panelName) { vm.testPanel() }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("ذخیره و تست") }
                    OutlinedButton(onClick = { vm.syncPanel() }, modifier = Modifier.weight(1f)) {
                        Text("همگام‌سازی")
                    }
                }
                if (panelStatus.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(panelStatus, color = PrimaryCyan, style = MaterialTheme.typography.labelMedium)
                }
                if (config?.panelVersion?.isNotBlank() == true) {
                    Text(
                        "نسخهٔ پنل: " + Money.toPersian(config?.panelVersion ?: ""),
                        color = TextMuted, style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "آدرس API: https://<دامنه>/<مسیر پروکسی>/api/v2/admin/  •  هدر: Hiddify-API-Key",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    "پایهٔ لینک اشتراک (اگر پنل در دسترس نبود)",
                    fallbackLink,
                    { fallbackLink = it },
                    placeholder = "https://panel.example.com/AbCdEf/"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "اکانت‌های همگام‌شده: " + Money.toPersian(accounts.size.toString()),
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            SectionCard(title = "همگام‌سازی و فاکتور خودکار") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("همگام‌سازی خودکار با پنل")
                        Text(
                            "هر ${Money.toPersian(settings.autoSyncMinutes.toString())} دقیقه، در پس‌زمینه",
                            color = TextMuted,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Switch(
                        checked = settings.autoSyncEnabled,
                        onCheckedChange = { vm.setAutoSyncEnabled(it) }
                    )
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
                LabeledField(
                    "قیمت هر گیگ (ریال) — برای بسته‌های بدون قیمت",
                    pricePerGbText,
                    { pricePerGbText = it.filter { ch -> ch.isDigit() } },
                    keyboardType = KeyboardType.Number
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
                                    pricePerGbRial = pricePerGbText.toLongOrNull() ?: 0L
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

            SectionCard(title = "پیامک") {
                DropdownField(
                    label = "روش ارسال",
                    items = listOf(SmsMode.GATEWAY, SmsMode.SIM),
                    selected = settings.smsMode,
                    itemLabel = { if (it == SmsMode.SIM) "سیم‌کارت گوشی (رایگان)" else "درگاه پیامکی (با گزارش تحویل)" },
                    onSelect = { mode -> vm.updateSettings { it.copy(smsMode = mode) } }
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
                LabeledField(
                    "قالب پیامک فاکتور",
                    template,
                    { template = it },
                    singleLine = false,
                    maxLines = 10
                )
                Text(
                    "متغیرها: {business} {invoiceNo} {customer} {total} {items} {date} {link} {footer}",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
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
                                invoiceFooter = footer,
                                fallbackSubscriptionBase = fallbackLink
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ذخیرهٔ تنظیمات پیامک") }
            }

            SectionCard(title = "پیامک بانکی") {
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

            SectionCard(title = "کسب‌وکار") {
                LabeledField("نام کسب‌وکار (در فاکتور)", businessName, { businessName = it })
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

            SectionCard(title = "امنیت") {
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

            SectionCard(title = "داده‌ها و پشتیبان") {
                OutlinedButton(
                    onClick = {
                        ExportManager(context, vm.appContainer()).exportBackup { r ->
                            r.onSuccess { vm.report("پشتیبان ساخته شد") }
                                .onFailure { vm.report("ناموفق: ${it.message}", isError = true) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("پشتیبان کامل (JSON)") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("بازیابی از فایل پشتیبان") }
                Spacer(Modifier.height(6.dp))
                Text(
                    "پشتیبان روزانه را در Google Drive/تلگرام خودتان ذخیره کنید. بدون آن، گم شدن گوشی = از دست رفتن دفتر.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            SectionCard(title = "درباره") {
                Text("حساب‌دفتر من — نسخهٔ 0.1.0", fontWeight = FontWeight.SemiBold)
                Text(
                    "اپ شخصی حسابداری + مدیریت پنل هیدی‌فای ۱۴. ساخته‌شده برای استفادهٔ تک‌کاربره روی گوشی خودتان.",
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
