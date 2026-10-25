package ir.cmbtools.ledger

import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.sms.BankSmsParser
import ir.cmbtools.ledger.sms.SmsSender
import ir.cmbtools.ledger.sms.SmsTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MoneyTest {

    @Test
    fun parses_persian_and_arabic_digits() {
        assertEquals(1234567L, Money.parseRial("۱,۲۳۴,۵۶۷"))
        assertEquals(1234567L, Money.parseRial("١٢٣٤٥٦٧"))
        assertEquals(500L, Money.parseRial(" ۵۰۰ "))
    }

    @Test
    fun formats_with_grouping_and_persian_digits() {
        assertEquals("۱۲٬۳۴۵ ریال", Money.format(12345))
        assertEquals("0 ریال", Money.format(0))
    }

    @Test
    fun toman_rial_conversion_is_lossless_for_whole_tomans() {
        assertEquals(120_000L, Money.tomanToRial(12_000L))
        assertEquals(12_000L, Money.rialToToman(120_000L))
    }

    @Test
    fun parse_rejects_garbage() {
        assertNull(Money.parseRial(""))
        assertNull(Money.parseRial("abc"))
    }
}

class JalaliTest {

    @Test
    fun converts_known_dates() {
        val j = Jalali.fromGregorian(LocalDate.of(2026, 10, 25))
        assertEquals(1405, j.year)
        assertEquals(8, j.month)
        assertEquals(3, j.day)

        val g = Jalali.toGregorian(Jalali.JDate(1404, 1, 1))
        assertEquals(LocalDate.of(2025, 3, 21), g)
    }

    @Test
    fun round_trip_is_stable() {
        var date = LocalDate.of(2020, 1, 1)
        repeat(400) {
            val j = Jalali.fromGregorian(date)
            assertEquals(date, Jalali.toGregorian(j))
            date = date.plusDays(17)
        }
    }

    @Test
    fun parses_persian_input() {
        val parsed = Jalali.parse("۱۴۰۵/۰۸/۰۳")
        assertNotNull(parsed)
        assertEquals(LocalDate.of(2026, 10, 25), parsed)
        assertNull(Jalali.parse("نامعتبر"))
    }
}

class BankSmsParserTest {

    @Test
    fun detects_deposit_with_rial_unit() {
        val body = "بانک ملت\nواریز: ۱٬۲۰۰٬۰۰۰ ریال\nبه حساب ۱۲۳۴\nموجودی: ۳٬۴۰۰٬۰۰۰ ریال"
        val parsed = BankSmsParser.parse("bankmellat", body, 0)
        assertEquals(1_200_000L, parsed.amountRial)
        assertTrue(parsed.isCredit)
        assertEquals("1234", parsed.last4)
        assertEquals(3_400_000L, parsed.balanceRial)
    }

    @Test
    fun assumes_toman_when_unit_missing() {
        val body = "واریز ۵۰۰٬۰۰۰ به حساب شما"
        val parsed = BankSmsParser.parse("9999", body, 0)
        assertEquals(5_000_000L, parsed.amountRial)
        assertTrue(parsed.isCredit)
    }

    @Test
    fun rejects_withdrawal() {
        val body = "برداشت ۲۰۰٬۰۰۰ ریال از کارت شما"
        val parsed = BankSmsParser.parse("bankmelli", body, 0)
        assertFalse(parsed.isCredit)
    }

    @Test
    fun rejects_otp() {
        val body = "رمز پویا: 123456"
        val parsed = BankSmsParser.parse("bankmelli", body, 0)
        assertTrue(parsed.isOtp)
        assertFalse(parsed.isCredit)
    }
}

class SmsTemplateTest {

    @Test
    fun counts_parts_by_language() {
        assertEquals(1, SmsTemplate.parts("کوتاه"))
        assertTrue(SmsTemplate.parts("ا".repeat(200)) > 1)
        assertEquals(1, SmsTemplate.parts("a".repeat(160)))
    }

    @Test
    fun normalizes_phone_numbers() {
        assertEquals("09121234567", SmsSender.normalizePhone("+989121234567"))
        assertEquals("09121234567", SmsSender.normalizePhone("۰۹۱۲۱۲۳۴۵۶۷"))
        assertTrue(SmsSender.isValidIranMobile("9121234567"))
        assertFalse(SmsSender.isValidIranMobile("021123456"))
    }
}
