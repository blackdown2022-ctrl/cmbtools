package ir.cmbtools.ledger.sms

import android.net.Uri
import ir.cmbtools.ledger.core.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Result from either the bot health endpoint or a bank-deposit confirmation. */
data class BotPaymentResult(
    val ok: Boolean,
    val message: String,
    val version: String = "",
    val orderId: String = "",
    val paymentConfirmed: Boolean = false,
    val fulfilled: Boolean = false,
    val retryable: Boolean = false,
    val httpStatus: Int = 0,
    val serverReason: String = ""
)

data class BotOrderStatusResult(
    val ok: Boolean,
    val found: Boolean = false,
    val orderId: String = "",
    val status: String = "",
    val amountToman: Long = 0L,
    val paymentMethod: String = "",
    val message: String = "",
    val httpStatus: Int = 0
)

/**
 * Client for salebot's documented Shetab-compatible REST endpoint.
 * The bot's final unique amount is in toman; local bank SMS amounts are stored in rial.
 */
class BotPaymentClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun testConnection(serverUrl: String, apiSecret: String): BotPaymentResult =
        withContext(Dispatchers.IO) {
            val endpoint = normalizeConfirmUrl(serverUrl)
                ?: return@withContext BotPaymentResult(false, "آدرس باید HTTPS و مسیر confirm پروژهٔ ربات باشد")
            if (apiSecret.isBlank()) return@withContext BotPaymentResult(false, "Secret اتصال ربات وارد نشده است")

            val healthUrl = endpoint.removeSuffix("/confirm") + "/health"
            execute(healthUrl, apiSecret, null).let { result ->
                if (!result.ok) result
                else if (result.version.isBlank()) result.copy(ok = false, message = "پاسخ سلامت سرور نسخهٔ ربات را ندارد")
                else result.copy(message = "اتصال امن به سرور ربات برقرار است")
            }
        }

    /** Read one order by its ID using salebot's documented public status endpoint. */
    suspend fun getOrderStatus(serverUrl: String, orderId: String): BotOrderStatusResult =
        withContext(Dispatchers.IO) {
            val endpoint = normalizeConfirmUrl(serverUrl)
                ?: return@withContext BotOrderStatusResult(false, message = "آدرس سرور معتبر نیست")
            val id = orderId.trim()
            if (!Regex("^[A-Za-z0-9_-]{4,40}$").matches(id)) {
                return@withContext BotOrderStatusResult(false, message = "شناسهٔ سفارش باید ۴ تا ۴۰ حرف/عدد باشد")
            }
            try {
                val statusUrl = Uri.parse(endpoint.removeSuffix(CONFIRM_PATH) + STATUS_PATH)
                    .buildUpon()
                    .appendQueryParameter("order_id", id)
                    .build()
                    .toString()
                http.newCall(
                    Request.Builder()
                        .url(statusUrl)
                        .header("Accept", "application/json")
                        .get()
                        .build()
                ).execute().use { response ->
                    val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    if (!response.isSuccessful || json == null) {
                        BotOrderStatusResult(
                            false,
                            message = "خواندن سفارش ناموفق بود (HTTP ${response.code})",
                            httpStatus = response.code
                        )
                    } else {
                        val status = json.optString("status")
                        val found = status.isNotBlank() && status != "not_found"
                        BotOrderStatusResult(
                            ok = true,
                            found = found,
                            orderId = json.optString("order_id", id),
                            status = status,
                            amountToman = json.optLong("amount", 0L),
                            paymentMethod = json.optString("payment_method"),
                            message = if (found) "وضعیت سفارش از سرور خوانده شد" else "سفارشی با این شناسه در سرور پیدا نشد",
                            httpStatus = response.code
                        )
                    }
                }
            } catch (e: Exception) {
                BotOrderStatusResult(false, message = "خواندن سفارش از سرور انجام نشد: ${e.message ?: "خطای شبکه"}")
            }
        }

    suspend fun confirmDeposit(
        settings: AppSettings,
        amountRial: Long,
        reference: String
    ): BotPaymentResult = withContext(Dispatchers.IO) {
        val endpoint = normalizeConfirmUrl(settings.botServerUrl)
            ?: return@withContext BotPaymentResult(false, "آدرس سرور ربات معتبر نیست")
        if (settings.botApiSecret.isBlank()) return@withContext BotPaymentResult(false, "Secret اتصال ربات وارد نشده است")
        if (reference.trim().isBlank()) return@withContext BotPaymentResult(false, "کد پیگیری خالی است؛ درخواست تأیید ارسال نشد")
        if (amountRial <= 0L || amountRial % 10L != 0L) {
            return@withContext BotPaymentResult(false, "مبلغ پیامک به تومان دقیق تبدیل نمی‌شود؛ تأیید خودکار انجام نشد")
        }

        // salebot expects the exact unique amount in toman (the bank SMS parser stores rial).
        val payload = JSONObject()
            .put("amount", amountRial / 10L)
            .put("recipeId", reference.trim().take(100))
        execute(endpoint, settings.botApiSecret, payload.toString())
    }

    private fun execute(url: String, apiSecret: String, jsonBody: String?): BotPaymentResult {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", apiSecret)
                .header("Accept", "application/json")
            val request = if (jsonBody == null) {
                builder.get().build()
            } else {
                builder.header("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrNull()
                val status = response.code
                val version = json?.optString("version").orEmpty()
                val orderId = json?.optString("order_id").orEmpty()
                val confirmed = json?.optBoolean("payment_confirmed", false) == true ||
                    json?.optBoolean("already_processed", false) == true
                val fulfilled = json?.optBoolean("fulfilled", true) != false

                if (response.isSuccessful && json?.optBoolean("success") == true) {
                    BotPaymentResult(
                        ok = true,
                        message = when {
                            confirmed && !fulfilled -> "واریز تأیید شد؛ اما تحویل سفارش نیاز به پیگیری مدیر ربات دارد"
                            confirmed && orderId.isNotBlank() -> "واریز و سفارش ${orderId} تأیید شد"
                            confirmed -> "واریز قبلاً تأیید شده بود؛ سفارش تکراری ساخته نشد"
                            else -> "سرور ربات پاسخ موفق داد"
                        },
                        version = version,
                        orderId = orderId,
                        paymentConfirmed = confirmed,
                        fulfilled = fulfilled,
                        httpStatus = status
                    )
                } else {
                    val reason = json?.optString("reason").orEmpty()
                    val hint = json?.optString("hint").orEmpty()
                    val message = when (reason) {
                        "secret_missing" -> "سرور ربات Secret پرداخت ندارد"
                        "secret_mismatch" -> "Secret گوشی با Secret سرور ربات یکی نیست"
                        "rate_limited" -> "درخواست‌ها موقتاً محدود شده‌اند؛ تنظیم Secret را بررسی کنید"
                        "ambiguous_amount" -> "چند سفارش با این مبلغ پیدا شد؛ تأیید خودکار متوقف شد"
                        "no_order_match" -> "سفارشی با مبلغ دقیق این واریز پیدا نشد؛ مدیر ربات مطلع شد"
                        "order_closed" -> "سفارش منطبق بسته یا منقضی شده است؛ مدیر ربات مطلع شد"
                        "amount_required" -> "سرور مبلغ معتبر دریافت نکرد"
                        else -> json?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: "سرور ربات پاسخ HTTP $status داد"
                    }
                    BotPaymentResult(
                        ok = false,
                        message = if (hint.isNotBlank()) "$message. $hint" else message,
                        version = version,
                        orderId = orderId,
                        retryable = status == 408 || status == 429 || status >= 500,
                        httpStatus = status,
                        serverReason = reason
                    )
                }
            }
        } catch (e: Exception) {
            BotPaymentResult(
                ok = false,
                message = "ارتباط با سرور ربات برقرار نشد: ${e.message ?: "خطای شبکه"}",
                retryable = true
            )
        }
    }

    companion object {
        const val CONFIRM_PATH = "/wp-json/webdide-cv/v1/confirm"
        const val STATUS_PATH = "/wp-json/webdide-cv/v1/status"

        /** Accept only HTTPS and the bot's exact documented confirm route (or a bare HTTPS origin). */
        fun normalizeConfirmUrl(input: String): String? {
            val uri = runCatching { Uri.parse(input.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
            if (!uri.userInfo.isNullOrBlank() || !uri.query.isNullOrBlank() || !uri.fragment.isNullOrBlank()) return null
            val path = uri.encodedPath.orEmpty().trimEnd('/').ifBlank { "/" }
            if (path != "/" && path != CONFIRM_PATH) return null
            return uri.buildUpon()
                .scheme("https")
                .encodedPath(CONFIRM_PATH)
                .clearQuery()
                .fragment(null)
                .build()
                .toString()
        }

        fun isEligibleBankDeposit(parsed: ParsedBankSms): Boolean =
            parsed.isCredit && !parsed.isOtp && !parsed.isReversal && parsed.amountRial > 0L &&
                parsed.amountUnit in setOf("RIAL", "TOMAN") && parsed.amountRial % 10L == 0L &&
                parsed.refNo.isNotBlank() && parsed.bank != "بانک ناشناس"
    }
}
