package ir.cmbtools.ledger.sms

import androidx.room.withTransaction
import ir.cmbtools.ledger.core.MatchStatus
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.PaymentMethod
import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.AppDatabase
import ir.cmbtools.ledger.data.BankSms
import ir.cmbtools.ledger.data.BankSmsDao
import ir.cmbtools.ledger.data.CustomerDao
import ir.cmbtools.ledger.data.LedgerDao
import ir.cmbtools.ledger.data.PaymentRepository
import ir.cmbtools.ledger.billing.AutoBilling
import java.security.MessageDigest
import java.time.LocalDate

/** Parser for SMS commands and reports that are private to the configured administrator number. */
object AdminSmsCommandParser {
    enum class Kind { RENEW, PAYMENT, BALANCE }

    data class PaymentCommand(val customerName: String, val amountRial: Long)
    data class BalanceCommand(val customerName: String)

    fun kind(body: String): Kind? = when (normalizeWord(commandLines(body).firstOrNull().orEmpty())) {
        "تمدید" -> Kind.RENEW
        "واریز" -> Kind.PAYMENT
        "مانده" -> Kind.BALANCE
        else -> null
    }

    fun payment(body: String): PaymentCommand? {
        val lines = commandLines(body)
        if (lines.size != 3 || normalizeWord(lines[0]) != "واریز") return null
        val name = lines[1].trim()
        if (name.isBlank()) return null
        val digits = Money.toLatinDigits(lines[2]).trim()
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
        val units = digits.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val amountRial = runCatching { Math.multiplyExact(units, 10_000L) }.getOrNull() ?: return null
        return PaymentCommand(name, amountRial)
    }

    fun balance(body: String): BalanceCommand? {
        val lines = commandLines(body)
        if (lines.size != 2 || normalizeWord(lines[0]) != "مانده") return null
        val name = lines[1].trim()
        return name.takeIf { it.isNotBlank() }?.let(::BalanceCommand)
    }

    private fun commandLines(body: String): List<String> =
        body.replace("\r", "").split('\n').map(String::trim).filter(String::isNotBlank)

    private fun normalizeWord(value: String): String = value
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace("\u200c", "")
        .trim()
}

object AdminSmsReportFormatter {
    fun renewal(customerName: String, packageName: String, gb: Double, days: Int): String =
        "تمدید شد\n$customerName\n${ir.cmbtools.ledger.core.PanelSyncRules.packageLabel(packageName, gb, days)}"

    fun payment(customerName: String, amountRial: Long, balanceRial: Long): String =
        "ثبت شد\n$customerName\n${Money.format(amountRial)}\nمانده: ${Money.format(balanceRial)}"

    fun balance(customerName: String, balanceRial: Long): String =
        "مانده حساب\n$customerName\nمانده: ${Money.format(balanceRial)}"
}

/**
 * Processes renewal, manual payment, and balance-query commands only from the configured number.
 * A manual payment command is recorded as a user instruction, never as bank-SMS evidence.
 */
class AdminSmsCommandProcessor(
    private val prefs: Prefs,
    private val db: AppDatabase,
    private val bankSmsDao: BankSmsDao,
    private val customerDao: CustomerDao,
    private val ledgerDao: LedgerDao,
    private val billing: AutoBilling,
    private val payments: PaymentRepository,
    private val smsSender: SmsSender,
    private val renewalProcessor: RenewalSmsCommandProcessor
) {
    data class Result(
        val success: Boolean,
        val message: String,
        val duplicate: Boolean = false
    )

    private data class PaymentOutcome(
        val customerId: Long = 0,
        val customerName: String = "",
        val amountRial: Long = 0,
        val balanceRial: Long = 0,
        val error: String = "",
        val duplicate: Boolean = false
    )

    private data class BalanceOutcome(
        val customerId: Long = 0,
        val customerName: String = "",
        val balanceRial: Long = 0,
        val error: String = "",
        val duplicate: Boolean = false
    )

    /** Returns null for messages that are not private commands from the configured number. */
    suspend fun handle(sender: String, body: String, receivedAtMillis: Long): Result? {
        val configuredNumber = prefs.current.renewalCommandSender.trim()
        val authorizedSender = canonicalSender(configuredNumber)
        if (authorizedSender.isBlank() || canonicalSender(sender) != authorizedSender) return null

        val kind = AdminSmsCommandParser.kind(body) ?: return null
        // Balance requests are read-only; allow an intentional second query in the same minute.
        // Payments and renewals use the existing minute window to avoid duplicate processing.
        val deduplicationTime = if (kind == AdminSmsCommandParser.Kind.BALANCE) {
            receivedAtMillis
        } else {
            receivedAtMillis / 60_000
        }
        val commandHash = sha256("$sender|$body|$deduplicationTime")

        return when (kind) {
            AdminSmsCommandParser.Kind.RENEW -> {
                if (!claimCommand(sender, body, receivedAtMillis, commandHash, "تمدید")) {
                    Result(false, "این فرمان قبلاً دریافت شده است", duplicate = true)
                } else {
                    val result = renewalProcessor.handle(sender, body)
                    if (result == null) Result(false, "قالب فرمان تمدید نامعتبر است؛ قالب: تمدید، نام مشتری، حجم به گیگ")
                    else Result(result.success, result.message)
                }
            }

            AdminSmsCommandParser.Kind.PAYMENT -> handlePayment(
                sender, body, receivedAtMillis, commandHash, configuredNumber, authorizedSender
            )

            AdminSmsCommandParser.Kind.BALANCE -> handleBalance(
                sender, body, receivedAtMillis, commandHash, configuredNumber, authorizedSender
            )
        }
    }

    private suspend fun handlePayment(
        sender: String,
        body: String,
        receivedAtMillis: Long,
        commandHash: String,
        configuredNumber: String,
        authorizedSender: String
    ): Result {
        val command = AdminSmsCommandParser.payment(body)
        val outcome = db.withTransaction {
            if (!claimCommandInTransaction(sender, body, receivedAtMillis, commandHash, "واریز")) {
                return@withTransaction PaymentOutcome(duplicate = true)
            }
            if (command == null) return@withTransaction PaymentOutcome(error = "قالب واریز نامعتبر است؛ مبلغ به واحد ۱۰٬۰۰۰ ریالی نوشته شود")

            val matches = billing.exactNameMatches(command.customerName)
            if (matches.size != 1) {
                return@withTransaction PaymentOutcome(
                    error = if (matches.isEmpty()) "مشتری با این نام دقیق پیدا نشد" else "نام مشتری تکراری است؛ واریز ثبت نشد"
                )
            }
            val customer = customerDao.byId(matches.single())
                ?: return@withTransaction PaymentOutcome(error = "مشتری پیدا نشد؛ واریز ثبت نشد")

            payments.record(
                customerId = customer.id,
                amountRial = command.amountRial,
                method = PaymentMethod.MANUAL,
                at = LocalDate.now(),
                refNo = "SMS-${commandHash.take(8)}",
                note = "ثبت دستی با فرمان پیامکی؛ بدون تأیید بانکی"
            )
            PaymentOutcome(
                customerId = customer.id,
                customerName = customer.name,
                amountRial = command.amountRial,
                balanceRial = ledgerDao.balance(customer.id)
            )
        }

        if (outcome.duplicate) return Result(false, "این فرمان قبلاً دریافت شده است", duplicate = true)
        if (outcome.error.isNotBlank()) return Result(false, "واریز ثبت نشد: ${outcome.error}")

        val report = AdminSmsReportFormatter.payment(outcome.customerName, outcome.amountRial, outcome.balanceRial)
        val sent = sendReport(configuredNumber, authorizedSender, outcome.customerId, report)
        return Result(
            true,
            if (sent.ok) "واریزی ثبت شد و گزارش به شمارهٔ مجاز ارسال شد"
            else "واریزی ثبت شد، اما ارسال گزارش ناموفق بود: ${sent.message}"
        )
    }

    private suspend fun handleBalance(
        sender: String,
        body: String,
        receivedAtMillis: Long,
        commandHash: String,
        configuredNumber: String,
        authorizedSender: String
    ): Result {
        val command = AdminSmsCommandParser.balance(body)
        val outcome = db.withTransaction {
            if (!claimCommandInTransaction(sender, body, receivedAtMillis, commandHash, "مانده")) {
                return@withTransaction BalanceOutcome(duplicate = true)
            }
            if (command == null) return@withTransaction BalanceOutcome(error = "قالب مانده نامعتبر است؛ پس از «مانده» نام مشتری را بنویسید")

            val matches = billing.exactNameMatches(command.customerName)
            if (matches.size != 1) {
                return@withTransaction BalanceOutcome(
                    error = if (matches.isEmpty()) "مشتری با این نام دقیق پیدا نشد" else "نام مشتری تکراری است"
                )
            }
            val customer = customerDao.byId(matches.single())
                ?: return@withTransaction BalanceOutcome(error = "مشتری پیدا نشد")
            BalanceOutcome(
                customerId = customer.id,
                customerName = customer.name,
                balanceRial = ledgerDao.balance(customer.id)
            )
        }

        if (outcome.duplicate) return Result(false, "این فرمان قبلاً دریافت شده است", duplicate = true)
        if (outcome.error.isNotBlank()) return Result(false, "گزارش مانده ارسال نشد: ${outcome.error}")

        val report = AdminSmsReportFormatter.balance(outcome.customerName, outcome.balanceRial)
        val sent = sendReport(configuredNumber, authorizedSender, outcome.customerId, report)
        return Result(
            sent.ok,
            if (sent.ok) "گزارش مانده به شمارهٔ مجاز ارسال شد" else "ارسال گزارش مانده ناموفق بود: ${sent.message}"
        )
    }

    private suspend fun sendReport(
        originallyConfiguredNumber: String,
        authorizedSender: String,
        customerId: Long,
        body: String
    ): SendResult {
        val currentNumber = prefs.current.renewalCommandSender.trim()
        if (canonicalSender(currentNumber) != authorizedSender ||
            canonicalSender(originallyConfiguredNumber) != authorizedSender
        ) {
            return SendResult(false, "شمارهٔ مجاز هنگام پردازش تغییر کرده است؛ برای امنیت گزارش ارسال نشد")
        }
        return smsSender.send(prefs.current, currentNumber, body, customerId = customerId)
    }

    private suspend fun claimCommand(
        sender: String,
        body: String,
        receivedAtMillis: Long,
        commandHash: String,
        label: String
    ): Boolean = db.withTransaction {
        claimCommandInTransaction(sender, body, receivedAtMillis, commandHash, label)
    }

    private suspend fun claimCommandInTransaction(
        sender: String,
        body: String,
        receivedAtMillis: Long,
        commandHash: String,
        label: String
    ): Boolean {
        if (bankSmsDao.byHash(commandHash) != null) return false
        val id = bankSmsDao.insert(
            BankSms(
                sender = sender,
                body = body,
                receivedAtMillis = receivedAtMillis,
                bank = "فرمان مدیریتی",
                isCredit = false,
                rawHash = commandHash,
                matchStatus = MatchStatus.IGNORED,
                note = "فرمان مدیریتی: $label (پیامک بانکی نیست)"
            )
        )
        return id > 0L
    }

    private fun canonicalSender(value: String): String {
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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
