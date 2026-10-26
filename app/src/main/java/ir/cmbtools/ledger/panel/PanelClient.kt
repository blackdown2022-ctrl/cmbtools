package ir.cmbtools.ledger.panel

import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.PanelConfig
import ir.cmbtools.ledger.data.PanelDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

class PanelException(message: String, val kind: Kind = Kind.GENERIC) : Exception(message) {
    enum class Kind { GENERIC, NOT_CONFIGURED, AUTH, NETWORK, NOT_FOUND, SERVER, LIMIT }
}

data class PanelConnection(
    val ok: Boolean,
    val version: String = "",
    val adminUuid: String = "",
    val canAddUsers: Boolean = true,
    val readOnly: Boolean = false,
    val linkBase: String = "",
    val usersCount: Int = 0,
    val endpoint: String = "",
    val message: String = ""
)

/** یک ردیف نتیجهٔ عیب‌یابی اتصال، برای نمایش در تنظیمات. */
data class PanelCheck(val title: String, val ok: Boolean, val detail: String)

/**
 * تجزیهٔ آدرس پنل.
 *
 * کاربر (به‌خصوص اگر تازه‌کار باشد) هر کدام از این‌ها را در فیلد «آدرس پنل» می‌چسباند
 * و همه باید یک نتیجه بدهند:
 *
 *   panel.example.com
 *   https://panel.example.com/
 *   https://panel.example.com/AbCdEf12/            ← آدرس پنل در مرورگر
 *   https://panel.example.com/AbCdEf12/admin/      ← گاهی با /admin
 *   https://panel.example.com/AbCdEf12/api/v2/…    ← کل لینک API
 *
 * نتیجه: دامنه (با پورت)، «مسیر پروکسی ادمین» و http/https.
 */
object PanelUrl {

    data class Parts(val host: String, val proxyPath: String, val useHttps: Boolean) {
        val scheme: String get() = if (useHttps) "https" else "http"

        /** https://panel.example.com/AbCdEf    (بدون اسلش آخر) */
        val panelUrl: String get() = "$scheme://$host" + if (proxyPath.isBlank()) "" else "/$proxyPath"

        /** پایهٔ درخواست‌های Retrofit: https://panel.example.com/ */
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
     * حذف پسوندهای API و `/admin` و جدا کردن «مسیر پروکسی ادمین».
     * مثال‌ها:
     *   `AbCdEf/api/v2/admin/users/` → `AbCdEf`
     *   `/AbCdEf/admin/`             → `AbCdEf`
     *   `api/v2/admin`               → `` (مسیر واقعی نیست)
     */
    fun cleanPath(raw: String): String {
        // ?query و #fragment هرگز بخشی از مسیر پروکسی نیستند
        var p = raw.trim().substringBefore('#').substringBefore('?').trim('/')
        listOf("/api/v2/admin", "/api/v2/panel", "/api/v2", "/api").forEach { marker ->
            val at = p.indexOf(marker)
            if (at >= 0) p = p.substring(0, at)
        }
        // آدرس پنل در مرورگر معمولاً به /admin/ ختم می‌شود (یا /admin/#/users)
        val adminAt = p.indexOf("/admin")
        if (adminAt >= 0) p = p.substring(0, adminAt)
        if (p == "admin") p = ""
        if (p == "api" || p.startsWith("api/")) p = ""
        return p.trim('/')
    }

    /** آدرس کامل پنل برای نمایش/ویرایش در تنظیمات */
    fun display(host: String, proxyPath: String, useHttps: Boolean): String =
        normalize(host, proxyPath, useHttps).panelUrl

    /** پایهٔ لینک را یکدست می‌کند: با https شروع شود و اسلش آخر داشته باشد */
    fun normalizeLinkBase(raw: String): String {
        var base = raw.trim()
        if (base.isBlank()) return ""
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) base = "https://$base"
        if (!base.endsWith("/")) base += "/"
        return base
    }
}

/**
 * همهٔ آدرس‌هایی که اپ از پنل استفاده می‌کند، به‌صورت خوانا.
 * در تنظیمات نشان داده می‌شود تا معلوم باشد «آدرس ساخت کاربر» دقیقاً چیست.
 */
data class PanelEndpoints(
    val panelUrl: String = "",
    val apiBase: String = "",
    val usersListUrl: String = "",
    val createUserUrl: String = "",
    val userUrl: String = "",
    val bulkUrl: String = "",
    val subscriptionBase: String = "",
    val subscriptionExample: String = ""
)

/**
 * کلاینت پنل هیدی‌فای (API v2 — نسخهٔ ۱۴).
 *
 * اصول:
 *  • همهٔ فراخوانی‌ها روی IO و با تایم‌اوت بزرگ (پنل پس از هر نوشتن، `quick_apply_users`
 *    را اجرا می‌کند و می‌تواند ده‌ها ثانیه طول بکشد).
 *  • پاسخ‌ها به‌صورت متن خام خوانده می‌شوند: شکل‌های `{...}`، `{"user":{...}}` و آرایه
 *    همه پشتیبانی می‌شوند و متن خطای خود پنل هم در دسترس است.
 *  • اگر پنل به‌جای JSON صفحهٔ ورود (HTML) برگرداند، خطای صریح «کلید/مسیر اشتباه» داده
 *    می‌شود (قبلاً این حالت «۰ کاربر» نمایش داده می‌شد و تصور می‌شد ساخت کار نمی‌کند).
 *  • اگر ساخت/تمدید با timeout همراه بود، وجود کاربر در پنل بررسی می‌شود؛ چون پنل ممکن است
 *    کاربر را ساخته باشد ولی پاسخ دیر برسد.
 */
class PanelClient(
    private val panelDao: PanelDao,
    private val prefs: Prefs
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        // تلاش مجدد فقط برای خطاهای اتصال، نه تکرار کورکورانهٔ POST
        .retryOnConnectionFailure(true)
        .build()

    /** آخرین مسیری که برای فهرست کاربران جواب داد (برای نمایش در عیب‌یابی) */
    @Volatile
    var workingEndpoint: String = ""
        private set

    /** متن آخرین خطای خام پنل (برای گزارش قابل‌کپی) */
    @Volatile
    var lastRawError: String = ""
        private set

    @Volatile
    private var preferPageApi: Boolean = true

    // ─────────────────────────────────────────────────────── آدرس‌ها

    private fun service(config: PanelConfig): HiddifyApi {
        val parts = PanelUrl.normalize(config.host, config.adminProxyPath, config.useHttps)
        return Retrofit.Builder()
            .baseUrl(parts.baseUrl)
            .client(http)
            .addConverterFactory(PanelJson.lenient.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HiddifyApi::class.java)
    }

    private fun proxyPath(config: PanelConfig): String =
        PanelUrl.normalize(config.host, config.adminProxyPath, config.useHttps).proxyPath

    /** پایهٔ لینک اشتراک: مقدار صریح تنظیمات، وگرنه مقدار کشف‌شده/قدیمی */
    private fun linkBaseOf(config: PanelConfig): String {
        val fromConfig = PanelUrl.normalizeLinkBase(config.subscriptionBase)
        if (fromConfig.isNotBlank()) return fromConfig
        return PanelUrl.normalizeLinkBase(prefs.current.fallbackSubscriptionBase)
    }

    /** آدرس‌های نهایی برای نمایش در تنظیمات (شامل «آدرس ساخت کاربر» و لینک اشتراک) */
    fun endpoints(config: PanelConfig): PanelEndpoints {
        val parts = PanelUrl.normalize(config.host, config.adminProxyPath, config.useHttps)
        val api = "${parts.panelUrl}/api/v2"
        val sub = linkBaseOf(config)
        return PanelEndpoints(
            panelUrl = parts.panelUrl,
            apiBase = "$api/",
            usersListUrl = "$api/admin/users/",
            createUserUrl = "$api/admin/users/",
            userUrl = "$api/admin/users/<uuid>/",
            bulkUrl = "$api/admin/users/bulk/",
            subscriptionBase = sub,
            subscriptionExample = if (sub.isBlank()) "" else sub + "00000000-0000-0000-0000-000000000000/"
        )
    }

    suspend fun currentConfig(): PanelConfig = panelDao.config() ?: PanelConfig()

    suspend fun endpointsNow(): PanelEndpoints = endpoints(currentConfig())

    private suspend fun config(): PanelConfig {
        val c = panelDao.config() ?: PanelConfig()
        if (c.host.isBlank() || c.apiKey.isBlank()) throw PanelException(
            "اتصال پنل تنظیم نشده است: «آدرس پنل» (همان آدرس مرورگر) و «کلید API» را وارد کنید.",
            PanelException.Kind.NOT_CONFIGURED
        )
        if (proxyPath(c).isBlank()) throw PanelException(
            "مسیر پنل از آدرس استخراج نشد. آدرس را کامل بچسبانید؛ مثل https://panel.example.com/AbCdEf12",
            PanelException.Kind.NOT_CONFIGURED
        )
        return c
    }

    // ─────────────────────────────────────────────────────── خطاها

    private fun httpDetail(e: HttpException): String {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val url = runCatching { e.response()?.raw()?.request?.url?.toString() }.getOrNull().orEmpty()
        val text = PanelJson.errorText(body).ifBlank { body?.take(200)?.trim().orEmpty() }
        lastRawError = buildString {
            append(url).append('\n')
            append("HTTP ").append(e.code()).append('\n')
            append(text)
        }
        return text.ifBlank { e.message().orEmpty() }
    }

    private suspend fun <T> guard(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: PanelException) {
            throw e
        } catch (e: PanelShapeException) {
            throw PanelException(e.message ?: "پاسخ پنل قابل خواندن نیست", PanelException.Kind.SERVER)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw PanelException(
                "پاسخ پنل قابل خواندن نیست. احتمالاً مسیر پروکسی ادمین یا کلید API اشتباه است.",
                PanelException.Kind.AUTH
            )
        } catch (e: HttpException) {
            val detail = httpDetail(e)
            throw when (e.code()) {
                400 -> PanelException("پنل درخواست را رد کرد: $detail", PanelException.Kind.SERVER)
                401, 403 -> PanelException("دسترسی رد شد (کلید API یا سطح دسترسی ادمین): $detail", PanelException.Kind.AUTH)
                404 -> PanelException("مسیر پیدا نشد (مسیر پروکسی ادمین یا نسخهٔ پنل): $detail", PanelException.Kind.NOT_FOUND)
                405 -> PanelException("این عملیات در نسخهٔ پنل شما پشتیبانی نمی‌شود: $detail", PanelException.Kind.SERVER)
                409 -> PanelException("تعارض در پنل: $detail", PanelException.Kind.SERVER)
                422 -> PanelException("پنل درخواست را رد کرد: $detail", PanelException.Kind.SERVER)
                in 500..599 -> PanelException("خطای سرور پنل (${e.code()}): $detail", PanelException.Kind.SERVER)
                else -> PanelException("خطای پنل: ${e.code()} — $detail", PanelException.Kind.SERVER)
            }
        } catch (e: java.net.UnknownHostException) {
            throw PanelException("دامنهٔ پنل پیدا نشد (اتصال اینترنت یا دامنه را بررسی کنید)", PanelException.Kind.NETWORK)
        } catch (e: java.net.SocketTimeoutException) {
            throw PanelException(
                "پاسخ پنل دیر شد (timeout). پنل پس از هر تغییر، کانفیگ‌ها را دوباره می‌سازد؛ چند لحظه بعد دوباره تلاش کنید.",
                PanelException.Kind.NETWORK
            )
        } catch (e: javax.net.ssl.SSLException) {
            throw PanelException("خطای امنیتی گواهی SSL پنل: ${e.message}", PanelException.Kind.NETWORK)
        } catch (e: java.io.IOException) {
            throw PanelException("ارتباط با پنل برقرار نشد: ${e.message}", PanelException.Kind.NETWORK)
        }
    }

    /**
     * متن خام پاسخ؛ اگر HTML صفحهٔ ورود بود، خطای روشن می‌دهد.
     * (هیدی‌فای برای کلید نامعتبر به‌جای 401، ریدایرکت به صفحهٔ ورود می‌دهد.)
     */
    private fun jsonText(body: ResponseBody, what: String): String {
        val text = body.use { it.string() }
        val head = text.trimStart().take(80).lowercase()
        if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<")) {
            throw PanelException(
                "پنل برای «$what» به‌جای JSON، صفحهٔ ورود (HTML) برگرداند؛ یعنی کلید API یا مسیر پروکسی ادمین درست نیست.",
                PanelException.Kind.AUTH
            )
        }
        if (text.isBlank()) throw PanelException("پنل برای «$what» پاسخ خالی داد", PanelException.Kind.SERVER)
        return text
    }

    private fun element(text: String) =
        runCatching { PanelJson.lenient.decodeFromString(kotlinx.serialization.json.JsonElement.serializer(), text) }
            .getOrNull()

    /** اگر مسیر جدید ۴۰۴/۴۰۵ داد، مسیر کلاسیک امتحان می‌شود. */
    private suspend fun <T> withFallback(primary: suspend () -> T, classic: suspend () -> T): T {
        return try {
            primary()
        } catch (e: HttpException) {
            if (e.code() == 404 || e.code() == 405) classic() else throw e
        }
    }

    // ─────────────────────────────────────────────────────── خواندن

    suspend fun testConnection(): PanelConnection = guard {
        val c = config()
        val api = service(c)
        val path = proxyPath(c)

        // نسخهٔ پنل «خوب است بدانیم» است؛ نبودنش اتصال را رد نمی‌کند.
        val version = runCatching {
            val text = jsonText(api.panelInfo(path, c.apiKey), "نسخهٔ پنل")
            PanelJson.lenient.decodeFromString(PanelInfoDto.serializer(), text)
                .let { dto -> dto.version.ifBlank { dto.panelVersion ?: "" } }
        }.getOrDefault("")

        val (users, meta, endpoint) = fetchUsersInternal(api, path, c.apiKey)

        // پایهٔ لینک اشتراک: اگر کاربر دستی وارد نکرده باشد، از خود پنل گرفته می‌شود.
        val discovered = PanelUrl.normalizeLinkBase(meta.linkDomains.firstOrNull()?.base ?: "")
        val storedBase = c.subscriptionBase.ifBlank { discovered }
        if (discovered.isNotBlank()) {
            prefs.update { it.copy(fallbackSubscriptionBase = discovered) }
        }
        panelDao.saveConfig(
            c.copy(
                panelVersion = version.ifBlank { c.panelVersion },
                lastSyncAt = System.currentTimeMillis(),
                subscriptionBase = storedBase
            )
        )
        PanelConnection(
            ok = true,
            version = version,
            adminUuid = meta.meUuid,
            canAddUsers = meta.canAdd,
            readOnly = meta.readOnly,
            linkBase = storedBase,
            usersCount = users.size,
            endpoint = endpoint,
            message = "اتصال برقرار است"
        )
    }

    suspend fun fetchUsers(): Pair<List<PanelUserDto>, UsersMetaDto> = guard {
        val c = config()
        val (users, meta, _) = fetchUsersInternal(service(c), proxyPath(c), c.apiKey)
        if (meta.linkDomains.isNotEmpty()) {
            val discovered = PanelUrl.normalizeLinkBase(meta.linkDomains.first().base)
            prefs.update { it.copy(fallbackSubscriptionBase = discovered) }
            if (c.subscriptionBase.isBlank() && discovered.isNotBlank()) {
                panelDao.saveConfig(c.copy(subscriptionBase = discovered))
            }
        }
        users to meta
    }

    private suspend fun fetchUsersInternal(
        api: HiddifyApi,
        path: String,
        apiKey: String
    ): Triple<List<PanelUserDto>, UsersMetaDto, String> {
        if (preferPageApi) {
            try {
                val text = jsonText(api.usersPage(path, apiKey), "فهرست کاربران")
                val (users, meta) = PanelJson.parseUsers(element(text))
                workingEndpoint = "/api/v2/admin/users/"
                return Triple(users, meta, workingEndpoint)
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 405) throw e
                preferPageApi = false
            }
        }
        // مسیر کلاسیک: آرایهٔ خالص کاربران. اگر پنل هنوز کاربری ندارد، ۴۰۴ می‌دهد
        // («You have no user») که یعنی «فهرست خالی»، نه خطا.
        val text = try {
            jsonText(api.usersClassic(path, apiKey), "فهرست کاربران (کلاسیک)")
        } catch (e: HttpException) {
            if (e.code() == 404) {
                workingEndpoint = "/api/v2/admin/user/"
                return Triple(emptyList(), UsersMetaDto(), workingEndpoint)
            }
            throw e
        }
        val (users, meta) = PanelJson.parseUsers(element(text))
        workingEndpoint = "/api/v2/admin/user/"
        return Triple(users, meta, workingEndpoint)
    }

    suspend fun fetchUser(uuid: String): PanelUserDto = guard {
        val c = config()
        val api = service(c)
        val path = proxyPath(c)
        val text = withFallback(
            primary = { jsonText(api.userPage(path, uuid, c.apiKey), "کاربر") },
            classic = { jsonText(api.userClassic(path, uuid, c.apiKey), "کاربر (کلاسیک)") }
        )
        PanelJson.parseUser(element(text))
    }

    private suspend fun findCreated(uuid: String): PanelUserDto? = runCatching {
        fetchUsers().first.firstOrNull { it.uuid == uuid }
    }.getOrNull()

    // ─────────────────────────────────────────────────────── نوشتن

    /**
     * ساخت کاربر = `POST <آدرس پنل>/api/v2/admin/users/`
     * (همان آدرسی که در تنظیمات به‌صورت «آدرس ساخت کاربر» نوشته می‌شود.)
     *
     * دلیل اینکه قبلاً «ساخته نشد» گزارش می‌شد در حالی که کاربر ساخته شده بود:
     * پنل بعد از هر کاربر، `quick_apply_users` را اجرا می‌کند و پاسخ ممکن است از تایم‌اوت
     * بگذرد؛ OkHttp هم درخواست را دوباره می‌فرستاد و بار دوم با پیام
     * «A user with this UUID exists» رد می‌شد. حالا UUID از قبل ساخته می‌شود و
     * در صورت خطا، وجود کاربر در پنل بررسی می‌شود؛ همان پیام «تکراری» هم موفقیت است.
     */
    suspend fun createUser(
        name: String,
        gb: Double,
        days: Int,
        comment: String = "",
        uuid: String = UUID.randomUUID().toString()
    ): PanelUserDto = guard {
        val c = config()
        val api = service(c)
        val path = proxyPath(c)
        val request = CreateUserRequest(
            name = name.ifBlank { "user" },
            usageLimitGb = if (gb <= 0) 100.0 else gb,
            packageDays = if (days <= 0) 30 else days,
            comment = comment.ifBlank { null },
            enable = true,
            mode = "no_reset",
            uuid = uuid
        )
        val attempt = runCatching {
            val text = withFallback(
                primary = { jsonText(api.createUserPage(path, c.apiKey, request), "ساخت کاربر") },
                classic = { jsonText(api.createUserClassic(path, c.apiKey, request), "ساخت کاربر (کلاسیک)") }
            )
            PanelJson.parseUser(element(text))
        }
        attempt.getOrNull()?.let { return@guard it }

        // ساخت ناموفق گزارش شد — پیش از خطا، مطمئن شویم پنل کاربر را نساخته است.
        val err = attempt.exceptionOrNull()!!
        val found = findCreated(uuid)
        if (found != null) return@guard found
        if (err.message.orEmpty().contains("exists", true)) {
            // پیام «A user with this UUID exists» یعنی ساخت قبلی موفق بوده؛ خود کاربر را بخوان
            val byUuid = runCatching { fetchUser(uuid) }.getOrNull()
            if (byUuid != null) return@guard byUuid
        }
        throw err
    }

    private suspend fun patch(uuid: String, body: PatchUserRequest): PanelUserDto = guard {
        val c = config()
        val api = service(c)
        val path = proxyPath(c)
        val text = withFallback(
            primary = { jsonText(api.patchUserPage(path, uuid, c.apiKey, body), "ویرایش کاربر") },
            classic = { jsonText(api.patchUserClassic(path, uuid, c.apiKey, body), "ویرایش کاربر (کلاسیک)") }
        )
        PanelJson.parseUser(element(text))
    }

    /**
     * تمدید = دورهٔ جدید: صفر شدن مصرف، شمارش روز از اتصال بعدی (`start_date = null`)، فعال‌سازی.
     *
     * در نسخهٔ ۱۴ هیدی‌فای PATCH فیلدهای `current_usage_GB` و `start_date` را نمی‌خواند و
     * به‌جای آن‌ها پرچم‌های `reset_usage` / `reset_days` را قبول می‌کند. هر چهار مورد
     * فرستاده می‌شود تا هم نسخهٔ ۱۴ و هم نسخه‌های قدیمی درست تمدید شوند.
     */
    suspend fun renewPackage(uuid: String, gb: Double, days: Int, name: String? = null): PanelUserDto {
        val body = PatchUserRequest(
            name = name?.takeIf { it.isNotBlank() },
            usageLimitGb = if (gb > 0) gb else null,
            packageDays = if (days > 0) days else null,
            currentUsageGb = 0.0,
            enable = true,
            resetUsage = true,
            resetDays = true
        )
        val first = runCatching { patch(uuid, body) }
        first.getOrNull()?.let { return it }
        // اگر پنل پرچم‌ها را نپذیرفت، با بدنهٔ سادهٔ سازگار دوباره تلاش می‌کنیم
        val retry = runCatching {
            patch(
                uuid,
                PatchUserRequest(
                    usageLimitGb = if (gb > 0) gb else null,
                    packageDays = if (days > 0) days else null,
                    currentUsageGb = 0.0,
                    enable = true
                )
            )
        }
        retry.getOrNull()?.let { return it }
        throw first.exceptionOrNull() ?: retry.exceptionOrNull()
            ?: PanelException("تمدید انجام نشد")
    }

    /** افزودن حجم/روز روی بستهٔ فعلی (بدون ریست مصرف) — از اندپوینت گروهی پنل */
    suspend fun extendLimits(uuids: List<String>, addGb: Double = 0.0, addDays: Int = 0): Int = guard {
        require(uuids.isNotEmpty()) { "کاربری انتخاب نشده است" }
        require(addGb > 0 || addDays > 0) { "حجم یا روز را وارد کنید" }
        val c = config()
        val text = jsonText(
            service(c).bulk(
                proxyPath(c),
                c.apiKey,
                BulkRequest(
                    action = "add_limits",
                    uuids = uuids,
                    gb = if (addGb > 0) addGb else 0.0,
                    days = if (addDays > 0) addDays else 0
                )
            ),
            "عملیات گروهی"
        )
        runCatching { PanelJson.lenient.decodeFromString(BulkResponse.serializer(), text).count }
            .getOrDefault(uuids.size)
    }

    suspend fun setEnabled(uuid: String, enabled: Boolean): PanelUserDto =
        patch(uuid, PatchUserRequest(enable = enabled))

    suspend fun deleteUser(uuid: String) = guard {
        val c = config()
        val api = service(c)
        val path = proxyPath(c)
        withFallback(
            primary = { jsonText(api.deleteUserPage(path, uuid, c.apiKey), "حذف کاربر") },
            classic = { jsonText(api.deleteUserClassic(path, uuid, c.apiKey), "حذف کاربر (کلاسیک)") }
        )
        Unit
    }

    // ─────────────────────────────────────────────────────── عیب‌یابی

    /**
     * بررسی مرحله‌به‌مرحلهٔ اتصال. هر مرحله نتیجهٔ خودش را می‌دهد تا معلوم شود
     * دقیقاً کجا مشکل است (دامنه، مسیر، کلید، اجازهٔ ساخت، لینک).
     */
    suspend fun diagnose(): List<PanelCheck> {
        val checks = mutableListOf<PanelCheck>()
        lastRawError = ""
        val c = runCatching { config() }.getOrElse {
            checks += PanelCheck("تنظیمات پنل", false, it.message ?: "تنظیم نشده")
            return checks
        }
        val parts = PanelUrl.normalize(c.host, c.adminProxyPath, c.useHttps)
        checks += PanelCheck(
            "آدرس پنل",
            parts.proxyPath.isNotBlank(),
            parts.panelUrl + if (parts.proxyPath.isBlank()) "  ← مسیر پروکسی ادمین پیدا نشد" else ""
        )
        val api = service(c)
        val path = parts.proxyPath

        // ۱) فهرست کاربران: هم مسیر و هم کلید را با هم می‌سنجد
        val users = runCatching { fetchUsers() }
        val meta = users.getOrNull()?.second
        checks += if (users.isSuccess) {
            val list = users.getOrThrow().first
            PanelCheck(
                "مسیر ادمین و کلید API",
                true,
                "پاسخ گرفت • ${list.size} کاربر • مسیر ${workingEndpoint.ifBlank { "?" }}" +
                    if (meta?.readOnly == true) " • پنل read-only" else ""
            )
        } else {
            val err = users.exceptionOrNull()!!
            val message = err.message.orEmpty()
            val hint = when {
                message.contains("صفحهٔ ورود") || message.contains("HTML") ->
                    "کلید API درست نیست یا مسیر ادمین اشتباه است (پنل به صفحهٔ ورود ریدایرکت کرد)."
                message.contains("invalid request", true) || message.contains("Invalid Proxy Path", true) ->
                    "این مسیر، «مسیر ادمین» نیست. آدرس پنل را از نوار آدرس مرورگر (وقتی با حساب ادمین وارد شده‌اید) کامل کپی کنید."
                else -> null
            }
            // اگر مسیر درست باشد ولی مسیر ادمین نباشد، api/panel/info جواب می‌دهد؛
            // این تفاوت را به کاربر بگوییم تا سردرگم نشود.
            val info = runCatching {
                jsonText(api.panelInfo(path, c.apiKey), "نسخهٔ پنل")
            }
            val infoNote = if (info.isSuccess && !message.contains("صفحهٔ ورود")) {
                " (اتصال به پنل برقرار است، پس آدرس/کلید سالم است؛ فقط مسیر ادمین فرق دارد.)"
            } else ""
            PanelCheck(
                "مسیر ادمین و کلید API",
                false,
                (hint ?: message).ifBlank { "خطای نامشخص" } + infoNote
            )
        }

        // ۲) نسخهٔ پنل (اطلاعاتی)
        val info = runCatching { jsonText(api.panelInfo(path, c.apiKey), "نسخهٔ پنل") }
        checks += if (info.isSuccess) {
            val v = runCatching {
                PanelJson.lenient.decodeFromString(PanelInfoDto.serializer(), info.getOrThrow()).version
            }.getOrDefault("")
            PanelCheck("نسخهٔ پنل", true, v.ifBlank { "نامشخص" })
        } else {
            PanelCheck("نسخهٔ پنل", true, "قابل خواندن نبود (برای کار اپ لازم نیست)")
        }

        // ۳) اجازهٔ ساخت
        checks += if (meta == null) {
            PanelCheck("اجازهٔ ساخت کاربر", false, "بدون فهرست کاربران قابل بررسی نیست")
        } else {
            PanelCheck(
                "اجازهٔ ساخت کاربر",
                meta.canAdd && !meta.readOnly,
                when {
                    meta.readOnly -> "پنل روی نود read-only است؛ ساخت کاربر مسدود است"
                    !meta.canAdd -> "سقف کاربران/ترافیک ادمین پر است (در پنل افزایش دهید)"
                    else -> "آزاد — از اپ می‌توانید کاربر بسازید"
                }
            )
        }

        // ۴) آدرس ساخت کاربر (همان چیزی که استفاده می‌شود)
        val ep = endpoints(c)
        checks += PanelCheck("آدرس ساخت کاربر", true, ep.createUserUrl)

        // ۵) لینک اشتراک — مستقل از مسیر ادمین
        val links = meta?.linkDomains.orEmpty()
        val sub = ep.subscriptionBase
        checks += PanelCheck(
            "پایهٔ لینک اشتراک",
            links.isNotEmpty() || sub.isNotBlank(),
            when {
                links.isNotEmpty() -> links.first().base
                sub.isNotBlank() -> "$sub  (دستی)"
                else -> "دامنهٔ کلاینت در پنل پیدا نشد؛ در تنظیمات دستی وارد کنید"
            } + if (links.isNotEmpty() && sub.isNotBlank() && sub != PanelUrl.normalizeLinkBase(links.first().base)) {
                "\nتوجه: مقدار دستی ($sub) با پنل (${links.first().base}) یکی نیست."
            } else ""
        )
        return checks
    }

    /**
     * تست واقعی ساخت کاربر: یک کاربر موقت می‌سازد و بلافاصله پاکش می‌کند.
     * این تنها راه قطعی برای فهمیدن اینکه «ساخت کاربر» کار می‌کند یا نه.
     */
    suspend fun probeCreate(): PanelCheck {
        lastRawError = ""
        val stamp = System.currentTimeMillis().toString().takeLast(6)
        val name = "cmbtools-test-$stamp"
        return try {
            val created = createUser(
                name = name,
                gb = 1.0,
                days = 1,
                comment = "cmbtools:probe",
                uuid = UUID.randomUUID().toString()
            )
            val removed = runCatching { deleteUser(created.uuid) }
            PanelCheck(
                "ساخت کاربر آزمایشی",
                true,
                if (removed.isSuccess) "کاربر «$name» ساخته و پاک شد ✅"
                else "کاربر «$name» ساخته شد ✅ (پاک‌کردن آزمایشی نشد: ${removed.exceptionOrNull()?.message})"
            )
        } catch (e: Exception) {
            PanelCheck("ساخت کاربر آزمایشی", false, errText(e) + rawSuffix())
        }
    }

    private fun rawSuffix(): String =
        if (lastRawError.isBlank()) "" else "\n" + lastRawError.take(400)

    private fun errText(e: Throwable): String = when (e) {
        is PanelException -> e.message ?: "خطا"
        is HttpException -> httpDetail(e)
        else -> e.message ?: e::class.java.simpleName
    }

    /** گزارش متنی برای کپی/ارسال به پشتیبانی */
    fun reportText(checks: List<PanelCheck>, config: PanelConfig?): String {
        val c = config ?: PanelConfig()
        val ep = endpoints(c)
        val keyMasked = if (c.apiKey.length <= 8) "****" else "****" + c.apiKey.takeLast(8)
        val sb = StringBuilder()
        sb.appendLine("گزارش اتصال تیزنت")
        sb.appendLine("آدرس پنل: ${ep.panelUrl}")
        sb.appendLine("آدرس API: ${ep.apiBase}")
        sb.appendLine("آدرس ساخت کاربر: ${ep.createUserUrl}")
        sb.appendLine("پایهٔ لینک اشتراک: ${ep.subscriptionBase.ifBlank { "—" }}")
        sb.appendLine("کلید API: $keyMasked")
        sb.appendLine("—")
        checks.forEach { c2 ->
            sb.appendLine((if (c2.ok) "✅ " else "❌ ") + c2.title + ": " + c2.detail)
        }
        if (lastRawError.isNotBlank()) {
            sb.appendLine("— آخرین پاسخ خام پنل —")
            sb.appendLine(lastRawError.take(800))
        }
        return sb.toString()
    }

    /**
     * لینک اشتراک کاربر.
     *
     * الگوی هیدی‌فای ۱۴: `https://<domain>/<proxy_path_client>/<user_uuid>/`
     * — یعنی پایهٔ لینک اشتراک (نه مسیر ادمین) + شناسهٔ کاربر.
     */
    fun subscriptionUrl(uuid: String, linkBase: String): String {
        val base = PanelUrl.normalizeLinkBase(
            linkBase.ifBlank { prefs.current.fallbackSubscriptionBase }
        )
        if (base.isBlank()) return ""
        return base + uuid + "/"
    }

    companion object {
        /** پنل تاریخ‌ها را ISO می‌دهد؛ اینجا به epochDay تبدیل می‌شود */
        fun parseEpochDay(iso: String?): Long {
            if (iso.isNullOrBlank()) return 0
            return runCatching { LocalDate.parse(iso.take(10)).toEpochDay() }.getOrDefault(0)
        }
    }
}
