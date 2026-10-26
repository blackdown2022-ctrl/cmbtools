package ir.cmbtools.ledger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.core.BotPaymentAudit
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.sms.BankSmsParser
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.GradientHeader
import ir.cmbtools.ledger.ui.common.SectionCard
import ir.cmbtools.ledger.ui.theme.AccentAmber
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.AccentRed
import ir.cmbtools.ledger.ui.theme.TextMuted

private val botTabs = listOf("وضعیت", "سفارش", "آزمایش", "تنظیمات")

@Composable
fun BotPaymentsScreen(vm: AppViewModel, nav: NavHostController) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val connection by vm.botConnectionResult.collectAsStateWithLifecycle()
    val orderStatus by vm.botOrderStatus.collectAsStateWithLifecycle()
    val audit by vm.botAudit.collectAsStateWithLifecycle()
    val smsRows by vm.allBankSms.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val canReceiveSms = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    var tab by remember { mutableStateOf(0) }
    var serverUrl by remember(settings.botServerUrl) { mutableStateOf(settings.botServerUrl) }
    var secret by remember(settings.botApiSecret) { mutableStateOf(settings.botApiSecret) }
    var orderId by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(
            title = "ربات",
            subtitle = "اتصال، وضعیت و بررسی سفارش‌های مبلغ‌یکتا",
            action = {
                IconButton(onClick = { nav.popBackStack("dashboard", inclusive = false) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        )
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
            botTabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (tab) {
                0 -> BotStatusTab(audit, settings.botPaymentEnabled, canReceiveSms, onEnabledChange = vm::setBotPaymentEnabled, onOpenBankTab = { nav.navigate("bank") })
                1 -> {
                    SectionCard(title = "خواندن یک سفارش از سرور") {
                        Text(
                            "شناسهٔ سفارش را از ربات تلگرام وارد کنید تا وضعیت فعلی و مبلغ ثبت‌شده روی سرور خوانده شود. API موجودِ salebot امکان فهرست‌کردن همهٔ سفارش‌ها را با Secret اتصال اپ نمی‌دهد؛ این بخش برای جست‌وجوی یک سفارش مشخص است.",
                            color = TextMuted
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = orderId,
                            onValueChange = { orderId = normalizeOrderIdInput(it).take(40); vm.clearBotOrderStatus() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("شناسهٔ سفارش") },
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { vm.lookupBotOrder(serverUrl, orderId) },
                            enabled = !busy && orderId.length >= 4 && serverUrl.isNotBlank()
                        ) { Text(if (busy) "در حال بررسی…" else "خواندن وضعیت از سرور") }
                        orderStatus?.let { result ->
                            Spacer(Modifier.height(10.dp))
                            if (result.ok && result.found) {
                                Text("سفارش ${result.orderId}", fontWeight = FontWeight.Bold)
                                Text("وضعیت: ${orderStatusLabel(result.status)}", color = if (result.status == "confirmed") AccentGreen else AccentAmber)
                                Text("مبلغ یکتای سرور: ${Money.format(result.amountToman, withUnit = false)} تومان • ${Money.format(result.amountToman * 10L)}")
                                if (result.paymentMethod.isNotBlank()) Text("روش پرداخت: ${result.paymentMethod}", color = TextMuted)
                                Text("HTTP ${result.httpStatus}", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                            } else {
                                Text(result.message, color = AccentRed)
                                if (result.httpStatus > 0) Text("HTTP ${result.httpStatus}", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                2 -> BotTestsTab(
                    url = serverUrl,
                    secret = secret,
                    connectionMessage = connection?.let { (if (it.ok) "✓ " else "✕ ") + it.message + if (it.version.isNotBlank()) " • نسخهٔ ${it.version}" else "" },
                    connectionOk = connection?.ok,
                    busy = busy,
                    smsRows = smsRows,
                    onTest = { vm.testBotConnection(serverUrl, secret) }
                )
                else -> BotSettingsTab(
                    serverUrl = serverUrl,
                    secret = secret,
                    enabled = settings.botPaymentEnabled,
                    busy = busy,
                    onUrlChange = { serverUrl = it; vm.clearBotConnectionResult() },
                    onSecretChange = { secret = it; vm.clearBotConnectionResult() },
                    onSave = { vm.saveBotConnection(serverUrl, secret) }
                )
            }
        }
    }
}

@Composable
private fun BotStatusTab(
    audit: BotPaymentAudit,
    enabled: Boolean,
    canReceiveSms: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenBankTab: () -> Unit
) {
    SectionCard(title = "فعال‌بودن اتصال") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("ارسال واریزهای واجدشرایط", fontWeight = FontWeight.SemiBold)
                Text(if (enabled) "روشن" else "خاموش — حالت امن پیش‌فرض", color = if (enabled) AccentGreen else TextMuted)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(Modifier.height(8.dp))
        if (!canReceiveSms) {
            Spacer(Modifier.height(8.dp))
            Text("مجوز دریافت SMS داده نشده است؛ آزمایش اتصال این مجوز را بررسی نمی‌کند. برای فعال‌سازی به تب «بانک» بروید.", color = AccentRed)
            TextButton(onClick = onOpenBankTab) { Text("رفتن به تب بانک") }
        }
        Text(
            "تست health فقط ارتباط با سرور و Secret را بررسی می‌کند؛ روشن‌شدن خودکار جداست و مجوز دریافت SMS را هم تضمین نمی‌کند. سرور فقط مبلغ یکتا و کد پیگیری را با سفارش باز تطبیق می‌دهد؛ شمارهٔ کارت مقصد را بررسی نمی‌کند و بانک را مستقیم استعلام نمی‌کند. این نتیجه اثبات مستقل بانکی نیست. فقط روی گوشی/سیم‌کارت اختصاصی پیامک‌های کارت فروشگاه روشن کنید.",
            color = AccentAmber
        )
    }
    SectionCard(title = "آخرین نتیجهٔ پیامک ربات") {
        if (audit.atMillis <= 0L) {
            Text("هنوز نتیجه‌ای برای اتصال ربات ثبت نشده است. تب «آزمایش» پیامک‌های اخیر را برای پیدا کردن علت بررسی می‌کند.", color = TextMuted)
        } else {
            Text(auditStateLabel(audit.state), color = auditColor(audit.state), fontWeight = FontWeight.Bold)
            Text(audit.message)
            if (audit.amountRial > 0L) Text("مبلغ خوانده‌شده: ${Money.format(audit.amountRial)} (${Money.format(audit.amountRial / 10L, withUnit = false)} تومان)")
            if (audit.bank.isNotBlank()) Text("بانک تشخیص‌داده‌شده: ${audit.bank}", color = TextMuted)
            if (audit.reference.isNotBlank()) Text("${if (audit.state == "skipped") "کد پیگیریِ خوانده‌شده" else "کد پیگیریِ ارسال‌شده"}: ${audit.reference}", color = TextMuted)
            if (audit.orderId.isNotBlank()) Text("شناسهٔ سفارش پاسخ‌داده‌شده: ${audit.orderId}", color = TextMuted)
            if (audit.httpStatus > 0) Text("پاسخ سرور: HTTP ${audit.httpStatus}", color = TextMuted, style = MaterialTheme.typography.labelSmall)
            Text("زمان: ${Jalali.formatDateTime(audit.atMillis)}", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BotSettingsTab(
    serverUrl: String,
    secret: String,
    enabled: Boolean,
    busy: Boolean,
    onUrlChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onSave: () -> Unit
) {
    SectionCard(title = "آدرس و Secret سرور شخصی") {
        Text(
            "آدرس و Secret را از بخش «اتصال اپ شتاب» در نصب خودتان بردارید. آدرس باید HTTPS باشد؛ اطلاعات مخزن عمومی را وارد نکنید.",
            color = TextMuted
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("آدرس HTTPS سرور یا مسیر confirm") },
            placeholder = { Text("https://example.com/wp-json/webdide-cv/v1/confirm") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = secret,
            onValueChange = onSecretChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Secret API") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onSave, enabled = !busy) { Text("ذخیرهٔ رمزگذاری‌شده") }
        Text(
            if (enabled) "اتصال روشن است؛ تغییر Secret یا آدرس را پس از آزمایش دوباره بررسی کنید."
            else "اتصال خاموش است؛ تا روشن‌کردن آن هیچ SMS برای سرور ارسال نمی‌شود.",
            color = TextMuted
        )
        Text("Secret در فضای رمزگذاری‌شدهٔ گوشی ذخیره می‌شود و در پشتیبان JSON قرار نمی‌گیرد.", color = TextMuted)
    }
}

@Composable
private fun BotTestsTab(
    url: String,
    secret: String,
    connectionMessage: String?,
    connectionOk: Boolean?,
    busy: Boolean,
    smsRows: List<ir.cmbtools.ledger.data.BankSms>,
    onTest: () -> Unit
) {
    SectionCard(title = "آزمایش اتصال به سرور") {
        Text("این آزمون فقط health و Secret را بررسی می‌کند و پرداخت آزمایشی نمی‌فرستد.", color = TextMuted)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onTest, enabled = !busy && url.isNotBlank() && secret.isNotBlank()) {
            Text(if (busy) "در حال آزمایش…" else "آزمایش health و Secret")
        }
        if (connectionMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(connectionMessage, color = if (connectionOk == true) AccentGreen else AccentRed)
        }
    }

    SectionCard(title = "بررسی پیامک‌های بانکی اخیر") {
        Text("پیش‌نمایش فقط نشان می‌دهد parser همین نسخه چه اطلاعاتی از پیامک ذخیره‌شده می‌خواند؛ پیامک‌های قدیمی را دوباره به سرور نمی‌فرستد.", color = TextMuted)
        Spacer(Modifier.height(8.dp))
        if (smsRows.isEmpty()) {
            Text("پیامک بانکی ذخیره‌شده‌ای نیست", color = TextMuted)
        } else {
            smsRows.take(8).forEach { sms ->
                val parsed = remember(sms.id, sms.body, sms.sender) {
                    BankSmsParser.parse(sms.sender, sms.body, sms.receivedAtMillis)
                }
                val eligible = BankSmsParserEligibility.isEligible(parsed)
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("${sms.bank.ifBlank { parsed.bank }} • ${Money.format(parsed.amountRial)}", fontWeight = FontWeight.SemiBold)
                    Text("مبلغی که اپ به ربات می‌فرستد: ${if (parsed.amountRial > 0L && parsed.amountRial % 10L == 0L) Money.format(parsed.amountRial / 10L, withUnit = false) + " تومان" else "نامشخص/غیرقابل تبدیل دقیق"}", color = TextMuted)
                    Text("کد پیگیری parser: ${parsed.refNo.ifBlank { "پیدا نشد" }}", color = TextMuted)
                    Text("نتیجه: ${eligibilityReason(parsed, eligible)}", color = if (eligible) AccentGreen else AccentAmber)
                    Text("ثبت‌شده: ${Jalali.formatDateTime(sms.receivedAtMillis)}", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private object BankSmsParserEligibility {
    fun isEligible(parsed: ir.cmbtools.ledger.sms.ParsedBankSms) =
        ir.cmbtools.ledger.sms.BotPaymentClient.isEligibleBankDeposit(parsed)
}

private fun eligibilityReason(parsed: ir.cmbtools.ledger.sms.ParsedBankSms, eligible: Boolean): String = when {
    eligible -> "واجد شرایط ارسال؛ برای پیامک‌های جدید در صف قرار می‌گیرد (پیامک قدیمی ارسال نشده)"
    parsed.isOtp -> "OTP/رمز یک‌بارمصرف است؛ ارسال نمی‌شود"
    parsed.isReversal -> "برگشت وجه است؛ ارسال نمی‌شود"
    !parsed.isCredit -> "واریز اعتباری تشخیص داده نشد: ${parsed.reason.ifBlank { "الگوی پیامک بانک پشتیبانی/تشخیص داده نشد" }}"
    parsed.amountRial <= 0L -> "مبلغ معتبر پیدا نشد"
    parsed.amountRial % 10L != 0L -> "مبلغ به تومان دقیق تبدیل نمی‌شود"
    parsed.refNo.isBlank() -> "کد پیگیری با الگوی فعلی استخراج نشد"
    parsed.bank == "بانک ناشناس" -> "فرستندهٔ SMS در فهرست بانک‌های پشتیبانی‌شده نیست"
    else -> "شرایط لازم برای تأیید را ندارد"
}

private fun normalizeOrderIdInput(value: String): String = buildString {
    value.forEach { char ->
        val ascii = when (char) {
            in '۰'..'۹' -> '0' + (char - '۰')
            in '٠'..'٩' -> '0' + (char - '٠')
            else -> char
        }
        if (ascii.isLetterOrDigit() && ascii.code < 128 || ascii == '_' || ascii == '-') append(ascii)
    }
}

private fun auditStateLabel(state: String) = when (state) {
    "pending" -> "در صف ارسال"
    "retrying" -> "خطای موقت؛ تلاش مجدد زمان‌بندی شد"
    "confirmed" -> "پرداخت از سوی سرور صریحاً تأیید شد"
    "skipped" -> "ارسال نشد"
    "cancelled" -> "ارسال لغو شد"
    "failed" -> "تأیید ناموفق"
    else -> state
}

private fun auditColor(state: String) = when (state) {
    "confirmed" -> AccentGreen
    "skipped", "failed", "cancelled" -> AccentRed
    else -> AccentAmber
}

private fun orderStatusLabel(status: String) = when (status) {
    "pending" -> "در انتظار پرداخت"
    "confirmed" -> "پرداخت‌شده/تأییدشده"
    "not_found" -> "پیدا نشد"
    "expired" -> "منقضی"
    "cancelled" -> "لغوشده"
    else -> status
}
