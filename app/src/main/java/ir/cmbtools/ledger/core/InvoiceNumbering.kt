package ir.cmbtools.ledger.core

/** Keeps visible invoice numbers as plain positive integers, independent of date or deletions. */
object InvoiceNumbering {
    data class InvoiceRef(val id: Long, val issuedAtEpochDay: Long)
    data class Assignment(val id: Long, val number: String)

    fun assignSequentially(invoices: List<InvoiceRef>): List<Assignment> =
        invoices.sortedWith(compareBy<InvoiceRef> { it.issuedAtEpochDay }.thenBy { it.id })
            .mapIndexed { index, invoice -> Assignment(invoice.id, (index + 1L).toString()) }

    fun next(lastIssued: Long, highestExisting: Long): Long = maxOf(lastIssued, highestExisting) + 1L
}
