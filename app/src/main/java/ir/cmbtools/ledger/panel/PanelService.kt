package ir.cmbtools.ledger.panel

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
        panelDao.saveConfig(
            existing.copy(
                name = name,
                host = host.trim(),
                adminProxyPath = adminProxyPath.trim().trim('/'),
                apiKey = apiKey.trim(),
                useHttps = useHttps
            )
        )
    }

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

        val user = client.createUser(
            name = name,
            gb = gb,
            days = days,
            comment = "cmbtools:${customer.id}:${customer.name}"
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
                subscriptionUrl = url
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
                subscriptionUrl = url
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

    /** همگام‌سازی کاربران پنل → اکانت‌های محلی (اتصال به مشتری از روی کامنت یا نام) */
    suspend fun sync(): SyncResult {
        val (users, meta) = client.fetchUsers()
        val linkBase = meta.linkDomains.firstOrNull()?.base ?: prefs.current.fallbackSubscriptionBase
        var linked = 0
        var unlinked = 0
        for (u in users) {
            val customerId = resolveCustomerId(u.comment, u.name)
            if (customerId > 0) linked++ else unlinked++
            panelDao.upsertAccount(
                PanelAccount(
                    id = panelDao.byPanelUuid(u.uuid)?.id ?: 0,
                    customerId = customerId,
                    panelUserUuid = u.uuid,
                    name = u.name,
                    gb = u.usageLimitGb,
                    days = u.packageDays,
                    startEpochDay = PanelClient.parseEpochDay(u.startDate),
                    expireEpochDay = PanelClient.parseEpochDay(u.expireDate),
                    usageGb = u.currentUsageGb,
                    enabled = u.enable,
                    status = u.status,
                    subscriptionUrl = client.subscriptionUrl(u.uuid, linkBase)
                )
            )
        }
        return SyncResult(
            total = users.size,
            linked = linked,
            unlinked = unlinked,
            panelVersion = (panelDao.config() ?: PanelConfig()).panelVersion
        )
    }

    /**
     * اتصال کاربر پنل به مشتری محلی، به ترتیب:
     * ۱) کامنتی که خود این اپ نوشته: cmbtools:<customerId>:<name>
     * ۲) نام دقیق مشتری
     * ۳) نام مشتری داخل نام کاربر پنل (prefix)
     */
    private suspend fun resolveCustomerId(comment: String?, name: String): Long {
        if (comment != null && comment.startsWith("cmbtools:")) {
            val id = comment.removePrefix("cmbtools:").substringBefore(":").toLongOrNull()
            if (id != null && id > 0 && customerDao.byId(id) != null) return id
        }
        customerDao.byName(name.trim())?.let { return it.id }
        val firstToken = name.trim().substringBefore(" ").trim()
        if (firstToken.length >= 3) customerDao.byName(firstToken)?.let { return it.id }
        return 0
    }

    data class SyncResult(
        val total: Int,
        val linked: Int,
        val unlinked: Int,
        val panelVersion: String
    )

    companion object {
        fun suggestedExpire(days: Int): Long = LocalDate.now().plusDays(days.toLong()).toEpochDay()
    }
}
