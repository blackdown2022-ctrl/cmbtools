package ir.cmbtools.ledger.panel

import ir.cmbtools.ledger.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * لایهٔ خامِ HTTP برای پنل.
 *
 * چرا Retrofit کنار گذاشته شد؟
 *  • مسیر پروکسی ادمین در هیدی‌فای یک «تکه» از URL است؛ Retrofit مقدار `@Path` را کدگذاری
 *    می‌کند و اگر مسیر یک اسلش اضافه داشته باشد، `%2F` تولید می‌شود و پنل ۴۰۴ می‌دهد.
 *  • برای عیب‌یابی لازم است **کد وضعیت و متن خام** هر تلاش را ببینیم؛ Retrofit برای پاسخ
 *    ۴xx استثنا پرتاب می‌کند و بدنه در دسترس نیست.
 *  • نسخه‌های ۱۱ تا ۱۴ مسیرهای متفاوتی دارند و باید بتوانیم چند مسیر را پشت سر هم امتحان کنیم.
 */
data class HttpResult(
    val url: String,
    val code: Int,
    val body: String,
    val contentType: String
) {
    val ok: Boolean get() = code in 200..299

    /** پاسخ HTML (صفحهٔ ورود/خطای وب‌سرور) — یعنی به API نرسیده‌ایم. */
    val isHtml: Boolean
        get() {
            val head = body.trimStart().take(120).lowercase()
            return contentType.contains("html", true) ||
                head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<")
        }

    /** پاسخ شبیه JSON (شیء یا آرایه). */
    val isJson: Boolean
        get() {
            val t = body.trimStart()
            return !isHtml && (t.startsWith("{") || t.startsWith("["))
        }

    /** پیام خطای خودِ پنل (`msg`/`detail`)، اگر JSON بود. */
    val panelMessage: String get() = PanelJson.errorText(body)

    /** ۴۰۴ «هنوز کاربری ساخته نشده» در مسیر کلاسیک — یعنی فهرست خالی، نه خطا. */
    val isEmptyUserList: Boolean
        get() = code == 404 && body.contains("You have no user", ignoreCase = true)

    /** نشانهٔ مسیر اشتباه (اعتبارسنجی مسیر پروکسی در خودِ هیدی‌فای). */
    val isInvalidProxyPath: Boolean
        get() = code == 400 && (
            body.contains("invalid request", ignoreCase = true) ||
                body.contains("Invalid Proxy Path", ignoreCase = true)
            )

    /** مسیر درست بوده ولی کلید API پذیرفته نشده (هیدی‌فای این را ۴۰۳ می‌دهد). */
    val isUnauthorized: Boolean get() = code == 401 || code == 403

    /** خطای ۴۰۴ معمولی (مسیر در این نسخه وجود ندارد). */
    val isNotFound: Boolean get() = code == 404 && !isEmptyUserList

    /**
     * «این مسیر در این نسخهٔ پنل وجود ندارد» — مهم‌ترین تشخیص برای سازگاری نسخه‌ها.
     *
     * چرا فقط کد ۴۰۴ کافی نیست؟ در نسخه‌های ۱۱ تا ۱۳ فایل
     * `hiddifypanel/panel/common.py` یک `@app.errorhandler(Exception)` دارد که هر خطای
     * مسیرِ API را — به‌جای ۴۰۴ — با **کد ۵۰۰** و بدنهٔ
     * `{"msg": "404 Not Found: The requested URL was not found on the server..."}`
     * برمی‌گرداند. اگر این حالت را «خطای سرور» بشماریم، پنل سالم هم قرمز می‌شود.
     *
     * ۴۰۵ هم یعنی متد/مسیر در این نسخه پشتیبانی نمی‌شود.
     */
    val isMissingRoute: Boolean
        get() = isNotFound || code == 405 ||
            (code in 500..599 && (
                body.contains("404 Not Found", ignoreCase = true) ||
                    body.contains("not found on the server", ignoreCase = true) ||
                    body.contains("The requested URL was not found", ignoreCase = true)
                ))

    fun short(): String = body.trim().replace("\n", " ").take(160)
}

/**
 * آدرس پایهٔ پنل: `https://host/proxyPath` (بدون اسلش آخر).
 * همهٔ درخواست‌ها زیر همین ساخته می‌شوند.
 */
data class PanelTarget(
    val scheme: String,
    val host: String,
    val proxyPath: String
) {
    val origin: String get() = "$scheme://$host"
    val panelUrl: String get() = if (proxyPath.isBlank()) origin else "$origin/$proxyPath"

    fun url(path: String): String {
        val p = if (path.startsWith("/")) path else "/$path"
        return panelUrl + p
    }

    companion object {
        fun of(config: ir.cmbtools.ledger.data.PanelConfig): PanelTarget {
            val parts = PanelUrl.normalize(config.host, config.adminProxyPath, config.useHttps)
            return PanelTarget(parts.scheme, parts.host, parts.proxyPath)
        }
    }
}

/**
 * انجام درخواست‌های پنل با OkHttp.
 *
 * نکته‌ها:
 *  • تایم‌اوت خواندن بزرگ است، چون پنل بعد از هر نوشتن `quick_apply_users` را اجرا می‌کند.
 *  • برای درخواست‌های نوشتن (POST/PATCH/DELETE) اگر پاسخ دیر برسد، پنل ممکن است کار را انجام
 *    داده باشد؛ تکرار کورکورانه انجام نمی‌شود و تأیید نتیجه در لایهٔ بالاتر انجام می‌گیرد.
 */
class PanelHttp {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // API key is a custom header; never forward it to a redirect's origin.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun get(url: String, apiKey: String): HttpResult =
        execute("GET", url, apiKey, null)

    fun post(url: String, apiKey: String, body: String): HttpResult =
        execute("POST", url, apiKey, body)

    fun patch(url: String, apiKey: String, body: String): HttpResult =
        execute("PATCH", url, apiKey, body)

    fun delete(url: String, apiKey: String): HttpResult =
        execute("DELETE", url, apiKey, null)

    private fun execute(method: String, url: String, apiKey: String, body: String?): HttpResult {
        val builder = Request.Builder().url(url)
        if (apiKey.isNotBlank()) builder.header("Hiddify-API-Key", apiKey)
        builder.header("Accept", "application/json, text/plain, */*")
        // بعضی سرورها/WAFها (از جمله Cloudflare) درخواست‌های با User-Agent ناشناس را
        // با ۴۰۳/۴۰۴ یا صفحهٔ چالش رد می‌کنند؛ یک UA معمولی مرورگر اندروید می‌فرستیم
        // (هیدی‌فای هم درخواست API با کلید معتبر را ریدایرکت نمی‌کند).
        builder.header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 cmbtools/${BuildConfig.VERSION_NAME}"
        )
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonType))
            "PATCH" -> builder.patch((body ?: "{}").toRequestBody(jsonType))
        }
        val request = builder.build()
        val response = client.newCall(request).execute()
        response.use { r ->
            val text = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
            return HttpResult(
                url = url,
                code = r.code,
                body = text,
                contentType = r.header("Content-Type").orEmpty()
            )
        }
    }
}
