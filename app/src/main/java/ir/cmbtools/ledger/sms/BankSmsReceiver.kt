package ir.cmbtools.ledger.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import ir.cmbtools.ledger.LedgerApp
import ir.cmbtools.ledger.core.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * دریافت زندهٔ پیامک بانکی (نصب مستقیم، استفادهٔ شخصی).
 * تطبیق محلی و ارسال اختیاری به ربات، دو مسیر مستقل هستند.
 */
class BankSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val app = context.applicationContext as? LedgerApp ?: return

        // پیامک چندبخشی را ادغام می‌کنیم. goAsync مانع توقف کار DB/صف WorkManager پس از خروج از onReceive است.
        val grouped = messages.groupBy { it.originatingAddress ?: "" }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                grouped.forEach { (sender, parts) ->
                    val body = parts.joinToString("") { it.messageBody ?: "" }
                    val time = parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

                    // مسیر محلی مستقل؛ خطای آن مانع رسیدن SMS واجدشرایط به صف ربات نمی‌شود.
                    runCatching { app.container.matcher.handle(sender, body, time) }
                        .onSuccess { notifyUser(app, it) }

                    // ربات فقط یک پیشنهاد مستقل برای سفارش‌های مبلغ‌یکتاست؛ مبلغ پیامک ریال است.
                    runCatching {
                        val settings = app.container.prefs.current
                        if (settings.botPaymentEnabled) {
                            val parsed = BankSmsParser.parse(sender, body, time)
                            if (BotPaymentClient.isEligibleBankDeposit(parsed)) {
                                BotPaymentWorker.enqueue(context, parsed.amountRial, parsed.refNo, parsed.bank)
                            } else if (parsed.isCredit && !parsed.isOtp && !parsed.isReversal && parsed.amountRial > 0L) {
                                app.notifications.show(
                                    "واریز برای ربات ارسال نشد",
                                    when {
                                        parsed.refNo.isBlank() -> "کد پیگیری در پیامک پیدا نشد؛ تأیید خودکار امن نیست"
                                        parsed.bank == "بانک ناشناس" -> "بانک پیامک شناخته نشد؛ این واریز به سرور ربات ارسال نشد"
                                        else -> "این پیامک شرایط لازم برای تأیید خودکار را نداشت"
                                    }
                                )
                            }
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun notifyUser(app: LedgerApp, result: PaymentMatcher.Result) {
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
