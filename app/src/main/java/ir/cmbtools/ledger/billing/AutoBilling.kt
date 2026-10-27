package ir.cmbtools.ledger.billing

import ir.cmbtools.ledger.core.AppSettings
import ir.cmbtools.ledger.core.InvoiceKind
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.Phone
import ir.cmbtools.ledger.core.PanelSyncRules
import ir.cmbtools.ledger.core.PanelCustomerNames
import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.Customer
import ir.cmbtools.ledger.data.CustomerDao
import ir.cmbtools.ledger.data.Invoice
import ir.cmbtools.ledger.data.InvoiceRepository
import ir.cmbtools.ledger.data.NewInvoiceLine
import ir.cmbtools.ledger.data.Plan
import ir.cmbtools.ledger.data.PlanDao
import ir.cmbtools.ledger.sms.AdminSmsReportFormatter
import ir.cmbtools.ledger.sms.SmsSender
import ir.cmbtools.ledger.sms.SmsTemplate

/** نتیجهٔ یک عملیات صورت‌حساب خودکار */
data class BillingResult(
    val ok: Boolean,
    val invoiceId: Long = 0,
    val invoiceNumber: String = "",
    val totalRial: Long = 0,
    val smsSent: Boolean = false,
    val message: String = "",
    val adminReportError: String = ""
)

/** بستهٔ قیمت‌گذاری‌شده برای یک حجم/روز مشخص */
data class PricedPlan(
    val planId: Long,
    val name: String,
    val gb: Double,
    val days: Int,
    val priceRial: Long,
    val matchedExistingPlan: Boolean
)

/**
 * موتور صورت‌حساب: هر جایی که «اکانت ساخته شد» یا «اکانت تمدید شد»، فاکتور ساختاریافته
 * می‌سازد و — اگر روشن باشد — بلافاصله پیامک می‌فرستد.
 *
 * این کلاس هم از UI (صفحهٔ فاکتور/مشتری) و هم از همگام‌سازی خودکار پس‌زمینه استفاده می‌شود،
 * تا رفتار در هر دو مسیر یکی باشد.
 */
class AutoBilling(
    private val invoices: InvoiceRepository,
    private val customers: CustomerDao,
    private val plans: PlanDao,
    private val smsSender: SmsSender,
    private val prefs: Prefs
) {

    private val settings: AppSettings get() = prefs.current

    // ─────────────────────────────────────────────────────────── قیمت‌گذاری

    /**
     * قیمت یک بستهٔ حجم/روز:
     * ۱) بستهٔ تعریف‌شده در صفحهٔ «بسته‌ها» (تطبیق حجم و روز)
     * ۲) اگر نبود، «قیمت هر گیگ» تنظیمات
     * ۳) اگر هیچ‌کدام نبود → null (فاکتور ساخته نمی‌شود؛ بهتر از فاکتور با مبلغ صفر)
     */
    suspend fun priceFor(gb: Double, days: Int): PricedPlan? {
        val all: List<Plan> = plans.activeOnce()
        val idx = PanelSyncRules.bestPlanIndex(
            gbs = all.map { it.gb.toDouble() },
            daysList = all.map { it.days },
            gb = gb,
            days = days
        )
        if (idx >= 0) {
            val p = all[idx]
            return PricedPlan(
                planId = p.id,
                name = p.name,
                gb = gb,
                days = days,
                priceRial = p.priceRial,
                matchedExistingPlan = true
            )
        }
        val fallback = PanelSyncRules.priceFromPerGb(gb, settings.pricePerGbRial)
        if (fallback > 0) {
            return PricedPlan(
                planId = 0,
                name = PanelSyncRules.packageLabel("", gb, days),
                gb = gb,
                days = days,
                priceRial = fallback,
                matchedExistingPlan = false
            )
        }
        return null
    }

    // ─────────────────────────────────────────────────────────── فاکتور

    /** فاکتور برای اکانت تازه‌ساخته‌شده (چه از داخل اپ، چه از خود پنل) */
    suspend fun invoiceNewAccount(
        customerId: Long,
        panelUuid: String,
        subscriptionUrl: String,
        gb: Double,
        days: Int,
        packageName: String,
        priceRial: Long,
        planId: Long = 0,
        discountRial: Long = 0,
        sendSms: Boolean = settings.autoSmsOnInvoice,
        note: String = ""
    ): BillingResult = createAndNotify(
        customerId = customerId,
        panelUuid = panelUuid,
        subscriptionUrl = subscriptionUrl,
        gb = gb,
        days = days,
        packageName = packageName,
        priceRial = priceRial,
        planId = planId,
        discountRial = discountRial,
        kind = InvoiceKind.NEW,
        note = note.ifBlank { "اکانت جدید" },
        sendSms = sendSms
    )

    /** فاکتور برای تمدید اکانت موجود */
    suspend fun invoiceRenewal(
        customerId: Long,
        panelUuid: String,
        subscriptionUrl: String,
        gb: Double,
        days: Int,
        packageName: String,
        priceRial: Long,
        planId: Long = 0,
        sendSms: Boolean = settings.autoSmsOnInvoice,
        note: String = ""
    ): BillingResult {
        val result = try {
            createAndNotify(
                customerId = customerId,
                panelUuid = panelUuid,
                subscriptionUrl = subscriptionUrl,
                gb = gb,
                days = days,
                packageName = packageName,
                priceRial = priceRial,
                planId = planId,
                discountRial = 0,
                kind = InvoiceKind.RENEW,
                note = note.ifBlank { "تمدید" },
                sendSms = sendSms
            )
        } catch (error: Exception) {
            // پنل پیش از صدور فاکتور تمدید شده؛ حتی در خطای فاکتور هم گزارش تمدید را امتحان کن.
            sendRenewalReport(customerId, packageName, gb, days)
            throw error
        }

        val report = sendRenewalReport(customerId, packageName, gb, days)
        return if (report != null && !report.ok) {
            result.copy(
                message = "${result.message} | گزارش تمدید به شمارهٔ تنظیم‌شده ارسال نشد: ${report.message}",
                adminReportError = report.message
            )
        } else {
            result
        }
    }

    /** گزارش تمدید فقط به شمارهٔ مجاز ذخیره‌شده در تنظیمات می‌رود؛ خالی بودن شماره یعنی خاموش. */
    suspend fun sendRenewalReport(
        customerId: Long,
        packageName: String,
        gb: Double,
        days: Int,
        customerNameFallback: String = ""
    ): ir.cmbtools.ledger.sms.SendResult? {
        val recipient = settings.renewalCommandSender.trim()
        if (recipient.isBlank()) return null
        val customerName = customers.byId(customerId)?.name ?: customerNameFallback.trim()
        if (customerName.isBlank()) return null
        val body = AdminSmsReportFormatter.renewal(customerName, packageName, gb, days)
        return runCatching {
            smsSender.send(settings, recipient, body, customerId = customerId)
        }.getOrElse { error ->
            ir.cmbtools.ledger.sms.SendResult(false, error.message ?: "خطای ارسال گزارش")
        }
    }

    suspend fun hasInvoicesForPanelUser(uuid: String): Boolean =
        invoices.hasInvoicesForPanelUser(uuid)

    private suspend fun createAndNotify(
        customerId: Long,
        panelUuid: String,
        subscriptionUrl: String,
        gb: Double,
        days: Int,
        packageName: String,
        priceRial: Long,
        planId: Long,
        discountRial: Long,
        kind: String,
        note: String,
        sendSms: Boolean
    ): BillingResult {
        if (customerId <= 0) return BillingResult(false, message = "مشتری مشخص نشد")
        if (priceRial <= 0) return BillingResult(false, message = "قیمت بسته مشخص نشد")

        val customer = customers.byId(customerId)
            ?: return BillingResult(false, message = "مشتری پیدا نشد")

        // ضد تکرار: برای «اکانت جدید» هر اکانت فقط یک فاکتور؛ تمدیدها فاکتور جدا دارند.
        if (kind == InvoiceKind.NEW && panelUuid.isNotBlank() && invoices.hasInvoicesForPanelUser(panelUuid)) {
            return BillingResult(false, message = "برای این اکانت قبلاً فاکتور صادر شده است")
        }

        val label = PanelSyncRules.packageLabel(packageName, gb, days)

        val invoiceId = invoices.create(
            customerId = customerId,
            lines = listOf(
                NewInvoiceLine(
                    description = label,
                    qty = 1,
                    unitPriceRial = priceRial,
                    planId = planId
                )
            ),
            planId = planId,
            discountRial = discountRial.coerceIn(0, priceRial),
            panelUserUuid = panelUuid,
            panelId = 1,
            subscriptionUrl = subscriptionUrl,
            note = note,
            packageName = label,
            cardNumber = settings.paymentCardNumber,
            kind = kind,
            autoIssued = true
        )

        val invoice = invoices.get(invoiceId)
        var smsSent = false
        var smsMessage = ""
        if (sendSms) {
            val result = sendInvoiceSms(invoiceId)
            smsSent = result.ok
            smsMessage = result.message
        }

        return BillingResult(
            ok = true,
            invoiceId = invoiceId,
            invoiceNumber = invoice?.number ?: "",
            totalRial = invoice?.totalRial ?: priceRial,
            smsSent = smsSent,
            message = buildString {
                append("فاکتور ").append(invoice?.number ?: "").append(" صادر شد")
                if (sendSms) append(" | ").append(smsMessage)
            }
        )
    }

    // ─────────────────────────────────────────────────────────── پیامک

    /** ارسال (یا ارسال دوبارهٔ) پیامک یک فاکتور */
    suspend fun sendInvoiceSms(invoiceId: Long): BillingResult {
        val invoice: Invoice = invoices.reconcileOpeningBalanceSnapshot(invoiceId)
            ?: return BillingResult(false, message = "فاکتور پیدا نشد")
        val customer: Customer = customers.byId(invoice.customerId)
            ?: return BillingResult(false, message = "مشتری پیدا نشد")

        val lines = invoices.lines(invoiceId)
        val body = SmsTemplate.render(settings, invoice, customer, lines, invoice.subscriptionUrl)
        val result = smsSender.send(
            settings = settings,
            to = customer.phone,
            body = body,
            invoiceId = invoiceId,
            customerId = customer.id
        )
        return BillingResult(
            ok = result.ok,
            invoiceId = invoiceId,
            invoiceNumber = invoice.number,
            totalRial = invoice.totalRial,
            smsSent = result.ok,
            message = if (result.ok) "پیامک ارسال شد" else "پیامک ارسال نشد: ${result.message}"
        )
    }

    /**
     * Retry failed/pending SMS for automatic invoices linked to this panel user.
     * The outbox's latest attempt enforces a cooldown and SENT entries are never resent.
     */
    suspend fun retryPendingInvoiceSms(panelUuid: String): List<BillingResult> {
        if (!settings.autoSmsOnInvoice || panelUuid.isBlank()) return emptyList()
        val results = mutableListOf<BillingResult>()
        for (invoice in invoices.autoIssuedForPanelUser(panelUuid)) {
            if (smsSender.invoiceSmsRetryDue(invoice.id)) {
                results += sendInvoiceSms(invoice.id)
            }
        }
        return results
    }

    /** متن پیامک برای پیش‌نمایش در UI */
    suspend fun previewSms(invoice: Invoice): String {
        val currentInvoice = invoices.reconcileOpeningBalanceSnapshot(invoice.id) ?: invoice
        val customer = customers.byId(currentInvoice.customerId) ?: return ""
        val lines = invoices.lines(currentInvoice.id)
        return SmsTemplate.render(settings, currentInvoice, customer, lines, currentInvoice.subscriptionUrl)
    }

    // ─────────────────────────────────────────────────────────── مشتری

    /**
     * پیدا کردن مشتری برای کاربر پنل؛ اگر نبود و اجازه داشته باشیم، مشتری می‌سازد.
     *
     * منابع شماره، به ترتیب اولویت:
     *  ۱) شناسهٔ پیوندی اپ در یادداشت پنل
     *  ۲) شمارهٔ یکتای مشتری در یادداشت پنل، سپس شمارهٔ داخل نام
     *  ۳) تطبیق نام کامل و یکتا؛ هرگز تطبیق بر اساس نام کوچک/پیشوند انجام نمی‌شود
     *
     * نام تکراری مبهم محسوب می‌شود و هیچ مشتری جدیدی به‌صورت خودکار ساخته نمی‌شود.
     */
    suspend fun resolveOrCreateCustomer(
        panelName: String,
        panelComment: String? = null,
        allowCreate: Boolean = settings.autoCreateCustomerFromPanel
    ): Customer? {
        val cleanName = sanitizePanelName(panelName)
        val phone = phoneFromComment(panelComment).ifBlank { Phone.extract(panelName) }
        val linkedId = customerIdFromComment(panelComment)

        // ۱) اگر خود اپ قبلاً این اکانت را ساخته و شناسه را در یادداشت گذاشته است
        if (linkedId > 0) {
            customers.byId(linkedId)?.let { found ->
                return withPhone(found, phone)
            }
        }
        // ۲) شمارهٔ داخل یادداشت پنل (سناریوی اصلی: کاربر شماره را در یادداشت می‌نویسد)
        if (phone.isNotBlank()) findCustomerByPhone(phone)?.let { return it }
        // ۳) فقط نام کامل و دقیق؛ اگر نام تکراری باشد هرگز حدس نمی‌زنیم.
        val nameMatches = exactNameMatches(cleanName)
        if (nameMatches.size == 1) {
            customers.byId(nameMatches.single())?.let { return withPhone(it, phone) }
        }
        if (nameMatches.size > 1) return null

        if (!allowCreate || cleanName.isBlank()) return null

        val id = customers.insert(
            Customer(
                name = cleanName.ifBlank { phone },
                phone = phone,
                note = "ایجاد خودکار از پنل هیدی‌فای"
            )
        )
        return customers.byId(id)
    }

    /** اگر مشتری شماره نداشت ولی در یادداشت پنل شماره بود، آن را ثبت می‌کند */
    /**
     * جست‌وجوی مشتری با شماره، مستقل از قالب ذخیره‌شده.
     *
     * چرا لازم است؟ شماره‌ای که کاربر قبلاً دستی ثبت کرده ممکن است «۰۹۱۲…»، «0912 123 4567»
     * یا «+98912…» باشد و مقایسهٔ متنی ساده آن را پیدا نکند؛ نتیجه‌اش ساخت مشتری تکراری و
     * تقسیم‌شدن بدهی بین دو پرونده است. اول جست‌وجوی مستقیم (سریع)، بعد مقایسهٔ نرمال‌شده.
     * جدول مشتری برای استفادهٔ شخصی کوچک است، پس این پیمایش هزینه‌ای ندارد.
     */
    suspend fun findCustomerByPhone(rawPhone: String): Customer? {
        val phone = Phone.normalize(rawPhone)
        if (phone.isBlank()) return null
        val matches = customers.all()
            .asSequence()
            .filter { !it.archived && Phone.normalize(it.phone) == phone }
            .toList()
        return matches.singleOrNull()
    }

    /**
     * یک‌بار در شروع برنامه: شماره‌های ذخیره‌شده را به قالب یکسان `09xxxxxxxxx` درمی‌آورد.
     * idempotent است؛ شماره‌های خالی/نامعتبر دست‌نخورده می‌مانند.
     */
    suspend fun normalizeStoredPhones(): Int {
        var changed = 0
        for (c in customers.all()) {
            if (c.phone.isBlank()) continue
            val normalized = Phone.normalize(c.phone)
            if (normalized.isNotBlank() && normalized != c.phone) {
                customers.update(c.copy(phone = normalized))
                changed++
            }
        }
        return changed
    }

    private suspend fun withPhone(customer: Customer, phone: String): Customer {
        if (phone.isBlank() || customer.phone == phone) return customer
        if (customer.phone.isNotBlank()) return customer
        val updated = customer.copy(phone = phone)
        customers.update(updated)
        return updated
    }

    /** شناسهٔ مشتری از کامنت `cmbtools:<id>:<...>` (نوشتهٔ خود این اپ) */
    fun customerIdFromComment(comment: String?): Long =
        PanelCustomerNames.customerIdFromComment(comment)

    /** شمارهٔ موبایل داخل یادداشت/کامنت کاربر پنل */
    fun phoneFromComment(comment: String?): String {
        if (comment.isNullOrBlank()) return ""
        return Phone.extract(PanelCustomerNames.commentBeforeLink(comment))
    }

    /** حذف شمارهٔ فاکتور و نام بسته از انتهای نام کاربر پنل (که خود اپ اضافه کرده بود) */
    fun sanitizePanelName(raw: String): String = PanelCustomerNames.cleanLegacyPanelName(raw)

    /** شناسهٔ یکتای مشتری با نام کامل؛ فهرست خالی یعنی نبودن یا ابهام در نام. */
    suspend fun exactNameMatches(panelName: String): List<Long> {
        val candidates = customers.all().filterNot { it.archived }.map { it.id to it.name }
        return PanelCustomerNames.exactMatchIds(panelName, candidates)
    }

    /** اولین شمارهٔ موبایل ایرانی داخل متن (سازگاری با کد قبلی) */
    fun extractPhone(text: String): String = Phone.extract(text)
}
