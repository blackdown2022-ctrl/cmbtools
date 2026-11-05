package ir.cmbtools.ledger.sms

import ir.cmbtools.ledger.billing.AutoBilling
import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.PanelDao
import ir.cmbtools.ledger.panel.PanelService
import kotlin.math.abs

/** Compatibility wrapper around the strict four-line subscription command parser. */
data class RenewalSmsCommand(val customerName: String, val gb: Double, val discountRial: Long)

object RenewalSmsCommandParser {
    fun parse(body: String): RenewalSmsCommand? = SubscriptionSmsCommandParser.renewal(body)?.let {
        RenewalSmsCommand(it.customerName, it.gb, it.discountRial)
    }
}

/**
 * Handles commands only from a user-configured exact sender address. It never treats an SMS as
 * payment evidence; a successful command only renews the panel account and issues an invoice.
 */
class RenewalSmsCommandProcessor(
    private val prefs: Prefs,
    private val panelDao: PanelDao,
    private val panelService: PanelService,
    private val billing: AutoBilling
) {
    data class Result(val success: Boolean, val message: String)

    suspend fun handle(sender: String, body: String): Result? {
        val allowedSender = normalizeSender(prefs.current.renewalCommandSender)
        if (allowedSender.isBlank() || normalizeSender(sender) != allowedSender) return null
        val command = RenewalSmsCommandParser.parse(body) ?: return null

        val customerIds = billing.exactNameMatches(command.customerName)
        if (customerIds.size != 1) {
            return Result(false, if (customerIds.isEmpty()) {
                "فرمان تمدید رد شد: مشتری با همین نام دقیق پیدا نشد"
            } else {
                "فرمان تمدید رد شد: نام مشتری تکراری است"
            })
        }

        val accounts = panelDao.accountsOf(customerIds.single())
        val account = when {
            accounts.size == 1 -> accounts.single()
            else -> accounts.filter { abs(it.gb - command.gb) < 0.001 }.singleOrNull()
        } ?: return Result(
            false,
            if (accounts.isEmpty()) "فرمان تمدید رد شد: برای این مشتری اکانت پنل پیدا نشد"
            else "فرمان تمدید رد شد: بیش از یک اکانت ممکن است؛ حجم را با اکانت یکتا تطبیق دهید"
        )
        if (account.days <= 0) return Result(false, "فرمان تمدید رد شد: مدت بستهٔ فعلی اکانت مشخص نیست")

        val price = billing.priceFor(command.gb, account.days)
            ?: return Result(false, "فرمان تمدید رد شد: برای ${command.gb} گیگ / ${account.days} روز قیمت تعریف نشده است")
        if (price.priceRial <= 0L) {
            return Result(false, "فرمان تمدید رد شد: قیمت بستهٔ انتخاب‌شده معتبر نیست")
        }
        if (command.discountRial > price.priceRial) {
            return Result(false, "فرمان تمدید رد شد: تخفیف از قیمت بسته بیشتر است")
        }

        return try {
            val provision = panelService.renew(account.panelUserUuid, command.gb, account.days)
            val adminReport = billing.sendRenewalReport(
                customerId = account.customerId,
                packageName = price.name,
                gb = command.gb,
                days = account.days,
                customerNameFallback = command.customerName
            )
            val invoice = billing.invoiceRenewal(
                customerId = account.customerId,
                panelUuid = account.panelUserUuid,
                subscriptionUrl = provision.subscriptionUrl,
                gb = command.gb,
                days = account.days,
                packageName = price.name,
                priceRial = price.priceRial,
                planId = price.planId,
                discountRial = command.discountRial,
                note = "تمدید با پیامک فرمانی"
            )
            if (!invoice.ok) {
                Result(
                    false,
                    "اکانت تمدید شد، اما فاکتور صادر نشد: ${invoice.message}" +
                        (if (adminReport != null && !adminReport.ok) " | پیامک گزارش مدیر ارسال نشد: ${adminReport.message}" else "")
                )
            } else {
                panelService.markRenewalInvoiced(account.panelUserUuid)
                Result(
                    true,
                    buildString {
                        append("تمدید انجام شد: ${command.customerName}، ${command.gb} گیگ / ${account.days} روز، فاکتور ${invoice.invoiceNumber}")
                        if (command.discountRial > 0L) {
                            append("، تخفیف ${ir.cmbtools.ledger.core.Money.format(command.discountRial)}")
                        }
                        if (adminReport != null && !adminReport.ok) {
                            append(" | پیامک گزارش مدیر ارسال نشد: ").append(adminReport.message)
                        }
                    }
                )
            }
        } catch (error: Exception) {
            Result(false, "تمدید انجام نشد: ${error.message ?: "خطای پنل یا فاکتور"}")
        }
    }

    private fun normalizeSender(value: String): String {
        val mobile = SmsSender.normalizePhone(value)
        if (SmsSender.isValidIranMobile(mobile)) return mobile
        return buildString(value.length) {
            value.forEach { char ->
                val latinDigit = when (char) {
                    in '۰'..'۹' -> ('0'.code + (char.code - '۰'.code)).toChar()
                    in '٠'..'٩' -> ('0'.code + (char.code - '٠'.code)).toChar()
                    else -> char
                }
                if (latinDigit.isLetterOrDigit()) append(latinDigit.lowercaseChar())
            }
        }
    }
}
