package ir.cmbtools.ledger.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.LedgerDirection
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.PaymentMethod
import ir.cmbtools.ledger.data.Customer
import ir.cmbtools.ledger.data.CustomerRow
import ir.cmbtools.ledger.data.Invoice
import ir.cmbtools.ledger.data.Payment
import ir.cmbtools.ledger.export.ExportManager
import ir.cmbtools.ledger.ui.AppViewModel
import ir.cmbtools.ledger.ui.common.CustomerPickerDialog
import ir.cmbtools.ledger.ui.common.EmptyState
import ir.cmbtools.ledger.ui.common.GradientHeader
import ir.cmbtools.ledger.ui.common.SectionCard
import ir.cmbtools.ledger.ui.common.StatCard
import ir.cmbtools.ledger.ui.common.StatusPill
import ir.cmbtools.ledger.ui.theme.AccentAmber
import ir.cmbtools.ledger.ui.theme.AccentGreen
import ir.cmbtools.ledger.ui.theme.AccentRed
import ir.cmbtools.ledger.ui.theme.PrimaryCyan
import ir.cmbtools.ledger.ui.theme.TextMuted
import java.time.LocalDate

private data class ReportMetric(val title: String, val value: String, val color: Color, val hint: String? = null)

private enum class TransactionFilter(val title: String) {
    ALL("همه"), INVOICES("فاکتورها"), PAYMENTS("دریافت‌ها"), EXPENSES("هزینه‌ها"), OTHER("تعدیل‌ها")
}

private data class ReportTransaction(
    val id: String,
    val category: TransactionFilter,
    val title: String,
    val detail: String,
    val dateLabel: String,
    val sortTime: Long,
    val amountRial: Long,
    val amountColor: Color,
    val customerId: Long = 0,
    val invoiceId: Long = 0
)

@Composable
fun ReportsScreen(vm: AppViewModel, nav: NavHostController) {
    val summary by vm.dashboard.collectAsStateWithLifecycle()
    val aging by vm.aging.collectAsStateWithLifecycle()
    val customerRows by vm.customers.collectAsStateWithLifecycle()
    val invoices by vm.invoices.collectAsStateWithLifecycle()
    val payments by vm.allPayments.collectAsStateWithLifecycle()
    val ledgerEntries by vm.allLedgerEntries.collectAsStateWithLifecycle()
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(0) }
    var transactionFilter by remember { mutableStateOf(TransactionFilter.ALL) }
    var selectedCustomer by remember { mutableStateOf<Customer?>(null) }
    var showCustomerPicker by remember { mutableStateOf(false) }

    val customerNames = remember(customerRows) { customerRows.associate { it.customer.id to it.customer.name } }
    val activeInvoices = remember(invoices) { invoices.filter { it.status != InvoiceStatus.VOID } }
    val totalInvoiced = remember(activeInvoices) { activeInvoices.sumOf { it.totalRial } }
    val totalReceived = remember(payments) { payments.sumOf { it.amountRial } }
    val totalExpenses = remember(expenses) { expenses.sumOf { it.amountRial } }
    val debtors = remember(customerRows) {
        customerRows.filter { it.balanceRial > 0 }.sortedByDescending { it.balanceRial }
    }
    val transactions = remember(ledgerEntries, expenses, customerNames) {
        buildList {
            ledgerEntries.forEach { entry ->
                val category = when (entry.refType) {
                    "INVOICE", "INVOICE_VOID" -> TransactionFilter.INVOICES
                    "PAYMENT" -> TransactionFilter.PAYMENTS
                    else -> TransactionFilter.OTHER
                }
                val title = when (entry.refType) {
                    "INVOICE" -> "صدور فاکتور"
                    "INVOICE_VOID" -> "ابطال فاکتور"
                    "PAYMENT" -> "دریافت وجه"
                    else -> "تعدیل حساب"
                }
                val customerName = customerNames[entry.customerId] ?: "مشتری"
                val amountColor = if (entry.direction == LedgerDirection.DEBIT) AccentAmber else AccentGreen
                add(
                    ReportTransaction(
                        id = "ledger-${entry.id}",
                        category = category,
                        title = "$title • $customerName",
                        detail = entry.memo.ifBlank { entry.account },
                        dateLabel = Jalali.formatEpochDay(entry.atEpochDay),
                        sortTime = entry.atMillis,
                        amountRial = entry.amountRial,
                        amountColor = amountColor,
                        customerId = entry.customerId,
                        invoiceId = if (entry.refType == "INVOICE" || entry.refType == "INVOICE_VOID") entry.refId else 0
                    )
                )
            }
            expenses.forEach { expense ->
                add(
                    ReportTransaction(
                        id = "expense-${expense.id}",
                        category = TransactionFilter.EXPENSES,
                        title = expense.title,
                        detail = expense.category + expense.note.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty(),
                        dateLabel = Jalali.formatEpochDay(expense.atEpochDay),
                        sortTime = expense.atEpochDay * 86_400_000L,
                        amountRial = expense.amountRial,
                        amountColor = AccentRed
                    )
                )
            }
        }.sortedByDescending { it.sortTime }
    }
    val filteredTransactions = remember(transactions, transactionFilter) {
        if (transactionFilter == TransactionFilter.ALL) transactions
        else transactions.filter { it.category == transactionFilter }
    }
    val reportListState = rememberLazyListState()
    LaunchedEffect(tab, transactionFilter, selectedCustomer?.id) {
        reportListState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        GradientHeader(title = "گزارش‌ها", subtitle = "گزارش کامل مالی، تراکنش‌ها و خرید مشتریان")
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = PrimaryCyan
        ) {
            listOf("نمای کلی", "همهٔ تراکنش‌ها", "خرید مشتری").forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }

        LazyColumn(
            state = reportListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (tab) {
                0 -> {
                    item {
                        MetricGrid(
                            listOf(
                                ReportMetric("فروش ثبت‌شده", Money.compact(totalInvoiced), AccentAmber, "مجموع فاکتورهای غیرابطال‌شده"),
                                ReportMetric("کل دریافتی", Money.compact(totalReceived), AccentGreen, "تمام پرداخت‌های ثبت‌شده"),
                                ReportMetric("مطالبات فعلی", Money.compact(summary.totalReceivableRial), PrimaryCyan, "ماندهٔ دفتر مشتریان"),
                                ReportMetric("هزینه‌های ثبت‌شده", Money.compact(totalExpenses), AccentRed),
                                ReportMetric(
                                    "سود ماه جاری",
                                    Money.compact(summary.profitThisMonthRial),
                                    if (summary.profitThisMonthRial >= 0) AccentGreen else AccentRed
                                )
                            )
                        )
                    }
                    item {
                        SectionCard(title = "سنی مطالبات") {
                            if (aging.totalRial == 0L) {
                                EmptyState("بدهی معوقی وجود ندارد")
                            } else {
                                aging.buckets.forEach { bucket ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(bucket.label, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${Money.format(bucket.amountRial)} • ${Money.toPersian(bucket.customers.toString())} مشتری",
                                            color = if (bucket.amountRial > 0) AccentAmber else TextMuted,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        SectionCard(title = "بدهکاران برتر") {
                            if (debtors.isEmpty()) EmptyState("همهٔ مشتریان تسویه‌اند")
                            else debtors.take(10).forEach { row -> DebtorRow(row, nav) }
                            OutlinedButton(
                                onClick = { tab = 2; showCustomerPicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("مشاهدهٔ خرید و حساب مشتری")
                            }
                        }
                    }
                    item {
                        SectionCard(title = "گزارش و خروجی") {
                            Text(
                                "تعداد تراکنش‌های مالی: ${Money.toPersian(transactions.size.toString())}",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        ExportManager(context, vm.appContainer())
                                            .exportInvoicesCsv(invoices, customerNames)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("خروجی فاکتورها") }
                                OutlinedButton(
                                    onClick = {
                                        ExportManager(context, vm.appContainer())
                                            .exportPaymentsCsv(payments, customerNames)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("خروجی دریافت‌ها") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        ExportManager(context, vm.appContainer()).exportCustomersCsv(customerRows)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("خروجی مشتریان") }
                                OutlinedButton(
                                    onClick = { nav.navigate("expenses") },
                                    modifier = Modifier.weight(1f)
                                ) { Text("مدیریت هزینه‌ها") }
                            }
                        }
                    }
                    item {
                        Text(
                            "به‌روزرسانی گزارش: ${Jalali.format(LocalDate.now())}",
                            color = TextMuted,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                1 -> {
                    item {
                        MetricGrid(
                            listOf(
                                ReportMetric("تعداد رویدادها", Money.toPersian(transactions.size.toString()), PrimaryCyan),
                                ReportMetric("فاکتورها", Money.compact(totalInvoiced), AccentAmber),
                                ReportMetric("دریافت‌ها", Money.compact(totalReceived), AccentGreen),
                                ReportMetric("هزینه‌ها", Money.compact(totalExpenses), AccentRed)
                            )
                        )
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(TransactionFilter.entries) { filter ->
                                FilterChip(
                                    selected = transactionFilter == filter,
                                    onClick = { transactionFilter = filter },
                                    label = { Text(filter.title, style = MaterialTheme.typography.labelLarge) }
                                )
                            }
                        }
                    }
                    if (filteredTransactions.isEmpty()) {
                        item { EmptyState("تراکنشی برای این فیلتر پیدا نشد") }
                    } else {
                        items(filteredTransactions, key = { it.id }) { transaction ->
                            val openDetails: (() -> Unit)? = when {
                                transaction.invoiceId > 0 -> ({ nav.navigate("invoice/${transaction.invoiceId}") })
                                transaction.customerId > 0 -> ({ nav.navigate("customer/${transaction.customerId}") })
                                else -> null
                            }
                            TransactionCard(transaction, openDetails)
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
                else -> {
                    item {
                        SectionCard(title = "انتخاب مشتری") {
                            OutlinedButton(onClick = { showCustomerPicker = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(selectedCustomer?.let { "${it.name}  •  تغییر مشتری" } ?: "جست‌وجو و انتخاب مشتری")
                            }
                            Text(
                                "همهٔ فاکتورها و پرداخت‌های ثبت‌شده برای مشتری انتخابی، از قدیمی‌ترین تا جدیدترین دادهٔ ذخیره‌شده قابل مشاهده است.",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    val customer = selectedCustomer
                    if (customer == null) {
                        item { EmptyState("مشتری‌ای انتخاب نشده است", "برای مشاهدهٔ خریدها و پرداخت‌ها، ابتدا مشتری را انتخاب کنید") }
                    } else {
                        val customerInvoices = invoices.filter { it.customerId == customer.id }
                            .sortedWith(compareByDescending<Invoice> { it.issuedAtEpochDay }.thenByDescending { it.id })
                        val customerPayments = payments.filter { it.customerId == customer.id }
                            .sortedWith(compareByDescending<Payment> { it.receivedAtEpochDay }.thenByDescending { it.id })
                        val validInvoices = customerInvoices.filter { it.status != InvoiceStatus.VOID }
                        val openBalance = validInvoices.sumOf { it.remainingRial }
                        item {
                            MetricGrid(
                                listOf(
                                    ReportMetric("خرید / فاکتور", Money.compact(validInvoices.sumOf { it.totalRial }), AccentAmber, "${Money.toPersian(validInvoices.size.toString())} فاکتور"),
                                    ReportMetric("پرداخت‌های ثبت‌شده", Money.compact(customerPayments.sumOf { it.amountRial }), AccentGreen, "${Money.toPersian(customerPayments.size.toString())} پرداخت"),
                                    ReportMetric("ماندهٔ فاکتورها", Money.compact(openBalance), if (openBalance > 0) AccentRed else AccentGreen),
                                    ReportMetric("اعتبار استفاده‌نشده", Money.compact(customerPayments.sumOf { it.unallocatedRial }), PrimaryCyan)
                                )
                            )
                        }
                        item { SectionCard(title = "سوابق خرید و فاکتورها • ${customer.name}") {
                            if (customerInvoices.isEmpty()) EmptyState("فاکتوری برای این مشتری ثبت نشده است")
                        } }
                        items(customerInvoices, key = { "customer-invoice-${it.id}" }) { invoice ->
                            PurchaseCard(invoice) { nav.navigate("invoice/${invoice.id}") }
                        }
                        item { SectionCard(title = "سوابق دریافت وجه") {
                            if (customerPayments.isEmpty()) EmptyState("پرداختی برای این مشتری ثبت نشده است")
                        } }
                        items(customerPayments, key = { "customer-payment-${it.id}" }) { payment ->
                            PaymentCard(payment)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }

    if (showCustomerPicker) {
        CustomerPickerDialog(
            customers = customerRows.map(CustomerRow::customer),
            title = "انتخاب مشتری برای گزارش خرید",
            onDismiss = { showCustomerPicker = false },
            onPick = {
                selectedCustomer = it
                showCustomerPicker = false
            }
        )
    }
}

@Composable
private fun MetricGrid(metrics: List<ReportMetric>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 760.dp -> 4
            maxWidth >= 320.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            metrics.chunked(columns).forEach { rowMetrics ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowMetrics.forEach { metric ->
                        StatCard(
                            title = metric.title,
                            value = metric.value,
                            modifier = Modifier.weight(1f),
                            accent = metric.color,
                            subtitle = metric.hint
                        )
                    }
                    repeat(columns - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DebtorRow(row: CustomerRow, nav: NavHostController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { nav.navigate("customer/${row.customer.id}") }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(row.customer.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Text(row.customer.phone.ifBlank { "بدون شماره" }, color = TextMuted, style = MaterialTheme.typography.labelMedium)
        }
        Text(Money.format(row.balanceRial), color = if (row.overLimit) AccentRed else AccentAmber, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TransactionCard(transaction: ReportTransaction, onClick: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth().then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(transaction.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                Text(transaction.detail, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(transaction.dateLabel, color = TextMuted, style = MaterialTheme.typography.labelMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Money.format(transaction.amountRial),
                    color = transaction.amountColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(5.dp))
                StatusPill(transaction.category.title, transaction.amountColor)
            }
        }
    }
}

@Composable
private fun PurchaseCard(invoice: Invoice, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("فاکتور ${Money.toPersian(invoice.number)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                StatusPill(InvoiceStatus.label(invoice.status), when (invoice.status) {
                    InvoiceStatus.PAID -> AccentGreen
                    InvoiceStatus.PARTIAL -> AccentAmber
                    InvoiceStatus.VOID -> AccentRed
                    else -> PrimaryCyan
                })
            }
            Text(invoice.packageName.ifBlank { "فاکتور فروش / سرویس" }, style = MaterialTheme.typography.bodyMedium)
            Text("تاریخ: ${Jalali.formatEpochDay(invoice.issuedAtEpochDay)}", color = TextMuted, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("کل: ${Money.format(invoice.totalRial)}", fontWeight = FontWeight.SemiBold, color = PrimaryCyan)
                Text("پرداخت: ${Money.format(invoice.paidRial)}", color = AccentGreen)
            }
            if (invoice.remainingRial > 0) Text("مانده: ${Money.format(invoice.remainingRial)}", color = AccentRed)
        }
    }
}

@Composable
private fun PaymentCard(payment: Payment) {
    SectionCard {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(PaymentMethod.label(payment.method), fontWeight = FontWeight.SemiBold)
                Text(Jalali.formatEpochDay(payment.receivedAtEpochDay), color = TextMuted, style = MaterialTheme.typography.labelMedium)
                if (payment.refNo.isNotBlank()) Text("پیگیری: ${payment.refNo}", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                if (payment.note.isNotBlank()) Text(payment.note, color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Money.format(payment.amountRial), color = AccentGreen, fontWeight = FontWeight.Bold)
                if (payment.unallocatedRial > 0) Text("اعتبار ${Money.format(payment.unallocatedRial)}", color = PrimaryCyan, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
