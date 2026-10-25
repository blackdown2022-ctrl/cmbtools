package ir.cmbtools.ledger.billing

import ir.cmbtools.ledger.core.AppSettings
import ir.cmbtools.ledger.core.InvoiceKind
import ir.cmbtools.ledger.core.Money
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
     * نام کاربر پنل ممکن است شمارهٔ موبایل داشته باشد (۰۹xxxxxxxxx) که استخراج می‌شود.
     */
    suspend fun resolveOrCreateCustomer(
        panelName: String,
        allowCreate: Boolean = settings.autoCreateCustomerFromPanel
    ): Customer? {
        val cleanName = sanitizePanelName(panelName)
        if (cleanName.isBlank()) return null

        customers.byName(cleanName)?.let { return it }
        val phone = extractPhone(panelName)

        if (!allowCreate) {
            // حتی اگر اجازهٔ ساخت نداریم، شاید همان شماره قبلاً ثبت شده باشد
            if (phone.isNotBlank()) customers.byPhone(phone)?.let { return it }
            return null
        }

        val existingByPhone = if (phone.isNotBlank()) customers.byPhone(phone) else null
        if (existingByPhone != null) return existingByPhone

        val id = customers.insert(
            Customer(
                name = cleanName,
                phone = phone,
                note = "ایجاد خودکار از پنل"
            )
        )
        return customers.byId(id)
    }

    /** حذف شمارهٔ فاکتور و نام بسته از انتهای نام کاربر پنل (که خود اپ اضافه کرده بود) */
    fun sanitizePanelName(raw: String): String {
        var name = raw.trim()
        name = name.replace(Regex("""\s\d{4}-\d{4}$"""), "")          // 1405-0001
        name = name.replace(Regex("""\s-\s[^-]{1,40}$"""), "")        // - یک‌ماهه ۳۰ گیگ
        return name.trim()
    }

    /** اولین شمارهٔ موبایل ایرانی داخل متن */
    fun extractPhone(text: String): String {
        val normalized = Money.toLatinDigits(text)
            .replace("-", "").replace(" ", "").replace("(", "").replace(")", "")
        Regex("""(?:\+98|0098|98|0)?9\d{9}""").find(normalized)?.let { m ->
            val digits = m.value.takeLast(10)
            return "0$digits"
        }
        return ""
    }
}
