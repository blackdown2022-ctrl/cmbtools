package ir.cmbtools.ledger.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import ir.cmbtools.ledger.ui.common.LabeledField
import ir.cmbtools.ledger.ui.common.SectionCard
import ir.cmbtools.ledger.ui.theme.TextMuted

/**
 * فرم صدور فاکتور (+ ساخت اکانت در پنل).
 *
 * نکته‌های مهم که باعث می‌شد دکمهٔ «صدور فاکتور» غیرفعال بماند و اینجا رفع شده‌اند:
 *  ۱) بسته‌ها به‌صورت async می‌آیند؛ اگر فرم قبل از رسیدن آن‌ها ساخته می‌شد، قیمت صفر می‌ماند.
 *     حالا با LaunchedEffect اولین بسته انتخاب و قیمتش پر می‌شود.
 *  ۲) انتخاب بسته با DropdownField (ExposedDropdownMenu) داخل AlertDialog روی بعضی گوشی‌ها
 *     باز نمی‌شد؛ حالا ردیف چیپ‌های افقی است که همیشه کار می‌کند.
 *  ۳) دکمه دیگر «بی‌صدا» غیرفعال نمی‌شود: همیشه قابل لمس است و دلیل ناقص بودن فرم را می‌گوید.
 */
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
    var selectedPlan by remember { mutableStateOf<Plan?>(null) }
    var description by remember { mutableStateOf("") }
    var gbText by remember { mutableStateOf("30") }
    var daysText by remember { mutableStateOf("30") }
    var priceRial by remember { mutableStateOf(0L) }
    var discountRial by remember { mutableStateOf(0L) }
    // پیش‌فرض از تنظیمات می‌آید: ساخت اکانت + ارسال فوری پیامک فاکتور
    var createAccount by remember { mutableStateOf(true) }
    var sendSms by remember { mutableStateOf(settings.autoSmsOnInvoice) }
    var dueDaysText by remember { mutableStateOf("0") }
    var note by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf("") }

    fun applyPlan(plan: Plan) {
        selectedPlan = plan
        description = plan.name
        gbText = plan.gb.toString()
        daysText = plan.days.toString()
        // قیمت بسته اعمال می‌شود؛ اگر صفر بود (بستهٔ رایگان) کاربر می‌تواند دستی بنویسد
        if (plan.priceRial > 0) priceRial = plan.priceRial
        formError = ""
    }

    // ① اگر مشتری از قبل تعیین شده ولی لیست مشتری‌ها دیر رسیده است
    LaunchedEffect(customers, presetCustomerId) {
        if (selectedCustomer == null && presetCustomerId > 0) {
            selectedCustomer = customers.firstOrNull { it.customer.id == presetCustomerId }?.customer
        }
    }

    // ② انتخاب خودکار اولین بسته به‌محض رسیدن لیست (ریشهٔ اصلی مشکل «دکمه فعال نمی‌شود»)
    LaunchedEffect(plans) {
        if (selectedPlan == null && plans.isNotEmpty()) applyPlan(plans.first())
    }

    val total = (priceRial - discountRial).coerceAtLeast(0)
    val missing = when {
        selectedCustomer == null -> "مشتری انتخاب نشده است"
        priceRial <= 0L -> "مبلغ فاکتور را وارد کنید یا یک بسته انتخاب کنید"
        else -> ""
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
                    Text("بسته", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        plans.forEach { plan ->
                            FilterChip(
                                selected = selectedPlan?.id == plan.id,
                                onClick = { applyPlan(plan) },
                                label = { Text(plan.name) }
                            )
                        }
                    }
                    selectedPlan?.let { plan ->
                        Text(
                            Money.toPersian(plan.gb.toString()) + " گیگ • " +
                                Money.toPersian(plan.days.toString()) + " روز • " +
                                Money.format(plan.priceRial),
                            color = TextMuted,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                } else {
                    Text(
                        "بسته‌ای تعریف نشده است؛ می‌توانید حجم/روز و مبلغ را دستی وارد کنید.",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium
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
                AmountField("مبلغ (ریال)", priceRial, { priceRial = it; formError = "" })
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
                        hint = "شمارهٔ مشتری در «یادداشت» کاربر پنل ثبت می‌شود"
                    )
                    SwitchRow(
                        title = "ارسال فاکتور با پیامک",
                        checked = sendSms,
                        onChange = { sendSms = it },
                        hint = "روش: " + if (settings.smsMode == "SIM") "سیم‌کارت گوشی" else "درگاه پیامکی"
                    )
                    Text(
                        "مبلغ نهایی: ${Money.format(total)}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // دلیل غیرفعال نبودن در دسترس بودن دکمه: پیام روشن زیر فرم
                val message = formError.ifBlank { missing }
                if (message.isNotBlank()) {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            Button(
                // عمداً همیشه فعال است (به‌جز هنگام ارسال) تا کاربر هیچ‌وقت در بن‌بست نماند
                enabled = !submitting,
                onClick = {
                    val validation = when {
                        selectedCustomer == null -> "ابتدا مشتری را انتخاب کنید"
                        priceRial <= 0L -> "مبلغ فاکتور باید بیشتر از صفر باشد"
                        createAccount && (gbText.toDoubleOrNull() ?: 0.0) <= 0.0 ->
                            "برای ساخت اکانت، حجم بسته را وارد کنید"
                        createAccount && (daysText.toIntOrNull() ?: 0) <= 0 ->
                            "برای ساخت اکانت، تعداد روز را وارد کنید"
                        else -> ""
                    }
                    if (validation.isNotBlank()) {
                        formError = validation
                        vm.report(validation, isError = true)
                        return@Button
                    }
                    formError = ""
                    submitting = true
                    vm.issueInvoice(
                        customerId = selectedCustomer!!.id,
                        planId = selectedPlan?.id ?: 0,
                        description = description.ifBlank { selectedPlan?.name ?: "سرویس" },
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
                formError = ""
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
