package ir.cmbtools.ledger.sms

import ir.cmbtools.ledger.core.AppSettings
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.data.Customer
import ir.cmbtools.ledger.data.Invoice
import ir.cmbtools.ledger.data.InvoiceLine

/**
 * قالب فاکتور متنی + شمارش بخش‌های پیامک.
 * پیامک فارسی UCS-2 است: ۷۰ کاراکتر در بخش اول (با هدر ۶۷) و ۶۷ در بخش‌های بعدی.
 */
object SmsTemplate {

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
        val remaining = invoice.remainingRial
        val amountText = if (invoice.status == InvoiceStatus.PARTIAL || remaining < invoice.totalRial) {
            val paid = Money.format(invoice.paidRial)
            val rem = Money.format(remaining)
            "${Money.format(invoice.totalRial)} (پرداخت‌شده $paid - مانده $rem)"
        } else {
            Money.format(invoice.totalRial)
        }

        val link = if (subscriptionUrl.isBlank()) "" else
            "لینک اشتراک:\n$subscriptionUrl"

        return settings.invoiceTemplate
            .replace("{business}", settings.businessName)
            .replace("{invoiceNo}", invoice.number)
            .replace("{customer}", customer.name)
            .replace("{total}", amountText)
            .replace("{items}", items)
            .replace("{date}", Jalali.formatEpochDay(invoice.issuedAtEpochDay))
            .replace("{link}", link)
            .replace("{footer}", settings.invoiceFooter)
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** یادآوری بدهی */
    fun renderReminder(settings: AppSettings, customer: Customer, balanceRial: Long, dueCount: Int): String =
        "${settings.businessName}\n" +
            "${customer.name} عزیز، ماندهٔ حساب شما ${Money.format(balanceRial)} است" +
            (if (dueCount > 0) " (${Money.toPersian(dueCount.toString())} فاکتور پرداخت‌نشده)." else ".") +
            "\nبا تشکر"

    /** خوش‌آمد/ارسال اکانت بدون فاکتور */
    fun renderAccount(settings: AppSettings, customer: Customer, subscriptionUrl: String): String =
        "${settings.businessName}\n${customer.name} عزیز، اکانت شما فعال شد:\n$subscriptionUrl"

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
