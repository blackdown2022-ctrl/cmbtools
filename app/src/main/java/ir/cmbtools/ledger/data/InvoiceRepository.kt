package ir.cmbtools.ledger.data

import ir.cmbtools.ledger.core.InvoiceKind
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.LedgerAccount
import ir.cmbtools.ledger.core.LedgerDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

data class NewInvoiceLine(
    val description: String,
    val qty: Int = 1,
    val unitPriceRial: Long,
    val planId: Long = 0
) {
    val amountRial: Long get() = unitPriceRial * qty.coerceAtLeast(1)
}

class InvoiceRepository(
    private val invoices: InvoiceDao,
    private val ledger: LedgerDao,
    private val payments: PaymentDao,
    private val invoiceNumbers: InvoiceNumberCounterDao
) {
    private val numberMutex = Mutex()

    fun observeAll(): Flow<List<Invoice>> = invoices.observeAll()

    fun observeByCustomer(customerId: Long): Flow<List<Invoice>> = invoices.observeByCustomer(customerId)

    fun observeById(id: Long): Flow<Invoice?> = invoices.observeById(id)

    fun observeLines(invoiceId: Long): Flow<List<InvoiceLine>> = invoices.observeLines(invoiceId)

    suspend fun get(id: Long): Invoice? = invoices.byId(id)

    suspend fun lines(invoiceId: Long): List<InvoiceLine> = invoices.lines(invoiceId)

    /** Hard-delete while returning allocations to the customers' unapplied payment credit. */
    suspend fun delete(invoiceId: Long) {
        val invoice = invoices.byId(invoiceId) ?: return
        payments.allocationsForInvoice(invoiceId)
            .groupBy { it.paymentId }
            .forEach { (paymentId, allocations) ->
                val payment = payments.byId(paymentId) ?: return@forEach
                val returnedCredit = allocations.sumOf { it.amountRial }
                payments.update(payment.copy(unallocatedRial = payment.unallocatedRial + returnedCredit))
            }
        ledger.deleteInvoiceEntries(invoiceId)
        invoices.delete(invoice.id)
    }

    /**
     * فاکتور را می‌سازد و ثبت بدهکاری در دفتر می‌زند.
     * شماره‌گذاری داخل Mutex انجام می‌شود تا در کلیک دوباره شمارهٔ تکراری ساخته نشود.
     */
    suspend fun create(
        customerId: Long,
        lines: List<NewInvoiceLine>,
        discountRial: Long = 0,
        issuedAt: LocalDate = LocalDate.now(),
        dueAt: LocalDate? = null,
        note: String = "",
        planId: Long = 0,
        panelUserUuid: String = "",
        panelId: Long = 0,
        subscriptionUrl: String = "",
        status: String = InvoiceStatus.ISSUED,
        packageName: String = "",
        cardNumber: String = "",
        kind: String = InvoiceKind.MANUAL,
        autoIssued: Boolean = false,
        issuedAtMillis: Long = System.currentTimeMillis()
    ): Long = numberMutex.withLock {
        require(customerId > 0) { "مشتری انتخاب نشده است" }
        val cleanLines = lines.filter { it.description.isNotBlank() && it.unitPriceRial > 0 }
        require(cleanLines.isNotEmpty()) { "حداقل یک ردیف با مبلغ لازم است" }

        val subtotal = cleanLines.sumOf { it.amountRial }
        val discount = discountRial.coerceIn(0, subtotal)
        val total = subtotal - discount

        val epochDay = issuedAt.toEpochDay()
        val number = invoiceNumbers.reserveNext().toString()

        // ماندهٔ مشتری پیش از این فاکتور. مثبت = بدهکار، منفی = بستانکار (اعتبار).
        val prevBalance = ledger.balance(customerId)
        val balanceAfter = prevBalance + total

        val invoiceId = invoices.insert(
            Invoice(
                number = number,
                customerId = customerId,
                issuedAtEpochDay = epochDay,
                dueAtEpochDay = dueAt?.toEpochDay() ?: 0,
                status = status,
                subtotalRial = subtotal,
                discountRial = discount,
                totalRial = total,
                paidRial = 0,
                note = note,
                panelUserUuid = panelUserUuid,
                panelId = panelId,
                subscriptionUrl = subscriptionUrl,
                planId = planId,
                issuedAtMillis = issuedAtMillis,
                packageName = packageName,
                cardNumber = cardNumber,
                prevBalanceRial = prevBalance,
                balanceAfterRial = balanceAfter,
                kind = kind,
                autoIssued = autoIssued
            )
        )
        invoices.insertLines(
            cleanLines.map {
                InvoiceLine(
                    invoiceId = invoiceId,
                    planId = it.planId,
                    description = it.description,
                    qty = it.qty.coerceAtLeast(1),
                    unitPriceRial = it.unitPriceRial,
                    amountRial = it.amountRial
                )
            }
        )

        ledger.insert(
            LedgerEntry(
                customerId = customerId,
                atEpochDay = epochDay,
                direction = LedgerDirection.DEBIT,
                amountRial = total,
                account = LedgerAccount.RECEIVABLE,
                refType = "INVOICE",
                refId = invoiceId,
                memo = "فاکتور $number"
            )
        )
        invoiceId
    }

    suspend fun recollectTotals(invoiceId: Long) {
        val invoice = invoices.byId(invoiceId) ?: return
        val paid = allocationsPaid(invoiceId)
        invoices.update(invoice.copy(paidRial = paid, status = statusFor(invoice, paid)))
    }

    private suspend fun allocationsPaid(invoiceId: Long): Long = payments.paidForInvoice(invoiceId)

    suspend fun void(invoiceId: Long, memo: String = "ابطال فاکتور") {
        val invoice = invoices.byId(invoiceId) ?: return
        if (invoice.status == InvoiceStatus.VOID) return
        if (invoice.paidRial > 0) error("فاکتور پرداخت‌شده را نمی‌توان ابطال کرد؛ ابتدا پرداخت را برگشت بزنید")
        invoices.update(invoice.copy(status = InvoiceStatus.VOID))
        ledger.insert(
            LedgerEntry(
                customerId = invoice.customerId,
                atEpochDay = LocalDate.now().toEpochDay(),
                direction = LedgerDirection.CREDIT,
                amountRial = invoice.totalRial,
                account = LedgerAccount.RECEIVABLE,
                refType = "INVOICE_VOID",
                refId = invoiceId,
                memo = "$memo ${invoice.number}"
            )
        )
    }

    suspend fun setPanelInfo(invoiceId: Long, panelUserUuid: String, panelId: Long, subscriptionUrl: String) {
        val invoice = invoices.byId(invoiceId) ?: return
        invoices.update(
            invoice.copy(
                panelUserUuid = panelUserUuid,
                panelId = panelId,
                subscriptionUrl = subscriptionUrl
            )
        )
    }

    suspend fun updatePaid(invoiceId: Long, paidRial: Long) {
        val invoice = invoices.byId(invoiceId) ?: return
        invoices.update(invoice.copy(paidRial = paidRial, status = statusFor(invoice, paidRial)))
    }

    suspend fun between(from: LocalDate, to: LocalDate): List<Invoice> =
        invoices.between(from.toEpochDay(), to.toEpochDay())

    /** آیا برای این اکانت پنل فاکتوری صادر شده است؟ (ضد تکرار) */
    suspend fun hasInvoicesForPanelUser(uuid: String): Boolean =
        uuid.isNotBlank() && invoices.countForPanelUser(uuid) > 0

    suspend fun lastForPanelUser(uuid: String): Invoice? =
        if (uuid.isBlank()) null else invoices.lastForPanelUser(uuid)

    companion object {
        fun statusFor(invoice: Invoice, paidRial: Long): String = when {
            invoice.status == InvoiceStatus.VOID -> InvoiceStatus.VOID
            paidRial <= 0 -> if (invoice.status == InvoiceStatus.DRAFT) InvoiceStatus.DRAFT else InvoiceStatus.ISSUED
            paidRial >= invoice.totalRial -> InvoiceStatus.PAID
            else -> InvoiceStatus.PARTIAL
        }
    }
}
