package ir.cmbtools.ledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.core.InvoiceKind
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.data.Invoice
import ir.cmbtools.ledger.data.InvoiceLine
import ir.cmbtools.ledger.sms.SmsTemplate
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.ConfirmDialog
import ir.cmbtools.ledger.ui.common.GradientHeader
import ir.cmbtools.ledger.ui.common.SectionCard
import ir.cmbtools.ledger.ui.common.StatusPill
import ir.cmbtools.ledger.ui.common.statusColor
import ir.cmbtools.ledger.ui.theme.AccentAmber
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.AccentRed
import ir.cmbtools.ledger.ui.theme.PrimaryCyan
import ir.cmbtools.ledger.ui.theme.TextMuted

@Composable
fun InvoiceDetailScreen(vm: AppViewModel, invoiceId: Long, nav: NavHostController) {
    // مانند صفحهٔ مشتری: Flowها یک‌بار ساخته می‌شوند تا در هر بازترکیب دوباره جمع‌آوری نشوند.
    val invoiceFlow = remember(invoiceId) { vm.invoiceFlow(invoiceId) }
    val linesFlow = remember(invoiceId) { vm.invoiceLines(invoiceId) }
    val invoice by invoiceFlow.collectAsStateWithLifecycle(initialValue = null)
    val lines by linesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val customers by vm.customers.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showSmsPreview by remember { mutableStateOf(false) }
    var showVoid by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    val customer = invoice?.let { inv -> customers.firstOrNull { it.customer.id == inv.customerId }?.customer }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(
            title = invoice?.number?.let { Money.toPersian(it) } ?: "فاکتور",
            subtitle = customer?.name,
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
            invoice?.let { inv ->
                // ── سند فاکتور: نام مشتری، تاریخ و ساعت، شماره، بسته، کارت، بدهی ──
                SectionCard(title = "مشخصات فاکتور") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("مبلغ کل", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                            Text(Money.format(inv.totalRial), fontWeight = FontWeight.Bold, color = AccentGreen)
                        }
                        StatusPill(InvoiceStatus.label(inv.status), statusColor(inv.status))
                    }
                    Spacer(Modifier.height(10.dp))

                    InfoRow("مشتری", customer?.name ?: "—")
                    InfoRow("تاریخ و ساعت", SmsTemplate.formatDateTime(inv))
                    InfoRow("شمارهٔ فاکتور", Money.toPersian(inv.number))
                    InfoRow(
                        "نوع بسته (" + InvoiceKind.label(inv.kind) + ")",
                        inv.packageName.ifBlank { lines.firstOrNull()?.description ?: "—" }
                    )
                    if (inv.cardNumber.isNotBlank()) {
                        // شمارهٔ کارت تنها عددی است که لاتین می‌ماند (خواندن/کپی آسان‌تر)
                        InfoRow("شمارهٔ کارت", inv.cardNumber)
                    }

                    // ماندهٔ قبلی فقط وقتی مشتری از قبل بدهکار بوده نمایش داده می‌شود
                    if (inv.prevBalanceRial > 0) {
                        Spacer(Modifier.height(6.dp))
                        InfoRow("مانده قبلی", Money.format(inv.prevBalanceRial), AccentAmber)
                        InfoRow(
                            "جمع کل بدهی",
                            Money.format(
                                (if (inv.balanceAfterRial != 0L) inv.balanceAfterRial
                                else inv.prevBalanceRial + inv.totalRial)
                            ),
                            AccentRed
                        )
                    } else if (inv.prevBalanceRial < 0) {
                        InfoRow("اعتبار قبلی", Money.format(-inv.prevBalanceRial), AccentGreen)
                    }

                    Spacer(Modifier.height(6.dp))
                    if (inv.dueAtEpochDay > 0) InfoRow("سررسید", Jalali.formatEpochDay(inv.dueAtEpochDay))
                    if (inv.discountRial > 0) InfoRow("تخفیف", Money.format(inv.discountRial))
                    InfoRow("پرداخت‌شده", Money.format(inv.paidRial))
                    InfoRow(
                        "مانده این فاکتور",
                        Money.format(inv.remainingRial),
                        if (inv.remainingRial > 0) AccentAmber else AccentGreen
                    )
                    if (inv.note.isNotBlank()) InfoRow("یادداشت", inv.note)
                }

                SectionCard(title = "ردیف‌ها") {
                    lines.forEach { line ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(line.description)
                            Text(Money.format(line.amountRial, withUnit = false), color = AccentGreen)
                        }
                    }
                }

                if (inv.panelUserUuid.isNotBlank() || inv.subscriptionUrl.isNotBlank()) {
                    SectionCard(title = "اکانت پنل") {
                        if (inv.panelUserUuid.isNotBlank()) InfoRow("UUID", inv.panelUserUuid)
                        if (inv.subscriptionUrl.isNotBlank()) {
                            Text(
                                inv.subscriptionUrl,
                                color = PrimaryCyan,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.copyToClipboard(inv.subscriptionUrl) }) {
                                    Text("کپی لینک")
                                }
                                OutlinedButton(onClick = {
                                    val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, inv.subscriptionUrl)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(share, "اشتراک لینک"))
                                }) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.height(16.dp))
                                    Text(" ارسال")
                                }
                            }
                        }
                    }
                } else if (inv.status != InvoiceStatus.VOID) {
                    SectionCard(title = "اکانت پنل") {
                        Text("برای این فاکتور اکانتی در پنل ساخته نشده است.", color = TextMuted)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = {
                            vm.createAccountForInvoice(invoiceId, gbForLines(lines), daysGuess(inv, lines))
                        }) { Text("ساخت اکانت در پنل") }
                    }
                }

                SectionCard(title = "اقدامات") {
                    Button(
                        onClick = { showSmsPreview = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = customer != null && customer.phone.isNotBlank()
                    ) {
                        Text(
                            if (customer?.phone.isNullOrBlank()) "شمارهٔ مشتری ثبت نشده"
                            else "پیش‌نمایش و ارسال پیامک فاکتور"
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { invoice?.let { vm.sendInvoiceSms(it.id) } },
                            modifier = Modifier.weight(1f),
                            enabled = customer?.phone?.isNotBlank() == true
                        ) { Text("ارسال سریع") }
                        OutlinedButton(
                            onClick = { invoice?.let { vm.sendReminder(it.customerId) } },
                            modifier = Modifier.weight(1f)
                        ) { Text("یادآوری بدهی") }
                    }
                    if (inv.status != InvoiceStatus.VOID && inv.paidRial == 0L) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { showVoid = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("ابطال فاکتور", color = AccentRed)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.height(18.dp), tint = AccentRed)
                        Text(" حذف کامل فاکتور", color = AccentRed)
                    }
                }
            } ?: Text("فاکتور پیدا نشد", color = TextMuted)

            Spacer(Modifier.height(30.dp))
        }
    }

    if (showSmsPreview && invoice != null && customer != null) {
        val body = remember(invoice, lines, settings) {
            SmsTemplate.render(settings, invoice!!, customer!!, lines, invoice!!.subscriptionUrl)
        }
        val parts = SmsTemplate.parts(body)
        AlertDialog(
            onDismissRequest = { showSmsPreview = false },
            title = { Text("پیش‌نمایش پیامک") },
            text = {
                Column {
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "تعداد بخش: ${Money.toPersian(parts.toString())} • طول: ${Money.toPersian(body.length.toString())} کاراکتر" +
                            " • روش: " + if (settings.smsMode == "SIM") "سیم‌کارت" else "درگاه",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        "به: " + Money.toPersian(customer!!.phone),
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.sendInvoiceSms(invoice!!.id)
                    showSmsPreview = false
                }) { Text("ارسال") }
            },
            dismissButton = {
                TextButton(onClick = { vm.copyToClipboard(body) }) { Text("کپی متن") }
            }
        )
    }

    if (showVoid) {
        ConfirmDialog(
            title = "ابطال فاکتور",
            text = "فاکتور باطل می‌شود و یک ثبت معکوس در دفتر مشتری زده می‌شود. ادامه می‌دهید؟",
            confirmText = "ابطال کن",
            onConfirm = {
                vm.voidInvoice(invoiceId)
                showVoid = false
            },
            onDismiss = { showVoid = false }
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = "حذف کامل فاکتور",
            text = "این کار برگشت‌پذیر نیست. خود فاکتور و ردیف‌هایش حذف می‌شود. پرداخت‌های تخصیص‌یافته به اعتبار آزاد مشتری برمی‌گردد." +
                if (invoice?.panelUserUuid?.isNotBlank() == true) " اکانت متصل در پنل هیدی‌فای هم ابتدا حذف می‌شود؛ اگر پنل در دسترس نباشد، فاکتور محلی حذف نخواهد شد." else "",
            confirmText = "حذف فاکتور",
            onConfirm = {
                vm.deleteInvoice(invoiceId) { nav.popBackStack() }
                showDelete = false
            },
            onDismiss = { showDelete = false }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextMuted, style = MaterialTheme.typography.labelMedium)
        Text(value, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun gbForLines(lines: List<InvoiceLine>): Double {
    // اگر شرح مثل «۵۰ گیگ» باشد، همان را می‌خوانیم؛ وگرنه ۳۰ پیش‌فرض
    for (line in lines) {
        val m = Regex("(\\d{1,3})\\s*(?:گیگ|GB|gb)").find(line.description) ?: continue
        m.groupValues[1].toDoubleOrNull()?.let { return it }
    }
    return 30.0
}

private fun daysGuess(invoice: Invoice, lines: List<InvoiceLine>): Int {
    for (line in lines) {
        val m = Regex("(\\d{1,3})\\s*(?:روز|day)").find(line.description) ?: continue
        m.groupValues[1].toIntOrNull()?.let { return it }
    }
    return 30
}
