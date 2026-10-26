package ir.cmbtools.ledger

import ir.cmbtools.ledger.sms.RenewalSmsCommand
import ir.cmbtools.ledger.sms.RenewalSmsCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenewalSmsCommandParserTest {
    @Test
    fun parsesThreeLineCommandWithPersianDigitsAndCrLf() {
        assertEquals(
            RenewalSmsCommand(customerName = "مشتری نمونه", gb = 30.0),
            RenewalSmsCommandParser.parse("تمدید\r\nمشتری نمونه\r\n۳۰")
        )
    }

    @Test
    fun rejectsExtraLinesAndInvalidVolume() {
        assertNull(RenewalSmsCommandParser.parse("تمدید\nمشتری نمونه\n۳۰\nاضافی"))
        assertNull(RenewalSmsCommandParser.parse("تمدید\nمشتری نمونه\nصفر"))
    }

    @Test
    fun acceptsArabicTextVariantsButRequiresExactCommandWord() {
        assertEquals(
            RenewalSmsCommand("مشتری نمونه", 20.0),
            RenewalSmsCommandParser.parse("تمد\u064aد\nمشتری نمونه\n٢٠")
        )
        assertNull(RenewalSmsCommandParser.parse("تمدید کن\nمشتری نمونه\n۲۰"))
    }
}
