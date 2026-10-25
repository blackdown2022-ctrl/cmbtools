package ir.cmbtools.ledger.billing

import ir.cmbtools.ledger.core.AppSettings
import ir.cmbtools.ledger.core.InvoiceKind
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.Phone
import ir.cmbtools.ledger.core.PanelSyncRules
import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.Customer
import ir.cmbtools.ledger.data.CustomerDao
import ir.cmbtools.ledger.data.Invoice
import ir.cmbtools.ledger.data.InvoiceRepository
import ir.cmbtools.ledger.data.NewInvoiceLine
import ir.cmbtools.ledger.data.Plan
import ir.cmbtools.ledger.data.PlanDao
import ir.cmbtools.ledger.sms.SmsSender
import ir.cmbtools.ledger.sms.SmsTemplate

/** نتیجهٔ یک عملیات صورت‌حساب خودکار */
data class BillingResult(
    val ok: Boolean,
    val invoiceId: Long = 0,
    val invoiceNumber: String = "",
    val totalRial: Long = 0,
    val smsSent: Boolean = false,
    val message: String = ""
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
    ): BillingResult = createAndNotify(
        customerId = customerId,
        panelUuid = panelUuid,
        subscriptionUrl = subscriptionUrl,
        gb = gb,
        days = days,
        packageName = packageName,
        priceRial = priceRial,
        planId = planId,
        kind = InvoiceKind.RENEW,
        note = note.ifBlank { "تمدید" },
        sendSms = sendSms
    )

    private suspend fun createAndNotify(
        customerId: Long,
        panelUuid: String,
        subscriptionUrl: String,
        gb: Double,
        days: Int,
        packageName: String,
        priceRial: Long,
        planId: Long,
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
        val invoice: Invoice = invoices.get(invoiceId)
            ?: return BillingResult(false, message = "فاکتور پیدا نشد")
        val customer: Customer = customers.byId(invoice.customerId)
            ?: return BillingResult(false, message = "مشتری پیدا نشد")
        if (customer.phone.isBlank()) return BillingResult(false, message = "شمارهٔ مشتری ثبت نشده")

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

    /** متن پیامک برای پیش‌نمایش در UI */
    suspend fun previewSms(invoice: Invoice): String {
        val customer = customers.byId(invoice.customerId) ?: return ""
        val lines = invoices.lines(invoice.id)
        return SmsTemplate.render(settings, invoice, customer, lines, invoice.subscriptionUrl)
    }

    // ─────────────────────────────────────────────────────────── مشتری

    /**
     * پیدا کردن مشتری برای کاربر پنل؛ اگر نبود و اجازه داشته باشیم، مشتری می‌سازد.
     *
     * منابع شماره، به ترتیب اولویت:
     *  ۱) **یادداشت/کامنت کاربر در پنل** — چون کاربر شمارهٔ مشتری را همان‌جا می‌نویسد
     *  ۲) شماره‌ای که داخل نام کاربر پنل باشد
     *
     * اگر مشتریِ موجود شماره نداشت و در یادداشت پنل شماره بود، شماره به پروفایلش اضافه می‌شود.
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
        // ۳) نام دقیق
        customers.byName(cleanName)?.let { return withPhone(it, phone) }
        // ۴) اولین کلمهٔ نام
        val firstToken = cleanName.substringBefore(" ").trim()
        if (firstToken.length >= 3) {
            customers.byName(firstToken)?.let { return withPhone(it, phone) }
        }

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
        customers.byPhone(phone)?.let { return it }
        return customers.all()
            .asSequence()
            .filter { !it.archived }
            .firstOrNull { Phone.normalize(it.phone) == phone }
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
    fun customerIdFromComment(comment: String?): Long {
        if (comment == null) return 0
        val trimmed = comment.trim()
        if (!trimmed.startsWith("cmbtools:") && !trimmed.startsWith("cmbtools|")) return 0
        val rest = trimmed.removePrefix("cmbtools:").removePrefix("cmbtools|")
        return rest.substringBefore(":").substringBefore("|").trim().toLongOrNull()?.takeIf { it > 0 } ?: 0
    }

    /** شمارهٔ موبایل داخل یادداشت/کامنت کاربر پنل */
    fun phoneFromComment(comment: String?): String {
        if (comment.isNullOrBlank()) return ""
        if (customerIdFromComment(comment) > 0 && Phone.extract(comment).isBlank()) return ""
        return Phone.extract(comment)
    }

    /** حذف شمارهٔ فاکتور و نام بسته از انتهای نام کاربر پنل (که خود اپ اضافه کرده بود) */
    fun sanitizePanelName(raw: String): String {
        var name = raw.trim()
        name = name.replace(Regex("""\s\d{4}-\d{4}$"""), "")          // 1405-0001
        name = name.replace(Regex("""\s-\s[^-]{1,40}$"""), "")        // - یک‌ماهه ۳۰ گیگ
        return name.trim()
    }

    /** اولین شمارهٔ موبایل ایرانی داخل متن (سازگاری با کد قبلی) */
    fun extractPhone(text: String): String = Phone.extract(text)
}
