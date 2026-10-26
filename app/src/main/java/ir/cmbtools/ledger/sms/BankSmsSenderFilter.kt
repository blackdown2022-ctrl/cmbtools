package ir.cmbtools.ledger.sms

/** Exact sender allowlist for incoming bank SMS. An empty setting intentionally allows none. */
object BankSmsSenderFilter {
    fun allows(sender: String, allowedSender: String): Boolean {
        val allowed = normalize(allowedSender)
        return allowed.isNotEmpty() && normalize(sender) == allowed
    }

    fun normalize(value: String): String = buildString(value.length) {
        value.trim().lowercase().forEach { ch ->
            when {
                ch in '۰'..'۹' -> append('0' + (ch - '۰'))
                ch in '٠'..'٩' -> append('0' + (ch - '٠'))
                ch == 'ي' -> append('ی')
                ch == 'ك' -> append('ک')
                ch.isWhitespace() || ch == '\u200c' || ch == '+' || ch == '-' || ch == '(' || ch == ')' -> Unit
                else -> append(ch)
            }
        }
    }
}
