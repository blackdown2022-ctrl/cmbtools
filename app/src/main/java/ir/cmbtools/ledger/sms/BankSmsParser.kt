package ir.cmbtools.ledger.sms

import ir.cmbtools.ledger.core.Money

/**
 * پارسر پیامک بانکی ایرانی.
 * amountRial برای سازگاری با دفتر محلی حفظ شده؛ amountUnit نشان می‌دهد تبدیل واحد قطعی بوده یا تخمینی.
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
    val reason: String = "",
    val amountRawValue: Long = 0L,
    /** RIAL, TOMAN, ASSUMED_RIAL, ASSUMED_TOMAN, UNKNOWN */
    val amountUnit: String = "UNKNOWN",
    val amountAmbiguous: Boolean = false
)

object BankSmsParser {

    private data class BankPattern(val name: String, val senders: List<String>)
    private data class AmountCandidate(
        val raw: Long,
        val unit: String,
        val start: Int,
        val end: Int,
        val score: Int
    )
    private data class AmountExtraction(
        val amountRial: Long,
        val raw: Long,
        val unit: String,
        val ambiguous: Boolean = false
    )

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
    private val explicitAmountRegex = Regex("""([\d][\d,٬]{2,})\s*(ریال|ريال|تومان|تومن)""")
    private val labeledAmountRegex = Regex(
        """(?:مبلغ(?:\s*(?:واریز|تراکنش))?|واریز(?:ی)?|دریافت|انتقال(?:\s*وجه)?)[^\d]{0,24}([\d][\d,٬]{2,})"""
    )
    private val balanceRegex = Regex("""موجودی[^\d]{0,12}([\d][\d,٬]{2,})""")
    private val cardRegex = Regex("""(?:کارت|حساب)[^\d]{0,20}(\d{4})""")
    private val refRegex = Regex(
        """(?:کد\s*(?:پیگیری|رهگیری|مرجع|تراکنش)|شماره\s*(?:پیگیری|رهگیری|مرجع|تراکنش|سند)|شناسه\s*(?:پیگیری|تراکنش)|پیگیری|رهگیری|مرجع|رسید|trace|ref)[^\d]{0,15}(\d{4,})""",
        RegexOption.IGNORE_CASE
    )
    private val transactionContext = listOf("مبلغ", "واریز", "واریزی", "دریافت", "تراکنش", "انتقال", "رسید")
    private val balanceContext = listOf("موجودی", "مانده", "balance")

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

        val extracted = extractAmount(normalized)
        val amount = extracted?.amountRial ?: 0L
        val balance = balanceRegex.find(normalized)?.groupValues?.get(1)?.let { Money.parseRial(it) } ?: -1
        val last4 = cardRegex.find(normalized)?.groupValues?.get(1) ?: ""
        val refNo = refRegex.find(normalized)?.groupValues?.get(1) ?: ""
        val isCredit = !isOtp && hasCredit && !hasDebit

        val reason = when {
            isOtp -> "رمز پویا/کد تأیید"
            extracted?.ambiguous == true -> "چند مبلغ محتمل پیدا شد؛ مبلغ تراکنش مبهم است"
            amount <= 0L -> "مبلغ تراکنش پیدا نشد"
            !isCredit -> "پیامک واریز نیست"
            isReversal -> "برگشت/کنسلی"
            else -> ""
        }

        return ParsedBankSms(
            bank = bank,
            amountRial = amount,
            last4 = last4,
            balanceRial = balance,
            isCredit = isCredit,
            isOtp = isOtp,
            isReversal = isReversal,
            refNo = refNo,
            reason = reason,
            amountRawValue = extracted?.raw ?: 0L,
            amountUnit = extracted?.unit ?: "UNKNOWN",
            amountAmbiguous = extracted?.ambiguous == true
        )
    }

    /** Select by transaction context, not by whichever currency-formatted number appears first. */
    private fun extractAmount(text: String): AmountExtraction? {
        val candidates = mutableListOf<AmountCandidate>()
        explicitAmountRegex.findAll(text).forEach { match ->
            val raw = Money.parseRial(match.groupValues[1]) ?: return@forEach
            val unitText = match.groupValues[2]
            val unit = if (unitText.contains("توم")) "TOMAN" else "RIAL"
            candidates += AmountCandidate(raw, unit, match.range.first, match.range.last + 1, contextScore(text, match.range.first))
        }
        labeledAmountRegex.findAll(text).forEach { match ->
            val numberGroup = match.groups[1] ?: return@forEach
            val raw = Money.parseRial(numberGroup.value) ?: return@forEach
            // اگر این عدد قبلاً با واحد صریح پیدا شده، کاندیدای تخمینیِ تکراری نساز.
            if (candidates.any { it.start == numberGroup.range.first }) return@forEach
            candidates += AmountCandidate(raw, "UNKNOWN", numberGroup.range.first, numberGroup.range.last + 1, contextScore(text, numberGroup.range.first))
        }

        val viable = candidates.filter { it.score > -50 }
        if (viable.isEmpty()) return null
        val bestScore = viable.maxOf { it.score }
        val best = viable.filter { it.score == bestScore }
        val distinct = best.distinctBy { it.raw to it.unit }
        if (distinct.size > 1) return AmountExtraction(0L, 0L, "UNKNOWN", ambiguous = true)

        val chosen = distinct.first()
        val amountRial: Long
        val unit: String
        when (chosen.unit) {
            "RIAL" -> { amountRial = chosen.raw; unit = "RIAL" }
            "TOMAN" -> {
                amountRial = runCatching { Math.multiplyExact(chosen.raw, 10L) }.getOrNull() ?: return null
                unit = "TOMAN"
            }
            else -> {
                // حفظ تخمین قدیمی برای نمایش/دفتر محلی؛ ولی ربات این واحد نامعلوم را خودکار نمی‌فرستد.
                if (chosen.raw < 1_000_000L) {
                    amountRial = runCatching { Math.multiplyExact(chosen.raw, 10L) }.getOrNull() ?: return null
                    unit = "ASSUMED_TOMAN"
                } else {
                    amountRial = chosen.raw
                    unit = "ASSUMED_RIAL"
                }
            }
        }
        return AmountExtraction(amountRial, chosen.raw, unit)
    }

    private fun contextScore(text: String, numberStart: Int): Int {
        val start = (numberStart - 36).coerceAtLeast(0)
        val prefix = text.substring(start, numberStart).lowercase()
        val lastTransaction = transactionContext.map { prefix.lastIndexOf(it) }.maxOrNull() ?: -1
        val lastBalance = balanceContext.map { prefix.lastIndexOf(it) }.maxOrNull() ?: -1
        val lastCard = listOf("کارت", "شماره حساب", "شماره کارت").map { prefix.lastIndexOf(it) }.maxOrNull() ?: -1
        return when {
            lastBalance > lastTransaction -> -100
            lastCard > lastTransaction -> -75
            lastTransaction >= 0 -> 100 + lastTransaction
            else -> 0
        }
    }

    /**
     * نرمال‌سازی متن پیامک: ارقام لاتین، «ی/ک» فارسی، فاصلهٔ یکسان.
     * از Money.toLatinDigits استفاده نمی‌شود چون فاصله‌های عبارت‌های چندکلمه‌ای را حذف می‌کند.
     */
    private fun normalize(body: String): String {
        val sb = StringBuilder(body.length)
        for (ch in body) {
            sb.append(
                when {
                    ch in '۰'..'۹' -> '0' + (ch - '۰')
                    ch in '٠'..'٩' -> '0' + (ch - '٠')
                    ch == '\u200c' -> ' '
                    ch == 'ي' -> 'ی'
                    ch == 'ك' -> 'ک'
                    else -> ch
                }
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
