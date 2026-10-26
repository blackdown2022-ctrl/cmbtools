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

    suspend fun saveConfig(host: String, adminProxyPath: String, apiKey: String, useHttps: Boolean, name: String) {
        val existing = panelDao.config() ?: PanelConfig()
        // آدرس کاربر تمیز می‌شود: ممکن است کل لینک API را در فیلد «دامنه» چسبانده باشد
        val parts = PanelUrl.normalize(host, adminProxyPath, useHttps)
        panelDao.saveConfig(
            existing.copy(
                name = name,
                host = parts.host,
                adminProxyPath = parts.proxyPath,
                apiKey = apiKey.trim(),
                useHttps = parts.useHttps
            )
        )
    }

    /** عیب‌یابی گام‌به‌گام اتصال پنل (برای نمایش در تنظیمات) */
    suspend fun diagnose(): List<PanelCheck> = client.diagnose()

    suspend fun test(): PanelConnection = client.testConnection()

    /**
     * ساخت اکانت جدید برای مشتری. اگر فاکتور از قبل ساخته شده باشد، uuid در فاکتور ثبت می‌شود.
     * نام کاربری در پنل: نام مشتری + شمارهٔ فاکتور تا یکتا و قابل جست‌وجو باشد.
     */
    suspend fun createAccount(
        customerId: Long,
        planName: String,
        gb: Double,
        days: Int,
        invoiceNumber: String = ""
    ): AccountProvisionResult {
        val customer = customerDao.byId(customerId) ?: error("مشتری پیدا نشد")
        val name = buildString {
            append(customer.name)
            if (invoiceNumber.isNotBlank()) append(" ").append(invoiceNumber)
            if (planName.isNotBlank()) append(" - ").append(planName)
        }.take(120)

        // یادداشت کاربر در پنل = شمارهٔ مشتری (تا همان‌جا قابل خواندن و ویرایش باشد).
        // اگر مشتری شماره نداشت، شناسهٔ داخلی نوشته می‌شود تا اتصال حفظ شود.
        val comment = Phone.normalize(customer.phone)
            .ifBlank { "cmbtools:${customer.id}:${customer.name}" }
        val user = client.createUser(
            name = name,
            gb = gb,
            days = days,
            comment = comment
        )
        val linkBase = (runCatching { client.fetchUsers().second.linkDomains.firstOrNull()?.base }.getOrNull())
            ?: prefs.current.fallbackSubscriptionBase
        val url = client.subscriptionUrl(user.uuid, linkBase)

        panelDao.upsertAccount(
            PanelAccount(
                customerId = customerId,
                panelUserUuid = user.uuid,
                name = user.name,
                gb = gb,
                days = days,
                startEpochDay = PanelClient.parseEpochDay(user.startDate),
                expireEpochDay = PanelClient.parseEpochDay(user.expireDate),
                usageGb = user.currentUsageGb,
                enabled = user.enable,
                status = user.status,
                subscriptionUrl = url,
                // فاکتور این اکانت پیش از ساخت آن در پنل صادر شده است
                lastInvoicedExpireEpochDay = PanelClient.parseEpochDay(user.expireDate)
            )
        )
        return AccountProvisionResult(
            uuid = user.uuid,
            subscriptionUrl = url,
            expireEpochDay = PanelClient.parseEpochDay(user.expireDate),
            expireInDays = user.remainingDays
        )
    }

    /** تمدید دورهٔ جدید برای اکانت موجود مشتری */
    suspend fun renew(uuid: String, gb: Double, days: Int): AccountProvisionResult {
        val user = client.renewPackage(uuid, gb, days)
        val existing = panelDao.byPanelUuid(uuid)
        val url = existing?.subscriptionUrl ?: client.subscriptionUrl(uuid, prefs.current.fallbackSubscriptionBase)
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
                expireEpochDay = PanelClient.parseEpochDay(user.expireDate),
                usageGb = user.currentUsageGb,
                enabled = user.enable,
                status = user.status,
                subscriptionUrl = url,
                prevExpireEpochDay = existing?.expireEpochDay ?: 0,
                // فاکتور تمدید پیش از فراخوانی این متد صادر شده است
                lastInvoicedExpireEpochDay = PanelClient.parseEpochDay(user.expireDate)
            )
        )
        return AccountProvisionResult(
            uuid = uuid,
            subscriptionUrl = url,
            expireEpochDay = PanelClient.parseEpochDay(user.expireDate),
            expireInDays = user.remainingDays
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
