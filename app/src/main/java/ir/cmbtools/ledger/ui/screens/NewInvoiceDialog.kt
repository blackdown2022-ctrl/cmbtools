package ir.cmbtools.ledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.data.Customer
import ir.cmbtools.ledger.data.Plan
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.AmountField
import ir.cmbtools.ledger.ui.common.CustomerPickerDialog
import ir.cmbtools.ledger.ui.common.DropdownField
import ir.cmbtools.ledger.ui.common.LabeledField
import ir.cmbtools.ledger.ui.common.SectionCard
import ir.cmbtools.ledger.ui.theme.TextMuted

@Composable
fun NewInvoiceDialog(
    vm: AppViewModel,
    presetCustomerId: Long,
    onDismiss: () -> Unit,
    onIssued: (Long) -> Unit
) {
    val customers by vm.customers.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var selectedCustomer by remember {
        mutableStateOf(customers.firstOrNull { it.customer.id == presetCustomerId }?.customer)
    }
    var selectedPlan by remember { mutableStateOf(plans.firstOrNull()) }
    var description by remember { mutableStateOf(selectedPlan?.name ?: "") }
    var gbText by remember { mutableStateOf(selectedPlan?.gb?.toString() ?: "30") }
    var daysText by remember { mutableStateOf(selectedPlan?.days?.toString() ?: "30") }
    var priceRial by remember { mutableStateOf(selectedPlan?.priceRial ?: 0L) }
    var discountRial by remember { mutableStateOf(0L) }
    // پیش‌فرض از تنظیمات می‌آید: ساخت اکانت + ارسال فوری پیامک فاکتور
    var createAccount by remember { mutableStateOf(true) }
    var sendSms by remember { mutableStateOf(settings.autoSmsOnInvoice) }
    var dueDaysText by remember { mutableStateOf("0") }
    var note by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    fun applyPlan(plan: Plan) {
        selectedPlan = plan
        description = plan.name
        gbText = plan.gb.toString()
        daysText = plan.days.toString()
        priceRial = plan.priceRial
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("فاکتور جدید", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(onClick = { showPicker = true }) {
                    Text(
                        selectedCustomer?.let { "مشتری: ${it.name}" } ?: "انتخاب مشتری",
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (plans.isNotEmpty()) {
                    DropdownField(
                        label = "بسته",
                        items = plans,
                        selected = selectedPlan,
                        itemLabel = { p ->
                            "${p.name} • ${Money.toPersian(p.gb.toString())} گیگ / ${Money.toPersian(p.days.toString())} روز"
                        },
                        onSelect = { applyPlan(it) }
                    )
                }

                LabeledField("شرح سرویس", description, { description = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(
                        "حجم (گیگ)",
                        gbText,
                        { gbText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Decimal
                    )
                    LabeledField(
                        "روز",
                        daysText,
                        { daysText = it.filter { ch -> ch.isDigit() } },
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number
                    )
                }
                AmountField("مبلغ (ریال)", priceRial, { priceRial = it })
                AmountField("تخفیف (ریال)", discountRial, { discountRial = it })
                LabeledField(
                    "سررسید (روز، ۰ = نقدی)",
                    dueDaysText,
                    { dueDaysText = it.filter { ch -> ch.isDigit() } },
                    keyboardType = KeyboardType.Number
                )
                LabeledField("یادداشت", note, { note = it }, singleLine = false)

                SectionCard(title = "عملیات پنل و پیامک") {
                    SwitchRow(
                        title = "ساخت اکانت در پنل هیدی‌فای",
                        checked = createAccount,
                        onChange = { createAccount = it },
                        hint = if (selectedPlan == null) "بسته‌ای انتخاب نشده" else null
                    )
                    SwitchRow(
                        title = "ارسال فاکتور با پیامک",
                        checked = sendSms,
                        onChange = { sendSms = it },
                        hint = "روش: " + if (settings.smsMode == "SIM") "سیم‌کارت گوشی" else "درگاه پیامکی"
                    )
                    val total = (priceRial - discountRial).coerceAtLeast(0)
                    Text(
                        "مبلغ نهایی: ${Money.format(total)}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting && selectedCustomer != null && priceRial > 0,
                onClick = {
                    submitting = true
                    vm.issueInvoice(
                        customerId = selectedCustomer!!.id,
                        planId = selectedPlan?.id ?: 0,
                        description = description,
                        gb = gbText.toDoubleOrNull() ?: 0.0,
                        days = daysText.toIntOrNull() ?: 0,
                        priceRial = priceRial,
                        discountRial = discountRial,
                        createPanelAccount = createAccount,
                        sendSms = sendSms,
                        dueInDays = dueDaysText.toLongOrNull() ?: 0L,
                        note = note
                    ) { result ->
                        submitting = false
                        if (result.invoiceId > 0) onIssued(result.invoiceId) else onDismiss()
                    }
                }
            ) { Text(if (submitting) "در حال صدور..." else "صدور فاکتور") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )

    if (showPicker) {
        CustomerPickerDialog(
            customers = customers.map { it.customer },
            onDismiss = { showPicker = false },
            onPick = { c: Customer ->
                selectedCustomer = c
                showPicker = false
            }
        )
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
