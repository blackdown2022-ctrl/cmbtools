package ir.cmbtools.ledger.panel

import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.PanelConfig
import ir.cmbtools.ledger.data.PanelDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

class PanelException(message: String, val kind: Kind = Kind.GENERIC) : Exception(message) {
    enum class Kind { NOT_CONFIGURED, AUTH, NETWORK, NOT_FOUND, SERVER, LIMIT }
}

data class PanelConnection(
    val ok: Boolean,
    val version: String = "",
    val adminUuid: String = "",
    val canAddUsers: Boolean = true,
    val readOnly: Boolean = false,
    val linkBase: String = "",
    val message: String = ""
)

/**
 * کلاینت پنل هیدی‌فای ۱۴ (API v2).
 * - همهٔ فراخوانی‌ها روی IO
 * - خطاها به پیام فارسی قابل‌نمایش تبدیل می‌شوند
 * - لینک اشتراک از `meta.link_domains[].base` ساخته می‌شود (همان چیزی که خود پنل می‌سازد)
 */
class PanelClient(
    private val panelDao: PanelDao,
    private val prefs: Prefs
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                // برای استفادهٔ شخصی: در صورت نیاز به دیباگ به Level.BASIC تغییر دهید.
                // کلید API هرگز لاگ نمی‌شود چون هدرها لاگ نمی‌شوند.
                level = HttpLoggingInterceptor.Level.NONE
            }
        )
        .build()

    private fun service(config: PanelConfig): HiddifyApi {
        val scheme = if (config.useHttps) "https" else "http"
        val host = config.host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        val base = "$scheme://$host/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HiddifyApi::class.java)
    }

    private fun proxyPath(config: PanelConfig): String =
        config.adminProxyPath.trim().trim('/')

    private suspend fun config(): PanelConfig {
        val c = panelDao.config() ?: PanelConfig()
        if (!c.isConfigured) throw PanelException(
            "اتصال پنل تنظیم نشده است. در تنظیمات، دامنهٔ پنل، مسیر پروکسی ادمین و کلید API را وارد کنید.",
            PanelException.Kind.NOT_CONFIGURED
        )
        return c
    }

    private suspend fun <T> guard(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            // اگر پنل به‌جای JSON صفحهٔ لاگین (HTML) برگرداند، همین‌جا به خطای مالکیت/مسیر تبدیل می‌شود.
        block()
        } catch (e: PanelException) {
            throw e
        } catch (e: kotlinx.serialization.SerializationException) {
            throw PanelException(
                "پاسخ پنل قابل خواندن نیست. احتمالاً مسیر پروکسی ادمین یا کلید API اشتباه است.",
                PanelException.Kind.AUTH
            )
        } catch (e: retrofit2.HttpException) {
            throw when (e.code()) {
                401, 403 -> PanelException("دسترسی رد شد (کلید API یا سطح دسترسی ادمین)", PanelException.Kind.AUTH)
                404 -> PanelException("مسیر پیدا نشد (مسیر پروکسی ادمین یا نسخهٔ پنل را بررسی کنید)", PanelException.Kind.NOT_FOUND)
                400 -> PanelException("درخواست نامعتبر: ${e.message()}", PanelException.Kind.SERVER)
                in 500..599 -> PanelException("خطای سرور پنل (${e.code()})", PanelException.Kind.SERVER)
                else -> PanelException("خطای پنل: ${e.code()}", PanelException.Kind.SERVER)
            }
        } catch (e: java.net.UnknownHostException) {
            throw PanelException("دامنهٔ پنل پیدا نشد (اتصال اینترنت یا دامنه را بررسی کنید)", PanelException.Kind.NETWORK)
        } catch (e: java.net.SocketTimeoutException) {
            throw PanelException("پاسخ پنل دیر شد (timeout)", PanelException.Kind.NETWORK)
        } catch (e: javax.net.ssl.SSLException) {
            throw PanelException("خطای امنیتی گواهی SSL پنل: ${e.message}", PanelException.Kind.NETWORK)
        } catch (e: java.io.IOException) {
            throw PanelException("ارتباط با پنل برقرار نشد: ${e.message}", PanelException.Kind.NETWORK)
        }
    }

    suspend fun testConnection(): PanelConnection = guard {
        val c = config()
        val info = service(c).panelInfo(proxyPath(c), c.apiKey)
        val users = runCatching { service(c).usersPage(proxyPath(c), c.apiKey) }.getOrNull()
        val version = info.version.ifBlank { info.panelVersion ?: "" }
        panelDao.saveConfig(c.copy(panelVersion = version, lastSyncAt = System.currentTimeMillis()))
        PanelConnection(
            ok = true,
            version = version,
            adminUuid = users?.meta?.meUuid ?: c.apiKey,
            canAddUsers = users?.meta?.canAdd ?: true,
            readOnly = users?.meta?.readOnly ?: false,
            linkBase = users?.meta?.linkDomains?.firstOrNull()?.base ?: prefs.current.fallbackSubscriptionBase,
            message = "اتصال برقرار است"
        )
    }

    suspend fun fetchUsers(): Pair<List<PanelUserDto>, UsersMetaDto> = guard {
        val c = config()
        val page = service(c).usersPage(proxyPath(c), c.apiKey)
        if (page.meta.linkDomains.isNotEmpty()) {
            prefs.update { it.copy(fallbackSubscriptionBase = page.meta.linkDomains.first().base) }
        }
        page.users to page.meta
    }

    suspend fun fetchUser(uuid: String): PanelUserDto = guard {
        val c = config()
        service(c).user(proxyPath(c), uuid, c.apiKey)
    }

    suspend fun createUser(
        name: String,
        gb: Double,
        days: Int,
        comment: String = "",
        uuid: String = UUID.randomUUID().toString()
    ): PanelUserDto = guard {
        val c = config()
        service(c).createUser(
            proxyPath(c),
            c.apiKey,
            CreateUserRequest(
                name = name,
                usageLimitGb = gb,
                packageDays = days,
                comment = comment.ifBlank { null },
                enable = true,
                mode = "no_reset",
                uuid = uuid
            )
        )
    }

    /**
     * تمدید = دورهٔ جدید: صفر شدن مصرف، شمارش روز از اتصال بعدی (`start_date = null`)، فعال‌سازی.
     * دقیقاً همان کاری که دکمهٔ تمدید خود پنل/ربات انجام می‌دهد.
     */
    suspend fun renewPackage(uuid: String, gb: Double, days: Int, name: String? = null): PanelUserDto = guard {
        val c = config()
        service(c).patchUser(
            proxyPath(c),
            uuid,
            c.apiKey,
            PatchUserRequest(
                name = name,
                usageLimitGb = gb,
                packageDays = days,
                currentUsageGb = 0.0,
                enable = true,
                startDate = JsonNull
            )
        )
    }

    /** افزودن حجم/روز روی بستهٔ فعلی (بدون ریست مصرف) — از اندپوینت گروهی پنل */
    suspend fun extendLimits(uuids: List<String>, addGb: Double = 0.0, addDays: Int = 0): Int = guard {
        require(uuids.isNotEmpty()) { "کاربری انتخاب نشده است" }
        require(addGb > 0 || addDays > 0) { "حجم یا روز را وارد کنید" }
        val c = config()
        service(c).bulk(
            proxyPath(c),
            c.apiKey,
            BulkRequest(
                action = "add_limits",
                uuids = uuids,
                gb = if (addGb > 0) addGb else 0.0,
                days = if (addDays > 0) addDays else 0
            )
        ).count
    }

    suspend fun setEnabled(uuid: String, enabled: Boolean): PanelUserDto = guard {
        val c = config()
        service(c).patchUser(proxyPath(c), uuid, c.apiKey, PatchUserRequest(enable = enabled))
    }

    suspend fun deleteUser(uuid: String) = guard {
        val c = config()
        service(c).deleteUser(proxyPath(c), uuid, c.apiKey)
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
