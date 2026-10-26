package ir.cmbtools.ledger.core

/** Limits automatic retries for invoice SMS after a recorded failure. */
object SmsRetryPolicy {
    const val RETRY_DELAY_MILLIS = 30 * 60 * 1_000L

    fun shouldRetry(status: String, lastAttemptMillis: Long, nowMillis: Long): Boolean {
        if (status !in setOf("FAILED", "PENDING") || lastAttemptMillis <= 0L || nowMillis < lastAttemptMillis) return false
        return nowMillis - lastAttemptMillis >= RETRY_DELAY_MILLIS
    }
}
