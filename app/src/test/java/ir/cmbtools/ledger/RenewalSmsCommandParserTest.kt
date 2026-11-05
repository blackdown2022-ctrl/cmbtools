package ir.cmbtools.ledger

import ir.cmbtools.ledger.sms.RenewalSmsCommand
import ir.cmbtools.ledger.sms.RenewalSmsCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenewalSmsCommandParserTest {
    @Test
    fun parsesFourLineCommandWithPersianDigitsAndCrLf() {
        assertEquals(
            RenewalSmsCommand(customerName = "مشتری نمونه", gb = 30.0, discountRial = 3_000_000L),
            RenewalSmsCommandParser.parse("تمدید\r\nمشتری نمونه\r\n۳۰\r\n۳۰۰")
        )
    }

    @Test
    fun rejectsOldThreeLineFormatExtraLinesAndInvalidValues() {
        assertNull(RenewalSmsCommandParser.parse("تمدید\nمشتری نمونه\n۳۰"))
        assertNull(RenewalSmsCommandParser.parse("تمدید\nمشتری نمونه\n۳۰\nاضافی\n۱"))
        assertNull(RenewalSmsCommandParser.parse("تمدید\nمشتری نمونه\nصفر\n۰"))
        assertNull(RenewalSmsCommandParser.parse("تمدید\nمشتری نمونه\n۳۰\n۹۹۹۹۹۹۹۹۹۹۹۹۹۹۹۹۹۹۹۹۹"))
    }

    @Test
    fun acceptsArabicTextVariantsButRequiresExactCommandWord() {
        assertEquals(
            RenewalSmsCommand("مشتری نمونه", 20.0, 50_000L),
            RenewalSmsCommandParser.parse("تمد\u064aد\nمشتری نمونه\n٢٠\n٥")
        )
        assertNull(RenewalSmsCommandParser.parse("تمدید کن\nمشتری نمونه\n۲۰\n۰"))
    }
}
