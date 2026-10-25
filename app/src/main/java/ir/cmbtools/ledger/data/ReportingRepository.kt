package ir.cmbtools.ledger.data

import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

data class DashboardSummary(
    val revenueThisMonthRial: Long = 0,
    val revenueTodayRial: Long = 0,
    val totalReceivableRial: Long = 0,
    val unallocatedCreditRial: Long = 0,
    val activeAccounts: Int = 0,
    val expiringIn7Days: Int = 0,
    val unmatchedBankSms: Int = 0,
    val customersCount: Int = 0,
    val expenseThisMonthRial: Long = 0
) {
    val profitThisMonthRial: Long get() = revenueThisMonthRial - expenseThisMonthRial
}

data class DailyPoint(val epochDay: Long, val label: String, val amountRial: Long)

data class AgingBucket(val label: String, val amountRial: Long, val customers: Int)

data class AgingReport(val buckets: List<AgingBucket>) {
    val totalRial: Long get() = buckets.sumOf { it.amountRial }
}

data class PlanSalesRow(val planName: String, val count: Int, val amountRial: Long)

class ReportingRepository(
    private val invoices: InvoiceDao,
    private val payments: PaymentDao,
    private val expenses: ExpenseDao,
    private val ledger: LedgerDao,
    private val customers: CustomerDao,
    private val panel: PanelDao,
    private val bankSms: BankSmsDao
) {
    fun observeTotalReceivable(): Flow<Long> = ledger.observeTotalReceivable()

    fun observeDashboard(): Flow<DashboardSummary> {
        return kotlinx.coroutines.flow.combine(
            ledger.observeTotalReceivable(),
            customers.observeAll(),
            panel.observeAccounts(),
            bankSms.observeUnmatched()
        ) { receivable, customerList, accounts, unmatched ->
            val today = LocalDate.now()
            val monthStart = startOfJalaliMonth(today)
            val allInvoices = invoices.between(monthStart.toEpochDay(), today.toEpochDay())
            val todayInvoices = allInvoices.filter { it.issuedAtEpochDay == today.toEpochDay() }
            val monthPayments = payments.between(monthStart.toEpochDay(), today.toEpochDay())
            val monthExpenses = expenses.between(monthStart.toEpochDay(), today.toEpochDay())

            val revenueMonth = monthPayments.sumOf { it.amountRial }
            val expiring = accounts.count {
                it.expireEpochDay in 1..(today.toEpochDay() + 7) && it.enabled
            }
            DashboardSummary(
                revenueThisMonthRial = revenueMonth,
                revenueTodayRial = todayInvoices.sumOf { it.totalRial },
                totalReceivableRial = receivable,
                unallocatedCreditRial = monthPayments.sumOf { it.unallocatedRial },
                activeAccounts = accounts.count { it.enabled },
                expiringIn7Days = expiring,
                unmatchedBankSms = unmatched.size,
                customersCount = customerList.size,
                expenseThisMonthRial = monthExpenses.sumOf { it.amountRial }
            )
        }
    }

    /** نمودار فروش ۱۴ روز اخیر */
    fun observeDailySales(days: Int = 14): Flow<List<DailyPoint>> =
        invoices.observeAll().map { list ->
            val today = LocalDate.now()
            (days - 1 downTo 0).map { offset ->
                val day = today.minusDays(offset.toLong())
                val total = list.filter { it.issuedAtEpochDay == day.toEpochDay() && it.status != InvoiceStatus.VOID }
                    .sumOf { it.totalRial }
                val j = Jalali.fromGregorian(day)
                DailyPoint(
                    epochDay = day.toEpochDay(),
                    label = Money.toPersian("%02d/%02d".format(j.month, j.day)),
                    amountRial = total
                )
            }
        }

    /** گزارش سنی بدهی بر اساس تاریخ فاکتورهای پرداخت‌نشده */
    fun observeAging(): Flow<AgingReport> =
        kotlinx.coroutines.flow.combine(invoices.observeAll(), customers.observeAll()) { invoiceList, customerList ->
            val today = LocalDate.now()
            val buckets = longArrayOf(0, 0, 0, 0)
            val counts = IntArray(4)
            invoiceList.filter { it.status != InvoiceStatus.VOID && it.status != InvoiceStatus.PAID }
                .filter { it.remainingRial > 0 }
                .forEach { inv ->
                    val age = today.toEpochDay() - inv.issuedAtEpochDay
                    val idx = when {
                        age <= 30 -> 0
                        age <= 60 -> 1
                        age <= 90 -> 2
                        else -> 3
                    }
                    buckets[idx] += inv.remainingRial
                    counts[idx] += 1
                }
            AgingReport(
                listOf(
                    AgingBucket("۰ تا ۳۰ روز", buckets[0], counts[0]),
                    AgingBucket("۳۱ تا ۶۰ روز", buckets[1], counts[1]),
                    AgingBucket("۶۱ تا ۹۰ روز", buckets[2], counts[2]),
                    AgingBucket("بیش از ۹۰ روز", buckets[3], counts[3])
                )
            )
        }

    fun observePlanSales(): Flow<List<PlanSalesRow>> = invoices.observeAll().map { list ->
        list.filter { it.status != InvoiceStatus.VOID }
            .groupBy { if (it.planId == 0L) "سایر" else "بستهٔ #${it.planId}" }
            .map { (name, invs) -> PlanSalesRow(name, invs.size, invs.sumOf { it.totalRial }) }
            .sortedByDescending { it.amountRial }
    }

    suspend fun monthlySales(months: Int = 6): List<Pair<String, Long>> {
        val today = LocalDate.now()
        val result = mutableListOf<Pair<String, Long>>()
        var cursor = startOfJalaliMonth(today)
        repeat(months) {
            val next = nextJalaliMonth(cursor)
            val from = cursor.toEpochDay()
            val to = next.minusDays(1).toEpochDay()
            val total = invoices.between(from, to).filter { it.status != InvoiceStatus.VOID }.sumOf { it.totalRial }
            val j = Jalali.fromGregorian(cursor)
            result.add(0, "${Jalali.monthName(j.month)} ${Money.toPersian(j.year.toString())}" to total)
            cursor = previousJalaliMonth(cursor)
        }
        return result
    }

    companion object {
        fun startOfJalaliMonth(date: LocalDate): LocalDate {
            val j = Jalali.fromGregorian(date)
            return Jalali.toGregorian(Jalali.JDate(j.year, j.month, 1))
        }

        fun nextJalaliMonth(date: LocalDate): LocalDate {
            val j = Jalali.fromGregorian(startOfJalaliMonth(date))
            val (y, m) = if (j.month == 12) (j.year + 1) to 1 else j.year to (j.month + 1)
            return Jalali.toGregorian(Jalali.JDate(y, m, 1))
        }

        fun previousJalaliMonth(date: LocalDate): LocalDate {
            val j = Jalali.fromGregorian(startOfJalaliMonth(date))
            val (y, m) = if (j.month == 1) (j.year - 1) to 12 else j.year to (j.month - 1)
            return Jalali.toGregorian(Jalali.JDate(y, m, 1))
        }
    }
}
