package ir.cmbtools.ledger.sync

import ir.cmbtools.ledger.billing.AutoBilling
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.PanelSyncRules
import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.CustomerDao
import ir.cmbtools.ledger.data.PanelAccount
import ir.cmbtools.ledger.data.PanelConfig
import ir.cmbtools.ledger.data.PanelDao
import ir.cmbtools.ledger.panel.PanelClient
import ir.cmbtools.ledger.panel.PanelException
import ir.cmbtools.ledger.panel.PanelUserDto

/** خروجی یک دور همگام‌سازی، برای نمایش خلاصه در UI و لاگ */
data class SyncReport(
    val total: Int = 0,
    val linked: Int = 0,
    val newUsers: Int = 0,
    val renewals: Int = 0,
    val invoicesIssued: Int = 0,
    val smsSent: Int = 0,
    val skipped: Int = 0,
    val firstRun: Boolean = false,
    val panelVersion: String = "",
    val atMillis: Long = System.currentTimeMillis(),
    val problems: List<String> = emptyList()
) {
    val message: String
        get() = buildString {
            if (firstRun) {
                append("نخستین همگام‌سازی انجام شد؛ وضعیت ")
                append(Money.toPersian(total.toString()))
                append(" کاربر ثبت شد (برای کاربران قبلی فاکتور صادر نمی‌شود).")
                return@buildString
            }
            append(Money.toPersian(total.toString())).append(" کاربر بررسی شد")
            if (newUsers > 0) append(" • ").append(Money.toPersian(newUsers.toString())).append(" جدید")
            if (renewals > 0) append(" • ").append(Money.toPersian(renewals.toString())).append(" تمدید")
            if (invoicesIssued > 0) append(" • ").append(Money.toPersian(invoicesIssued.toString())).append(" فاکتور")
            if (smsSent > 0) append(" • ").append(Money.toPersian(smsSent.toString())).append(" پیامک")
            if (skipped > 0) append(" • ").append(Money.toPersian(skipped.toString())).append(" رد شد")
        }
}

/**
 * همگام‌سازی هوشمند پنل:
 *  ۱) کاربران پنل را می‌خواند و به مشتری محلی وصل می‌کند.
 *  ۲) کاربر تازه را تشخیص می‌دهد و (اگر روشن باشد) برایش فاکتور می‌سازد.
 *  ۳) تمدید را از روی جلو رفتن تاریخ انقضا تشخیص می‌دهد و فاکتور تمدید می‌سازد.
 *  ۴) فاکتور را بلافاصله با پیامک برای شمارهٔ ثبت‌شده می‌فرستد.
 *
 * ضد تکرار:
 *  - اولین همگام‌سازی فقط «خط پایه» می‌سازد و فاکتور صادر نمی‌کند.
 *  - برای هر اکانت، «اکانت جدید» فقط یک بار فاکتور می‌گیرد.
 *  - هر انقضای مشخص فقط یک بار فاکتور تمدید می‌گیرد (`lastInvoicedExpireEpochDay`).
 */
class SyncEngine(
    private val client: PanelClient,
    private val panelDao: PanelDao,
    private val customerDao: CustomerDao,
    private val billing: AutoBilling,
    private val prefs: Prefs
) {

    suspend fun sync(allowInvoicing: Boolean = true): SyncReport {
        val config = panelDao.config() ?: PanelConfig()
        if (!config.isConfigured) {
            throw PanelException(
                "اتصال پنل تنظیم نشده است. در تنظیمات، دامنه، مسیر پروکسی ادمین و کلید API را وارد کنید.",
                PanelException.Kind.NOT_CONFIGURED
            )
        }

        val (users, meta) = client.fetchUsers()
        val linkBase = meta.linkDomains.firstOrNull()?.base ?: prefs.current.fallbackSubscriptionBase
        val settings = prefs.current
        val firstRun = config.baselineAtMillis == 0L
        val known = panelDao.allAccounts().associateBy { it.panelUserUuid }

        var linked = 0
        var newUsers = 0
        var renewals = 0
        var issued = 0
        var sms = 0
        var skipped = 0
        val problems = mutableListOf<String>()

        for (u in users) {
            val old = known[u.uuid]
            var customerId = old?.customerId ?: 0L
            if (customerId <= 0) customerId = resolveCustomerId(u.comment, u.name)

            val newExpire = PanelClient.parseEpochDay(u.expireDate)
            val oldExpire = old?.expireEpochDay ?: 0L
            var lastInvoicedExpire = old?.lastInvoicedExpireEpochDay ?: 0L

            val isNewUser = old == null
            val renewed = !isNewUser && PanelSyncRules.isRenewal(
                oldExpireEpochDay = oldExpire,
                newExpireEpochDay = newExpire,
                oldUsageGb = old?.usageGb ?: 0.0,
                newUsageGb = u.currentUsageGb
            )

            if (firstRun) {
                // خط پایه: فقط ثبت وضعیت، بدون فاکتور
            } else if (allowInvoicing) {
                if (isNewUser) newUsers++
                if (renewed) renewals++

                val shouldInvoiceNew = isNewUser && settings.autoInvoiceOnNewAccount
                val shouldInvoiceRenew = renewed && settings.autoInvoiceOnRenew &&
                    newExpire != lastInvoicedExpire

                if (shouldInvoiceNew || shouldInvoiceRenew) {
                    // ۱) مشتری: اتصال موجود، یا ساخت خودکار از نام کاربر پنل
                    if (customerId <= 0) {
                        val created = runCatching { billing.resolveOrCreateCustomer(u.name) }.getOrNull()
                        if (created != null) customerId = created.id
                    }

                    if (customerId <= 0) {
                        skipped++
                        problems += "${u.name}: مشتری متناظر پیدا نشد"
                    } else {
                        // ۲) قیمت: بستهٔ تعریف‌شده یا قیمت هر گیگ
                        val priced = runCatching { billing.priceFor(u.usageLimitGb, u.packageDays) }.getOrNull()
                        if (priced == null) {
                            skipped++
                            problems += "${u.name}: بستهٔ ${formatGb(u.usageLimitGb)} گیگ / ${u.packageDays} روز قیمت‌گذاری نشده"
                        } else {
                            val subUrl = client.subscriptionUrl(u.uuid, linkBase)
                            val result = runCatching {
                                if (shouldInvoiceRenew) {
                                    billing.invoiceRenewal(
                                        customerId = customerId,
                                        panelUuid = u.uuid,
                                        subscriptionUrl = subUrl,
                                        gb = u.usageLimitGb,
                                        days = u.packageDays,
                                        packageName = priced.name,
                                        priceRial = priced.priceRial,
                                        planId = priced.planId
                                    )
                                } else {
                                    billing.invoiceNewAccount(
                                        customerId = customerId,
                                        panelUuid = u.uuid,
                                        subscriptionUrl = subUrl,
                                        gb = u.usageLimitGb,
                                        days = u.packageDays,
                                        packageName = priced.name,
                                        priceRial = priced.priceRial,
                                        planId = priced.planId
                                    )
                                }
                            }.getOrElse { e ->
                                problems += "${u.name}: ${e.message ?: "خطای صدور فاکتور"}"
                                null
                            }

                            if (result != null && result.ok) {
                                issued++
                                if (result.smsSent) sms++
                                if (shouldInvoiceRenew) lastInvoicedExpire = newExpire
                            } else if (result != null) {
                                skipped++
                                problems += "${u.name}: ${result.message}"
                            }
                        }
                    }
                }
            }

            if (customerId > 0) linked++

            panelDao.upsertAccount(
                PanelAccount(
                    id = old?.id ?: 0,
                    panelId = old?.panelId ?: 1,
                    customerId = customerId,
                    panelUserUuid = u.uuid,
                    name = u.name,
                    gb = u.usageLimitGb,
                    days = u.packageDays,
                    startEpochDay = PanelClient.parseEpochDay(u.startDate),
                    expireEpochDay = newExpire,
                    usageGb = u.currentUsageGb,
                    enabled = u.enable,
                    status = u.status,
                    subscriptionUrl = client.subscriptionUrl(u.uuid, linkBase),
                    prevExpireEpochDay = oldExpire,
                    lastInvoicedExpireEpochDay = lastInvoicedExpire
                )
            )
        }

        panelDao.saveConfig(
            config.copy(
                lastSyncAt = System.currentTimeMillis(),
                baselineAtMillis = if (firstRun) System.currentTimeMillis() else config.baselineAtMillis
            )
        )

        return SyncReport(
            total = users.size,
            linked = linked,
            newUsers = newUsers,
            renewals = renewals,
            invoicesIssued = issued,
            smsSent = sms,
            skipped = skipped,
            firstRun = firstRun,
            panelVersion = config.panelVersion,
            problems = problems
        )
    }

    /**
     * اتصال کاربر پنل به مشتری محلی، به ترتیب:
     * ۱) کامنتی که خود این اپ نوشته: cmbtools:<customerId>:<name>
     * ۲) نام دقیق مشتری
     * ۳) شمارهٔ موبایل داخل نام/کامنت
     * ۴) اولین کلمهٔ نام
     */
    suspend fun resolveCustomerId(comment: String?, name: String): Long {
        if (comment != null && comment.startsWith("cmbtools:")) {
            val id = comment.removePrefix("cmbtools:").substringBefore(":").toLongOrNull()
            if (id != null && id > 0 && customerDao.byId(id) != null) return id
        }
        customerDao.byName(name.trim())?.let { return it.id }

        val phone = billing.extractPhone(name)
        if (phone.isNotBlank()) customerDao.byPhone(phone)?.let { return it.id }

        val firstToken = name.trim().substringBefore(" ").trim()
        if (firstToken.length >= 3) customerDao.byName(firstToken)?.let { return it.id }
        return 0
    }

    private fun formatGb(gb: Double): String =
        if (gb % 1.0 == 0.0) gb.toInt().toString() else gb.toString()

    /** برای تست/دیباگ: خلاصهٔ یک کاربر پنل */
    fun describe(u: PanelUserDto): String = "${u.name} • ${formatGb(u.usageLimitGb)} گیگ • ${u.packageDays} روز"
}
