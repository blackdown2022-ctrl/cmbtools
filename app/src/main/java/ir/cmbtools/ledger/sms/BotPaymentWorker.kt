package ir.cmbtools.ledger.sms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ir.cmbtools.ledger.LedgerApp
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Retries transient network failures without ever persisting the API secret in WorkManager input. */
class BotPaymentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LedgerApp ?: return Result.failure()
        val settings = app.container.prefs.current
        val amountRial = inputData.getLong(KEY_AMOUNT_RIAL, 0L)
        val reference = inputData.getString(KEY_REFERENCE).orEmpty()
        if (amountRial <= 0L || reference.isBlank()) return Result.failure()
        val bank = inputData.getString(KEY_BANK).orEmpty()
        fun record(state: String, message: String, httpStatus: Int = 0, orderId: String = "") {
            val latest = app.container.prefs.botAudit.value
            if (latest.amountRial != amountRial || latest.reference != reference || latest.bank != bank) return
            app.container.prefs.recordBotAudit(
                latest.copy(
                    atMillis = System.currentTimeMillis(),
                    state = state,
                    orderId = orderId,
                    message = message,
                    httpStatus = httpStatus
                )
            )
        }
        if (!settings.botPaymentEnabled) {
            record("cancelled", "اتصال ربات پیش از ارسال خاموش شد")
            return Result.failure()
        }

        val response = BotPaymentClient().confirmDeposit(settings, amountRial, reference)
        if (response.ok && response.paymentConfirmed) {
            record("confirmed", response.message, response.httpStatus, response.orderId)
            app.notifications.show("واریز ربات تأیید شد", response.message)
            return Result.success()
        }
        if (response.retryable && runAttemptCount < MAX_RETRIES) {
            record("retrying", "خطای موقت شبکه/سرور؛ تلاش مجدد زمان‌بندی شد: ${response.message}", response.httpStatus, response.orderId)
            return Result.retry()
        }

        val message = if (response.ok) {
            "پاسخ ربات پرداخت را صریحاً تأیید نکرد؛ پرداخت در اپ تأییدشده نیست"
        } else {
            response.message + if (response.serverReason.isNotBlank()) " (کد: ${response.serverReason})" else ""
        }
        record("failed", message, response.httpStatus, response.orderId)
        app.notifications.show("تأیید واریز ربات انجام نشد", message)
        return Result.failure(Data.Builder().putString("reason", message).build())
    }

    companion object {
        private const val KEY_AMOUNT_RIAL = "amount_rial"
        private const val KEY_REFERENCE = "reference"
        private const val KEY_BANK = "bank"
        private const val MAX_RETRIES = 5

        fun enqueue(context: Context, amountRial: Long, reference: String, bank: String) {
            val identity = sha256("$bank|$amountRial|$reference")
            val request = OneTimeWorkRequestBuilder<BotPaymentWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_AMOUNT_RIAL, amountRial)
                        .putString(KEY_REFERENCE, reference)
                        .putString(KEY_BANK, bank)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "bot-payment-$identity",
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
