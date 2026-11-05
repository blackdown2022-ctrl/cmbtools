package ir.cmbtools.ledger.sms

import ir.cmbtools.ledger.billing.AutoBilling
import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.InvoiceRepository
import ir.cmbtools.ledger.panel.PanelService

/** Creates one panel account and its discounted invoice from the authorized admin SMS command. */
class CreateAccountSmsCommandProcessor(
    private val prefs: Prefs,
    private val invoices: InvoiceRepository,
    private val panelService: PanelService,
    private val billing: AutoBilling
) {
    data class Result(val success: Boolean, val message: String)

    suspend fun handle(body: String): Result? {
        val command = SubscriptionSmsCommandParser.create(body) ?: return null
        val customerIds = billing.exactNameMatches(command.customerName)
        if (customerIds.size != 1) {
            return Result(
                false,
                if (customerIds.isEmpty()) "فرمان ایجاد رد شد: مشتری با این نام دقیق پیدا نشد"
                else "فرمان ایجاد رد شد: نام مشتری تکراری است"
            )
        }
        val customerId = customerIds.single()
        val plan = billing.priceForVolume(command.gb)
            ?: return Result(
                false,
                "فرمان ایجاد رد شد: حجم باید دقیقاً با یک بستهٔ فعال در تنظیمات تطبیق داشته باشد"
            )
        if (plan.days <= 0 || plan.priceRial <= 0L) {
            return Result(false, "فرمان ایجاد رد شد: مدت یا قیمت بستهٔ انتخاب‌شده معتبر نیست")
        }
        if (command.discountRial > plan.priceRial) {
            return Result(false, "فرمان ایجاد رد شد: تخفیف از قیمت بسته بیشتر است")
        }
        if (!panelService.isConfigured()) {
            return Result(false, "فرمان ایجاد رد شد: اتصال پنل در تنظیمات کامل نیست؛ فاکتوری ساخته نشد")
        }

        val invoice = billing.invoiceNewAccount(
            customerId = customerId,
            panelUuid = "",
            subscriptionUrl = "",
            gb = plan.gb,
            days = plan.days,
            packageName = plan.name,
            priceRial = plan.priceRial,
            planId = plan.planId,
            discountRial = command.discountRial,
            sendSms = false,
            note = "ایجاد با فرمان پیامکی"
        )
        if (!invoice.ok) return Result(false, "فرمان ایجاد رد شد: ${invoice.message}")

        val provision = try {
            panelService.createAccount(customerId, plan.gb, plan.days)
        } catch (error: Exception) {
            return Result(
                false,
                "فاکتور ${invoice.invoiceNumber} صادر شد، اما ساخت اکانت ناموفق بود: ${error.message ?: "خطای پنل"}"
            )
        }

        val adminReport = billing.sendNewAccountReport(
            customerId = customerId,
            packageName = plan.name,
            gb = plan.gb,
            days = plan.days,
            customerNameFallback = command.customerName
        )

        return try {
            invoices.setPanelInfo(invoice.invoiceId, provision.uuid, 1, provision.subscriptionUrl)
            val customerSmsMessage = if (prefs.current.autoSmsOnInvoice) {
                val sent = billing.sendInvoiceSms(invoice.invoiceId)
                if (sent.ok) "پیامک فاکتور مشتری ارسال شد" else "پیامک فاکتور مشتری ارسال نشد: ${sent.message}"
            } else ""
            Result(
                true,
                buildString {
                    append("اکانت ساخته شد و فاکتور ${invoice.invoiceNumber} صادر شد")
                    if (command.discountRial > 0L) {
                        append("؛ تخفیف ${ir.cmbtools.ledger.core.Money.format(command.discountRial)} ریال")
                    }
                    if (adminReport != null && !adminReport.ok) {
                        append("؛ پیامک گزارش مدیر ارسال نشد: ").append(adminReport.message)
                    }
                    if (customerSmsMessage.isNotBlank()) append("؛ ").append(customerSmsMessage)
                }
            )
        } catch (error: Exception) {
            Result(
                false,
                "اکانت ساخته شد، اما اتصال فاکتور ${invoice.invoiceNumber} به اکانت کامل نشد: ${error.message ?: "خطای داخلی"}"
            )
        }
    }
}
