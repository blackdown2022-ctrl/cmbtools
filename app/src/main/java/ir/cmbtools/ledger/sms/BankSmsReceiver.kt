package ir.cmbtools.ledger.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import ir.cmbtools.ledger.LedgerApp
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * دریافت زندهٔ پیامک بانکی (نصب مستقیم، استفادهٔ شخصی).
 * هر پیامک واریز یا خودکار به فاکتور می‌چسبد یا در صف «در انتظار تطبیق» می‌ماند.
 */
class BankSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val app = context.applicationContext as? LedgerApp ?: return

        // پیامک چندبخشی را ادغام می‌کنیم
        val grouped = messages.groupBy { it.originatingAddress ?: "" }
        val scope = CoroutineScope(Dispatchers.IO)

        grouped.forEach { (sender, parts) ->
            val body = parts.joinToString("") { it.messageBody ?: "" }
            val time = parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
            scope.launch {
                runCatching {
                    val command = app.container.adminSmsCommandProcessor.handle(sender, body, time)
                    if (command != null) {
                        if (!command.duplicate) {
                            app.notifications.show(
                                if (command.success) "فرمان پیامکی انجام شد" else "فرمان پیامکی ناموفق بود",
                                command.message
                            )
                        }
                        return@launch
                    }
                    val result = app.container.matcher.handle(sender, body, time)
                    notifyUser(context, app, result)
                }
            }
        }
    }

    private fun notifyUser(context: Context, app: LedgerApp, result: PaymentMatcher.Result) {
        val (title, text) = when (result) {
            is PaymentMatcher.Result.AutoMatched -> "پرداخت ثبت شد" to
                "${Money.format(result.amountRial)} از ${result.customerName} برای فاکتور ${result.invoiceNumber}"
            is PaymentMatcher.Result.NeedsReview -> "پیامک بانکی در انتظار تطبیق" to
                "${Money.format(result.amountRial)} - ${result.reason}"
            is PaymentMatcher.Result.Ignored -> return
            PaymentMatcher.Result.Duplicate -> return
        }
        app.notifications.show(title, text)
    }
}
