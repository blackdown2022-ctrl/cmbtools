package ir.cmbtools.ledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.data.Invoice
import ir.cmbtools.ledger.data.InvoiceLine
import ir.cmbtools.ledger.data.NewInvoiceLine
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.AmountField
import ir.cmbtools.ledger.ui.common.LabeledField
import ir.cmbtools.ledger.ui.theme.TextMuted

private data class EditableInvoiceLine(
    val description: String,
    val qty: Int,
    val unitPriceRial: Long,
    val planId: Long
) {
    val amountRial: Long get() = runCatching {
        Math.multiplyExact(unitPriceRial, qty.coerceAtLeast(1).toLong())
    }.getOrDefault(Long.MAX_VALUE)
}

@Composable
fun InvoiceEditDialog(
    vm: AppViewModel,
    invoice: Invoice,
    originalLines: List<InvoiceLine>,
    onDismiss: () -> Unit
) {
    var drafts by remember(invoice.id, originalLines) {
        mutableStateOf(originalLines.map { EditableInvoiceLine(it.description, it.qty, it.unitPriceRial, it.planId) })
    }
    var discount by remember(invoice.id) { mutableStateOf(invoice.discountRial) }
    var note by remember(invoice.id) { mutableStateOf(invoice.note) }
    var sendSms by remember(invoice.id) { mutableStateOf(false) }
    var error by remember(invoice.id) { mutableStateOf("") }
    var saving by remember(invoice.id) { mutableStateOf(false) }

    val subtotal = drafts.fold(0L) { sum, line -> Money.safeSum(sum, line.amountRial) }
    val finalTotal = (subtotal - discount.coerceIn(0, subtotal)).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("ویرایش فاکتور ${Money.toPersian(invoice.number)}", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                drafts.forEachIndexed { index, line ->
                    Text("ردیف ${Money.toPersian((index + 1).toString())}", fontWeight = FontWeight.SemiBold)
                    LabeledField(
                        "شرح",
                        line.description,
                        { value -> drafts = drafts.toMutableList().also { it[index] = line.copy(description = value) } }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LabeledField(
                            "تعداد",
                            line.qty.toString(),
                            { value -> drafts = drafts.toMutableList().also { it[index] = line.copy(qty = Money.toLatinDigits(value).filter { ch -> ch.isDigit() }.toIntOrNull() ?: 0) } },
                            modifier = Modifier.weight(1f),
                            keyboardType = KeyboardType.Number
                        )
                        AmountField(
                            "قیمت واحد",
                            line.unitPriceRial,
                            { value -> drafts = drafts.toMutableList().also { it[index] = line.copy(unitPriceRial = value) } },
                            modifier = Modifier.weight(2f)
                        )
                    }
                    TextButton(
                        onClick = { drafts = drafts.toMutableList().also { it.removeAt(index) } },
                        enabled = drafts.size > 1
                    ) { Text("حذف این ردیف", color = MaterialTheme.colorScheme.error) }
                }
                OutlinedButton(
                    onClick = { drafts = drafts + EditableInvoiceLine("", 1, 0, 0) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("افزودن ردیف") }

                AmountField("تخفیف (ریال)", discount, { discount = it })
                LabeledField("یادداشت", note, { note = it }, singleLine = false)
                Text("جمع ردیف‌ها: ${Money.format(subtotal)}", color = TextMuted)
                Text("مبلغ نهایی پس از تخفیف: ${Money.format(finalTotal)}", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = sendSms, onCheckedChange = { sendSms = it })
                    Text("ارسال پیامکِ نسخهٔ اصلاح‌شده", modifier = Modifier.weight(1f))
                }
                Text(
                    "پرداخت‌های ثبت‌شده حفظ می‌شوند؛ مبلغ نهایی نمی‌تواند از مبلغ پرداخت‌شده کمتر شود.",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    val invalid = drafts.any { it.description.isBlank() || it.qty <= 0 || it.unitPriceRial <= 0 }
                    if (invalid) {
                        error = "شرح، تعداد و قیمت همهٔ ردیف‌ها را کامل کنید"
                        return@Button
                    }
                    saving = true
                    error = ""
                    vm.updateInvoice(
                        invoiceId = invoice.id,
                        lines = drafts.map { NewInvoiceLine(it.description.trim(), it.qty, it.unitPriceRial, it.planId) },
                        discountRial = discount,
                        note = note,
                        sendUpdatedSms = sendSms
                    ) { result ->
                        saving = false
                        if (result.ok) onDismiss() else error = result.message
                    }
                }
            ) { Text(if (saving) "در حال ذخیره..." else "ذخیرهٔ تغییرات") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("انصراف") }
        }
    )
}
