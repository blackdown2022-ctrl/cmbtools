package ir.cmbtools.ledger.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import ir.cmbtools.ledger.LedgerApp
import ir.cmbtools.ledger.core.BotPaymentAudit
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
                                val audit = BotPaymentAudit(
                                    atMillis = time,
                                    state = "pending",
                                    bank = parsed.bank,
                                    amountRial = parsed.amountRial,
                                    reference = parsed.refNo,
                                    message = "پیامک واجد شرایط است؛ درخواست در صف ارسال به سرور قرار گرفت"
                                )
                                app.container.prefs.recordBotAudit(audit)
                                BotPaymentWorker.enqueue(context, parsed.amountRial, parsed.refNo, parsed.bank)
                            } else if (!parsed.isOtp && parsed.amountRial > 0L && (parsed.isCredit || parsed.isReversal)) {
                                val reason = when {
                                    parsed.isReversal -> "پیامک برگشت وجه است؛ ارسال نشد"
                                    parsed.amountRial % 10L != 0L -> "مبلغ به تومان دقیق تبدیل نمی‌شود"
                                    parsed.refNo.isBlank() -> "کد پیگیری از متن پیامک استخراج نشد"
                                    parsed.bank == "بانک ناشناس" -> "فرستنده به‌عنوان بانک شناخته نشد"
                                    else -> "پیامک شرایط لازم برای تأیید را نداشت"
                                }
                                app.container.prefs.recordBotAudit(
                                    BotPaymentAudit(
                                        atMillis = time,
                                        state = "skipped",
                                        bank = parsed.bank,
                                        amountRial = parsed.amountRial,
                                        reference = if (parsed.isReversal) "" else parsed.refNo,
                                        message = reason
                                    )
                                )
                                app.notifications.show("واریز برای ربات ارسال نشد", reason)
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
