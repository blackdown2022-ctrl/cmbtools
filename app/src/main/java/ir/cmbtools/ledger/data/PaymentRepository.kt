package ir.cmbtools.ledger.data

import ir.cmbtools.ledger.core.LedgerAccount
import ir.cmbtools.ledger.core.LedgerDirection
import ir.cmbtools.ledger.core.PaymentMethod
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

data class PaymentResult(
    val paymentId: Long,
    val allocatedRial: Long,
    val unallocatedRial: Long
)

class PaymentRepository(
    private val payments: PaymentDao,
    private val invoices: InvoiceDao,
    private val invoiceRepository: InvoiceRepository,
    private val ledger: LedgerDao
) {
    fun observeByCustomer(customerId: Long): Flow<List<Payment>> = payments.observeByCustomer(customerId)

    fun observeRecent(): Flow<List<Payment>> = payments.observeRecent()

    fun observeLedger(customerId: Long): Flow<List<LedgerEntry>> = ledger.observeByCustomer(customerId)

    fun observeRecentLedger(): Flow<List<LedgerEntry>> = ledger.observeRecent()

    suspend fun get(id: Long): Payment? = payments.byId(id)

    /**
     * پرداخت ثبت می‌شود و به‌صورت FIFO روی بدهی‌های قدیمی‌تر تخصیص می‌یابد.
     * مبلغی که فاکتور باز برایش نماند، به‌عنوان «اعتبار/بستانکاری» مشتری در دفتر می‌ماند و گم نمی‌شود.
     */
    suspend fun record(
        customerId: Long,
        amountRial: Long,
        method: String = PaymentMethod.CASH,
        at: LocalDate = LocalDate.now(),
        refNo: String = "",
        note: String = "",
        bankSmsId: Long = 0,
        preferredInvoiceId: Long = 0
    ): PaymentResult {
        require(customerId > 0) { "مشتری انتخاب نشده است" }
        require(amountRial > 0) { "مبلغ پرداخت باید بزرگ‌تر از صفر باشد" }

        val epochDay = at.toEpochDay()
        val paymentId = payments.insert(
            Payment(
                customerId = customerId,
                amountRial = amountRial,
                method = method,
                receivedAtEpochDay = epochDay,
                refNo = refNo,
                note = note,
                bankSmsId = bankSmsId
            )
        )

        var remaining = amountRial
        val allocations = mutableListOf<PaymentAllocation>()
        val touchedInvoices = mutableSetOf<Long>()

        // ۱) اگر فاکتور مشخصی انتخاب شده باشد، اول همان
        if (preferredInvoiceId > 0) {
            val invoice = invoices.byId(preferredInvoiceId)
            if (invoice != null && invoice.status != ir.cmbtools.ledger.core.InvoiceStatus.VOID) {
                val due = (invoice.totalRial - invoice.paidRial).coerceAtLeast(0)
                val pay = minOf(due, remaining)
                if (pay > 0) {
                    allocations += PaymentAllocation(paymentId = paymentId, invoiceId = invoice.id, amountRial = pay)
                    remaining -= pay
                    touchedInvoices += invoice.id
                }
            }
        }

        // ۲) بقیه به ترتیب قدیمی‌ترین بدهی
        if (remaining > 0) {
            val open = invoices.openInvoicesOldestFirst(customerId)
            for (invoice in open) {
                if (remaining <= 0) break
                if (invoice.id in touchedInvoices) continue
                val due = (invoice.totalRial - invoice.paidRial).coerceAtLeast(0)
                if (due <= 0) continue
                val pay = minOf(due, remaining)
                allocations += PaymentAllocation(paymentId = paymentId, invoiceId = invoice.id, amountRial = pay)
                remaining -= pay
                touchedInvoices += invoice.id
            }
        }

        if (allocations.isNotEmpty()) {
            payments.insertAllocations(allocations)
        }
        if (remaining > 0) {
            val p = payments.byId(paymentId)
            if (p != null) payments.update(p.copy(unallocatedRial = remaining))
        }

        ledger.insert(
            LedgerEntry(
                customerId = customerId,
                atEpochDay = epochDay,
                direction = LedgerDirection.CREDIT,
                amountRial = amountRial,
                account = LedgerAccount.CASH,
                refType = "PAYMENT",
                refId = paymentId,
                memo = PaymentMethod.label(method) + if (refNo.isNotBlank()) " - $refNo" else ""
            )
        )

        touchedInvoices.forEach { invoiceRepository.recollectTotals(it) }

        return PaymentResult(
            paymentId = paymentId,
            allocatedRial = amountRial - remaining,
            unallocatedRial = remaining
        )
    }

    suspend fun between(from: LocalDate, to: LocalDate): List<Payment> =
        payments.between(from.toEpochDay(), to.toEpochDay())
}
