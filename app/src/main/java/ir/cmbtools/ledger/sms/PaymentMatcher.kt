package ir.cmbtools.ledger.sms

import ir.cmbtools.ledger.core.MatchStatus
import ir.cmbtools.ledger.core.PaymentMethod
import ir.cmbtools.ledger.data.BankSms
import ir.cmbtools.ledger.data.BankSmsDao
import ir.cmbtools.ledger.data.CustomerDao
import ir.cmbtools.ledger.data.InvoiceDao
import ir.cmbtools.ledger.data.PaymentRepository
import ir.cmbtools.ledger.core.Prefs
import java.security.MessageDigest
import java.time.LocalDate

/**
 * موتور تطبیق پرداخت.
 *
 * قواعد (به ترتیب):
 *  ۱) پیامک تکراری (hash یکسان) → نادیده
 *  ۲) پیامک غیرواریز / OTP / برگشتی → ذخیره با وضعیت IGNORED
 *  ۳) اگر مبلغ با ماندهٔ یک فاکتور باز + ۴ رقم کارت همان مشتری یکی بود → تطبیق خودکار
 *  ۴) اگر شمارهٔ کارت مشتری ثبت شده و مبلغ با یکی از فاکتورهایش برابر بود → تطبیق خودکار
 *  ۵) اگر فقط یک فاکتور با همان مبلغ در کل سیستم بود → پیشنهاد (نه خودکار)
 *  ۶) در غیر این صورت → صف «در انتظار تطبیق» برای تأیید دستی
 *
 *  مبالغ بالاتر از آستانهٔ تنظیمات، هرگز خودکار تطبیق نمی‌شوند.
 */
class PaymentMatcher(
    private val bankSmsDao: BankSmsDao,
    private val customerDao: CustomerDao,
    private val invoiceDao: InvoiceDao,
    private val payments: PaymentRepository,
    private val prefs: Prefs
) {

    sealed class Result {
        data class AutoMatched(val customerName: String, val invoiceNumber: String, val amountRial: Long) : Result()
        data class NeedsReview(val reason: String, val amountRial: Long) : Result()
        data class Ignored(val reason: String) : Result()
        data object Duplicate : Result()
    }

    /** Reparse only unpaid/unmatched historical SMS after parser improvements; settled payments remain untouched. */
    suspend fun refreshUnmatchedSmsParsing() {
        bankSmsDao.unmatchedForReparse().forEach { sms ->
            val parsed = BankSmsParser.parse(sms.sender, sms.body, sms.receivedAtMillis)
            bankSmsDao.refreshParsedFields(
                id = sms.id,
                amountRial = parsed.amountRial,
                last4 = parsed.last4,
                balanceRial = parsed.balanceRial,
                bank = parsed.bank,
                isCredit = parsed.isCredit
            )
        }
    }

    suspend fun handle(sender: String, body: String, receivedAtMillis: Long): Result {
        val hash = sha256("$sender|$body|${receivedAtMillis / 60_000}")
        if (bankSmsDao.byHash(hash) != null) return Result.Duplicate

        val parsed = BankSmsParser.parse(sender, body, receivedAtMillis)
        val settings = prefs.current

        val base = BankSms(
            sender = sender,
            body = body,
            receivedAtMillis = receivedAtMillis,
            amountRial = parsed.amountRial,
            last4 = parsed.last4,
            balanceRial = parsed.balanceRial,
            bank = parsed.bank,
            isCredit = parsed.isCredit,
            rawHash = hash
        )

        if (!parsed.isCredit || parsed.isOtp || parsed.isReversal || parsed.amountRial <= 0) {
            val id = bankSmsDao.insert(base.copy(matchStatus = MatchStatus.IGNORED, note = parsed.reason))
            return Result.Ignored(parsed.reason.ifBlank { "پیامک واریز نیست" })
        }

        if (parsed.amountUnit != "RIAL" && parsed.amountUnit != "TOMAN") {
            val reason = "واحد ریال/تومان در پیامک مشخص نیست؛ تطبیق خودکار امن نیست"
            bankSmsDao.insert(base.copy(note = reason))
            return Result.NeedsReview(reason, parsed.amountRial)
        }

        if (!settings.autoMatchBankSms) {
            bankSmsDao.insert(base.copy(note = "تطبیق خودکار خاموش است"))
            return Result.NeedsReview("تطبیق خودکار خاموش است", parsed.amountRial)
        }

        val amount = parsed.amountRial
        val tooLarge = amount > settings.autoMatchMaxRial
        val epochDay = LocalDate.now().toEpochDay()

        if (!tooLarge) {
            // قاعدهٔ ۳/۴: تطبیق قطعی بر اساس ۴ رقم کارت یا فاکتور همان مبلغ برای همان مشتری
            if (parsed.last4.isNotBlank()) {
                val customer = findCustomerByLast4(parsed.last4)
                if (customer != null) {
                    val open = invoiceDao.openInvoicesOldestFirst(customer.id)
                        .filter { it.remainingRial == amount }
                    if (open.size == 1) {
                        return settle(base, hash, customer.id, open.first().id, MatchStatus.AUTO, epochDay, parsed)
                    }
                }
            }
            val allOpen = invoiceDao.allOpen().filter { it.remainingRial == amount }
            if (allOpen.size == 1) {
                val inv = allOpen.first()
                val customer = customerDao.byId(inv.customerId)
                if (customer != null && (parsed.last4.isBlank() || customer.cardLast4.isBlank())) {
                    // بدون شاهد کارت، خودکار نکنیم؛ فقط صف بازبینی
                    bankSmsDao.insert(base.copy(note = "پیشنهاد: ${customer.name} / ${inv.number}"))
                    return Result.NeedsReview("یک فاکتور با این مبلغ پیدا شد؛ نیازمند تأیید", amount)
                }
            }
        }

        bankSmsDao.insert(base.copy(note = if (tooLarge) "مبلغ بالاتر از آستانهٔ تطبیق خودکار" else ""))
        return Result.NeedsReview(
            if (tooLarge) "مبلغ بالای آستانه؛ تأیید دستی لازم است" else "فاکتور منطبقی پیدا نشد",
            amount
        )
    }

    /** تأیید دستی: کاربر در UI مشتری/فاکتور را انتخاب می‌کند */
    suspend fun confirmManually(
        smsId: Long,
        customerId: Long,
        invoiceId: Long = 0,
        note: String = ""
    ): Long {
        val sms = bankSmsDao.byId(smsId) ?: error("پیامک پیدا نشد")
        val payment = payments.record(
            customerId = customerId,
            amountRial = sms.amountRial,
            method = PaymentMethod.BANK_SMS,
            refNo = sms.id.toString(),
            note = note.ifBlank { "تطبیق دستی پیامک ${sms.bank}" },
            bankSmsId = sms.id,
            preferredInvoiceId = invoiceId
        )
        bankSmsDao.markMatched(smsId, MatchStatus.MANUAL, payment.paymentId, note)
        return payment.paymentId
    }

    private suspend fun settle(
        base: BankSms,
        hash: String,
        customerId: Long,
        invoiceId: Long,
        status: String,
        epochDay: Long,
        parsed: ParsedBankSms
    ): Result {
        val smsId = bankSmsDao.insert(base)
        if (smsId <= 0) return Result.Duplicate
        val payment = payments.record(
            customerId = customerId,
            amountRial = parsed.amountRial,
            method = PaymentMethod.BANK_SMS,
            refNo = parsed.refNo.ifBlank { hash.take(8) },
            note = "${parsed.bank} - تطبیق خودکار",
            bankSmsId = smsId,
            preferredInvoiceId = invoiceId
        )
        bankSmsDao.markMatched(smsId, status, payment.paymentId, "تطبیق خودکار")
        val customer = customerDao.byId(customerId)
        val invoice = invoiceDao.byId(invoiceId)
        return Result.AutoMatched(
            customerName = customer?.name ?: "",
            invoiceNumber = invoice?.number ?: "",
            amountRial = parsed.amountRial
        )
    }

    private suspend fun findCustomerByLast4(last4: String) =
        customerDao.byLast4(last4)

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
