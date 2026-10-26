package ir.cmbtools.ledger.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import ir.cmbtools.ledger.core.AppSettings
import ir.cmbtools.ledger.core.SmsGatewayProvider
import ir.cmbtools.ledger.core.SmsMode
import ir.cmbtools.ledger.core.SmsRetryPolicy
import ir.cmbtools.ledger.data.SmsOutbox
import ir.cmbtools.ledger.data.SmsOutboxDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class SendResult(val ok: Boolean, val message: String, val providerRef: String = "")

/**
 * ارسال پیامک با دو روش (قابل انتخاب در تنظیمات):
 *  - GATEWAY: درگاه پیامکی (کاوه‌نگار / SMS.ir) — گزارش تحویل دارد
 *  - SIM: سیم‌کارت گوشی با SmsManager — رایگان، بدون گزارش تحویل
 * هر ارسال در جدول sms_outbox ثبت می‌شود تا سابقه و خطاها را داشته باشید.
 */
class SmsSender(
    private val context: Context,
    private val outboxDao: SmsOutboxDao
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Automatic sync may retry a failed/pending invoice SMS, but never repeat a sent message. */
    suspend fun invoiceSmsRetryDue(invoiceId: Long, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (invoiceId <= 0L) return false
        val latest = outboxDao.latestForInvoice(invoiceId) ?: return false
        return SmsRetryPolicy.shouldRetry(latest.status, latest.createdAt, nowMillis)
    }

    suspend fun send(
        settings: AppSettings,
        to: String,
        body: String,
        invoiceId: Long = 0,
        customerId: Long = 0
    ): SendResult = withContext(Dispatchers.IO) {
        val phone = normalizePhone(to)
        val parts = SmsTemplate.parts(body)
        val logId = outboxDao.insert(
            SmsOutbox(
                to = phone,
                body = body,
                provider = settings.smsMode,
                parts = parts,
                invoiceId = invoiceId,
                customerId = customerId
            )
        )
        if (phone.length < 10) {
            val error = "شمارهٔ موبایل نامعتبر است یا ثبت نشده"
            outboxDao.mark(logId, SmsOutbox.FAILED, error, System.currentTimeMillis())
            return@withContext SendResult(false, error)
        }

        val result = when (settings.smsMode) {
            SmsMode.SIM -> sendWithSim(phone, body)
            else -> sendWithGateway(settings, phone, body)
        }

        outboxDao.mark(
            logId,
            if (result.ok) SmsOutbox.SENT else SmsOutbox.FAILED,
            result.message,
            System.currentTimeMillis()
        )
        result
    }

    private fun sendWithSim(phone: String, body: String): SendResult {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return SendResult(false, "مجوز ارسال پیامک داده نشده است")
        return try {
            val manager = if (android.os.Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val chunks = manager.divideMessage(body)
            if (chunks.size > 1) {
                manager.sendMultipartTextMessage(phone, null, chunks, null, null)
            } else {
                manager.sendTextMessage(phone, null, body, null, null)
            }
            SendResult(true, "به سیم‌کارت تحویل شد")
        } catch (e: Exception) {
            SendResult(false, e.message ?: "خطای ارسال با سیم‌کارت")
        }
    }

    private fun sendWithGateway(settings: AppSettings, phone: String, body: String): SendResult {
        if (settings.gatewayApiKey.isBlank()) {
            return SendResult(false, "کلید API درگاه پیامکی تنظیم نشده است")
        }
        return try {
            when (settings.gatewayProvider) {
                SmsGatewayProvider.KAVENEGAR -> {
                    val url = "https://api.kavenegar.com/v1/${settings.gatewayApiKey}/sms/send.json"
                    val form = FormBody.Builder()
                        .add("receptor", phone)
                        .add("message", body)
                        .apply { if (settings.gatewaySender.isNotBlank()) add("sender", settings.gatewaySender) }
                        .build()
                    val request = Request.Builder().url(url).post(form).build()
                    http.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (response.isSuccessful && !text.contains("\"status\":400")) {
                            SendResult(true, "ارسال به درگاه", extractRef(text))
                        } else {
                            SendResult(false, "خطای کاوه‌نگار: ${text.take(180)}")
                        }
                    }
                }

                SmsGatewayProvider.SMS_IR -> {
                    // SMS.ir v1: ارسالVerify برای الگو، ارسالBulk برای متن آزاد
                    val url = "https://api.sms.ir/v1/send/bulk"
                    val payload = org.json.JSONObject().apply {
                        put("lineNumber", settings.gatewaySender)
                        put("messageText", body)
                        put("mobiles", org.json.JSONArray().put(phone))
                    }
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("x-api-key", settings.gatewayApiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    http.newCall(request).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (response.isSuccessful && text.contains("\"status\":1")) {
                            SendResult(true, "ارسال به SMS.ir", extractRef(text))
                        } else {
                            SendResult(false, "خطای SMS.ir: ${text.take(180)}")
                        }
                    }
                }

                else -> SendResult(false, "درگاه پشتیبانی نمی‌شود")
            }
        } catch (e: Exception) {
            SendResult(false, "خطای شبکه درگاه: ${e.message}")
        }
    }

    private fun extractRef(json: String): String =
        Regex("\"(?:messageid|packId|id)\"\\s*:\\s*\"?(\\d+)").find(json)?.groupValues?.get(1) ?: ""

    companion object {
        fun normalizePhone(input: String): String {
            var s = ir.cmbtools.ledger.core.Money.toLatinDigits(input).replace(Regex("[^0-9+]"), "")
            if (s.startsWith("+98")) s = "0" + s.removePrefix("+98")
            if (s.startsWith("0098")) s = "0" + s.removePrefix("0098")
            if (s.startsWith("98") && s.length == 12) s = "0" + s.removePrefix("98")
            if (s.length == 10 && s.startsWith("9")) s = "0$s"
            return s
        }

        fun isValidIranMobile(input: String): Boolean =
            normalizePhone(input).matches(Regex("^09\\d{9}$"))
    }
}
