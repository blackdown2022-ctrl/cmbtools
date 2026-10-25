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

    fun fromGregorian(date: LocalDate): JDate {
        val gy = date.year
        val gm = date.monthValue
        val gd = date.dayOfMonth
        val gDaysInMonth = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

        var jy = if (gy <= 1600) 0 else 979
        var gy2 = if (gy <= 1600) gy - 621 else gy - 1600
        var gm2 = gm - 1
        var daysNo = 365 * gy2 + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) - 80 + gd + gDaysInMonth[gm2]
        if (gm > 2 && isGregorianLeap(gy)) daysNo++

        jy += 33 * (daysNo / 12053)
        daysNo %= 12053
        jy += 4 * (daysNo / 1461)
        daysNo %= 1461
        if (daysNo > 365) {
            jy += (daysNo - 1) / 365
            daysNo = (daysNo - 1) % 365
        }
        val jm = if (daysNo < 186) 1 + daysNo / 31 else 7 + (daysNo - 186) / 30
        val jd = 1 + if (daysNo < 186) daysNo % 31 else (daysNo - 186) % 30
        return JDate(jy, jm, jd)
    }

    fun toGregorian(date: JDate): LocalDate {
        val jy = date.year
        val jm = date.month
        val jd = date.day
        var gy = if (jy <= 979) 621 else 1600
        val y = if (jy <= 979) jy else jy - 979
        var daysNo = 365 * y + ((y + 3) / 4) - ((y + 99) / 100) + ((y + 399) / 400) - 80 + jd +
            (if (jm < 7) (jm - 1) * 31 else (jm - 7) * 30 + 186)
        gy += 400 * (daysNo / 146097)
        daysNo %= 146097
        var leap = true
        if (daysNo >= 36525) {
            daysNo -= 36525
            gy += 100 * (daysNo / 36524)
            daysNo %= 36524
            if (daysNo >= 365) daysNo++ else leap = false
        }
        gy += 4 * (daysNo / 1461)
        daysNo %= 1461
        if (daysNo >= 366) {
            leap = false
            daysNo -= 366
            gy += (daysNo / 365)
            daysNo %= 365
        }
        var gd = daysNo + 1
        val monthDays = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 0
        while (gm < 12 && gd > monthDays[gm]) {
            gd -= monthDays[gm]
            gm++
        }
        return LocalDate.of(gy, gm + 1, gd)
    }

    private fun isGregorianLeap(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

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
