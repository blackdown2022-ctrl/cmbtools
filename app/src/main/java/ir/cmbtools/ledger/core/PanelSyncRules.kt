package ir.cmbtools.ledger.core

/**
 * قواعد خالصِ (بدون وابستگی به اندروید) تطبیق بسته و تشخیص تمدید.
 * این‌ها همان تصمیم‌هایی هستند که فاکتور تمدید بر پایهٔ آن‌ها صادر می‌شود،
 * بنابراین جدا و تست‌پذیر نگه داشته شده‌اند.
 */
object PanelSyncRules {

    /** اختلاف مجاز حجم (گیگ) هنگام تطبیق بسته */
    const val GB_TOLERANCE = 0.6

    /**
     * آیا این تغییرات «تمدید» است؟
     *
     * **تعریف تمدید در این اپ:** ریست سهمیهٔ حجم یا آغاز دوبارهٔ دورهٔ زمانی.
     * هر کاربری در پنل که حجمش ریست شود یا شمارش دوره‌اش از نو آغاز شود، تمدید شده است — حتی اگر
     * تاریخ انقضای قبلی‌اش دورتر بوده باشد (کاربر استفاده‌نشده).
     *
     * حالت‌هایی که تمدید حساب می‌شوند:
     *  ۱) `start_date` پاک شده (`null`) — همان کاری که دکمهٔ «تمدید» پنل و اپ می‌کند؛
     *     شمارش روزها از اولین اتصال بعدی شروع می‌شود.
     *  ۲) تاریخ شروع دوره جلو رفته (تمدید با تاریخ دستی).
     *  ۳) تاریخ انقضا بیش از ۱ روز جلو رفته (اختلاف ۱ روز = ساعت/منطقه‌زمانی).
     *  ۴) مصرف حجم به‌طور محسوس کاهش/ریست شده، حتی اگر تاریخ یا بسته تغییر نکرده باشد.
     *  ۵) روزهای باقی‌مانده به‌طور محسوس افزایش یافته یا از انقضا به دورهٔ فعال برگشته باشد.
     *
     * حالت «بستهٔ شروع‌نشده»: در نسخه‌های ۱۱–۱۳ تا اولین اتصال کاربر `start_date`
     * خالی است، پس تاریخ انقضا فقط یک «پیش‌بینی روزانه» است و هر روز یک روز جلو
     * می‌رود. در این حالت به تاریخ انقضا تکیه نمی‌کنیم و فقط تغییر خودِ بسته
     * (روز/حجم) را نشانهٔ تمدید می‌گیریم.
     */
    fun isRenewal(
        oldExpireEpochDay: Long,
        newExpireEpochDay: Long,
        oldUsageGb: Double = 0.0,
        newUsageGb: Double = 0.0,
        oldStartEpochDay: Long = 0,
        newStartEpochDay: Long = 0,
        oldDays: Int = 0,
        newDays: Int = 0,
        oldRemainingDays: Int = 0,
        newRemainingDays: Int = 0,
        oldLimitGb: Double = 0.0,
        newLimitGb: Double = 0.0,
        oldNotStarted: Boolean = false,
        newNotStarted: Boolean = false
    ): Boolean {
        // افزایش محسوس روزهای باقی‌مانده فقط وقتی معتبر است که مقدار قبلی واقعاً ذخیره شده باشد.
        // صفر در بکاپ‌های قدیمی/بازیابی‌شده یعنی «نامعلوم»؛ آن را با صفر روز باقی‌مانده اشتباه نگیریم.
        val remainingDaysReset = oldRemainingDays != 0 && newRemainingDays > oldRemainingDays + 1
        if (remainingDaysReset) return true

        // هر دو «شروع‌نشده»: انقضا فقط پیش‌بینی است، نه واقعیت؛ تغییر بسته یا ریست مصرف
        // نشانهٔ تمدید محسوب می‌شود.
        val usageReset = oldUsageGb > 0.3 && newUsageGb + 0.3 < oldUsageGb
        if (oldNotStarted && newNotStarted) {
            val packageChanged = newDays > 0 && newDays != oldDays
            val limitChanged = oldLimitGb > 0 &&
                kotlin.math.abs(newLimitGb - oldLimitGb) > GB_TOLERANCE
            return packageChanged || limitChanged || usageReset
        }

        // اگر مصرف حجم واقعاً به مقدار پایین‌تری برگشت، خودِ ریست برای تمدید کافی است؛
        // لازم نیست تاریخ انقضا یا بسته هم حتماً عوض شده باشد.
        if (usageReset) return true
        // تغییر مدت بستهٔ یک حساب شناخته‌شده هم شروع دورهٔ جدید محسوب می‌شود.
        if (oldDays > 0 && newDays > 0 && oldDays != newDays) return true

        // اکانتی که هنوز تاریخ انقضای قبلی ندارد، فقط با شواهد صریح شروع دوره شناخته می‌شود.
        if (oldExpireEpochDay <= 0) {
            return oldStartEpochDay > 0 &&
                (newStartEpochDay == 0L || newStartEpochDay > oldStartEpochDay + 1)
        }

        // ۱) شمارش روزها از نو: start_date پاک شده است
        if (oldStartEpochDay > 0 && newStartEpochDay == 0L) return true
        // ۲) تاریخ شروع دوره جلو رفته
        if (oldStartEpochDay > 0 && newStartEpochDay > oldStartEpochDay + 1) return true
        // ۳) تاریخ انقضا جلو رفته
        if (newExpireEpochDay > oldExpireEpochDay + 1) return true

        return false
    }

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
        val sameDays = (0 until n).filter { daysList[it] == days }
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
