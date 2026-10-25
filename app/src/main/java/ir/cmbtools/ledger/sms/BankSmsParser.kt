package ir.cmbtools.ledger.sms

import ir.cmbtools.ledger.core.Money

/**
 * پارسر پیامک بانکی ایرانی.
 * فلسفه: «محافظه‌کار» — اگر مطمئن نیستیم واریز است، رد می‌کنیم تا پرداخت اشتباه ثبت نشود.
 */
data class ParsedBankSms(
    val bank: String,
    val amountRial: Long,
    val last4: String,
    val balanceRial: Long,
    val isCredit: Boolean,
    val isOtp: Boolean,
    val isReversal: Boolean,
    val refNo: String,
    val reason: String = ""
)

object BankSmsParser {

    private data class BankPattern(val name: String, val senders: List<String>)

    private val banks = listOf(
        BankPattern("بانک ملی", listOf("bankmelli", "melli", "6104", "9830")),
        BankPattern("بانک ملت", listOf("bankmellat", "mellat", "610433", "9911")),
        BankPattern("بانک صادرات", listOf("saderat", "bsi", "6037")),
        BankPattern("بانک تجارت", listOf("tejarat", "btejarat", "5859")),
        BankPattern("بانک سپه", listOf("sepah", "5892", "6393")),
        BankPattern("بانک پاسارگاد", listOf("pasargad", "bpi", "5022")),
        BankPattern("بانک سامان", listOf("saman", "samansms", "6219")),
        BankPattern("بانک پارسیان", listOf("parsian", "bpi_parsian", "6221")),
        BankPattern("بانک کشاورزی", listOf("keshavarzi", "bki", "6037")),
        BankPattern("بانک رفاه", listOf("refah", "5833")),
        BankPattern("بانک مسکن", listOf("maskan", "bankmaskan", "6290")),
        BankPattern("بانک شهر", listOf("shahr", "bank shahr", "5047")),
        BankPattern("بانک اقتصاد نوین", listOf("enbank", "economy", "6274")),
        BankPattern("بانک دی", listOf("day", "bankday", "5029")),
        BankPattern("بانک سینا", listOf("sina", "banksina", "6393")),
        BankPattern("بانک قرض‌الحسنه رسالت", listOf("resalat", "bankresalat")),
        BankPattern("بلوبانک", listOf("blubank", "blue")),
        BankPattern("بانک آینده", listOf("ayandeh", "ba24")),
        BankPattern("بانک ایران‌زمین", listOf("izbank", "iran zamin")),
        BankPattern("پست بانک", listOf("postbank", "post")),
        BankPattern("بانک سینا", listOf("sinabank")),
        BankPattern("بانک خاورمیانه", listOf("me bank", "middleeast", "mbank")),
        BankPattern("بانک کارآفرین", listOf("karafarin", "kbank")),
        BankPattern("بانک گردشگری", listOf("gardeshgari", "tourism")),
        BankPattern("بانک ایران زمین", listOf("iran-zamin"))
    )

    private val creditWords = listOf(
        "واریز", "افزایش موجودی", "به حساب شما", "به حساب", "انتقال از", "کارت به کارت",
        "کارت‌به‌کارت", "پایا", "ساتنا", "شتاب", "دریافت", "وجه به حساب", "مبلغ واریز",
        "به شما", "واریزی", "اعتبار"
    )

    private val debitWords = listOf(
        "برداشت", "کاهش موجودی", "خرید", "پرداخت شد", "انتقال به", "کارت شما", "کسر",
        "شماره کارت شما", "طلب", "بدهکار"
    )

    private val otpWords = listOf(
        "رمز پویا", "رمز یکبار", "رمز یک بار", "کد تایید", "کد تأیید", "otp", "رمز دوم",
        "کد فعال‌سازی", "کد ورود", "رمز اینترنتی", "CVV2"
    )

    private val reversalWords = listOf("برگشت", "کنسل", "لغو", "reverse", "عودت")

    private val amountRegex = Regex("""([\d][\d,٬]{2,})\s*(?:ریال|ريال)""")
    private val amountFallback = Regex("""(?:مبلغ|به مبلغ|مبلغ:|واریز)\s*:?\s*([\d][\d,٬]{2,})""")
    private val balanceRegex = Regex("""موجودی[^\d]{0,12}([\d][\d,٬]{2,})""")
    private val cardRegex = Regex("""(?:کارت|حساب)[^\d]{0,20}(\d{4})""")
    private val refRegex = Regex("""(?:پیگیری|مرجع|رسید|شماره سند)[^\d]{0,10}(\d{4,})""")

    fun detectBank(sender: String): String {
        val s = sender.lowercase().replace(" ", "")
        val found = banks.firstOrNull { b -> b.senders.any { s.contains(it) } }
        return found?.name ?: "بانک ناشناس"
    }

    fun parse(sender: String, body: String, receivedAtMillis: Long): ParsedBankSms {
        val normalized = normalize(body)
        val lower = normalized.lowercase()
        val bank = detectBank(sender)

        val isOtp = otpWords.any { lower.contains(it.lowercase()) }
        val isReversal = reversalWords.any { lower.contains(it.lowercase()) }
        val hasCredit = creditWords.any { lower.contains(it.lowercase()) }
        val hasDebit = debitWords.any { lower.contains(it.lowercase()) }

        val amount = extractAmount(normalized)
        val balance = balanceRegex.find(normalized)?.groupValues?.get(1)?.let { Money.parseRial(it) } ?: -1
        val last4 = cardRegex.find(normalized)?.groupValues?.get(1) ?: ""
        val refNo = refRegex.find(normalized)?.groupValues?.get(1) ?: ""

        val isCredit = !isOtp && hasCredit && !hasDebit

        val reason = when {
            isOtp -> "رمز پویا/کد تأیید"
            amount == null || amount <= 0 -> "مبلغ پیدا نشد"
            !isCredit -> "پیامک واریز نیست"
            isReversal -> "برگشت/کنسلی"
            else -> ""
        }

        return ParsedBankSms(
            bank = bank,
            amountRial = amount ?: 0,
            last4 = last4,
            balanceRial = balance,
            isCredit = isCredit,
            isOtp = isOtp,
            isReversal = isReversal,
            refNo = refNo,
            reason = reason
        )
    }

    private fun extractAmount(normalized: String): Long? {
        // ۱) «مبلغ ۱٬۲۰۰٬۰۰۰ ریال» یا «۱۲٬۰۰۰ تومان» → واحد مهم است
        val withUnit = Regex("""([\d][\d,٬]{2,})\s*(ریال|ريال|تومان|تومن)""").find(normalized)
        if (withUnit != null) {
            val value = Money.parseRial(withUnit.groupValues[1]) ?: return null
            val unit = withUnit.groupValues[2]
            return if (unit.contains("توم")) Money.tomanToRial(value) else value
        }
        // ۲) بدون واحد: فرض می‌کنیم تومان است (رایج‌ترین حالت پیامک بانک‌های ایرانی)
        val fallback = amountFallback.find(normalized) ?: amountRegex.find(normalized)
        val value = fallback?.groupValues?.get(1)?.let { Money.parseRial(it) } ?: return null
        return if (value < 1_000_000L) Money.tomanToRial(value) else value
    }

    private fun normalize(body: String): String = Money.toLatinDigits(body)
        .replace('\u200c', ' ')
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace(Regex("\\s+"), " ")
        .trim()
}
