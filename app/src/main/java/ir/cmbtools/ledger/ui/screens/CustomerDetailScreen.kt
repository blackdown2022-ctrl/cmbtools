package ir.cmbtools.ledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.LedgerDirection
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.PaymentMethod
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.AmountField
import ir.cmbtools.ledger.ui.common.DropdownField
import ir.cmbtools.ledger.ui.common.EmptyState
import ir.cmbtools.ledger.ui.common.GradientHeader
import ir.cmbtools.ledger.ui.common.JalaliDateField
import ir.cmbtools.ledger.ui.common.LabeledField
import ir.cmbtools.ledger.ui.common.SectionCard
import ir.cmbtools.ledger.ui.common.StatusPill
import ir.cmbtools.ledger.ui.common.statusColor
import ir.cmbtools.ledger.ui.theme.AccentAmber
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.PrimaryCyan
import ir.cmbtools.ledger.ui.theme.TextMuted
import java.time.LocalDate

@Composable
fun CustomerDetailScreen(vm: AppViewModel, customerId: Long, nav: NavHostController) {
    val customer by vm.customerFlow(customerId).collectAsStateWithLifecycle(initialValue = null)
    val balance by vm.customerBalance(customerId).collectAsStateWithLifecycle(initialValue = 0L)
    val invoices by vm.customerInvoices(customerId).collectAsStateWithLifecycle(initialValue = emptyList())
    val payments by vm.customerPayments(customerId).collectAsStateWithLifecycle(initialValue = emptyList())
    val ledger by vm.customerLedger(customerId).collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by vm.customerAccounts(customerId).collectAsStateWithLifecycle(initialValue = emptyList())
    val plans by vm.plans.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(0) }
    var showEdit by remember { mutableStateOf(false) }
    var showPayment by remember { mutableStateOf(false) }
    var showAdjust by remember { mutableStateOf(false) }
    var showRenew by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(
            title = customer?.name ?: "مشتری",
            subtitle = customer?.phone?.takeIf { it.isNotBlank() }?.let { Money.toPersian(it) }
                ?: "بدون شماره",
            action = {
                Row {
                    IconButton(onClick = { showEdit = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "ویرایش", tint = PrimaryCyan)
                    }
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت", tint = TextMuted)
                    }
                }
            }
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionCard(modifier = Modifier.weight(1f)) {
                        Text("مانده حساب", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            Money.format(balance),
                            color = if (balance > 0) AccentAmber else AccentGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (balance > 0) "بدهکار" else if (balance < 0) "بستانکار" else "تسویه",
                            color = TextMuted, style = MaterialTheme.typography.labelMedium
                        )
                    }
                    SectionCard(modifier = Modifier.weight(1f)) {
                        Text("اکانت پنل", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        if (accounts.isEmpty()) {
                            Text("ثبت نشده", color = TextMuted)
                        } else {
                            val acc = accounts.first()
                            Text(
                                if (acc.enabled) "فعال" else "غیرفعال",
                                color = if (acc.enabled) AccentGreen else AccentAmber,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "تا " + Jalali.formatEpochDay(acc.expireEpochDay),
                                color = TextMuted, style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.requestNewInvoice(customerId) }, modifier = Modifier.weight(1f)) {
                        Text("فاکتور جدید")
                    }
                    OutlinedButton(onClick = { showPayment = true }, modifier = Modifier.weight(1f)) {
                        Text("ثبت پرداخت")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (accounts.isNotEmpty()) {
                        Button(onClick = { showRenew = true }, modifier = Modifier.weight(1f)) {
                            Text("تمدید اکانت")
                        }
                        OutlinedButton(onClick = { vm.sendReminder(customerId) }, modifier = Modifier.weight(1f)) {
                            Text("یادآوری")
                        }
                    } else {
                        OutlinedButton(onClick = { vm.sendReminder(customerId) }, modifier = Modifier.weight(1f)) {
                            Text("یادآوری پیامکی")
                        }
                        OutlinedButton(onClick = { showAdjust = true }, modifier = Modifier.weight(1f)) {
                            Text("تعدیل مانده")
                        }
                    }
                }
            }
            if (accounts.isNotEmpty()) {
                item {
                    OutlinedButton(onClick = { showAdjust = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("تعدیل مانده")
                    }
                }
            }

            item {
                TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("فاکتورها", "پرداخت‌ها", "دفتر", "پنل").forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(title, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }

            when (tab) {
                0 -> {
                    if (invoices.isEmpty()) item { EmptyState("فاکتوری ندارد") }
                    items(invoices, key = { it.id }) { inv ->
                        InvoiceRowCard(inv.number, inv.issuedAtEpochDay, inv.totalRial, inv.status) {
                            nav.navigate("invoice/${inv.id}")
                        }
                    }
                }
                1 -> {
                    if (payments.isEmpty()) item { EmptyState("پرداختی ثبت نشده") }
                    items(payments, key = { it.id }) { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(Money.format(p.amountRial), color = AccentGreen, fontWeight = FontWeight.SemiBold)
                                Text(
                                    PaymentMethod.label(p.method) + " • " + Jalali.formatEpochDay(p.receivedAtEpochDay),
                                    color = TextMuted, style = MaterialTheme.typography.labelMedium
                                )
                                if (p.unallocatedRial > 0) {
                                    Text(
                                        "اعتبار باقی‌مانده: " + Money.format(p.unallocatedRial),
                                        color = PrimaryCyan, style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            if (p.refNo.isNotBlank()) {
                                Text(p.refNo, color = TextMuted, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                2 -> {
                    if (ledger.isEmpty()) item { EmptyState("دفتری ثبت نشده") }
                    items(ledger, key = { it.id }) { entry ->
                        val isDebit = entry.direction == LedgerDirection.DEBIT
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.memo.ifBlank { entry.account })
                                Text(
                                    Jalali.formatEpochDay(entry.atEpochDay),
                                    color = TextMuted, style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Text(
                                (if (isDebit) "+ " else "− ") + Money.format(entry.amountRial, withUnit = false),
                                color = if (isDebit) AccentAmber else AccentGreen
                            )
                        }
                    }
                }
                else -> {
                    if (accounts.isEmpty()) item { EmptyState("اکانتی متصل نیست", "با صدور فاکتور، اکانت ساخته می‌شود") }
                    items(accounts, key = { it.id }) { acc ->
                        SectionCard {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(acc.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "مصرف " + Money.toPersian("%.1f".format(acc.usageGb)) + " از " +
                                            Money.toPersian("%.0f".format(acc.gb)) + " گیگ" +
                                            " • انقضا " + Jalali.formatEpochDay(acc.expireEpochDay),
                                        color = TextMuted, style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(acc.panelUserUuid, color = TextMuted, style = MaterialTheme.typography.labelMedium)
                                }
                                StatusPill(
                                    if (acc.enabled) "فعال" else "غیرفعال",
                                    if (acc.enabled) AccentGreen else AccentAmber
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.toggleAccount(acc) }) {
                                    Text(if (acc.enabled) "غیرفعال کن" else "فعال کن")
                                }
                                OutlinedButton(onClick = { vm.extendAccount(acc, 10.0, 30) }) {
                                    Text("+۱۰ گیگ / ۳۰ روز")
                                }
                                if (acc.subscriptionUrl.isNotBlank()) {
                                    TextButton(onClick = { vm.copyToClipboard(acc.subscriptionUrl) }) {
                                        Text("کپی لینک")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }

    if (showEdit && customer != null) {
        CustomerEditorDialog(vm = vm, existing = customer, onDismiss = { showEdit = false }, onSaved = { showEdit = false })
    }
    if (showPayment) {
        PaymentDialog(
            vm = vm,
            customerId = customerId,
            openInvoices = invoices.filter { it.status != InvoiceStatus.VOID && it.remainingRial > 0 },
            onDismiss = { showPayment = false }
        )
    }
    if (showAdjust) {
        AdjustDialog(vm = vm, customerId = customerId, onDismiss = { showAdjust = false })
    }
    if (showRenew && accounts.isNotEmpty()) {
        RenewDialog(
            vm = vm,
            customerId = customerId,
            panelUuid = accounts.first().panelUserUuid,
            plans = plans,
            onDismiss = { showRenew = false },
            onDone = { showRenew = false }
        )
    }
}

@Composable
fun InvoiceRowCard(
    number: String,
    epochDay: Long,
    totalRial: Long,
    status: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(number, fontWeight = FontWeight.SemiBold)
            Text(Jalali.formatEpochDay(epochDay), color = TextMuted, style = MaterialTheme.typography.labelMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Money.format(totalRial, withUnit = false), color = AccentGreen)
            Spacer(Modifier.width(8.dp))
            StatusPill(InvoiceStatus.label(status), statusColor(status))
        }
    }
}

@Composable
fun PaymentDialog(
    vm: AppViewModel,
    customerId: Long,
    openInvoices: List<ir.cmbtools.ledger.data.Invoice>,
    onDismiss: () -> Unit
) {
    var amount by remember { mutableStateOf(0L) }
    var method by remember { mutableStateOf(PaymentMethod.CASH) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var ref by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var invoiceId by remember { mutableStateOf(0L) }

    val methods = listOf(PaymentMethod.CASH, PaymentMethod.CARD, PaymentMethod.MANUAL)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ثبت پرداخت") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField("مبلغ (ریال)", amount, { amount = it })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    methods.forEach { m ->
                        val selected = method == m
                        Text(
                            PaymentMethod.label(m),
                            modifier = Modifier
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(50)
                                )
                                .clickable { method = m }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else TextMuted
                        )
                    }
                }
                JalaliDateField("تاریخ", date, { date = it })
                LabeledField("شمارهٔ پیگیری (اختیاری)", ref, { ref = it })
                LabeledField("یادداشت", note, { note = it })
                if (openInvoices.isNotEmpty()) {
                    Text("تخصیص به فاکتور", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { invoiceId = 0L },
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("خودکار (قدیمی‌ترین بدهی)", color = if (invoiceId == 0L) PrimaryCyan else TextMuted)
                        if (invoiceId == 0L) Text("✓", color = PrimaryCyan)
                    }
                    openInvoices.take(5).forEach { inv ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { invoiceId = inv.id },
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${inv.number} • مانده ${Money.format(inv.remainingRial, withUnit = false)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (invoiceId == inv.id) PrimaryCyan else TextMuted
                            )
                            if (invoiceId == inv.id) Text("✓", color = PrimaryCyan)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = amount > 0,
                onClick = {
                    vm.recordPayment(customerId, amount, method, date, ref, note, invoiceId)
                    onDismiss()
                }
            ) { Text("ثبت") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
private fun AdjustDialog(vm: AppViewModel, customerId: Long, onDismiss: () -> Unit) {
    var isIncrease by remember { mutableStateOf(true) }
    var amount by remember { mutableStateOf(0L) }
    var memo by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعدیل مانده") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { isIncrease = true }, modifier = Modifier.weight(1f)) {
                        Text(if (isIncrease) "افزایش بدهی ✓" else "افزایش بدهی")
                    }
                    OutlinedButton(onClick = { isIncrease = false }, modifier = Modifier.weight(1f)) {
                        Text(if (!isIncrease) "کاهش بدهی ✓" else "کاهش بدهی")
                    }
                }
                AmountField("مبلغ (ریال)", amount, { amount = it })
                JalaliDateField("تاریخ", date, { date = it })
                LabeledField("علت", memo, { memo = it })
            }
        },
        confirmButton = {
            Button(enabled = amount > 0, onClick = {
                vm.adjustBalance(customerId, if (isIncrease) amount else -amount, memo, date)
                onDismiss()
            }) { Text("ثبت") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

/**
 * تمدید = دورهٔ جدید در پنل (ریست مصرف + شمارش روز از اتصال بعدی) + فاکتور تمدید + پیامک اختیاری.
 */
@Composable
private fun RenewDialog(
    vm: AppViewModel,
    customerId: Long,
    panelUuid: String,
    plans: List<ir.cmbtools.ledger.data.Plan>,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    var plan by remember { mutableStateOf(plans.firstOrNull()) }
    var gb by remember { mutableStateOf(plans.firstOrNull()?.gb?.toString() ?: "30") }
    var days by remember { mutableStateOf(plans.firstOrNull()?.days?.toString() ?: "30") }
    var price by remember { mutableStateOf(plans.firstOrNull()?.priceRial ?: 0L) }
    var description by remember { mutableStateOf(plans.firstOrNull()?.name ?: "تمدید سرویس") }
    var sendSms by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تمدید اکانت") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plans.isNotEmpty()) {
                    DropdownField(
                        label = "بستهٔ تمدید",
                        items = plans,
                        selected = plan,
                        itemLabel = { p ->
                            p.name + " • " + Money.toPersian(p.gb.toString()) + " گیگ / " +
                                Money.toPersian(p.days.toString()) + " روز"
                        },
                        onSelect = { p ->
                            plan = p
                            description = p.name
                            gb = p.gb.toString()
                            days = p.days.toString()
                            price = p.priceRial
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(
                        "حجم (گیگ)",
                        gb,
                        { gb = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        modifier = Modifier.weight(1f),
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                    LabeledField(
                        "روز",
                        days,
                        { days = it.filter { ch -> ch.isDigit() } },
                        modifier = Modifier.weight(1f),
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                }
                AmountField("مبلغ فاکتور تمدید (ریال)", price, { price = it })
                LabeledField("شرح", description, { description = it })
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ارسال فاکتور با پیامک", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Switch(checked = sendSms, onCheckedChange = { sendSms = it })
                }
                Text(
                    "مصرف اکانت صفر می‌شود و شمارش روزها از اولین اتصال بعدی شروع می‌شود (همان رفتار دکمهٔ تمدید پنل).",
                    color = TextMuted,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !working,
                onClick = {
                    working = true
                    vm.renewAccount(
                        customerId = customerId,
                        panelUuid = panelUuid,
                        planId = plan?.id ?: 0,
                        description = description,
                        gb = gb.toDoubleOrNull() ?: 0.0,
                        days = days.toIntOrNull() ?: 0,
                        priceRial = price,
                        sendSms = sendSms
                    ) { working = false; onDone() }
                }
            ) { Text(if (working) "در حال تمدید..." else "تمدید کن") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}
