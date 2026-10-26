package ir.cmbtools.ledger.sms

import ir.cmbtools.ledger.core.AppSettings
import ir.cmbtools.ledger.core.InvoiceKind
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.data.Customer
import ir.cmbtools.ledger.data.Invoice
import ir.cmbtools.ledger.data.InvoiceLine

/**
 * قالب فاکتور متنی + شمارش بخش‌های پیامک.
 * پیامک فارسی UCS-2 است: ۷۰ کاراکتر در بخش اول (با هدر ۶۷) و ۶۷ در بخش‌های بعدی.
 *
 * قانون متن پیامک فاکتور (خواستهٔ صریح):
 *  • نام فروشگاه در پیامک نمی‌آید.
 *  • لینک اشتراک در پیامک نمی‌آید.
 *  • خط اول فقط نام مشتری است.
 */
object SmsTemplate {

    // پارامتر `subscriptionUrl` برای سازگاری با فراخوانی‌های موجود نگه داشته شده است،
    // ولی طبق خواستهٔ کاربر دیگر در متن پیامک فاکتور درج نمی‌شود.
    @Suppress("UNUSED_PARAMETER")
    fun render(
        settings: AppSettings,
        invoice: Invoice,
        customer: Customer,
        lines: List<InvoiceLine>,
        subscriptionUrl: String
    ): String {
        val items = lines.joinToString("، ") { line ->
            val qty = if (line.qty > 1) "×${Money.toPersian(line.qty.toString())} " else ""
            "${line.description} $qty"
        }

        // ── مبلغ و مانده ─────────────────────────────────────────────────────
        val remaining = invoice.remainingRial
        val amountText = if (invoice.status == InvoiceStatus.PARTIAL || remaining < invoice.totalRial) {
            val paid = Money.format(invoice.paidRial)
            val rem = Money.format(remaining)
            "${Money.format(invoice.totalRial)} (پرداخت‌شده $paid - مانده $rem)"
        } else {
            Money.format(invoice.totalRial)
        }

        // ماندهٔ قبلی از عکس فوری فاکتور خوانده می‌شود (نه از وضعیت امروز مشتری)،
        // تا اگر بعداً پرداخت ثبت شود، متن همان فاکتور عوض نشود.
        val prevBalance = invoice.prevBalanceRial
        val totalDebt = if (invoice.balanceAfterRial != 0L) invoice.balanceAfterRial else prevBalance + invoice.totalRial
        val hasPrevDebt = prevBalance > 0
        val debtBlock = if (hasPrevDebt) {
            buildString {
                append("مانده قبلی: ").append(Money.format(prevBalance)).append('\n')
                append("جمع کل بدهی: ").append(Money.format(totalDebt)).append('\n')
            }
        } else {
            ""
        }

        val packageName = invoice.packageName.ifBlank {
            lines.joinToString("، ") { it.description }
        }
        val card = invoice.cardNumber.ifBlank { settings.paymentCardNumber }
        val cardLine = if (card.isBlank()) "" else card

        return settings.invoiceTemplate
            // نام فروشگاه عمداً درج نمی‌شود (خواستهٔ کاربر: پیامک با نام مشتری شروع شود).
            // متغیر برای سازگاری با قالب‌های قدیمی نگه داشته شده ولی خالی جایگزین می‌شود.
            .replace("{business}", "")
            .replace("{invoiceNo}", Money.toPersian(invoice.number))
            .replace("{customer}", customer.name)
            .replace("{total}", amountText)
            .replace("{items}", items)
            .replace("{package}", packageName)
            .replace("{kind}", InvoiceKind.label(invoice.kind))
            .replace("{date}", Jalali.formatEpochDay(invoice.issuedAtEpochDay))
            .replace("{datetime}", formatDateTime(invoice))
            .replace("{prevBalance}", if (hasPrevDebt) Money.format(prevBalance) else "۰ ریال")
            .replace("{totalDebt}", Money.format(totalDebt.coerceAtLeast(0)))
            .replace("{debt}", debtBlock)
            .replace("{card}", cardLine)
            // لینک اشتراک هم در پیامک فاکتور نمی‌آید (فقط در بخش اکانت/کپی لینک)
            .replace("{link}", "")
            .replace("{footer}", settings.invoiceFooter)
            .let { cleanup(it) }
    }

    /**
     * پیش‌نمایش با دادهٔ نمونه — برای دیدن دقیق متن در تنظیمات، قبل از ذخیره.
     * همان مسیر [render] را طی می‌کند تا هر تغییری در قالب، این‌جا هم دیده شود.
     */
    fun preview(
        settings: AppSettings,
        template: String = settings.invoiceTemplate,
        footer: String = settings.invoiceFooter
    ): String {
        val sample = Invoice(
            number = "1405-0042",
            customerId = 0,
            issuedAtEpochDay = java.time.LocalDate.now().toEpochDay(),
            subtotalRial = 1_500_000,
            totalRial = 1_500_000,
            packageName = "یک‌ماهه ۳۰ گیگ",
            cardNumber = settings.paymentCardNumber.ifBlank { "6037-9977-1234-5678" },
            kind = InvoiceKind.NEW
        )
        val customer = Customer(id = 0, name = "علی رضایی", phone = "09120000000")
        return render(
            settings.copy(invoiceTemplate = template, invoiceFooter = footer),
            sample,
            customer,
            emptyList(),
            ""
        )
    }

    /**
     * تمیزکاری متن نهایی:
     *  - خطوطی که فقط یک برچسب هستند و مقدارشان خالی بوده (مثل «کارت:») حذف می‌شوند
     *  - فاصله‌های انتهای خط و خطوط خالی تکراری جمع می‌شوند
     */
    private fun cleanup(text: String): String =
        text.lines()
            .map { it.trimEnd() }
            .filterNot { line -> line.length in 1..40 && line.endsWith(":") }
            .joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    /** «تاریخ و ساعت» فاکتور: از زمان دقیق صدور، و اگر نبود از روز صدور */
    fun formatDateTime(invoice: Invoice): String =
        if (invoice.issuedAtMillis > 0) {
            Jalali.formatDateTime(invoice.issuedAtMillis)
        } else {
            Jalali.formatEpochDay(invoice.issuedAtEpochDay)
        }

    /** یادآوری بدهی — مثل پیامک فاکتور با نام مشتری شروع می‌شود (بدون نام فروشگاه) */
    fun renderReminder(settings: AppSettings, customer: Customer, balanceRial: Long, dueCount: Int): String =
        "${customer.name} عزیز، ماندهٔ حساب شما ${Money.format(balanceRial)} است" +
            (if (dueCount > 0) " (${Money.toPersian(dueCount.toString())} فاکتور پرداخت‌نشده)." else ".") +
            "\nبا تشکر"

    /** خوش‌آمد/ارسال اکانت بدون فاکتور — این پیامک فقط برای رساندن لینک است */
    fun renderAccount(settings: AppSettings, customer: Customer, subscriptionUrl: String): String =
        "${customer.name} عزیز، اکانت شما فعال شد:\n$subscriptionUrl"

    fun isPersianUcs2(body: String): Boolean = body.any { it.code > 127 }

    fun charLimit(body: String): Int = if (isPersianUcs2(body)) 70 else 160

    fun parts(body: String): Int {
        val limit = charLimit(body)
        if (body.isEmpty()) return 1
        if (body.length <= limit) return 1
        val perPart = if (isPersianUcs2(body)) 67 else 153
        return (body.length + perPart - 1) / perPart
    }

    fun previewCost(body: String, pricePerPartRial: Long): Long = parts(body) * pricePerPartRial

    fun truncateToParts(body: String, maxParts: Int): String {
        val limit = charLimit(body)
        if (parts(body) <= maxParts) return body
        val perPart = if (isPersianUcs2(body)) 67 else 153
        val max = limit + (maxParts - 1) * perPart - 3
        return body.take(max).trimEnd() + "..."
    }
}
