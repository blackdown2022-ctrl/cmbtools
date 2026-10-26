package ir.cmbtools.ledger.panel

import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.PanelConfig
import ir.cmbtools.ledger.data.PanelDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.UUID

class PanelException(message: String, val kind: Kind = Kind.GENERIC) : Exception(message) {
    enum class Kind { GENERIC, NOT_CONFIGURED, AUTH, NETWORK, NOT_FOUND, SERVER, LIMIT, PATH }
}

data class PanelConnection(
    val ok: Boolean,
    val version: String = "",
    val adminUuid: String = "",
    val adminName: String = "",
    val canAddUsers: Boolean = true,
    val readOnly: Boolean = false,
    val linkBase: String = "",
    val usersCount: Int = 0,
    val endpoint: String = "",
    val flavor: String = "",
    val message: String = ""
)

/** یک ردیف نتیجهٔ عیب‌یابی اتصال، برای نمایش در تنظیمات. */
data class PanelCheck(val title: String, val ok: Boolean, val detail: String)

/**
 * تجزیهٔ آدرس پنل.
 *
 * کاربر ممکن است هر کدام از این‌ها را در فیلد «آدرس پنل» بچسباند و همه باید یک نتیجه بدهند:
 *
 *   panel.example.com
 *   https://panel.example.com/AbCdEf12/
 *   https://panel.example.com/AbCdEf12/admin/
 *   https://panel.example.com/AbCdEf12/admin/#/users
 *   https://panel.example.com/AbCdEf12/api/v2/admin/users/
 *   https://panel.example.com/AbCdEf12/8f14e45f-…/     ← لینک اشتراک یک کاربر
 *
 * **نکتهٔ مهم (همهٔ نسخه‌های ۱۱ تا ۱۴):** خودِ هیدی‌فای با تابع
 * `get_proxy_path_from_url` فقط **تکهٔ اول** مسیر را به‌عنوان proxy_path می‌شناسد؛
 * پس هر چیز اضافه‌ای بعد از آن باید حذف شود، وگرنه آدرس به مسیر ناموجود می‌رسد و ۴۰۴ می‌گیریم.
 */
object PanelUrl {

    data class Parts(val host: String, val proxyPath: String, val useHttps: Boolean) {
        val scheme: String get() = if (useHttps) "https" else "http"
        val panelUrl: String get() = "$scheme://$host" + if (proxyPath.isBlank()) "" else "/$proxyPath"
        val baseUrl: String get() = "$scheme://$host/"
    }

    fun normalize(hostRaw: String, pathRaw: String = "", useHttps: Boolean = true): Parts {
        var raw = hostRaw.trim()
        var https = useHttps
        when {
            raw.startsWith("https://", true) -> { https = true; raw = raw.substring(8) }
            raw.startsWith("http://", true) -> { https = false; raw = raw.substring(7) }
        }
        raw = raw.trim().trim('/')
        var extraPath = ""
        val slash = raw.indexOf('/')
        if (slash >= 0) {
            extraPath = raw.substring(slash + 1)
            raw = raw.substring(0, slash)
        }
        var path = pathRaw.trim().trim('/')
        if (path.isBlank() && extraPath.isNotBlank()) path = extraPath
        path = cleanPath(path)
        return Parts(host = raw.trim(), proxyPath = path, useHttps = https)
    }

    /**
     * استخراج «مسیر پروکسی ادمین» — همیشه **فقط تکهٔ اول** مسیر.
     * مثال‌ها:
     *   `AbCdEf/api/v2/admin/users/`      → `AbCdEf`
     *   `AbCdEf/admin/#/users`            → `AbCdEf`
     *   `AbCdEf/8f14e45f-…/`              → `AbCdEf`
     *   `api/v2/admin`                    → `` (این مسیر واقعی نیست)
     */
    fun cleanPath(raw: String): String {
        // حذف ?query و #fragment
        var p = raw.trim().substringBefore('#').substringBefore('?').trim('/')
        // اگر کاربر کل لینک API را چسبانده باشد
        listOf("/api/v2/admin", "/api/v2/panel", "/api/v2", "/api").forEach { marker ->
            val at = p.indexOf(marker)
            if (at >= 0) p = p.substring(0, at)
        }
        // آدرس پنل در مرورگر معمولاً به /admin/ ختم می‌شود
        val adminAt = p.indexOf("/admin")
        if (adminAt >= 0) p = p.substring(0, adminAt)
        p = p.trim('/')
        if (p.isEmpty()) return ""
        // هیدی‌فای فقط تکهٔ اول را می‌خواند؛ بقیهٔ تکه‌ها آدرس را به ۴۰۴ می‌برند
        val first = p.substringBefore('/')
        if (first == "api" || first == "admin") return ""
        return first
    }

    /** آدرس کامل پنل برای نمایش/ویرایش در تنظیمات */
    fun display(host: String, proxyPath: String, useHttps: Boolean): String =
        normalize(host, proxyPath, useHttps).panelUrl

    /** پایهٔ لینک اشتراک را یکدست می‌کند: http(s) + اسلش آخر */
    fun normalizeLinkBase(raw: String): String {
        var base = raw.trim()
        if (base.isBlank()) return ""
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) base = "https://$base"
        if (!base.endsWith("/")) base += "/"
        // مسیرهای اضافی بعد از شناسهٔ دامنه، جزو پایهٔ لینک نیستند
        return base
    }

    /** آیا این رشته شبیه UUID است؟ (برای تشخیص لینک اشتراک چسبانده‌شده) */
    fun looksLikeUuid(value: String): Boolean =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            .matches(value.trim())

    /** ساخت لینک اشتراک مشتری: پایه + شناسهٔ کاربر + اسلش */
    fun subscriptionLink(base: String, uuid: String): String {
        val b = normalizeLinkBase(base)
        if (b.isBlank() || uuid.isBlank()) return ""
        return b + uuid + "/"
    }
}

/** همهٔ آدرس‌هایی که اپ از پنل استفاده می‌کند، به‌صورت خوانا (برای نمایش در تنظیمات). */
data class PanelEndpoints(
    val panelUrl: String = "",
    val apiBase: String = "",
    val usersListUrl: String = "",
    val createUserUrl: String = "",
    val userUrl: String = "",
    val subscriptionBase: String = "",
    val subscriptionExample: String = "",
    val flavor: String = "",
    val version: String = ""
)

/**
 * کلاینت پنل هیدی‌فای، سازگار با **نسخه‌های ۱۱ تا ۱۴**.
 *
 * ایدهٔ اصلی: نسخه‌ها مسیرهای متفاوتی دارند و اپ نباید به یکی قفل شود.
 *  ۱) `GET /api/v2/admin/me/` → آیا مسیر ادمین و کلید درست است؟ (۱۱–۱۴)
 *  ۲) فهرست کاربران به سه شکل:
 *       • `/api/v2/admin/users/`  ← نسخهٔ ۱۴ (کامل‌ترین، با لینک‌دامنه‌ها)
 *       • `/api/v2/admin/user/`   ← نسخه‌های ۱۱ تا ۱۴ (کلاسیک)
 *       • `/api/v1/user/`         ← نسخه‌های ۱۰ تا ۱۴ (قدیمی، فقط ادمین کل)
 *  ۳) ساخت/ویرایش/حذف با همان سبکی که جواب داد.
 *
 * کدهای وضعیت هیدی‌فای معنی دقیق دارند و همین‌ها را به پیام فارسی تبدیل می‌کنیم:
 *  • **۴۰۰ invalid request** → مسیر پروکسی، «مسیر ادمین» نیست (معمولاً مسیر اشتراک یا اشتباه)
 *  • **۴۰۳ Unathorized**     → مسیر درست است، ولی کلید API معتبر نیست
 *  • **۴۰۴**                 → این سرویس در این نسخه وجود ندارد، یا آدرس تکهٔ اضافه دارد
 *  • **HTML**                → به صفحهٔ ورود ریدایرکت شده‌ایم (کلید/مسیر غلط)
 */
class PanelClient(
    private val panelDao: PanelDao,
    private val prefs: Prefs,
    private val http: PanelHttp = PanelHttp()
) {
    /** آخرین مسیری که برای فهرست کاربران جواب داد */
    @Volatile
    var workingEndpoint: String = ""
        private set

    /** متن آخرین پاسخ خام مشکل‌دار (برای گزارش قابل‌کپی) */
    @Volatile
    var lastRawError: String = ""
        private set

    /** سبک API که جواب داد */
    @Volatile
    var flavor: ApiFlavor? = null
        private set

    @Volatile
    var panelVersion: String = ""
        private set

    @Volatile
    var adminMe: AdminMeDto? = null
        private set

    /** آدرس‌ها و کدهای وضعیت آخرین اسکن (برای نمایش) */
    @Volatile
    var lastProbes: List<PanelCheck> = emptyList()
        private set

    // نتیجهٔ تشخیص مسیر/نسخه، کوتاه‌مدت نگه داشته می‌شود تا هر عملیات ۴ درخواست اضافه نزند
    @Volatile
    private var cache: Resolved? = null

    @Volatile
    private var cacheAt: Long = 0

    /** بعد از تغییر تنظیمات، تشخیص باید از نو انجام شود */
    fun invalidate() {
        cache = null
        cacheAt = 0
        flavor = null
        workingEndpoint = ""
    }

    // ─────────────────────────────────────────────────────── پیکربندی

    suspend fun currentConfig(): PanelConfig = panelDao.config() ?: PanelConfig()

    suspend fun endpointsNow(): PanelEndpoints = endpoints(currentConfig())

    fun endpoints(config: PanelConfig): PanelEndpoints {
        val t = PanelTarget.of(config)
        val api = if (t.proxyPath.isBlank()) "" else "${t.panelUrl}/api/v2"
        val sub = linkBaseOf(config)
        val f = ApiFlavor.from(config.apiFlavor)
        val createUrl = when (f) {
            ApiFlavor.PAGE -> if (api.isBlank()) "" else t.panelUrl + PanelRoutes.USERS_PAGE
            ApiFlavor.V1 -> if (api.isBlank()) "" else t.panelUrl + PanelRoutes.V1_USERS
            else -> if (api.isBlank()) "" else t.panelUrl + PanelRoutes.USERS_LEGACY
        }
        val userUrl = when (f) {
            ApiFlavor.PAGE -> if (api.isBlank()) "" else t.panelUrl + "/api/v2/admin/users/<uuid>/"
            ApiFlavor.V1 -> if (api.isBlank()) "" else t.panelUrl + "/api/v1/user/?uuid=<uuid>"
            else -> if (api.isBlank()) "" else t.panelUrl + "/api/v2/admin/user/<uuid>/"
        }
        return PanelEndpoints(
            panelUrl = t.panelUrl,
            apiBase = if (api.isBlank()) "" else "$api/",
            usersListUrl = when (f) {
                ApiFlavor.PAGE -> t.panelUrl + PanelRoutes.USERS_PAGE
                ApiFlavor.V1 -> t.panelUrl + PanelRoutes.V1_USERS
                else -> t.panelUrl + PanelRoutes.USERS_LEGACY
            }.takeIf { !api.isBlank() } ?: "",
            createUserUrl = createUrl,
            userUrl = userUrl,
            subscriptionBase = sub,
            subscriptionExample = if (sub.isBlank()) "" else sub + "00000000-0000-0000-0000-000000000000/",
            flavor = f?.label ?: "خودکار (اولین اتصال تشخیص می‌دهد)",
            version = config.panelVersion
        )
    }

    /** پایهٔ لینک اشتراک: مقدار صریح تنظیمات، وگرنه مقدار کشف‌شده/قدیمی */
    private fun linkBaseOf(config: PanelConfig): String {
        val fromConfig = PanelUrl.normalizeLinkBase(config.subscriptionBase)
        if (fromConfig.isNotBlank()) return fromConfig
        return PanelUrl.normalizeLinkBase(prefs.current.fallbackSubscriptionBase)
    }

    private suspend fun configOrThrow(): PanelConfig {
        val c = panelDao.config() ?: PanelConfig()
        if (c.host.isBlank() || c.apiKey.isBlank()) throw PanelException(
            "اتصال پنل تنظیم نشده است: «آدرس پنل» (همان آدرس مرورگر) و «کلید API» را وارد کنید.",
            PanelException.Kind.NOT_CONFIGURED
        )
        val t = PanelTarget.of(c)
        if (t.proxyPath.isBlank()) throw PanelException(
            "مسیر پنل از آدرس استخراج نشد. آدرس را کامل بچسبانید؛ مثل https://panel.example.com/AbCdEf12",
            PanelException.Kind.NOT_CONFIGURED
        )
        return c
    }

    // ─────────────────────────────────────────────────────── خطاها

    private fun saveRaw(url: String, r: HttpResult) {
        lastRawError = buildString {
            append(r.code).append(" — ").append(url).append('\n')
            if (r.body.isNotBlank()) append(r.body.trim().take(600))
        }
    }

    /**
     * تبدیل یک پاسخ ناموفق به استثنای فارسی با «علت دقیق».
     * این تابع قلب رفع ابهام «۴۰۴» است: هر کد وضعیت، معنی متفاوتی دارد.
     */
    private fun failure(r: HttpResult, action: String): PanelException {
        saveRaw(r.url, r)
        return when {
            r.isInvalidProxyPath -> PanelException(
                "پنل پاسخ داد «invalid request»: مسیری که درخواست به آن رفته، «مسیر پروکسی ادمین» نیست.\n" +
                    "علت رایج: در فیلد «آدرس پنل» آدرسِ پنل کاربر یا لینک اشتراک گذاشته شده است.\n" +
                    "راه‌حل: در مرورگر با حساب ادمین وارد پنل شوید و آدرس نوار آدرس را کامل کپی کنید " +
                    "(مثل https://panel.example.com/AbCdEf12/admin/).",
                PanelException.Kind.PATH
            )
            r.isUnauthorized -> PanelException(
                "مسیر ادمین درست است، ولی «کلید API» پذیرفته نشد (${r.code}).\n" +
                    "کلید باید UUID خودِ ادمین باشد (پنل → ادمین‌ها → کلید API)." +
                    (r.panelMessage.takeIf { it.isNotBlank() }?.let { " پیام پنل: $it" } ?: ""),
                PanelException.Kind.AUTH
            )
            r.isHtml -> PanelException(
                "پنل به‌جای JSON، صفحهٔ ورود/HTML برگرداند. یعنی کلید API یا مسیر ادمین درست نیست.",
                PanelException.Kind.AUTH
            )
            r.code == 404 -> PanelException(
                "آدرس «$action» در پنل پیدا نشد (۴۰۴).\n" +
                    "یعنی یا نسخهٔ پنل این سرویس را ندارد، یا در آدرس، مسیر اضافه وجود دارد.\n" +
                    "آدرس استفاده‌شده: ${r.url}",
                PanelException.Kind.NOT_FOUND
            )
            r.code == 405 -> PanelException(
                "نسخهٔ پنل شما این عملیات را در این مسیر پشتیبانی نمی‌کند (۴۰۵) — آدرس: ${r.url}",
                PanelException.Kind.SERVER
            )
            r.code == 400 -> PanelException(
                "پنل درخواست را رد کرد: " + r.panelMessage.ifBlank { r.short() },
                PanelException.Kind.SERVER
            )
            r.code in 500..599 -> PanelException(
                "خطای سرور پنل (${r.code}): " + r.panelMessage.ifBlank { r.short() },
                PanelException.Kind.SERVER
            )
            else -> PanelException(
                "خطای پنل (${r.code}): " + r.panelMessage.ifBlank { r.short() },
                PanelException.Kind.SERVER
            )
        }
    }

    private fun panelExceptionFor(e: Throwable): PanelException = when (e) {
        is PanelException -> e
        is java.net.UnknownHostException -> PanelException(
            "دامنهٔ پنل پیدا نشد (اتصال اینترنت یا دامنه را بررسی کنید)",
            PanelException.Kind.NETWORK
        )
        is java.net.SocketTimeoutException -> PanelException(
            "پاسخ پنل دیر شد (timeout). پنل پس از هر تغییر، کانفیگ‌ها را دوباره می‌سازد؛ چند لحظه بعد دوباره تلاش کنید.",
            PanelException.Kind.NETWORK
        )
        is javax.net.ssl.SSLException -> PanelException(
            "خطای گواهی SSL پنل: ${e.message}",
            PanelException.Kind.NETWORK
        )
        is java.io.IOException -> PanelException("ارتباط با پنل برقرار نشد: ${e.message}", PanelException.Kind.NETWORK)
        else -> PanelException(e.message ?: e::class.java.simpleName, PanelException.Kind.GENERIC)
    }

    private suspend fun <T> guard(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: PanelException) {
            throw e
        } catch (e: Exception) {
            throw panelExceptionFor(e)
        }
    }

    // ─────────────────────────────────────────────────────── JSON

    private fun element(text: String): JsonElement? =
        runCatching { PanelJson.lenient.decodeFromString(JsonElement.serializer(), text) }.getOrNull()

    private fun parseUserBody(text: String): PanelUserDto? {
        val el = element(text) ?: return null
        return runCatching { PanelJson.parseUser(el) }.getOrNull()
    }

    private fun objOf(vararg pairs: Pair<String, JsonElement?>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                null -> put(k, JsonNull)
                else -> put(k, v)
            }
        }
    }

    private fun str(v: String): JsonElement = JsonPrimitive(v)
    private fun num(v: Double): JsonElement = JsonPrimitive(v)
    private fun int(v: Int): JsonElement = JsonPrimitive(v)
    private fun bool(v: Boolean): JsonElement = JsonPrimitive(v)

    // ─────────────────────────────────────────────────────── تشخیص نسخه

    /**
     * کاوش پنل: اول وضعیت مسیر و کلید (`/me/`)، بعد پیدا کردن مسیری که فهرست کاربران را می‌دهد.
     * نتیجه در `lastProbes` برای نمایش ذخیره می‌شود.
     */
    suspend fun resolve(force: Boolean = false): Resolved = guard {
        val c = configOrThrow()
        if (!force) {
            val hit = cache
            if (hit != null &&
                System.currentTimeMillis() - cacheAt < 60_000 &&
                hit.config.host == c.host &&
                hit.config.adminProxyPath == c.adminProxyPath &&
                hit.config.apiKey == c.apiKey
            ) {
                flavor = hit.flavor
                return@guard hit
            }
        }
        var config = c
        var target = PanelTarget.of(config)
        val probes = mutableListOf<PanelCheck>()

        // ۱) مسیر و کلید: /me/ در نسخه‌های ۱۱ تا ۱۴ وجود دارد
        //
        // اگر پنل با http بالا آمده باشد (مثلاً 192.168.1.10:8000) و ما با https برویم،
        // خطای اتصال/SSL می‌گیریم نه ۴۰۴. اینجا خودکار یک بار با http هم امتحان می‌شود.
        var meRes: HttpResult
        try {
            meRes = http.get(target.url(PanelRoutes.ME), config.apiKey)
        } catch (e: Exception) {
            if (target.scheme == "https") {
                val alt = target.copy(scheme = "http")
                meRes = try {
                    http.get(alt.url(PanelRoutes.ME), config.apiKey)
                } catch (e2: Exception) {
                    throw panelExceptionFor(e2)
                }
                target = alt
                config = config.copy(useHttps = false)
                probes += PanelCheck("پروتکل", true, "پنل روی http جواب داد (به‌جای https)")
                runCatching { panelDao.saveConfig(config) }
            } else {
                throw panelExceptionFor(e)
            }
        }
        probes += PanelCheck(
            "اتصال به مسیر ادمین (${PanelRoutes.ME})",
            meRes.ok,
            when {
                meRes.ok -> "کد ۲۰۰ — مسیر و کلید پذیرفته شد"
                meRes.isInvalidProxyPath -> "کد ۴۰۰ invalid request — این مسیر، مسیر ادمین نیست"
                meRes.isUnauthorized -> "کد ${meRes.code} — مسیر درست است ولی کلید API معتبر نیست"
                meRes.isHtml -> "صفحهٔ HTML — به صفحهٔ ورود ریدایرکت شدیم (کلید یا مسیر غلط)"
                else -> "کد ${meRes.code} — ${meRes.short().ifBlank { "بدون توضیح" }}"
            } + "\n" + meRes.url
        )
        if (meRes.ok) {
            adminMe = runCatching {
                PanelJson.lenient.decodeFromString(AdminMeDto.serializer(), meRes.body)
            }.getOrNull()
        }
        // مسیر غلط یا کلید غلط → امتحان بقیهٔ مسیرها بی‌فایده است؛ همان‌جا توضیح دقیق بده
        if (!meRes.ok && (meRes.isInvalidProxyPath || meRes.isUnauthorized || meRes.isHtml)) {
            lastProbes = probes
            throw failure(meRes, "تشخیص مسیر ادمین")
        }

        // ۲) نسخهٔ پنل (اطلاعاتی)
        val infoRes = http.get(target.url(PanelRoutes.INFO), config.apiKey)
        var version = ""
        if (infoRes.ok) {
            version = runCatching {
                PanelJson.lenient.decodeFromString(PanelInfoDto.serializer(), infoRes.body)
                    .let { it.version.ifBlank { it.panelVersion ?: "" } }
            }.getOrDefault("")
        }
        probes += PanelCheck(
            "نسخهٔ پنل (${PanelRoutes.INFO})",
            infoRes.ok,
            if (infoRes.ok) version.ifBlank { "نامشخص" } else "کد ${infoRes.code} — اطلاعاتی است، ادامه می‌دهیم"
        )

        // ۳) فهرست کاربران: سه سبک، اول آن که قبلاً جواب داده
        val saved = ApiFlavor.from(config.apiFlavor)
        val order = buildList {
            saved?.let { add(it) }
            ApiFlavor.entries.forEach { if (it != saved) add(it) }
        }
        var lastError: PanelException? = null
        for (f in order) {
            val url = target.url(
                when (f) {
                    ApiFlavor.PAGE -> PanelRoutes.USERS_PAGE
                    ApiFlavor.LEGACY -> PanelRoutes.USERS_LEGACY
                    ApiFlavor.V1 -> PanelRoutes.V1_USERS
                }
            )
            val r = try {
                http.get(url, config.apiKey)
            } catch (e: Exception) {
                lastError = panelExceptionFor(e)
                probes += PanelCheck("فهرست کاربران — ${f.label}", false, panelExceptionFor(e).message ?: "خطا")
                continue
            }
            if (r.ok || r.isEmptyUserList) {
                val users = parseUsers(r, f)
                probes += PanelCheck(
                    "فهرست کاربران — ${f.label}",
                    true,
                    (if (r.isEmptyUserList) "بدون کاربر" else "${users.size} کاربر") + "\n" + url
                )
                flavor = f
                workingEndpoint = url
                lastProbes = probes
                val resolved = Resolved(
                    config = config,
                    target = target,
                    flavor = f,
                    users = users,
                    meta = metaOf(r, f),
                    version = version
                )
                cache = resolved
                cacheAt = System.currentTimeMillis()
                return@guard resolved
            }
            probes += PanelCheck(
                "فهرست کاربران — ${f.label}",
                false,
                when {
                    r.isInvalidProxyPath -> "کد ۴۰۰ invalid request — مسیر ادمین نیست"
                    r.isUnauthorized -> "کد ${r.code} — کلید API رد شد"
                    r.isHtml -> "صفحهٔ HTML — به صفحهٔ ورود ریدایرکت شدیم"
                    else -> "کد ${r.code} — در این نسخه وجود ندارد"
                } + "\n" + url
            )
            if (lastError == null) lastError = failure(r, "فهرست کاربران")
        }

        lastProbes = probes
        throw lastError ?: PanelException(
            "هیچ‌کدام از روش‌های شناخته‌شدهٔ ساخت کاربر در پنل شما جواب نداد. " +
                "آدرس پنل را بررسی کنید یا گزارش عیب‌یابی را بفرستید.",
            PanelException.Kind.NOT_FOUND
        )
    }

    private fun metaOf(r: HttpResult, f: ApiFlavor): UsersMetaDto {
        if (!r.ok || f != ApiFlavor.PAGE) return UsersMetaDto()
        val el = element(r.body) ?: return UsersMetaDto()
        val obj = el as? JsonObject ?: return UsersMetaDto()
        val src: JsonElement = (obj["meta"] as? JsonObject) ?: obj
        return runCatching { PanelJson.lenient.decodeFromJsonElement<UsersMetaDto>(src) }
            .getOrDefault(UsersMetaDto())
    }

    private fun parseUsers(r: HttpResult, f: ApiFlavor): List<PanelUserDto> {
        if (r.isEmptyUserList) return emptyList()
        val el = element(r.body) ?: return emptyList()
        return when (f) {
            ApiFlavor.PAGE -> PanelJson.parseUsers(el).first
            else -> when (el) {
                is JsonArray -> el.mapNotNull { row -> runCatching { PanelJson.parseUser(row) }.getOrNull() }
                is JsonObject -> (el["users"] as? JsonArray)
                    ?.mapNotNull { row -> runCatching { PanelJson.parseUser(row) }.getOrNull() }
                    ?: emptyList()
                else -> emptyList()
            }
        }
    }

    data class Resolved(
        val config: PanelConfig,
        val target: PanelTarget,
        val flavor: ApiFlavor,
        val users: List<PanelUserDto>,
        val meta: UsersMetaDto,
        val version: String
    )

    // ─────────────────────────────────────────────────────── خواندن

    suspend fun testConnection(): PanelConnection = guard {
        val res = resolve()
        val created = endpointOf(res.target, res.flavor)
        val discovered = discoverSubscriptionBase(res)
        val storedBase = if (res.config.subscriptionBase.isBlank()) discovered else res.config.subscriptionBase
        panelDao.saveConfig(
            res.config.copy(
                panelVersion = res.version.ifBlank { res.config.panelVersion },
                lastSyncAt = System.currentTimeMillis(),
                subscriptionBase = storedBase,
                apiFlavor = res.flavor.id
            )
        )
        if (discovered.isNotBlank()) prefs.update { it.copy(fallbackSubscriptionBase = discovered) }
        PanelConnection(
            ok = true,
            version = res.version.ifBlank { panelVersion },
            adminUuid = adminMe?.uuid ?: res.meta.meUuid,
            adminName = adminMe?.name.orEmpty(),
            canAddUsers = res.meta.canAdd,
            readOnly = res.meta.readOnly,
            linkBase = storedBase,
            usersCount = res.users.size,
            endpoint = created,
            flavor = res.flavor.label,
            message = "اتصال برقرار است"
        )
    }

    private fun endpointOf(target: PanelTarget, f: ApiFlavor): String = when (f) {
        ApiFlavor.PAGE -> target.url(PanelRoutes.USERS_PAGE)
        ApiFlavor.LEGACY -> target.url(PanelRoutes.USERS_LEGACY)
        ApiFlavor.V1 -> target.url(PanelRoutes.V1_USERS)
    }

    /** فهرست تازه از پنل (برای همگام‌سازی؛ از حافظهٔ موقت استفاده نمی‌کند) */
    suspend fun fetchUsers(): Pair<List<PanelUserDto>, UsersMetaDto> {
        val res = resolve(force = true)
        return res.users to res.meta
    }

    suspend fun fetchUser(uuid: String): PanelUserDto = guard {
        val res = resolve()
        userOf(res, uuid) ?: throw PanelException("کاربر در پنل پیدا نشد", PanelException.Kind.NOT_FOUND)
    }

    private fun userOf(res: Resolved, uuid: String): PanelUserDto? {
        val url = when (res.flavor) {
            ApiFlavor.PAGE -> res.target.url(PanelRoutes.userPage(uuid))
            ApiFlavor.LEGACY -> res.target.url(PanelRoutes.userLegacy(uuid))
            ApiFlavor.V1 -> res.target.url(PanelRoutes.V1_USERS + "?uuid=" + uuid)
        }
        val r = http.get(url, res.config.apiKey)
        if (!r.ok) return null
        val text = r.body.trim()
        if (res.flavor == ApiFlavor.V1 && text.startsWith("[")) {
            // پاسخ v1 برای ?uuid= یک شیء است؛ ولی اگر آرایه بود، همان را می‌خوانیم
            return (element(text) as? JsonArray)?.firstOrNull()?.let {
                runCatching { PanelJson.parseUser(it) }.getOrNull()
            }
        }
        return parseUserBody(text)
    }

    // ─────────────────────────────────────────────────────── نوشتن

    /**
     * ساخت کاربر — همان عملیاتی که «کار نمی‌کرد».
     *
     * بدنهٔ درخواست بر اساس نسخه ساخته می‌شود:
     *  • ۱۴ (`/users/`): `name, usage_limit_GB, package_days, mode, comment, enable, uuid`
     *  • ۱۱–۱۴ (`/user/`): همان + `uuid` (اسکیمای نسخه‌های قدیمی همین‌ها را می‌شناسد)
     *  • ۱۰–۱۴ (`/api/v1/user/`): `uuid` اجباری است و پاسخ «کاربر» برنمی‌گرداند
     *
     * اگر پاسخ خطا/تایم‌اوت بود، **وجود کاربر در پنل بررسی می‌شود**؛ چون پنل بعد از هر نوشتن
     * `quick_apply_users` را اجرا می‌کند و ممکن است پاسخ دیر برسد.
     */
    suspend fun createUser(
        name: String,
        gb: Double,
        days: Int,
        comment: String = "",
        uuid: String = UUID.randomUUID().toString()
    ): PanelUserDto = guard {
        val res = resolve()
        val body = objOf(
            "uuid" to str(uuid),
            "name" to str(name.ifBlank { "user" }),
            "usage_limit_GB" to num(if (gb <= 0) 100.0 else gb),
            "package_days" to int(if (days <= 0) 30 else days),
            "mode" to str("no_reset"),
            "comment" to if (comment.isBlank()) null else str(comment),
            "enable" to bool(true)
        )
        val url = when (res.flavor) {
            ApiFlavor.PAGE -> res.target.url(PanelRoutes.USERS_PAGE)
            ApiFlavor.LEGACY -> res.target.url(PanelRoutes.USERS_LEGACY)
            ApiFlavor.V1 -> res.target.url(PanelRoutes.V1_USERS)
        }
        val json = PanelJson.lenient.encodeToString(JsonObject.serializer(), body)
        val r = http.post(url, res.config.apiKey, json)

        if (r.ok) {
            parseUserBody(r.body)?.let { return@guard it }
            // v1 پاسخ `{status, msg}` می‌دهد؛ کاربر را جدا می‌خوانیم
            userOf(res, uuid)?.let { return@guard it }
            // ساخت انجام شده ولی پاسخ قابل خواندن نبود — از فهرست تأیید می‌گیریم
            resolve(force = true).users.firstOrNull { it.uuid == uuid }?.let { return@guard it }
            throw PanelException(
                "پنل پاسخ داد ولی کاربر در فهرست دیده نشد. «عیب‌یابی» را اجرا کنید. آدرس: $url",
                PanelException.Kind.SERVER
            )
        }

        // پیام «کاربر تکراری» یعنی ساخت قبلی موفق بوده (تکرار درخواست یا تأخیر پنل)
        if (r.code == 400 && (
                r.body.contains("exists", true) || r.body.contains("duplicat", true)
                )
        ) {
            userOf(res, uuid)?.let { return@guard it }
        }

        // پیش از گزارش خطا، یک بار دیگر مطمئن شویم ساخته نشده
        val after = runCatching { resolve(force = true).users.firstOrNull { it.uuid == uuid } }.getOrNull()
        if (after != null) return@guard after

        throw failure(r, "ساخت کاربر")
    }

    /** آپدیت کاربر؛ بدنه بر اساس سبک ساخته می‌شود (و `start_date = null` صریح فرستاده می‌شود). */
    private suspend fun updateUser(
        res: Resolved,
        uuid: String,
        fields: Map<String, JsonElement>
    ): PanelUserDto? {
        val body = buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }
        val json = PanelJson.lenient.encodeToString(JsonObject.serializer(), body)
        val r = when (res.flavor) {
            ApiFlavor.PAGE -> http.patch(res.target.url(PanelRoutes.userPage(uuid)), res.config.apiKey, json)
            ApiFlavor.LEGACY -> http.patch(res.target.url(PanelRoutes.userLegacy(uuid)), res.config.apiKey, json)
            // v1 متد PATCH ندارد؛ همان POST «add_or_update» با uuid کار را انجام می‌دهد
            ApiFlavor.V1 -> http.post(res.target.url(PanelRoutes.V1_USERS), res.config.apiKey, json)
        }
        if (!r.ok) throw failure(r, "ویرایش کاربر")
        return parseUserBody(r.body) ?: userOf(res, uuid)
    }

    /**
     * تمدید = دورهٔ جدید.
     *
     *  • نسخهٔ ۱۴ صفحهٔ کاربران فقط پرچم‌های `reset_usage` / `reset_days` را می‌خواند.
     *  • مسیر کلاسیک ۱۱ تا ۱۴ `start_date = null` و `current_usage_GB = 0` را مستقیم می‌پذیرد.
     * هر دو گروه فیلد در یک بدنه فرستاده می‌شوند تا در همهٔ نسخه‌ها درست کار کند.
     */
    suspend fun renewPackage(uuid: String, gb: Double, days: Int, name: String? = null): PanelUserDto = guard {
        val res = resolve()
        val fields = mutableMapOf<String, JsonElement>(
            "enable" to bool(true),
            "current_usage_GB" to num(0.0),
            "start_date" to JsonNull,
            "reset_usage" to bool(true),
            "reset_days" to bool(true)
        )
        if (gb > 0) fields["usage_limit_GB"] = num(gb)
        if (days > 0) fields["package_days"] = int(days)
        if (!name.isNullOrBlank()) fields["name"] = str(name)
        val updated = updateUser(res, uuid, fields)
        updated ?: throw PanelException("تمدید انجام شد ولی پاسخ پنل قابل خواندن نبود", PanelException.Kind.SERVER)
    }

    /** افزودن حجم/روز روی بستهٔ فعلی */
    suspend fun extendLimits(uuids: List<String>, addGb: Double = 0.0, addDays: Int = 0): Int = guard {
        require(uuids.isNotEmpty()) { "کاربری انتخاب نشده است" }
        require(addGb > 0 || addDays > 0) { "حجم یا روز را وارد کنید" }
        val res = resolve()

        // نسخهٔ ۱۴ عملیات گروهی دارد؛ نسخه‌های قدیمی‌تر ندارند → یکی‌یکی
        if (res.flavor == ApiFlavor.PAGE) {
            val body = objOf(
                "action" to str("add_limits"),
                "uuids" to JsonArray(uuids.map { JsonPrimitive(it) }),
                "gb" to num(if (addGb > 0) addGb else 0.0),
                "days" to int(if (addDays > 0) addDays else 0)
            )
            val json = PanelJson.lenient.encodeToString(JsonObject.serializer(), body)
            val r = http.post(res.target.url(PanelRoutes.BULK_PAGE), res.config.apiKey, json)
            if (r.ok) {
                return@guard runCatching { (element(r.body) as? JsonObject)?.get("count")?.toString()?.toInt() }
                    .getOrNull() ?: uuids.size
            }
            if (!r.isNotFound && r.code !in 500..599) throw failure(r, "عملیات گروهی")
            // اگر نسخهٔ ۱۴ هم bulk نداشت، به روش یکی‌یکی ادامه می‌دهیم
        }

        var done = 0
        for (uuid in uuids) {
            val current = userOf(res, uuid) ?: continue
            val fields = mutableMapOf<String, JsonElement>()
            if (addGb > 0) fields["usage_limit_GB"] = num(current.usageLimitGb + addGb)
            if (addDays > 0) fields["package_days"] = int(current.packageDays + addDays)
            runCatching { updateUser(res, uuid, fields) }.onSuccess { done++ }
        }
        if (done == 0) throw PanelException("افزودن حجم/روز انجام نشد", PanelException.Kind.SERVER)
        done
    }

    suspend fun setEnabled(uuid: String, enabled: Boolean): PanelUserDto = guard {
        val res = resolve()
        updateUser(res, uuid, mapOf("enable" to bool(enabled)))
            ?: throw PanelException("تغییر وضعیت انجام شد ولی پاسخ پنل خوانده نشد", PanelException.Kind.SERVER)
    }

    suspend fun deleteUser(uuid: String) = guard {
        val res = resolve()
        val r = when (res.flavor) {
            ApiFlavor.PAGE -> http.delete(res.target.url(PanelRoutes.userPage(uuid)), res.config.apiKey)
            ApiFlavor.LEGACY -> http.delete(res.target.url(PanelRoutes.userLegacy(uuid)), res.config.apiKey)
            ApiFlavor.V1 -> http.delete(res.target.url(PanelRoutes.V1_USERS + "?uuid=" + uuid), res.config.apiKey)
        }
        if (!r.ok && !r.isNotFound) throw failure(r, "حذف کاربر")
        Unit
    }

    // ─────────────────────────────────────────────────────── لینک اشتراک

    /**
     * پایهٔ لینک اشتراک را از خودِ پنل پیدا می‌کند (اگر کاربر وارد نکرده باشد).
     *  ۱) نسخهٔ ۱۴: `link_domains[0].base` در پاسخ فهرست کاربران
     *  ۲) همهٔ نسخه‌ها با کلید ادمین کل: `/api/v2/admin/all-configs/` → `proxy_path_client`
     */
    suspend fun discoverSubscriptionBase(res: Resolved? = null): String = runCatching {
        val r = res ?: resolve()
        // مقدار دستی کاربر مقدم است
        if (r.config.subscriptionBase.isNotBlank()) return@runCatching PanelUrl.normalizeLinkBase(r.config.subscriptionBase)
        r.meta.linkDomains.firstOrNull()?.base?.let { base ->
            val b = PanelUrl.normalizeLinkBase(base)
            if (b.isNotBlank()) return@runCatching b
        }
        // all-configs: فقط ادمین کل اجازه دارد؛ اگر ۴۰۳ داد بی‌صدا رد می‌شویم
        val ac = http.get(r.target.url(PanelRoutes.ALL_CONFIGS), r.config.apiKey)
        if (ac.ok) {
            val root = parseMaybeStringJson(ac.body)
            val clientPath = findString(root, "proxy_path_client")
            if (!clientPath.isNullOrBlank()) {
                return@runCatching PanelUrl.normalizeLinkBase("${r.target.origin}/$clientPath/")
            }
        }
        PanelUrl.normalizeLinkBase(prefs.current.fallbackSubscriptionBase)
    }.getOrDefault(PanelUrl.normalizeLinkBase(prefs.current.fallbackSubscriptionBase))

    /** پاسخ همهٔ‌جا ممکن است یک رشتهٔ JSON دوباره‌کدگذاری‌شده باشد (`json.dumps` داخل پاسخ). */
    private fun parseMaybeStringJson(text: String): JsonElement? {
        val first = element(text) ?: return null
        return if (first is JsonPrimitive && first.isString) element(first.content) else first
    }

    /** جست‌وجوی بازگشتی کلید در JSON (برای پیدا کردن `proxy_path_client` در هر ساختاری) */
    private fun findString(el: JsonElement?, key: String): String? {
        if (el == null) return null
        return when (el) {
            is JsonObject -> {
                (el[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: el.values.asSequence().mapNotNull { findString(it, key) }.firstOrNull()
            }
            is JsonArray -> el.asSequence().mapNotNull { findString(it, key) }.firstOrNull()
            else -> null
        }
    }

    fun subscriptionUrl(uuid: String, linkBase: String): String {
        val base = PanelUrl.normalizeLinkBase(linkBase.ifBlank { prefs.current.fallbackSubscriptionBase })
        return PanelUrl.subscriptionLink(base, uuid)
    }

    // ─────────────────────────────────────────────────────── عیب‌یابی

    /**
     * عیب‌یابی کامل: هر مرحله یک ردیف، همراه با آدرس دقیق و کد وضعیت.
     * این همان چیزی است که «۴۰۴» را از یک پیام مبهم به علتِ مشخص تبدیل می‌کند.
     */
    suspend fun diagnose(): List<PanelCheck> {
        lastRawError = ""
        val checks = mutableListOf<PanelCheck>()
        val c = runCatching { configOrThrow() }.getOrElse {
            return listOf(PanelCheck("تنظیمات پنل", false, it.message ?: "تنظیم نشده"))
        }
        val t = PanelTarget.of(c)
        checks += PanelCheck("آدرس پنل", t.proxyPath.isNotBlank(), t.panelUrl + "\nمسیر پروکسی ادمین: " + t.proxyPath)

        val res = runCatching { resolve(force = true) }.getOrElse { e ->
            checks += lastProbes
            val pe = panelExceptionFor(e)
            checks += PanelCheck(
                "نتیجه",
                false,
                (pe.message ?: "خطای نامشخص") +
                    if (lastRawError.isNotBlank()) "\n— آخرین پاسخ خام —\n" + lastRawError.take(500) else ""
            )
            return checks
        }

        checks += lastProbes
        checks += PanelCheck("سبک API که جواب داد", true, res.flavor.label)
        checks += PanelCheck(
            "کاربر متصل",
            adminMe?.uuid?.isNotBlank() == true,
            (adminMe?.name?.ifBlank { "—" } ?: "—") + " • " + (adminMe?.uuid ?: "شناسه خوانده نشد")
        )
        checks += PanelCheck("تعداد کاربران", true, res.users.size.toString())
        checks += PanelCheck(
            "اجازهٔ ساخت کاربر",
            res.meta.canAdd && !res.meta.readOnly,
            when {
                res.meta.readOnly -> "پنل read-only است؛ ساخت مسدود"
                !res.meta.canAdd -> "سقف کاربران/ترافیک ادمین پر است"
                res.flavor != ApiFlavor.PAGE -> "در این نسخه اطلاعات محدودیت از پنل خوانده نمی‌شود؛ ساخت آزمایشی را بزنید"
                else -> "آزاد"
            }
        )
        val ep = endpoints(c.copy(apiFlavor = res.flavor.id, subscriptionBase =
        if (c.subscriptionBase.isNotBlank()) c.subscriptionBase else discoverSubscriptionBase(res)))
        checks += PanelCheck("آدرس ساخت کاربر", true, ep.createUserUrl)
        checks += PanelCheck(
            "پایهٔ لینک اشتراک",
            ep.subscriptionBase.isNotBlank(),
            ep.subscriptionBase.ifBlank { "پیدا نشد؛ در تنظیمات دستی وارد کنید" } +
                if (ep.subscriptionExample.isNotBlank()) "\nنمونه: " + ep.subscriptionExample else ""
        )
        return checks
    }

    /** فقط جدول کاوش (برای دکمهٔ «جستجوی خودکار» در تنظیمات) */
    suspend fun scan(): List<PanelCheck> {
        val res = runCatching { resolve(force = true) }
        val checks = lastProbes.toMutableList()
        res.onSuccess {
            checks += PanelCheck("سبک API", true, it.flavor.label + " — " + it.users.size + " کاربر")
        }.onFailure {
            checks += PanelCheck("نتیجه", false, panelExceptionFor(it).message ?: "خطا")
        }
        return checks
    }

    /**
     * آزمون واقعی ساخت کاربر: یک کاربر موقت می‌سازد و بلافاصله پاکش می‌کند.
     * اگر این سبز شد، ساختِ واقعی هم کار می‌کند.
     */
    suspend fun probeCreate(): PanelCheck {
        lastRawError = ""
        val stamp = System.currentTimeMillis().toString().takeLast(6)
        val name = "cmbtools-test-$stamp"
        val uuid = UUID.randomUUID().toString()
        return try {
            val created = createUser(name = name, gb = 1.0, days = 1, comment = "cmbtools:probe", uuid = uuid)
            val removed = runCatching { deleteUser(created.uuid) }
            PanelCheck(
                "ساخت کاربر آزمایشی",
                true,
                "کاربر «$name» ساخته شد ✅" +
                    (if (removed.isSuccess) " و پاک شد." else " (پاک‌کردن نشد: ${removed.exceptionOrNull()?.message})") +
                    "\nسبک: " + (flavor?.label ?: "—")
            )
        } catch (e: Exception) {
            PanelCheck(
                "ساخت کاربر آزمایشی",
                false,
                (panelExceptionFor(e).message ?: "خطا") +
                    if (lastRawError.isNotBlank()) "\n— آخرین پاسخ خام —\n" + lastRawError.take(400) else ""
            )
        }
    }

    /** گزارش متنی برای کپی/ارسال (کلید ماسک می‌شود) */
    fun reportText(checks: List<PanelCheck>, config: PanelConfig?): String {
        val c = config ?: PanelConfig()
        val ep = endpoints(c)
        val keyMasked = if (c.apiKey.length <= 8) "****" else "****" + c.apiKey.takeLast(8)
        val sb = StringBuilder()
        sb.appendLine("گزارش اتصال تیزنت")
        sb.appendLine("نسخهٔ اپ: 0.3.2")
        sb.appendLine("آدرس پنل: ${ep.panelUrl}")
        sb.appendLine("مسیر پروکسی ادمین: ${PanelTarget.of(c).proxyPath}")
        sb.appendLine("سبک API: ${ep.flavor}")
        sb.appendLine("نسخهٔ پنل: ${ep.version.ifBlank { "?" }}")
        sb.appendLine("آدرس ساخت کاربر: ${ep.createUserUrl.ifBlank { "—" }}")
        sb.appendLine("پایهٔ لینک اشتراک: ${ep.subscriptionBase.ifBlank { "—" }}")
        sb.appendLine("کلید API: $keyMasked")
        sb.appendLine("—")
        checks.forEach { chk ->
            sb.appendLine((if (chk.ok) "✅ " else "❌ ") + chk.title + ": " + chk.detail)
        }
        if (lastRawError.isNotBlank()) {
            sb.appendLine("— آخرین پاسخ خام پنل —")
            sb.appendLine(lastRawError.take(900))
        }
        return sb.toString()
    }

    companion object {
        /** پنل تاریخ‌ها را ISO می‌دهد؛ اینجا به epochDay تبدیل می‌شود */
        fun parseEpochDay(iso: String?): Long {
            if (iso.isNullOrBlank()) return 0
            return runCatching { LocalDate.parse(iso.take(10)).toEpochDay() }.getOrDefault(0)
        }
    }
}
