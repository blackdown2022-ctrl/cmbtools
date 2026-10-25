package ir.cmbtools.ledger.core

/**
 * نرمال‌سازی و استخراج شمارهٔ موبایل ایران.
 *
 * چرا جدا؟ هم پارسر پیامک بانکی، هم ساخت کاربر در پنل، و هم خواندن «یادداشت/کامنت»
 * کاربران پنل به همین قاعده نیاز دارند. یک نسخهٔ واحد یعنی رفتار یکسان همه‌جا.
 */
object Phone {

    /** ارقام فارسی/عربی → لاتین و حذف جداکننده‌ها */
    private fun digitsOnly(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch in '0'..'9' -> sb.append(ch)
                ch in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                ch in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                // جداکننده‌ها: فاصله، خط تیره، پرانتز، نقطه و پیش‌شمارهٔ بین‌المللی
                ch == '+' || ch == '-' || ch == ' ' || ch == '(' || ch == ')' ||
                    ch == '.' || ch == '\u200c' || ch == '\u00a0' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * به شکل استاندارد `09xxxxxxxxx` برمی‌گرداند؛ اگر شمارهٔ موبایل معتبر نبود رشتهٔ خالی.
     * ورودی‌های پذیرفته‌شده: 09121234567 / 9121234567 / +989121234567 / 00989121234567
     */
    fun normalize(raw: String): String {
        var d = digitsOnly(raw.trim())
        d = when {
            d.startsWith("0098") -> d.removePrefix("0098")
            d.startsWith("+98") -> d.removePrefix("+98")
            d.startsWith("98") && d.length == 12 -> d.removePrefix("98")
            d.startsWith("0") -> d.removePrefix("0")
            else -> d
        }
        if (d.length == 10 && d.startsWith("9")) return "0$d"
        if (d.length == 11 && d.startsWith("09")) return d
        return ""
    }

    fun isValid(raw: String): Boolean = normalize(raw).isNotEmpty()

    /**
     * اولین شمارهٔ موبایل داخل یک متن (برای «یادداشت» کاربر پنل).
     * اگر شماره پیدا نشد رشتهٔ خالی.
     */
    fun extract(text: String): String {
        if (text.isBlank()) return ""
        val normalizedText = digitsOnly(text.replace("+", " +"))
        // الگوهای رایج: 09xxxxxxxxx / 9xxxxxxxxx / 989xxxxxxxxx / +989xxxxxxxxx
        Regex("""(?<!\d)(?:\+?98|0)?9\d{9}(?!\d)""").find(normalizedText)?.let {
            return normalize(it.value)
        }
        return ""
    }
}
