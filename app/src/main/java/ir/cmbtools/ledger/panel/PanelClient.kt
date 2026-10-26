package ir.cmbtools.ledger.panel

import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.PanelConfig
import ir.cmbtools.ledger.data.PanelDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
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
 * تجزیهٔ آدرس پنل. کاربر ممکن است هر کدام از این‌ها را در فیلد «دامنه» بچسباند:
 *   panel.example.com
 *   https://panel.example.com/
 *   panel.example.com/AbCdEf
 *   https://panel.example.com/AbCdEf/api/v2/admin/users/
 * همه به (host, proxyPath) درست تبدیل می‌شوند تا درخواست‌ها ۴۰۴ نگیرند.
 */
object PanelUrl {

    data class Parts(val host: String, val proxyPath: String, val useHttps: Boolean)

    fun normalize(hostRaw: String, pathRaw: String, useHttps: Boolean = true): Parts {
        var host = hostRaw.trim()
        var https = useHttps
        when {
            host.startsWith("https://", true) -> { https = true; host = host.substring(8) }
            host.startsWith("http://", true) -> { https = false; host = host.substring(7) }
        }
        host = host.trim().trim('/')
        var extraPath = ""
        val slash = host.indexOf('/')
        if (slash >= 0) {
            extraPath = host.substring(slash + 1)
            host = host.substring(0, slash)
        }
        var path = pathRaw.trim().trim('/')
        if (path.isBlank() && extraPath.isNotBlank()) path = extraPath
        path = cleanPath(path)
        return Parts(host = host.trim(), proxyPath = path, useHttps = https)
    }

    /**
     * جدا کردن «مسیر پروکسی ادمین» از هر چیز اضافه‌ای که کاربر چسبانده باشد.
     * مثال: `AbCdEf/api/v2/admin/users/` → `AbCdEf`
     */
    fun cleanPath(raw: String): String {
        var p = raw.trim().trim('/')
        listOf("/api/v2/admin", "/api/v2/panel", "/api/v2", "/api").forEach { marker ->
            val at = p.indexOf(marker)
            if (at >= 0) p = p.substring(0, at)
        }
        // مسیری که فقط «api/...» بوده، پروکسی ادمین نیست
        if (p == "api" || p.startsWith("api/")) p = ""
        return p.trim('/')
    }
}

/**
 * کلاینت پنل هیدی‌فای (API v2).
 *
 * - همهٔ فراخوانی‌ها روی IO
 * - خطاها به پیام فارسی قابل‌نمایش تبدیل می‌شوند (متن خطای خود پنل هم آورده می‌شود)
 * - هم با مسیر صفحهٔ کاربران نسخهٔ ۱۴ و هم با مسیر کلاسیک کار می‌کند
 * - لینک اشتراک از `meta.link_domains[].base` ساخته می‌شود (همان چیزی که خود پنل می‌سازد)
 */
class PanelClient(
    private val panelDao: PanelDao,
    private val prefs: Prefs
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                // برای استفادهٔ شخصی: در صورت نیاز به دیباگ به Level.BASIC تغییر دهید.
                // هدرها (کلید API) لاگ نمی‌شوند.
                level = HttpLoggingInterceptor.Level.NONE
            }
        )
        .build()

    /** آخرین مسیری که برای فهرست کاربران جواب داد (برای نمایش در عیب‌یابی) */
    @Volatile
    var workingEndpoint: String = ""
        private set

    // در حافظه نگه می‌داریم تا هر بار یک درخواست ۴۰۴ اضافه نزنیم
    @Volatile
    private var preferPageApi: Boolean = true

    private fun service(config: PanelConfig): HiddifyApi {
        val parts = PanelUrl.normalize(config.host, config.adminProxyPath, config.useHttps)
        val scheme = if (parts.useHttps) "https" else "http"
        return Retrofit.Builder()
            .baseUrl("$scheme://${parts.host}/")
            .client(http)
            .addConverterFactory(PanelJson.lenient.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HiddifyApi::class.java)
    }

    private fun proxyPath(config: PanelConfig): String =
        PanelUrl.normalize(config.host, config.adminProxyPath, config.useHttps).proxyPath

    private suspend fun config(): PanelConfig {
        val c = panelDao.config() ?: PanelConfig()
        if (!c.isConfigured) throw PanelException(
            "اتصال پنل تنظیم نشده است. در تنظیمات، دامنهٔ پنل، مسیر پروکسی ادمین و کلید API را وارد کنید.",
            PanelException.Kind.NOT_CONFIGURED
        )
        return c
    }

    /** متن قابل‌فهم از بدنهٔ خطای پنل */
    private fun httpMessage(e: HttpException): String {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val text = PanelJson.errorText(body)
        return text.ifBlank { e.message() }
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
            val detail = httpMessage(e)
            throw when (e.code()) {
                401, 403 -> PanelException("دسترسی رد شد (کلید API یا سطح دسترسی ادمین): $detail", PanelException.Kind.AUTH)
                404 -> PanelException("مسیر پیدا نشد (مسیر پروکسی ادمین یا نسخهٔ پنل): $detail", PanelException.Kind.NOT_FOUND)
                405 -> PanelException("این عملیات در نسخهٔ پنل شما پشتیبانی نمی‌شود: $detail", PanelException.Kind.SERVER)
                409 -> PanelException("تعارض در پنل: $detail", PanelException.Kind.SERVER)
                400, 422 -> PanelException("پنل درخواست را رد کرد: $detail", PanelException.Kind.SERVER)
                in 500..599 -> PanelException("خطای سرور پنل (${e.code()}): $detail", PanelException.Kind.SERVER)
                else -> PanelException("خطای پنل: ${e.code()} — $detail", PanelException.Kind.SERVER)
            }
        } catch (e: java.net.UnknownHostException) {
            throw PanelException("دامنهٔ پنل پیدا نشد (اتصال اینترنت یا دامنه را بررسی کنید)", PanelException.Kind.NETWORK)
        } catch (e: java.net.SocketTimeoutException) {
            throw PanelException("پاسخ پنل دیر شد (timeout) — دامنه/شبکه را بررسی کنید", PanelException.Kind.NETWORK)
        } catch (e: javax.net.ssl.SSLException) {
            throw PanelException("خطای امنیتی گواهی SSL پنل: ${e.message}", PanelException.Kind.NETWORK)
        } catch (e: java.io.IOException) {
            throw PanelException("ارتباط با پنل برقرار نشد: ${e.message}", PanelException.Kind.NETWORK)
        }
    }

    /** اگر مسیر جدید ۴۰۴/۴۰۵ داد، مسیر کلاسیک امتحان می‌شود. */
    private suspend fun <T> withFallback(primary: suspend () -> T, classic: suspend () -> T): T {
        return try {
            primary()
        } catch (e: HttpException) {
            if (e.code() == 404 || e.code() == 405) classic() else throw e
        }
    }

    private fun bodyText(body: ResponseBody): String = body.use { it.string() }

    // ─────────────────────────────────────────────────────────── خواندن

    suspend fun testConnection(): PanelConnection = guard {
        val c = config()
        val api = service(c)
        val path = proxyPath(c)

        // نسخهٔ پنل «خوب است بدانیم» است؛ اگر این مسیر در نسخه‌ای نبود، اتصال را رد نمی‌کنیم.
        val infoText = runCatching { api.panelInfo(path, c.apiKey).use { it.string() } }.getOrNull()
        val version = infoText?.let {
            runCatching {
                PanelJson.lenient.decodeFromString(PanelInfoDto.serializer(), it)
                    .let { dto -> dto.version.ifBlank { dto.panelVersion ?: "" } }
            }.getOrDefault("")
        } ?: ""

        val (users, meta, endpoint) = fetchUsersInternal(api, path, c.apiKey)

        val linkBase = meta.linkDomains.firstOrNull()?.base ?: prefs.current.fallbackSubscriptionBase
        panelDao.saveConfig(c.copy(panelVersion = version, lastSyncAt = System.currentTimeMillis()))
        PanelConnection(
            ok = true,
            version = version,
            adminUuid = meta.meUuid,
            canAddUsers = meta.canAdd,
            readOnly = meta.readOnly,
            linkBase = linkBase,
            usersCount = users.size,
            endpoint = endpoint,
            message = "اتصال برقرار است"
        )
    }

    /** فهرست کاربران پنل + متادیتا (لینک‌دامنه‌ها، دسترسی، ...) */
    suspend fun fetchUsers(): Pair<List<PanelUserDto>, UsersMetaDto> = guard {
        val c = config()
        val (users, meta, _) = fetchUsersInternal(service(c), proxyPath(c), c.apiKey)
        if (meta.linkDomains.isNotEmpty()) {
            prefs.update { it.copy(fallbackSubscriptionBase = meta.linkDomains.first().base) }
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
                val text = bodyText(api.usersPage(path, apiKey))
                val (users, meta) = PanelJson.parseUsers(
                    runCatching { PanelJson.lenient.decodeFromString(kotlinx.serialization.json.JsonElement.serializer(), text) }.getOrNull()
                )
                workingEndpoint = "/admin/users/"
                return Triple(users, meta, workingEndpoint)
            } catch (e: HttpException) {
                if (e.code() != 404 && e.code() != 405) throw e
                preferPageApi = false
            }
        }
        // مسیر کلاسیک: آرایهٔ خالص کاربران. اگر پنل هنوز کاربری ندارد، ۴۰۴ می‌دهد
        // («You have no user») که یعنی «فهرست خالی»، نه خطا.
        val text = try {
            bodyText(api.usersClassic(path, apiKey))
        } catch (e: HttpException) {
            if (e.code() == 404) {
                workingEndpoint = "/admin/user/"
                return Triple(emptyList(), UsersMetaDto(), workingEndpoint)
            }
            throw e
        }
        val element = runCatching {
            PanelJson.lenient.decodeFromString(kotlinx.serialization.json.JsonElement.serializer(), text)
        }.getOrNull()
        val (users, meta) = PanelJson.parseUsers(element)
        workingEndpoint = "/admin/user/"
        return Triple(users, meta, workingEndpoint)
    }

    suspend fun fetchUser(uuid: String): PanelUserDto = guard {
        val c = config()
        val api = service(c)
        val path = proxyPath(c)
        val text = withFallback(
            primary = { api.userPage(path, uuid, c.apiKey).use { it.string() } },
            classic = { api.userClassic(path, uuid, c.apiKey).use { it.string() } }
        )
        val element = runCatching {
            PanelJson.lenient.decodeFromString(kotlinx.serialization.json.JsonElement.serializer(), text)
        }.getOrNull()
        PanelJson.parseUser(element)
    }

    // ─────────────────────────────────────────────────────────── نوشتن

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
        val text = withFallback(
            primary = { api.createUserPage(path, c.apiKey, request).use { it.string() } },
            classic = { api.createUserClassic(path, c.apiKey, request).use { it.string() } }
        )
        val element = runCatching {
            PanelJson.lenient.decodeFromString(kotlinx.serialization.json.JsonElement.serializer(), text)
        }.getOrNull()
        PanelJson.parseUser(element)
    }

    private suspend fun patch(uuid: String, body: PatchUserRequest): PanelUserDto = guard {
        val c = config()
        val api = service(c)
        val path = proxyPath(c)
        val text = withFallback(
            primary = { api.patchUserPage(path, uuid, c.apiKey, body).use { it.string() } },
            classic = { api.patchUserClassic(path, uuid, c.apiKey, body).use { it.string() } }
        )
        val element = runCatching {
            PanelJson.lenient.decodeFromString(kotlinx.serialization.json.JsonElement.serializer(), text)
        }.getOrNull()
        PanelJson.parseUser(element)
    }

    /**
     * تمدید = دورهٔ جدید: صفر شدن مصرف، شمارش روز از اتصال بعدی (`start_date = null`)، فعال‌سازی.
     *
     * در نسخهٔ ۱۴ هیدی‌فای، PATCH فیلدهای `current_usage_GB` و `start_date` را قبول نمی‌کند؛
     * به‌جای آن‌ها پرچم‌های `reset_usage` و `reset_days` را می‌خواند. هر چهار مورد فرستاده
     * می‌شود تا هم نسخهٔ ۱۴ و هم نسخه‌های قدیمی درست تمدید شوند.
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
        if (first.isSuccess) return first.getOrThrow()
        // اگر پنل پرچم‌ها را نپذیرفت، با بدنهٔ سادهٔ سازگار دوباره تلاش می‌کنیم
        val retry = patch(
            uuid,
            PatchUserRequest(
                usageLimitGb = if (gb > 0) gb else null,
                packageDays = if (days > 0) days else null,
                currentUsageGb = 0.0,
                enable = true
            )
        )
        return retry
    }

    /** افزودن حجم/روز روی بستهٔ فعلی (بدون ریست مصرف) — از اندپوینت گروهی پنل */
    suspend fun extendLimits(uuids: List<String>, addGb: Double = 0.0, addDays: Int = 0): Int = guard {
        require(uuids.isNotEmpty()) { "کاربری انتخاب نشده است" }
        require(addGb > 0 || addDays > 0) { "حجم یا روز را وارد کنید" }
        val c = config()
        val text = service(c).bulk(
            proxyPath(c),
            c.apiKey,
            BulkRequest(
                action = "add_limits",
                uuids = uuids,
                gb = if (addGb > 0) addGb else 0.0,
                days = if (addDays > 0) addDays else 0
            )
        ).use { it.string() }
        runCatching {
            PanelJson.lenient.decodeFromString(BulkResponse.serializer(), text).count
        }.getOrDefault(uuids.size)
    }

    suspend fun setEnabled(uuid: String, enabled: Boolean): PanelUserDto =
        patch(uuid, PatchUserRequest(enable = enabled))

    suspend fun deleteUser(uuid: String) = guard {
        val c = config()
        val api = service(c)
        val path = proxyPath(c)
        withFallback(
            primary = { api.deleteUserPage(path, uuid, c.apiKey).use { it.string() } },
            classic = { api.deleteUserClassic(path, uuid, c.apiKey).use { it.string() } }
        )
        Unit
    }

    // ─────────────────────────────────────────────────────────── عیب‌یابی

    /**
     * بررسی مرحله‌به‌مرحلهٔ اتصال. هر مرحله نتیجهٔ خودش را می‌دهد تا معلوم شود
     * دقیقاً کجا مشکل است (دامنه، مسیر، کلید، دسترسی نوشتن، ...).
     */
    suspend fun diagnose(): List<PanelCheck> {
        val checks = mutableListOf<PanelCheck>()
        val c = runCatching { config() }.getOrElse {
            checks += PanelCheck("تنظیمات پنل", false, it.message ?: "تنظیم نشده")
            return checks
        }
        val parts = PanelUrl.normalize(c.host, c.adminProxyPath, c.useHttps)
        checks += PanelCheck(
            "تنظیمات پنل",
            true,
            "دامنه: ${parts.host} • مسیر: ${parts.proxyPath} • ${if (parts.useHttps) "https" else "http"}"
        )
        val api = service(c)
        val path = parts.proxyPath

        val info = runCatching { api.panelInfo(path, c.apiKey).use { it.string() } }
        checks += if (info.isSuccess) {
            val v = runCatching {
                PanelJson.lenient.decodeFromString(PanelInfoDto.serializer(), info.getOrThrow()).version
            }.getOrDefault("")
            PanelCheck("اتصال و کلید API", true, "نسخهٔ پنل: ${v.ifBlank { "?" }}")
        } else {
            PanelCheck("اتصال و کلید API", false, info.exceptionOrNull()?.let { errText(it) } ?: "خطای نامشخص")
        }

        val users = runCatching { fetchUsers() }
        checks += if (users.isSuccess) {
            val (list, meta) = users.getOrThrow()
            PanelCheck(
                "فهرست کاربران",
                true,
                "${list.size} کاربر • مسیر: ${workingEndpoint.ifBlank { "?" }}" +
                    if (meta.readOnly) " • پنل read-only" else ""
            )
        } else {
            PanelCheck("فهرست کاربران", false, users.exceptionOrNull()?.let { errText(it) } ?: "خطای نامشخص")
        }

        val meta = users.getOrNull()?.second
        checks += if (meta == null) {
            PanelCheck("دسترسی ساخت کاربر", false, "بدون فهرست کاربران قابل بررسی نیست")
        } else {
            PanelCheck(
                "دسترسی ساخت کاربر",
                meta.canAdd && !meta.readOnly,
                when {
                    meta.readOnly -> "پنل روی نود read-only است؛ ساخت کاربر مسدود است"
                    !meta.canAdd -> "سقف کاربران/ترافیک ادمین پر است (در پنل افزایش دهید)"
                    else -> "آزاد — می‌توانید از اپ کاربر بسازید"
                }
            )
        }

        val links = meta?.linkDomains.orEmpty()
        checks += PanelCheck(
            "لینک اشتراک",
            links.isNotEmpty() || prefs.current.fallbackSubscriptionBase.isNotBlank(),
            links.firstOrNull()?.base ?: prefs.current.fallbackSubscriptionBase.ifBlank { "دامنهٔ کلاینت در پنل پیدا نشد" }
        )
        return checks
    }

    private fun errText(e: Throwable): String = when (e) {
        is PanelException -> e.message ?: "خطا"
        is HttpException -> httpMessage(e)
        else -> e.message ?: e::class.java.simpleName
    }

    /**
     * لینک اشتراک کاربر. اول از link_domains پنل، بعد fallback تنظیمات.
     * الگوی پنل: https://<domain>/<client_proxy_path>/<user_uuid>/
     */
    fun subscriptionUrl(uuid: String, linkBase: String): String {
        val base = linkBase.ifBlank { prefs.current.fallbackSubscriptionBase }.trim()
        if (base.isBlank()) return ""
        val normalized = if (base.endsWith("/")) base else "$base/"
        return normalized + uuid + "/"
    }

    companion object {
        /** پنل تاریخ‌ها را ISO می‌دهد؛ اینجا به epochDay تبدیل می‌شود */
        fun parseEpochDay(iso: String?): Long {
            if (iso.isNullOrBlank()) return 0
            return runCatching { LocalDate.parse(iso.take(10)).toEpochDay() }.getOrDefault(0)
        }
    }
}
