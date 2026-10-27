package ir.cmbtools.ledger.core

import java.util.Locale

/** نام پنل را فقط با نام کامل مشتری تطبیق می‌دهد؛ هرگز بر اساس پیشوند/نام کوچک حدس نمی‌زند. */
object PanelCustomerNames {
    data class DiscountSuffix(
        val customerName: String,
        /** تخفیف بر حسب ریال؛ null یعنی پسوند عددی نامعتبر/سرریز کرده است. */
        val discountRial: Long?,
        val hasSuffix: Boolean
    )

    /**
     * پسوند اختیاری `/عدد` در انتهای نام پنل را تخفیف می‌داند؛ عدد بر حسب واحدهای
     * ۱۰٬۰۰۰ ریالی است (مثلاً `/50` یعنی ۵۰۰٬۰۰۰ ریال). ارقام فارسی و عربی هم پذیرفته‌اند.
     */
    fun discountSuffix(raw: String): DiscountSuffix {
        val match = Regex("""/([0-9۰-۹٠-٩]+)\s*$""").find(raw.trim())
            ?: return DiscountSuffix(raw.trim(), 0L, false)
        val units = Money.toLatinDigits(match.groupValues[1]).toLongOrNull()
        val rial = units?.let { runCatching { Math.multiplyExact(it, 10_000L) }.getOrNull() }
        return DiscountSuffix(raw.trim().substring(0, match.range.first).trimEnd(), rial, true)
    }

    /** پسوندهای شناخته‌شدهٔ تخفیف و نسخه‌های قدیمی اپ را از نام تطبیقی حذف می‌کند. */
    fun cleanLegacyPanelName(raw: String): String = discountSuffix(raw).customerName.replace(
        Regex("""\s+\d{4}-\d{4}(?:\s+-\s+.+)?$"""),
        ""
    ).trim()

    /** یکسان‌سازی شکل‌های نوشتاری، بدون یکی‌کردن نام کوتاه با نام کامل‌تر. */
    fun normalize(raw: String): String = raw
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "")
        .replace("\u200c", "")
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

    /** شناسه‌های نام دقیق؛ وجود چند مورد یعنی ابهام و نباید هیچ‌کدام انتخاب شوند. */
    fun exactMatchIds(panelName: String, customers: List<Pair<Long, String>>): List<Long> {
        val target = normalize(cleanLegacyPanelName(panelName))
        if (target.isBlank()) return emptyList()
        return customers.asSequence()
            .filter { normalize(it.second) == target }
            .map { it.first }
            .distinct()
            .toList()
    }

    /**
     * نتیجهٔ شناسایی مطمئن بر اتصال قدیمی اولویت دارد تا اتصال‌های اشتباهِ ساخته‌شده با
     * الگوریتم قبلی (تطبیق اولین واژه) با نام کامل اصلاح شوند. در وضعیت مبهم، اتصال قدیمی
     * هم موقتاً کنار گذاشته می‌شود تا فاکتور به فرد اشتباه نرود.
     */
    fun chooseCustomerId(resolvedId: Long, previousId: Long, ambiguousName: Boolean): Long =
        when {
            ambiguousName -> 0L
            resolvedId > 0L -> resolvedId
            else -> previousId.coerceAtLeast(0L)
        }

    /** استخراج شناسهٔ پیوندی از یادداشت‌های قدیمی و جدید برنامه. */
    fun customerIdFromComment(comment: String?): Long {
        if (comment.isNullOrBlank()) return 0
        val match = Regex("""(?:^|[|;\s])cmbtools[:|](\d+)(?=[:|;\s]|$)""", RegexOption.IGNORE_CASE)
            .find(comment.trim()) ?: return 0
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0 } ?: 0
    }

    /** متن آزاد قبل از شناسهٔ برنامه؛ برای استخراج شمارهٔ تلفن بدون خواندن ارقام شناسه. */
    fun commentBeforeLink(comment: String?): String {
        if (comment.isNullOrBlank()) return ""
        val text = comment.trim()
        val marker = Regex("cmbtools[:|]", RegexOption.IGNORE_CASE).find(text) ?: return text
        return text.substring(0, marker.range.first).trim().trimEnd('|', ';', ':').trim()
    }
}
