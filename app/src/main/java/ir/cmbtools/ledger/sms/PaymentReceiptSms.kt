package ir.cmbtools.ledger.sms

import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.CustomerDao
import ir.cmbtools.ledger.data.LedgerDao
import kotlinx.coroutines.CancellationException

/** Formats and sends a customer receipt only after the payment has been recorded. */
object PaymentReceiptSmsFormatter {
    fun format(customerName: String, amountRial: Long, balanceRial: Long): String {
        val safeName = customerName.replace(Regex("[\\r\\n]+"), " ").trim().ifBlank { "مشتری" }
        return "$safeName گرامی، واریزی شما به مبلغ ${Money.format(amountRial)} ثبت گردید. " +
            "مانده حساب شما ${Money.format(balanceRial)} می‌باشد."
    }
}

class PaymentReceiptNotifier(
    private val customers: CustomerDao,
    private val ledger: LedgerDao,
    private val prefs: Prefs,
    private val smsSender: SmsSender
) {
    /** Returns null when receipt SMS is disabled; sending failures never undo the recorded payment. */
    suspend fun send(customerId: Long, amountRial: Long): SendResult? {
        val settings = prefs.current
        if (!settings.autoSmsOnPayment) return null
        return try {
            val customer = customers.byId(customerId)
                ?: return SendResult(false, "مشتری برای ارسال رسید پیدا نشد")
            if (customer.phone.isBlank()) return SendResult(false, "شمارهٔ موبایل مشتری ثبت نشده است")
            val body = PaymentReceiptSmsFormatter.format(
                customerName = customer.name,
                amountRial = amountRial,
                balanceRial = ledger.balance(customerId)
            )
            smsSender.send(
                settings = settings,
                to = customer.phone,
                body = body,
                customerId = customerId
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SendResult(false, error.message ?: "خطا در ارسال رسید واریزی")
        }
    }
}
