package ir.cmbtools.ledger.panel

import ir.cmbtools.ledger.core.Phone
import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.CustomerDao
import ir.cmbtools.ledger.data.PanelAccount
import ir.cmbtools.ledger.data.PanelConfig
import ir.cmbtools.ledger.data.PanelDao
import java.time.LocalDate

data class AccountProvisionResult(
    val uuid: String,
    val subscriptionUrl: String,
    val expireEpochDay: Long,
    val expireInDays: Int
)

/**
 * پل بین «مشتری/فاکتور» محلی و «اکانت» پنل هیدی‌فای.
 * هر عملیات پنل اینجا متمرکز است تا در UI فقط یک فراخوانی داشته باشیم.
 */
class PanelService(
    private val client: PanelClient,
    private val panelDao: PanelDao,
    private val customerDao: CustomerDao,
    private val prefs: Prefs
) {
    suspend fun config(): PanelConfig = panelDao.config() ?: PanelConfig()
    suspend fun isConfigured(): Boolean = (panelDao.config() ?: PanelConfig()).isConfigured

    /**
     * ذخیرهٔ اتصال از سه فیلد مستقل:
     *  • `panelUrl` — آدرس کامل پنل در مرورگر (مسیر ادمین از همین استخراج می‌شود)
     *  • `apiKey`   — UUID ادمین
     *  • `subscriptionBase` — پایهٔ لینک اشتراک مشتری (مستقل از مسیر ادمین؛ خالی = خودکار)
     */
    suspend fun saveConfig(panelUrl: String, apiKey: String, subscriptionBase: String, name: String) {
        client.invalidate()
        val existing = panelDao.config() ?: PanelConfig()
        val parts = PanelUrl.normalize(panelUrl, "", existing.useHttps)
        val samePanel = existing.host == parts.host &&
            existing.adminProxyPath == parts.proxyPath &&
            existing.apiKey == apiKey.trim()
        panelDao.saveConfig(
            existing.copy(
                name = name.ifBlank { "پنل اصلی" },
                host = parts.host,
                adminProxyPath = parts.proxyPath,
                apiKey = apiKey.trim(),
                useHttps = parts.useHttps,
                subscriptionBase = PanelUrl.normalizeLinkBase(subscriptionBase),
                // اگر آدرس یا کلید عوض شده، سبک API و نسخه دوباره تشخیص داده می‌شوند
                apiFlavor = if (samePanel) existing.apiFlavor else "",
                panelVersion = if (samePanel) existing.panelVersion else ""
            )
        )
    }

    /** ذخیرهٔ فقط پایهٔ لینک اشتراک (بعد از خواندن خودکار از پنل) */
    suspend fun saveSubscriptionBase(base: String) {
        client.invalidate()
        val existing = panelDao.config() ?: PanelConfig()
        panelDao.saveConfig(existing.copy(subscriptionBase = PanelUrl.normalizeLinkBase(base)))
    }

    /** آدرس‌های دقیقی که استفاده می‌شوند (برای نمایش در تنظیمات) */
    suspend fun endpoints(): PanelEndpoints = client.endpointsNow()

    /** عیب‌یابی گام‌به‌گام اتصال پنل (برای نمایش در تنظیمات) */
    suspend fun diagnose(): List<PanelCheck> = client.diagnose()

    /** ساخت واقعی یک کاربر آزمایشی و پاک کردن آن — قطعی‌ترین آزمون «ساخت کاربر» */
    suspend fun probeCreate(): PanelCheck = client.probeCreate()

    /** کاوش مسیرها/نسخه‌ها و برگرداندن جدول نتیجه (دکمهٔ «جستجوی خودکار») */
    suspend fun scan(): List<PanelCheck> = client.scan()

    /** سبک API که جواب داد (برای نمایش) */
    suspend fun flavorLabel(): String = client.flavor?.label ?: "تشخیص داده نشده"

    /** متن گزارش برای کپی کردن */
    suspend fun report(checks: List<PanelCheck>): String =
        client.reportText(checks, panelDao.config() ?: PanelConfig())

    suspend fun lastRawError(): String = client.lastRawError

    suspend fun test(): PanelConnection = client.testConnection()

    /** حذف اکانت از خود پنل؛ در صورت خطا، حذف محلی/فاکتور هم انجام نمی‌شود. */
    suspend fun deletePanelAccount(uuid: String) {
        if (uuid.isNotBlank()) client.deleteUser(uuid)
    }

    /**
     * ساخت اکانت جدید برای مشتری؛ نام پنل دقیقاً همان نام واردشدهٔ مشتری است.
     */
    suspend fun createAccount(
        customerId: Long,
        gb: Double,
        days: Int
    ): AccountProvisionResult {
        val customer = customerDao.byId(customerId) ?: error("مشتری پیدا نشد")
        val name = customer.name

        // شماره برای خوانایی حفظ می‌شود و شناسهٔ محلی اتصال قطعی را تضمین می‌کند.
        // شناسه بعد از شماره قرار می‌گیرد تا شماره‌خوانی نسخه‌های قبلی هم سازگار بماند.
        val phone = Phone.normalize(customer.phone)
        val comment = if (phone.isNotBlank()) "$phone | cmbtools:${customer.id}" else "cmbtools:${customer.id}"
        val user = client.createUser(
            name = name,
            gb = gb,
            days = days,
            comment = comment
        )
        val url = client.subscriptionUrl(user.uuid, linkBaseForCurrent())
        panelDao.upsertAccount(
            PanelAccount(
                customerId = customerId,
                panelUserUuid = user.uuid,
                name = user.name,
                gb = gb,
                days = days,
                startEpochDay = PanelClient.parseEpochDay(user.startDate),
                expireEpochDay = user.expireEpochDay,
                usageGb = user.currentUsageGb,
                enabled = user.enable,
                status = user.statusOrComputed,
                subscriptionUrl = url,
                // فاکتور این اکانت پیش از ساخت آن در پنل صادر شده است
                lastInvoicedExpireEpochDay = user.expireEpochDay
            )
        )
        return AccountProvisionResult(
            uuid = user.uuid,
            subscriptionUrl = url,
            expireEpochDay = user.expireEpochDay,
            expireInDays = user.remainingDaysOrComputed
        )
    }

    /**
     * پایهٔ لینک اشتراک برای این لحظه:
     *  ۱) مقدار ذخیره‌شده در تنظیمات پنل (اگر ادمین خودش وارد کرده باشد)
     *  ۲) کشف مجدد از خود پنل (دیگر لازم نیست کاربر چیزی حدس بزند)
     *  ۳) مقدار قدیمی تنظیمات
     */
    private suspend fun linkBaseForCurrent(): String {
        val saved = PanelUrl.normalizeLinkBase((panelDao.config() ?: PanelConfig()).subscriptionBase)
        if (saved.isNotBlank()) return saved
        val discovered = runCatching {
            PanelUrl.normalizeLinkBase(client.fetchUsers().second.linkDomains.firstOrNull()?.base ?: "")
        }.getOrNull().orEmpty()
        if (discovered.isNotBlank()) {
            runCatching { saveSubscriptionBase(discovered) }
            return discovered
        }
        return PanelUrl.normalizeLinkBase(prefs.current.fallbackSubscriptionBase)
    }

    /**
     * **تمدید** دورهٔ جدید برای اکانت موجود مشتری.
     *
     * معنای تمدید (همان تعریف پنل): «روز» و «حجم» از نو تنظیم می‌شود:
     *  • مصرف فعلی صفر می‌شود،
     *  • `start_date` پاک می‌شود تا شمارش روز از اولین اتصال بعدی شروع شود،
     *  • سقف حجم و تعداد روز برابر بستهٔ جدید می‌شود.
     * این کار برای هر سه سبک API (۱۱ تا ۱۴) انجام می‌شود.
     */
    suspend fun renew(uuid: String, gb: Double, days: Int): AccountProvisionResult {
        val user = client.renewPackage(uuid, gb, days)
        val now = System.currentTimeMillis()
        val existing = panelDao.byPanelUuid(uuid)
        val url = existing?.subscriptionUrl?.takeIf { it.isNotBlank() }
            ?: client.subscriptionUrl(uuid, linkBaseForCurrent())
        panelDao.upsertAccount(
            PanelAccount(
                id = existing?.id ?: 0,
                panelId = existing?.panelId ?: 1,
                customerId = existing?.customerId ?: 0,
                panelUserUuid = uuid,
                name = user.name,
                gb = gb,
                days = days,
                startEpochDay = PanelClient.parseEpochDay(user.startDate),
                expireEpochDay = user.expireEpochDay,
                usageGb = user.currentUsageGb,
                enabled = user.enable,
                status = user.statusOrComputed,
                subscriptionUrl = url,
                prevExpireEpochDay = existing?.expireEpochDay ?: 0,
                // فاکتور تمدید پیش از فراخوانی این متد صادر شده است
                lastInvoicedExpireEpochDay = user.expireEpochDay,
                lastRenewedAtMillis = now
            )
        )
        return AccountProvisionResult(
            uuid = uuid,
            subscriptionUrl = url,
            expireEpochDay = user.expireEpochDay,
            expireInDays = user.remainingDaysOrComputed
        )
    }

    /** افزودن حجم/روز روی بستهٔ فعلی */
    suspend fun extend(uuid: String, addGb: Double, addDays: Int): Int =
        client.extendLimits(listOf(uuid), addGb, addDays)

    suspend fun setEnabled(uuid: String, enabled: Boolean) {
        client.setEnabled(uuid, enabled)
        panelDao.byPanelUuid(uuid)?.let { panelDao.upsertAccount(it.copy(enabled = enabled)) }
    }

    companion object {
        fun suggestedExpire(days: Int): Long = LocalDate.now().plusDays(days.toLong()).toEpochDay()
    }
}
