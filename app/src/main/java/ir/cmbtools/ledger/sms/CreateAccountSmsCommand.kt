package ir.cmbtools.ledger.sms

import ir.cmbtools.ledger.billing.AutoBilling
import ir.cmbtools.ledger.core.Phone
import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.Customer
import ir.cmbtools.ledger.data.CustomerDao
import ir.cmbtools.ledger.data.InvoiceRepository
import ir.cmbtools.ledger.panel.PanelService

internal sealed interface SmsCustomerResolution {
    data class Existing(val customer: Customer) : SmsCustomerResolution
    data object Create : SmsCustomerResolution
    data class Reject(val reason: String) : SmsCustomerResolution
}

/** Resolve SMS customer identity without ever assigning a phone already owned by another record. */
internal fun resolveSmsCustomer(
    namedCustomers: List<Customer>,
    allCustomers: List<Customer>,
    normalizedPhone: String
): SmsCustomerResolution {
    val phoneOwners = allCustomers.filter { Phone.normalize(it.phone) == normalizedPhone }
    if (phoneOwners.size > 1) {
        return SmsCustomerResolution.Reject("این شماره برای بیش از یک مشتری ثبت شده است؛ ابتدا اطلاعات مشتریان را اصلاح کنید")
    }
    val phoneOwner = phoneOwners.singleOrNull()
    if (phoneOwner != null) {
        if (phoneOwner.archived) {
            return SmsCustomerResolution.Reject("این شماره متعلق به مشتری بایگانی‌شده است؛ ابتدا مشتری را از بایگانی خارج کنید")
        }
        if (namedCustomers.any { it.id == phoneOwner.id }) {
            return SmsCustomerResolution.Existing(phoneOwner)
        }
        val reason = if (namedCustomers.isNotEmpty()) {
            "شماره با شمارهٔ ثبت‌شدهٔ این مشتری تطبیق ندارد"
        } else {
            "این شماره قبلاً برای مشتری دیگری ثبت شده است"
        }
        return SmsCustomerResolution.Reject(reason)
    }
    if (namedCustomers.isNotEmpty()) {
        return SmsCustomerResolution.Reject("شماره با شمارهٔ ثبت‌شدهٔ این مشتری تطبیق ندارد")
    }
    return SmsCustomerResolution.Create
}

/** Creates one panel account and its discounted invoice from the authorized admin SMS command. */
class CreateAccountSmsCommandProcessor(
    private val prefs: Prefs,
    private val customers: CustomerDao,
    private val invoices: InvoiceRepository,
    private val panelService: PanelService,
    private val billing: AutoBilling
) {
    data class Result(val success: Boolean, val message: String)

    suspend fun handle(body: String): Result? {
        val command = SubscriptionSmsCommandParser.create(body) ?: return null
        val nameMatches = billing.exactNameMatches(command.customerName)
        val resolution = resolveSmsCustomer(
            namedCustomers = nameMatches.mapNotNull { customers.byId(it) },
            allCustomers = customers.all(),
            normalizedPhone = command.phone
        )
        if (resolution is SmsCustomerResolution.Reject) {
            return Result(false, "فرمان ایجاد رد شد: ${resolution.reason}")
        }
        val existingCustomer = (resolution as? SmsCustomerResolution.Existing)?.customer
        val createCustomer = resolution is SmsCustomerResolution.Create

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

        val customerId = existingCustomer?.id ?: try {
            customers.insert(
                Customer(
                    name = command.customerName.trim(),
                    phone = command.phone,
                    note = "ثبت‌شده از فرمان پیامکی ایجاد"
                )
            )
        } catch (error: Exception) {
            return Result(false, "فرمان ایجاد رد شد: ثبت مشتری جدید ناموفق بود: ${error.message ?: "خطای پایگاه داده"}")
        }
        if (customerId <= 0L) {
            return Result(false, "فرمان ایجاد رد شد: شناسهٔ مشتری جدید معتبر نیست")
        }
        val customerCreated = existingCustomer == null

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
        if (!invoice.ok) {
            val prefix = if (customerCreated) "مشتری جدید ثبت شد، اما " else ""
            return Result(false, "${prefix}فاکتور صادر نشد: ${invoice.message}")
        }

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
                    if (customerCreated) append("مشتری جدید ثبت شد؛ ")
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
