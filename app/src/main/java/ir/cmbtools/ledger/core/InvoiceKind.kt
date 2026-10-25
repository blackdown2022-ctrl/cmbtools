package ir.cmbtools.ledger.core

/** نوع فاکتور: اکانت جدید، تمدید، یا فاکتور دستی */
object InvoiceKind {
    const val NEW = "NEW"
    const val RENEW = "RENEW"
    const val MANUAL = "MANUAL"

    fun label(v: String): String = when (v) {
        NEW -> "اکانت جدید"
        RENEW -> "تمدید"
        else -> "فاکتور"
    }
}
