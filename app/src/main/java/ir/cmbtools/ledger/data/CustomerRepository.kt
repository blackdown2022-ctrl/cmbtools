package ir.cmbtools.ledger.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class CustomerRow(
    val customer: Customer,
    val balanceRial: Long,
    val lastInvoiceEpochDay: Long = 0
) {
    /** بدهی سررسیدگذشته‌ای ندارد ولی مانده مثبت است */
    val isDebtor: Boolean get() = balanceRial > 0
    val overLimit: Boolean
        get() = customer.creditLimitRial > 0 && balanceRial > customer.creditLimitRial
}

class CustomerRepository(
    private val customers: CustomerDao,
    private val ledger: LedgerDao,
    private val invoices: InvoiceDao
) {
    fun observeAll(): Flow<List<Customer>> = customers.observeAll()

    fun observeRows(): Flow<List<CustomerRow>> =
        combine(customers.observeAll(), ledger.observeBalances(), invoices.observeAll()) { list, balances, invoiceList ->
            val balanceMap = balances.associate { it.customerId to it.balanceRial }
            val lastInvoice = invoiceList.groupBy { it.customerId }
                .mapValues { (_, invs) -> invs.maxOfOrNull { it.issuedAtEpochDay } ?: 0L }
            list.map { c ->
                CustomerRow(
                    customer = c,
                    balanceRial = balanceMap[c.id] ?: 0L,
                    lastInvoiceEpochDay = lastInvoice[c.id] ?: 0L
                )
            }
        }

    fun observeDebtors(): Flow<List<CustomerRow>> = observeRows().map { rows ->
        rows.filter { it.balanceRial != 0L }.sortedByDescending { it.balanceRial }
    }

    fun observeById(id: Long): Flow<Customer?> = customers.observeById(id)

    fun observeBalance(id: Long): Flow<Long> = ledger.observeBalance(id)

    fun observeOpeningBalance(id: Long): Flow<Long> = ledger.observeOpeningBalance(id)

    suspend fun get(id: Long): Customer? = customers.byId(id)

    /**
     * ثبت یا اصلاح ماندهٔ ابتدای استفاده از اپ.
     * به‌جای تغییر سابقه، اختلاف به‌صورت یک رویداد دفتر ثبت می‌شود تا تاریخچه حفظ شود.
     */
    suspend fun setOpeningBalance(customerId: Long, targetRial: Long, epochDay: Long) {
        val currentRial = ledger.openingBalance(customerId)
        val deltaRial = try {
            Math.subtractExact(targetRial, currentRial)
        } catch (_: ArithmeticException) {
            error("مبلغ مانده از حد مجاز بیشتر است")
        }
        if (deltaRial == Long.MIN_VALUE) error("مبلغ مانده از حد مجاز بیشتر است")

        if (deltaRial != 0L) {
            ledger.insert(
                LedgerEntry(
                    customerId = customerId,
                    atEpochDay = epochDay,
                    direction = if (deltaRial > 0) ir.cmbtools.ledger.core.LedgerDirection.DEBIT else ir.cmbtools.ledger.core.LedgerDirection.CREDIT,
                    amountRial = kotlin.math.abs(deltaRial),
                    account = ir.cmbtools.ledger.core.LedgerAccount.ADJUSTMENT,
                    refType = "OPENING_BALANCE",
                    memo = if (currentRial == 0L) "مانده از قبل" else "اصلاح مانده از قبل"
                )
            )
        }

        reconcileOpeningBalanceSnapshots(customerId)
    }

    /**
     * همگام‌سازی idempotent ماندهٔ افتتاحیه با snapshot همهٔ فاکتورهای مشتری.
     * هنگام ورود به فاکتور و پیش از ارسال پیامک هم صدا زده می‌شود تا دفتر مشتری و سند جدا نشوند.
     */
    suspend fun reconcileOpeningBalanceSnapshots(customerId: Long): Int {
        val targetRial = ledger.openingBalance(customerId)
        var updated = 0
        for (invoice in invoices.allForCustomer(customerId)) {
            val reconciled = invoice.withOpeningBalanceTarget(targetRial)
            if (reconciled != invoice) {
                invoices.update(reconciled)
                updated++
            }
        }
        return updated
    }

    /** یک‌بار همگام‌سازی همهٔ snapshotها پس از بازشدن دیتابیس/ورود به نسخهٔ جدید. */
    suspend fun reconcileAllOpeningBalanceSnapshots(): Int {
        var updated = 0
        for (customer in customers.all()) {
            updated += reconcileOpeningBalanceSnapshots(customer.id)
        }
        return updated
    }

    suspend fun create(name: String, phone: String, cardLast4: String, note: String, creditLimitRial: Long): Long {
        require(name.isNotBlank()) { "نام مشتری الزامی است" }
        return customers.insert(
            Customer(
                name = name.trim(),
                phone = phone.trim(),
                cardLast4 = cardLast4.trim().takeLast(4),
                note = note.trim(),
                creditLimitRial = creditLimitRial.coerceAtLeast(0)
            )
        )
    }

    suspend fun update(customer: Customer) = customers.update(customer)

    suspend fun archive(id: Long) = customers.archive(id)

    /** اصلاح مانده به‌صورت دستی (تعدیل) — یک ثبت جدید، بدون دست‌زدن به گذشته */
    suspend fun adjustBalance(customerId: Long, deltaRial: Long, memo: String, epochDay: Long) {
        if (deltaRial == 0L) return
        ledger.insert(
            LedgerEntry(
                customerId = customerId,
                atEpochDay = epochDay,
                direction = if (deltaRial > 0) ir.cmbtools.ledger.core.LedgerDirection.DEBIT else ir.cmbtools.ledger.core.LedgerDirection.CREDIT,
                amountRial = kotlin.math.abs(deltaRial),
                account = ir.cmbtools.ledger.core.LedgerAccount.ADJUSTMENT,
                refType = "ADJUST",
                memo = memo
            )
        )
    }
}

/** Reconcile an invoice snapshot to the customer's current opening balance. */
internal fun Invoice.withOpeningBalanceTarget(targetRial: Long): Invoice {
    val deltaRial = try {
        Math.subtractExact(targetRial, openingBalanceSnapshotRial)
    } catch (_: ArithmeticException) {
        error("مبلغ مانده از حد مجاز بیشتر است")
    }
    val previousTotalDebt = if (balanceAfterRial != 0L) balanceAfterRial
    else ir.cmbtools.ledger.core.Money.safeSum(prevBalanceRial, totalRial)
    return copy(
        prevBalanceRial = ir.cmbtools.ledger.core.Money.safeSum(prevBalanceRial, deltaRial),
        balanceAfterRial = ir.cmbtools.ledger.core.Money.safeSum(previousTotalDebt, deltaRial),
        openingBalanceSnapshotRial = targetRial
    )
}
