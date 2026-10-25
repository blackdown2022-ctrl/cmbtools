package ir.cmbtools.ledger.core

/**
 * قواعد خالصِ (بدون وابستگی به اندروید) تطبیق بسته و تشخیص تمدید.
 * این‌ها همان تصمیم‌هایی هستند که «فاکتور خودکار» بر پایهٔ آن‌ها صادر می‌شود،
 * بنابراین جدا و تست‌پذیر نگه داشته شده‌اند.
 */
object PanelSyncRules {

    /** اختلاف مجاز حجم (گیگ) هنگام تطبیق بسته */
    const val GB_TOLERANCE = 0.6

    /**
     * آیا این تغییرات «تمدید» است؟
     * تمدید = تاریخ انقضا جلو رفته باشد. اختلاف کمتر از ۱ روز نادیده گرفته می‌شود
     * تا تغییر ساعت/منطقه‌زمانی باعث فاکتور الکی نشود.
     */
    fun isRenewal(oldExpireEpochDay: Long, newExpireEpochDay: Long): Boolean =
        oldExpireEpochDay > 0 && newExpireEpochDay > oldExpireEpochDay + 1

    /**
     * ایندکس بستهٔ متناسب با حجم/روز. اول تطبیق دقیق، بعد نزدیک‌ترین حجم با همان تعداد روز.
     * اگر هیچ بسته‌ای منطقی نبود، ۱- برمی‌گردد.
     */
    fun bestPlanIndex(
        gbs: List<Double>,
        daysList: List<Int>,
        gb: Double,
        days: Int,
        tolerance: Double = GB_TOLERANCE
    ): Int {
        if (gbs.isEmpty()) return -1
        val n = minOf(gbs.size, daysList.size)

        // ۱) حجم و روز هر دو برابر
        for (i in 0 until n) {
            if (daysList[i] == days && kotlin.math.abs(gbs[i] - gb) <= tolerance) return i
        }
        // ۲) فقط روز برابر → نزدیک‌ترین حجم
        val sameDays = (0 until n).filter { daysList[i(it)] == days }
        if (sameDays.isNotEmpty()) {
            return sameDays.minByOrNull { kotlin.math.abs(gbs[it] - gb) } ?: -1
        }
        // ۳) هیچ تطبیقی نبود
        return -1
    }

    /** قیمت تخمینی بر پایهٔ «قیمت هر گیگ» (ریال). صفر یعنی تنظیم نشده است. */
    fun priceFromPerGb(gb: Double, pricePerGbRial: Long): Long {
        if (pricePerGbRial <= 0 || gb <= 0) return 0
        return kotlin.math.round(gb * pricePerGbRial).toLong()
    }

    /** نام نمایشی بسته برای متن فاکتور */
    fun packageLabel(name: String, gb: Double, days: Int): String = when {
        name.isNotBlank() -> name
        gb > 0 && days > 0 -> "${gb.toInt()} گیگ / $days روز"
        gb > 0 -> "${gb.toInt()} گیگ"
        else -> "سرویس اینترنت"
    }
}
