package ir.cmbtools.ledger.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * پول همیشه به «ریال» و به صورت Long نگهداری می‌شود. هیچ‌جا Float/Double استفاده نشده است.
 */
object Money {

    private val grouping = DecimalFormat("#,###", DecimalFormatSymbols(Locale.US))
    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    private val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

    /** ۱۲٬۳۴۵ ریال (فارسی) */
    fun format(rial: Long, persian: Boolean = true, withUnit: Boolean = true): String {
        val sign = if (rial < 0) "-" else ""
        val body = grouping.format(kotlin.math.abs(rial))
        // جداکنندهٔ هزارگان در نمایش فارسی «٬» است؛ در خروجی لاتین (CSV) کاما می‌ماند.
        val text = if (persian) toPersian(sign + body).replace(',', '٬') else sign + body
        return if (withUnit) "$text ریال" else text
    }

    /** برای ورودی‌های کوتاه داشبورد */
    fun compact(rial: Long, persian: Boolean = true): String {
        val m = rial / 1_000_000L
        return when {
            m >= 1_000_000 -> "${toPersianOrNot(m / 1_000_000, persian)} میلیارد"
            m >= 1_000 -> "${toPersianOrNot(m / 1_000, persian)} میلیون"
            else -> format(rial, persian, false)
        }
    }

    private fun toPersianOrNot(v: Long, persian: Boolean) = if (persian) toPersian(v.toString()) else v.toString()

    fun toPersian(text: String): String = buildString {
        for (ch in text) {
            append(
                when (ch) {
                    in '0'..'9' -> persianDigits[ch - '0']
                    else -> ch
                }
            )
        }
    }

    fun toLatinDigits(text: String): String = buildString {
        for (ch in text) {
            val p = persianDigits.indexOf(ch)
            val a = arabicDigits.indexOf(ch)
            when {
                p >= 0 -> append('0' + p)
                a >= 0 -> append('0' + a)
                ch == '٬' || ch == ',' || ch == ' ' || ch == '\u200c' || ch == '٫' -> Unit
                ch == '،' -> Unit
                else -> append(ch)
            }
        }
    }

    /** ورودی آزاد کاربر/پیامک → ریال. ورودی نامعتبر → null */
    fun parseRial(input: String): Long? {
        val cleaned = toLatinDigits(input).replace(",", "").replace(" ", "").trim()
        if (cleaned.isEmpty()) return null
        return cleaned.toLongOrNull()?.takeIf { it >= 0 }
    }

    /** ورودی تومان → ریال */
    fun tomanToRial(toman: Long): Long = toman * 10L

    fun rialToToman(rial: Long): Long = rial / 10L

    /** درصد تخفیف روی مبلغ */
    fun percentOf(amountRial: Long, percent: Double): Long =
        BigDecimal.valueOf(amountRial.toDouble())
            .multiply(BigDecimal.valueOf(percent))
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
            .toLong()

    fun safeSum(a: Long, b: Long): Long = try {
        Math.addExact(a, b)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

/** وضعیت‌های ثابت (به‌جای enum در دیتابیس، رشتهٔ کوتاه ذخیره می‌شود تا مهاجرت ساده بماند) */
object InvoiceStatus {
    const val DRAFT = "DRAFT"
    const val ISSUED = "ISSUED"
    const val PARTIAL = "PARTIAL"
    const val PAID = "PAID"
    const val VOID = "VOID"

    fun label(status: String): String = when (status) {
        DRAFT -> "پیش‌نویس"
        ISSUED -> "صادرشده"
        PARTIAL -> "پرداخت جزئی"
        PAID -> "تسویه‌شده"
        VOID -> "ابطال‌شده"
        else -> status
    }
}

object PaymentMethod {
    const val CASH = "CASH"
    const val CARD = "CARD"
    const val BANK_SMS = "BANK_SMS"
    const val MANUAL = "MANUAL"

    fun label(method: String): String = when (method) {
        CASH -> "نقدی"
        CARD -> "کارت‌به‌کارت"
        BANK_SMS -> "پیامک بانکی"
        MANUAL -> "دستی"
        else -> method
    }
}

object MatchStatus {
    const val UNMATCHED = "UNMATCHED"
    const val AUTO = "AUTO"
    const val MANUAL = "MANUAL"
    const val IGNORED = "IGNORED"

    fun label(status: String): String = when (status) {
        UNMATCHED -> "در انتظار تطبیق"
        AUTO -> "تطبیق خودکار"
        MANUAL -> "تطبیق دستی"
        IGNORED -> "نادیده‌گرفته"
        else -> status
    }
}

object LedgerAccount {
    const val RECEIVABLE = "RECEIVABLE"
    const val SALES = "SALES"
    const val CASH = "CASH"
    const val DISCOUNT = "DISCOUNT"
    const val EXPENSE = "EXPENSE"
    const val ADJUSTMENT = "ADJUSTMENT"
}

object LedgerDirection {
    const val DEBIT = "DEBIT"
    const val CREDIT = "CREDIT"
}
