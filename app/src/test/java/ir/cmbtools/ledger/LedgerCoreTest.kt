package ir.cmbtools.ledger

import ir.cmbtools.ledger.core.AppSettings
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.InvoiceKind
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.PanelSyncRules
import ir.cmbtools.ledger.sms.BankSmsParser
import ir.cmbtools.ledger.sms.SmsSender
import ir.cmbtools.ledger.data.Customer
import ir.cmbtools.ledger.data.Invoice
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
        assertEquals("۰ ریال", Money.format(0))
        // خروجی لاتین (برای CSV/Excel) باید کاما و رقم لاتین داشته باشد
        assertEquals("12,345", Money.format(12345, persian = false, withUnit = false))
        assertEquals("12345", Money.format(12345, persian = false, withUnit = false).replace(",", ""))
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

// ─────────────────────────────────────────────────────────────────────────────
//  منطق «همگام‌سازی + فاکتور خودکار»
// ─────────────────────────────────────────────────────────────────────────────

class PanelSyncRulesTest {

    @Test
    fun detects_renewal_only_when_expiry_moves_forward() {
        closeTo(1L, 0L)
        assertFalse(PanelSyncRules.isRenewal(0, 19000))          // نخستین بار
        assertFalse(PanelSyncRules.isRenewal(19000, 19000))       // بی‌تغییر
        assertFalse(PanelSyncRules.isRenewal(19000, 19000 + 1))   // تغییر ناچیز (ساعت/منطقه)
        assertTrue(PanelSyncRules.isRenewal(19000, 19031))        // ۳۰ روز جلو
        assertFalse(PanelSyncRules.isRenewal(19031, 19000))       // عقب رفتن = تمدید نیست
    }

    @Test
    fun matches_plan_by_gb_and_days() {
        val gbs = listOf(30.0, 50.0, 100.0)
        val days = listOf(30, 30, 60)
        assertEquals(0, PanelSyncRules.bestPlanIndex(gbs, days, 30.0, 30))
        assertEquals(1, PanelSyncRules.bestPlanIndex(gbs, days, 50.0, 30))
        assertEquals(2, PanelSyncRules.bestPlanIndex(gbs, days, 100.0, 60))
        // حجم کمی متفاوت (اعشار پنل) باید همان بسته را پیدا کند
        assertEquals(0, PanelSyncRules.bestPlanIndex(gbs, days, 30.4, 30))
        // روز متفاوت → نزدیک‌ترین حجم با همان روز، یا ۱-
        assertEquals(-1, PanelSyncRules.bestPlanIndex(gbs, days, 30.0, 45))
        assertEquals(-1, PanelSyncRules.bestPlanIndex(emptyList(), emptyList(), 30.0, 30))
    }

    @Test
    fun prices_from_per_gb_rate() {
        assertEquals(1_500_000L, PanelSyncRules.priceFromPerGb(30.0, 50_000))
        assertEquals(0L, PanelSyncRules.priceFromPerGb(30.0, 0))
        assertEquals(0L, PanelSyncRules.priceFromPerGb(0.0, 50_000))
    }

    @Test
    fun builds_package_label() {
        assertEquals("یک‌ماهه ۳۰ گیگ", PanelSyncRules.packageLabel("یک‌ماهه ۳۰ گیگ", 30.0, 30))
        assertEquals("30 گیگ / 30 روز", PanelSyncRules.packageLabel("", 30.0, 30))
        assertEquals("30 گیگ", PanelSyncRules.packageLabel("", 30.0, 0))
        assertEquals("سرویس اینترنت", PanelSyncRules.packageLabel("", 0.0, 0))
    }

    private fun closeTo(expected: Long, actual: Long) = assertEquals(expected, actual)
}

class InvoiceTemplateTest {

    private fun invoice(
        prevBalance: Long = 0,
        balanceAfter: Long = 0,
        total: Long = 1_500_000,
        card: String = "6037-9977-1234-5678",
        pkg: String = "یک‌ماهه ۳۰ گیگ",
        kind: String = InvoiceKind.RENEW
    ) = Invoice(
        id = 7,
        number = "1405-0042",
        customerId = 1,
        issuedAtEpochDay = LocalDate.of(2026, 10, 25).toEpochDay(),
        totalRial = total,
        subtotalRial = total,
        packageName = pkg,
        cardNumber = card,
        prevBalanceRial = prevBalance,
        balanceAfterRial = balanceAfter,
        kind = kind,
        issuedAtMillis = LocalDate.of(2026, 10, 25).atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli(),
        status = InvoiceStatus.ISSUED
    )

    private val customer = Customer(id = 1, name = "علی رضایی", phone = "09121234567")

    @Test
    fun renders_required_invoice_fields() {
        val body = SmsTemplate.render(AppSettings(), invoice(), customer, emptyList(), "")
        assertTrue(body.contains("علی رضایی"))
        assertTrue(body.contains("1405-0042"))
        assertTrue(body.contains("یک‌ماهه ۳۰ گیگ"))
        assertTrue(body.contains("6037-9977-1234-5678"))
        // تاریخ و ساعت (شمسی، با ارقام فارسی)
        val expectedDate = Jalali.format(LocalDate.of(2026, 10, 25))
        assertTrue("متن باید تاریخ $expectedDate را داشته باشد: $body", body.contains(expectedDate))
        // هیچ متغیر جایگزین‌نشده‌ای نباید بماند
        assertFalse(Regex("""\{[a-zA-Z]+}""").containsMatchIn(body))
    }

    @Test
    fun shows_previous_debt_only_when_there_was_one() {
        val withoutDebt = SmsTemplate.render(AppSettings(), invoice(), customer, emptyList(), "")
        assertFalse(withoutDebt.contains("مانده قبلی"))

        val withDebt = SmsTemplate.render(
            AppSettings(),
            invoice(prevBalance = 800_000, balanceAfter = 2_300_000),
            customer,
            emptyList(),
            ""
        )
        assertTrue(withDebt.contains("مانده قبلی"))
        assertTrue(withDebt.contains("جمع کل بدهی"))
        // ارقام فارسی شده‌اند
        assertTrue(withDebt.contains("۸۰۰٬۰۰۰"))
        assertTrue(withDebt.contains("۲٬۳۰۰٬۰۰۰"))
    }

    @Test
    fun drops_empty_lines_instead_of_leaving_labels() {
        val body = SmsTemplate.render(
            AppSettings(),
            invoice(card = ""),
            customer,
            emptyList(),
            ""
        )
        assertFalse(body.lines().any { it.trimEnd().endsWith(":") })
    }

    @Test
    fun defaults_to_structured_template() {
        val t = AppSettings.DEFAULT_INVOICE_TEMPLATE
        listOf("{customer}", "{invoiceNo}", "{datetime}", "{package}", "{total}", "{debt}", "{card}")
            .forEach { assertTrue("قالب پیش‌فرض باید $it را داشته باشد", t.contains(it)) }
    }
}
