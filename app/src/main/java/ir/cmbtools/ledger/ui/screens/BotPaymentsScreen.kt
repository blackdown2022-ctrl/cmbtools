package ir.cmbtools.ledger.ui.screens

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.GradientHeader
import ir.cmbtools.ledger.ui.common.SectionCard
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.AccentRed
import ir.cmbtools.ledger.ui.theme.TextMuted

@Composable
fun BotPaymentsScreen(vm: AppViewModel, nav: NavHostController) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val connection by vm.botConnectionResult.collectAsStateWithLifecycle()
    var serverUrl by remember(settings.botServerUrl) { mutableStateOf(settings.botServerUrl) }
    var secret by remember(settings.botApiSecret) { mutableStateOf(settings.botApiSecret) }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(
            title = "ربات",
            subtitle = "تأیید واریزهای ربات فروش از روی مبلغ یکتای سفارش",
            action = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionCard(title = "پیش از فعال‌سازی — محدودیت مهم") {
                Text(
                    "سرور salebot فقط مبلغ یکتای سفارش و کد پیگیری را با سفارش باز تطبیق می‌دهد. این سرویس شمارهٔ کارت مقصد را از گوشی نمی‌گیرد و بانک را مستقیماً استعلام نمی‌کند؛ پس نتیجه، تأیید بر پایهٔ مبلغ یکتا است و اثبات مستقل بانکی نیست.",
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "فقط روی گوشی و سیم‌کارتی روشن کنید که پیامک‌های کارت فروشگاه را دریافت می‌کند. پیامک‌های واریزِ کارت‌های دیگرِ همین گوشی هم ممکن است به این سرور فرستاده شوند.",
                    color = TextMuted
                )
            }

            SectionCard(title = "اتصال امن به سرور") {
                Text(
                    "آدرس سرور self-hosted خودتان و Secret را از تنظیمات اتصال اپ در پنل ربات بردارید. آدرس باید HTTPS باشد؛ آدرس یا Secret مخزن عمومی را وارد نکنید.",
                    color = TextMuted
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; vm.clearBotConnectionResult() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("آدرس HTTPS سرور یا مسیر confirm") },
                    placeholder = { Text("https://example.com/wp-json/webdide-cv/v1/confirm") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it; vm.clearBotConnectionResult() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Secret API") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveBotConnection(serverUrl, secret) }) {
                        Text("ذخیرهٔ رمزگذاری‌شده")
                    }
                    OutlinedButton(
                        onClick = { vm.testBotConnection(serverUrl, secret) },
                        enabled = !busy
                    ) {
                        Text(if (busy) "در حال آزمایش…" else "آزمایش اتصال")
                    }
                }
                connection?.let { result ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = (if (result.ok) "✓ " else "✕ ") + result.message +
                            if (result.version.isNotBlank()) " • نسخهٔ ${result.version}" else "",
                        color = if (result.ok) AccentGreen else AccentRed,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            SectionCard(title = "ارسال پیامک‌های واریز") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("تأیید خودکار ربات", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (settings.botPaymentEnabled) "روشن — فقط SMS واجدشرایط ارسال می‌شود"
                            else "خاموش — تنظیمات ذخیره‌اند، اما پیامکی ارسال نمی‌شود",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = settings.botPaymentEnabled,
                        onCheckedChange = vm::setBotPaymentEnabled
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "فقط پیامک واریزِ بانک شناخته‌شده که مبلغ مثبت و کد پیگیری دارد، غیر OTP و غیر برگشتی باشد، فرستاده می‌شود. مبلغ ریالی گوشی پیش از ارسال به تومان تبدیل می‌شود. برداشت، رمز یک‌بارمصرف، برگشت وجه، بانک ناشناس یا پیامک بدون کد پیگیری ارسال نمی‌شوند.",
                    color = TextMuted
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "قطع اینترنت یا timeout هیچ‌وقت به‌عنوان پرداخت موفق ثبت نمی‌شود. خطاهای موقت تا ۵ بار با فاصلهٔ افزایشی دوباره تلاش می‌شوند؛ نتیجهٔ نهایی در اعلان گوشی می‌آید، پس اعلان‌های برنامه را در تنظیمات Android خاموش نکنید.",
                    color = TextMuted
                )
            }
        }
    }
}
