package ir.cmbtools.ledger.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * تبدیل تاریخ میلادی/شمسی و قالب‌بندی فارسی.
 * الگوریتم تبدیل بر اساس jalaali-js (دقیق تا سال ۳۱۷۷ هجری شمسی).
 */
object Jalali {

    private val monthNames = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    private val weekDays = arrayOf(
        "شنبه", "یک‌شنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنج‌شنبه", "جمعه"
    )

    data class JDate(val year: Int, val month: Int, val day: Int)

    fun today(): JDate = fromGregorian(LocalDate.now())

    fun monthName(month: Int): String =
        if (month in 1..12) monthNames[month - 1] else ""

    fun weekdayName(date: JDate): String {
        val g = toGregorian(date)
        // DayOfWeek: MONDAY=1 ... SUNDAY=7  → شنبه = 0
        val idx = (g.dayOfWeek.value + 1) % 7
        return weekDays[idx]
    }

    // ── الگوریتم تبدیل (پورت وفادار از jalaali-js v2) ──────────────────────────
    // نسخهٔ قبلی از فرمول ساده‌شده استفاده می‌کرد و نتیجه تا ~۱۸ ماه خطا داشت؛
    // این نسخه با تاریخ‌های مرجع (نوروز ۱۴۰۳/۱۴۰۴/۱۴۰۵) و ۴۰۰ تاریخ تصادفی تست شده است.

    private val BREAKS = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
        1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178
    )

    private class JalCal(val leap: Int, val gy: Int, val march: Int)

    private fun jalCal(jy: Int, withoutLeap: Boolean): JalCal {
        val bl = BREAKS.size
        val gy = jy + 621
        var leapJ = -14
        var jp = BREAKS[0]
        var jump = 0
        for (i in 1 until bl) {
            val jm = BREAKS[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += (jump / 33) * 8 + (jump % 33) / 4
            jp = jm
        }
        var n = jy - jp
        leapJ += (n / 33) * 8 + (n % 33 + 3) / 4
        if (jump % 33 == 4 && jump - n == 4) leapJ += 1

        val leapG = gy / 4 - ((gy / 100) + 1) * 3 / 4 - 150
        val march = 20 + leapJ - leapG
        if (withoutLeap) return JalCal(-1, gy, march)

        if (jump - n < 6) n = n - jump + (jump + 4) / 33 * 33
        var leap = ((n + 1) % 33 - 1) % 4
        if (leap == -1) leap = 4
        return JalCal(leap, gy, march)
    }

    /** شمارهٔ روز جولیَن از تاریخ میلادی */
    private fun g2d(gy: Int, gm: Int, gd: Int): Int {
        var d = (gy + (gm - 8) / 6 + 100100) * 1461 / 4 +
            (153 * ((gm + 9) % 12) + 2) / 5 + gd - 34840408
        d = d - ((gy + 100100 + (gm - 8) / 6) / 100) * 3 / 4 + 752
        return d
    }

    /** تاریخ میلادی از شمارهٔ روز جولیَن */
    private fun d2g(jdn: Int): Triple<Int, Int, Int> {
        var j = 4 * jdn + 139361631
        j = j + ((4 * jdn + 183187720) / 146097) * 3 / 4 * 4 - 3908
        val i = (j % 1461) / 4 * 5 + 308
        val gd = (i % 153) / 5 + 1
        val gm = (i / 153) % 12 + 1
        val gy = j / 1461 - 100100 + (8 - gm) / 6
        return Triple(gy, gm, gd)
    }

    /** شمارهٔ روز جولیَن از تاریخ شمسی */
    private fun j2d(jy: Int, jm: Int, jd: Int): Int {
        val r = jalCal(jy, true)
        return g2d(r.gy, 3, r.march) + (jm - 1) * 31 - (jm / 7) * (jm - 7) + jd - 1
    }

    /** تاریخ شمسی از شمارهٔ روز جولیَن */
    private fun d2j(jdn: Int): JDate {
        val gy = d2g(jdn).first
        var jy = gy - 621
        val r = jalCal(jy, false)
        val jdn1f = g2d(gy, 3, r.march)
        var k = jdn - jdn1f
        if (k >= 0) {
            if (k <= 185) {
                return JDate(jy, 1 + k / 31, k % 31 + 1)
            }
            k -= 186
        } else {
            jy -= 1
            k += 179
            if (r.leap == 1) k += 1
        }
        return JDate(jy, 7 + k / 30, k % 30 + 1)
    }

    fun fromGregorian(date: LocalDate): JDate =
        d2j(g2d(date.year, date.monthValue, date.dayOfMonth))

    fun toGregorian(date: JDate): LocalDate {
        val (gy, gm, gd) = d2g(j2d(date.year, date.month, date.day))
        return LocalDate.of(gy, gm, gd)
    }

    /** ۱۴۰۵/۰۸/۰۳ */
    fun format(date: LocalDate, persian: Boolean = true): String {
        val j = fromGregorian(date)
        val text = "%04d/%02d/%02d".format(j.year, j.month, j.day)
        return if (persian) Money.toPersian(text) else text
    }

    fun formatEpochDay(epochDay: Long, persian: Boolean = true): String =
        format(LocalDate.ofEpochDay(epochDay), persian)

    /** ۳ آبان ۱۴۰۵ */
    fun formatLong(epochDay: Long, persian: Boolean = true): String {
        val j = fromGregorian(LocalDate.ofEpochDay(epochDay))
        val text = "${j.day} ${monthName(j.month)} ${j.year}"
        return if (persian) Money.toPersian(text) else text
    }

    /** ۱۴۰۵/۰۸/۰۳ - ۱۲:۳۰ */
    fun formatDateTime(millis: Long, persian: Boolean = true): String {
        val dt = java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDateTime()
        val text = "${format(dt.toLocalDate(), false)} - %02d:%02d".format(dt.hour, dt.minute)
        return if (persian) Money.toPersian(text) else text
    }

    /** خروجی: LocalDate یا null */
    fun parse(input: String): LocalDate? {
        val latin = Money.toLatinDigits(input).replace('-', '/').replace('.', '/').trim()
        val parts = latin.split('/').filter { it.isNotBlank() }
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        // ورودی ۲۰۰۰/۰۱/۰۱ را هم بپذیر (کاربر ممکن است میلادی وارد کند)
        return if (y > 1700) {
            runCatching { LocalDate.of(y, m, d) }.getOrNull()
        } else {
            runCatching { toGregorian(JDate(y, m, d)) }.getOrNull()
        }
    }

    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

    fun addDays(date: LocalDate, days: Long): LocalDate = date.plusDays(days)
}
