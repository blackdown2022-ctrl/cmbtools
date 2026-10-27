package ir.cmbtools.ledger.sync

import ir.cmbtools.ledger.billing.AutoBilling
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.PanelSyncRules
import ir.cmbtools.ledger.core.PanelCustomerNames
import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.CustomerDao
import ir.cmbtools.ledger.data.PanelAccount
import ir.cmbtools.ledger.data.PanelConfig
import ir.cmbtools.ledger.data.PanelDao
import ir.cmbtools.ledger.panel.PanelClient
import ir.cmbtools.ledger.panel.PanelException
import ir.cmbtools.ledger.panel.PanelService
import ir.cmbtools.ledger.panel.PanelUserDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** خروجی یک دور همگام‌سازی، برای نمایش خلاصه در UI و لاگ */
data class SyncReport(
    val total: Int = 0,
    val linked: Int = 0,
    val newUsers: Int = 0,
    val renewals: Int = 0,
    val invoicesIssued: Int = 0,
    val discountsCorrected: Int = 0,
    val smsSent: Int = 0,
    val customersCreated: Int = 0,
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
                append(" کاربر پنل ثبت شد")
                if (customersCreated > 0) {
                    append(" و ").append(Money.toPersian(customersCreated.toString())).append(" مشتری متصل شد")
                }
                if (invoicesIssued > 0) {
                    append(" • ").append(Money.toPersian(invoicesIssued.toString())).append(" فاکتور برای اکانت‌های دارای پسوند تخفیف")
                }
                if (discountsCorrected > 0) {
                    append(" • تخفیف ").append(Money.toPersian(discountsCorrected.toString())).append(" فاکتور اصلاح شد")
                }
                append(" (برای کاربران قبلیِ بدون پسوند تخفیف فاکتور صادر نمی‌شود).")
                return@buildString
            }
            append(Money.toPersian(total.toString())).append(" کاربر بررسی شد")
            if (newUsers > 0) append(" • ").append(Money.toPersian(newUsers.toString())).append(" جدید")
            if (renewals > 0) append(" • ").append(Money.toPersian(renewals.toString())).append(" تمدید")
            if (invoicesIssued > 0) append(" • ").append(Money.toPersian(invoicesIssued.toString())).append(" فاکتور")
            if (discountsCorrected > 0) append(" • تخفیف ").append(Money.toPersian(discountsCorrected.toString())).append(" فاکتور اصلاح شد")
            if (customersCreated > 0) append(" • ").append(Money.toPersian(customersCreated.toString())).append(" مشتری متصل")
            if (smsSent > 0) append(" • ").append(Money.toPersian(smsSent.toString())).append(" پیامک")
            if (skipped > 0) append(" • ").append(Money.toPersian(skipped.toString())).append(" رد شد")
        }
}

/**
 * همگام‌سازی هوشمند پنل:
 *  ۱) کاربران پنل را می‌خواند و به مشتری محلی وصل می‌کند.
 *  ۲) کاربر تازه را تشخیص می‌دهد و (اگر روشن باشد) برایش فاکتور می‌سازد.
 *  ۳) تمدید را از روی «ریست شدن روز و حجم» تشخیص می‌دهد (پاک شدن start_date،
 *     جلو رفتن تاریخ شروع/انقضا، یا ریست هم‌زمان مصرف و بسته) و فاکتور تمدید می‌سازد.
 *  ۴) فاکتور را بلافاصله با پیامک برای شمارهٔ ثبت‌شده می‌فرستد.
 *
 * ضد تکرار:
 *  - اولین همگام‌سازی کاربران عادی فقط «خط پایه» می‌سازد؛ پسوند تخفیف `/عدد` سیگنال صریح صدور فاکتور اولیه است.
 *  - برای هر اکانت، فاکتور اولیه با UUID کنترل می‌شود تا دوباره صادر نشود.
 *  - هر انقضای مشخص فقط یک بار فاکتور تمدید می‌گیرد (`lastInvoicedExpireEpochDay`).
 */
class SyncEngine(
    private val client: PanelClient,
    private val panelService: PanelService,
    private val panelDao: PanelDao,
    private val customerDao: CustomerDao,
    private val billing: AutoBilling,
    private val prefs: Prefs
) {

    private val syncMutex = Mutex()

    suspend fun sync(allowInvoicing: Boolean = true): SyncReport =
        syncMutex.withLock { syncInternal(allowInvoicing) }

    private suspend fun syncInternal(allowInvoicing: Boolean): SyncReport {
        val config = panelDao.config() ?: PanelConfig()
        if (!config.isConfigured) {
            throw PanelException(
                "اتصال پنل تنظیم نشده است. در تنظیمات، «آدرس پنل» (همان آدرس مرورگر) و «کلید API» را وارد کنید.",
                PanelException.Kind.NOT_CONFIGURED
            )
        }

        val (initialUsers, meta) = client.fetchUsers()
        var users = initialUsers
        // پایهٔ لینک اشتراک، مستقل از مسیر ادمین است:
        // اول مقدار ذخیره‌شده در تنظیمات پنل، بعد مقدار خودِ پنل (link_domains)، بعد مقدار قدیمی.
        val linkBase = ir.cmbtools.ledger.panel.PanelUrl.normalizeLinkBase(
            config.subscriptionBase.ifBlank {
                meta.linkDomains.firstOrNull()?.base ?: prefs.current.fallbackSubscriptionBase
            }
        )
        val settings = prefs.current
        val firstRun = config.baselineAtMillis == 0L
        var known = panelDao.allAccounts().associateBy { it.panelUserUuid }

        var linked = 0
        var newUsers = 0
        var renewals = 0
        var issued = 0
        var sms = 0
        var createdCustomers = 0
        var skipped = 0
        val problems = mutableListOf<String>()

        // هر تمدید خودکاری که همین دور برایش فاکتور صادر شد از صدور دوباره در حلقهٔ sync عادی مستثناست.
        val renewalInvoicedThisSync = mutableSetOf<String>()
        val renewalInvoicedAtThisSync = mutableMapOf<String, Long>()
        var discountsCorrected = 0

        // Renew only accounts explicitly opted in, and only after a successful panel read.
        // After a successful renewal, refresh users before normal sync so old expiry data cannot
        // trigger a second invoice in this same pass.
        var panelRenewed = false
        if (!firstRun && allowInvoicing) {
            for (u in users) {
                val old = known[u.uuid] ?: continue
                if (!old.autoRenewEnabled || old.customerId <= 0 || !u.enable) continue
                val quotaEnded = u.usageLimitGb > 0.0 && u.currentUsageGb >= u.usageLimitGb
                val daysEnded = u.packageDays > 0 &&
                    (u.remainingDaysOrComputed <= 0 || u.statusOrComputed.equals("expired", ignoreCase = true))
                if (!quotaEnded && !daysEnded) continue

                val gb = u.usageLimitGb.takeIf { it > 0.0 } ?: old.gb
                val days = u.packageDays.takeIf { it > 0 } ?: old.days
                val price = billing.priceFor(gb, days)
                if (gb <= 0.0 || days <= 0 || price == null || old.customerId <= 0) {
                    skipped++
                    problems += "تمدید خودکار ${u.name}: بسته یا قیمت معتبر برای ${gb} گیگ / ${days} روز پیدا نشد"
                    continue
                }

                val provision = runCatching { panelService.renew(u.uuid, gb, days) }.getOrElse { error ->
                    skipped++
                    problems += "تمدید خودکار ${u.name} انجام نشد: ${error.message ?: "خطای پنل"}"
                    null
                } ?: continue

                panelRenewed = true
                renewals++
                val invoice = runCatching {
                    billing.invoiceRenewal(
                        customerId = old.customerId,
                        panelUuid = u.uuid,
                        subscriptionUrl = provision.subscriptionUrl,
                        gb = gb,
                        days = days,
                        packageName = price.name,
                        priceRial = price.priceRial,
                        planId = price.planId
                    )
                }.getOrElse { error ->
                    skipped++
                    problems += "اکانت ${u.name} تمدید شد، اما صدور فاکتور خودکار ناموفق بود: ${error.message ?: "خطای داخلی"}"
                    null
                }
                if (invoice != null && invoice.ok) {
                    issued++
                    val renewalAt = System.currentTimeMillis()
                    renewalInvoicedThisSync += u.uuid
                    renewalInvoicedAtThisSync[u.uuid] = renewalAt
                    if (invoice.smsSent) sms++
                } else if (invoice != null) {
                    skipped++
                    problems += "اکانت ${u.name} تمدید شد، اما فاکتور صادر نشد: ${invoice.message}"
                }
            }
        }
        if (panelRenewed) {
            val refreshed = client.fetchUsers()
            users = refreshed.first
            known = panelDao.allAccounts().associateBy { it.panelUserUuid }
        }

        for (u in users) {
            val old = known[u.uuid]
            val panelName = PanelCustomerNames.discountSuffix(u.name)
            val resolvedCustomerId = resolveCustomerId(u.comment, panelName.customerName)
            val ambiguousName = resolvedCustomerId <= 0 && billing.exactNameMatches(u.name).size > 1
            var customerId = PanelCustomerNames.chooseCustomerId(
                resolvedId = resolvedCustomerId,
                previousId = old?.customerId ?: 0L,
                ambiguousName = ambiguousName
            )
            if (ambiguousName) problems += "${u.name}: چند مشتری با این نام وجود دارد؛ برای جلوگیری از صدور فاکتور اشتباه، اتصال انجام نشد. شمارهٔ مشتری را در یادداشت پنل وارد کنید."

            // کاربر پنل که مشتری محلی ندارد: اگر «ساخت خودکار مشتری» روشن باشد، همین‌جا
            // ساخته می‌شود — حتی وقتی فاکتور خودکار خاموش است. قبلاً فقط داخل مسیر
            // صدور فاکتور ساخته می‌شد و کاربر نتیجه می‌گرفت «همگام‌سازی چیزی نمی‌سازد».
            if (customerId <= 0 && settings.autoCreateCustomerFromPanel) {
                val made = runCatching { billing.resolveOrCreateCustomer(panelName.customerName, u.comment) }.getOrNull()
                if (made != null) {
                    customerId = made.id
                    createdCustomers++
                }
            }

            // در نسخه‌های ۱۱–۱۳ فیلد expire_date وجود ندارد؛ از start_date + package_days حساب می‌شود
            val newExpire = u.expireEpochDay
            val oldExpire = old?.expireEpochDay ?: 0L
            var lastInvoicedExpire = old?.lastInvoicedExpireEpochDay ?: 0L
            val newStart = PanelClient.parseEpochDay(u.startDate)

            val isNewUser = old == null
            // «تمدید» = ریست شدن روز و حجم. همهٔ نشانه‌ها (پاک شدن start_date، جلو رفتن
            // تاریخ شروع/انقضا، و ریست هم‌زمان مصرف و بسته) به PanelSyncRules داده می‌شود.
            val renewed = !isNewUser && PanelSyncRules.isRenewal(
                oldExpireEpochDay = oldExpire,
                newExpireEpochDay = newExpire,
                oldUsageGb = old?.usageGb ?: 0.0,
                newUsageGb = u.currentUsageGb,
                oldStartEpochDay = old?.startEpochDay ?: 0L,
                newStartEpochDay = newStart,
                oldDays = old?.days ?: 0,
                newDays = u.packageDays,
                oldRemainingDays = old?.remainingDays ?: 0,
                newRemainingDays = u.remainingDaysOrComputed,
                oldLimitGb = old?.gb ?: 0.0,
                newLimitGb = u.usageLimitGb,
                oldNotStarted = (old?.startEpochDay ?: 0L) == 0L,
                newNotStarted = u.notStarted
            )
            // اگر فاکتور رویداد تمدید قبلی ناموفق بوده باشد، در این sync دوباره امتحان می‌شود.
            val pendingRenewal = old != null && old.lastRenewedAtMillis > old.lastInvoicedRenewalAtMillis
            // زمان آخرین تمدید برای نمایش در کارت اکانت و اتصال به فاکتور موفق.
            val renewedAt = renewalInvoicedAtThisSync[u.uuid]
                ?: if (renewed) System.currentTimeMillis() else (old?.lastRenewedAtMillis ?: 0L)
            var initialInvoice = billing.initialInvoiceForPanelUser(u.uuid)
            if (allowInvoicing && initialInvoice != null && panelName.hasSuffix && panelName.discountRial != null &&
                panelName.discountRial > initialInvoice.discountRial
            ) {
                runCatching {
                    billing.applyPanelInitialDiscount(initialInvoice.id, panelName.discountRial)
                }.onSuccess { corrected ->
                    if (corrected != null && corrected.discountRial > initialInvoice!!.discountRial) {
                        discountsCorrected++
                        initialInvoice = corrected
                        if (settings.autoSmsOnInvoice) {
                            val smsResult = billing.sendInvoiceSms(corrected.id)
                            if (smsResult.smsSent) sms++
                            else problems += "${u.name}: تخفیف فاکتور اصلاح شد، اما پیامک اصلاح‌شده ارسال نشد: ${smsResult.message}"
                        }
                    }
                }.onFailure { error ->
                    problems += "${u.name}: تخفیف فاکتور قبلی اعمال نشد: ${error.message ?: "فاکتور پرداخت‌شده یا خطای داخلی"}"
                }
            }
            val hasInitialInvoice = initialInvoice != null
            val shouldInvoiceNew = PanelCustomerNames.shouldInvoiceInitialAccount(
                autoInvoiceEnabled = settings.autoInvoiceOnNewAccount,
                firstSync = firstRun,
                wasKnownBefore = !isNewUser,
                alreadyHasInvoice = hasInitialInvoice,
                hasDiscountSuffix = panelName.hasSuffix
            )

            if (firstRun && !shouldInvoiceNew) {
                // خط پایه: کاربران عادی فقط ثبت وضعیت می‌شوند؛ پسوند تخفیف سیگنال صریح صدور است.
            } else if (allowInvoicing) {
                if (isNewUser) newUsers++
                if (renewed) renewals++

                val renewalNeedsInvoice = (renewed || pendingRenewal) && u.uuid !in renewalInvoicedThisSync
                val shouldInvoiceRenew = renewalNeedsInvoice && !shouldInvoiceNew
                if (shouldInvoiceNew || shouldInvoiceRenew) {
                    // ۱) مشتری: پیش‌تر در همین دور حل/ساخته شده است
                    if (customerId <= 0) {
                        skipped++
                        if (!ambiguousName) problems += "${u.name}: مشتری متناظر پیدا نشد"
                        if (shouldInvoiceRenew) {
                            sendDetectedRenewalReport(customerId, u.name, u.usageLimitGb, u.packageDays)
                                ?.let { problems += "${u.name}: گزارش تمدید ارسال نشد: $it" }
                        }
                    } else {
                        // ۲) قیمت: بستهٔ تعریف‌شده یا قیمت هر گیگ
                        val priced = runCatching { billing.priceFor(u.usageLimitGb, u.packageDays) }.getOrNull()
                        if (priced == null) {
                            skipped++
                            problems += "${u.name}: بستهٔ ${formatGb(u.usageLimitGb)} گیگ / ${u.packageDays} روز قیمت‌گذاری نشده"
                            if (shouldInvoiceRenew) {
                                sendDetectedRenewalReport(customerId, u.name, u.usageLimitGb, u.packageDays)
                                    ?.let { problems += "${u.name}: گزارش تمدید ارسال نشد: $it" }
                            }
                        } else {
                            val subUrl = client.subscriptionUrl(u.uuid, linkBase)
                            val result = runCatching {
                                if (shouldInvoiceNew && panelName.hasSuffix && panelName.discountRial == null) {
                                    error("پسوند تخفیف پس از / نامعتبر یا بیش از حد بزرگ است؛ فاکتور بدون تخفیف صادر نشد")
                                }
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
                                        planId = priced.planId,
                                        discountRial = if (shouldInvoiceNew) panelName.discountRial ?: 0L else 0L
                                    )
                                }
                            }.getOrElse { e ->
                                problems += "${u.name}: ${e.message ?: "خطای صدور فاکتور"}"
                                null
                            }

                            if (result != null && result.ok) {
                                issued++
                                if (result.smsSent) sms++
                                else if (settings.autoSmsOnInvoice) problems += "${u.name}: ${result.message}"
                                if (renewalNeedsInvoice) {
                                    lastInvoicedExpire = newExpire
                                    renewalInvoicedThisSync += u.uuid
                                    renewalInvoicedAtThisSync[u.uuid] = renewedAt
                                }
                            } else if (result != null) {
                                skipped++
                                problems += "${u.name}: ${result.message}"
                            } else {
                                skipped++
                            }
                        }
                    }
                }
            }

            // اگر تلاش فوری پیامک فاکتور شکست خورده باشد، در همگام‌سازی بعدی دوباره امتحان می‌شود؛
            // صف خروجی فاصلهٔ ۳۰ دقیقه‌ای دارد و پیامک‌های موفق را تکرار نمی‌کند.
            val smsRetries = runCatching { billing.retryPendingInvoiceSms(u.uuid) }
                .getOrElse { e ->
                    problems += "${u.name}: تلاش دوبارهٔ پیامک ناموفق بود: ${e.message ?: "خطای نامشخص"}"
                    emptyList()
                }
            smsRetries.forEach { retry ->
                if (retry.smsSent) sms++
                else if (retry.message.isNotBlank()) problems += "${u.name}: ${retry.message}"
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
                    remainingDays = u.remainingDaysOrComputed,
                    startEpochDay = newStart,
                    expireEpochDay = newExpire,
                    usageGb = u.currentUsageGb,
                    enabled = u.enable,
                    status = u.statusOrComputed,
                    subscriptionUrl = client.subscriptionUrl(u.uuid, linkBase),
                    prevExpireEpochDay = oldExpire,
                    lastInvoicedExpireEpochDay = lastInvoicedExpire,
                    lastRenewedAtMillis = renewedAt,
                    lastInvoicedRenewalAtMillis = renewalInvoicedAtThisSync[u.uuid]
                        ?: (old?.lastInvoicedRenewalAtMillis ?: 0L),
                    autoRenewEnabled = old?.autoRenewEnabled ?: false
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
            discountsCorrected = discountsCorrected,
            smsSent = sms,
            customersCreated = createdCustomers,
            skipped = skipped,
            firstRun = firstRun,
            panelVersion = config.panelVersion,
            problems = problems
        )
    }

    /**
     * اتصال امن کاربر پنل: شناسهٔ اپ، شمارهٔ یادداشت، نام کامل دقیق، سپس شمارهٔ داخل نام.
     * تطبیق نام کوچک/پیشوند عمداً انجام نمی‌شود تا «رضا» با «رضا عباسی» یکی نشود.
     */
    suspend fun resolveCustomerId(comment: String?, name: String): Long {
        val idFromComment = billing.customerIdFromComment(comment)
        if (idFromComment > 0 && customerDao.byId(idFromComment) != null) return idFromComment

        val phoneFromComment = billing.phoneFromComment(comment)
        if (phoneFromComment.isNotBlank()) {
            billing.findCustomerByPhone(phoneFromComment)?.let { return it.id }
        }

        val exactIds = billing.exactNameMatches(name)
        if (exactIds.size == 1) return exactIds.single()

        val phoneFromName = billing.extractPhone(name)
        if (phoneFromName.isNotBlank()) billing.findCustomerByPhone(phoneFromName)?.let { return it.id }
        return 0
    }

    private suspend fun sendDetectedRenewalReport(
        customerId: Long,
        customerName: String,
        gb: Double,
        days: Int
    ): String? {
        val packageName = runCatching { billing.priceFor(gb, days)?.name.orEmpty() }.getOrDefault("")
        val sent = billing.sendRenewalReport(
            customerId = customerId,
            packageName = packageName,
            gb = gb,
            days = days,
            customerNameFallback = customerName
        )
        return sent?.takeIf { !it.ok }?.message
    }

    private fun formatGb(gb: Double): String =
        if (gb % 1.0 == 0.0) gb.toInt().toString() else gb.toString()

    /** برای تست/دیباگ: خلاصهٔ یک کاربر پنل */
    fun describe(u: PanelUserDto): String = "${u.name} • ${formatGb(u.usageLimitGb)} گیگ • ${u.packageDays} روز"
}
