package ir.cmbtools.ledger

import ir.cmbtools.ledger.core.SmsRetryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsRetryPolicyTest {
    @Test
    fun failed_sms_retries_after_cooldown() {
        val attemptedAt = 1_000_000L
        assertFalse(SmsRetryPolicy.shouldRetry("FAILED", attemptedAt, attemptedAt + 29 * 60_000L))
        assertTrue(SmsRetryPolicy.shouldRetry("FAILED", attemptedAt, attemptedAt + SmsRetryPolicy.RETRY_DELAY_MILLIS))
    }

    @Test
    fun sent_or_invalid_attempt_is_never_retried() {
        val now = 2_000_000L
        assertFalse(SmsRetryPolicy.shouldRetry("SENT", now - SmsRetryPolicy.RETRY_DELAY_MILLIS, now))
        assertFalse(SmsRetryPolicy.shouldRetry("FAILED", 0L, now))
        assertFalse(SmsRetryPolicy.shouldRetry("FAILED", now + 1L, now))
    }
}
