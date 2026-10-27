package ir.cmbtools.ledger

import ir.cmbtools.ledger.core.AppSettings
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.InvoiceKind
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.PanelSyncRules
import ir.cmbtools.ledger.core.Phone
import ir.cmbtools.ledger.sms.AdminSmsCommandParser
import ir.cmbtools.ledger.sms.AdminSmsReportFormatter
import ir.cmbtools.ledger.sms.BankSmsParser
import ir.cmbtools.ledger.sms.RenewalSmsCommandParser
import ir.cmbtools.ledger.sms.SmsSender
import ir.cmbtools.ledger.data.Customer
import ir.cmbtools.ledger.data.Invoice
import ir.cmbtools.ledger.panel.PanelUrl
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
    fun parses_postbank_credit_line_separately_from_balance() {
        val body = """پست بانک

واريز به کارت: 2972

+1,006,320

1405/08/4

19:51

مانده: 7,425,882 ريال"""
        val parsed = BankSmsParser.parse("unknown", body, 0)

        assertEquals("پست بانک", parsed.bank)
        assertEquals(1_006_320L, parsed.amountRial)
        assertEquals(7_425_882L, parsed.balanceRial)
        assertEquals("2972", parsed.last4)
        assertTrue(parsed.isCredit)
    }

    @Test
    fun postbank_balance_is_never_used_when_credit_line_is_not_seven_digits() {
        val body = """پست بانک
واريز به کارت: 2972
+100632
1405/08/4
19:51
مانده: 7,425,882 ريال"""
        val parsed = BankSmsParser.parse("postbank", body, 0)

        assertEquals(0L, parsed.amountRial)
        assertEquals(7_425_882L, parsed.balanceRial)
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

class AdminSmsCommandParserTest {

    @Test
    fun parses_manual_deposit_amount_in_ten_thousand_rial_units() {
        val command = AdminSmsCommandParser.payment("واریز\nمحسن علیزاده\n۳۰۰")
        assertNotNull(command)
        assertEquals("محسن علیزاده", command?.customerName)
        assertEquals(3_000_000L, command?.amountRial)
    }

    @Test
    fun rejects_invalid_or_overflowing_manual_deposits() {
        assertNull(AdminSmsCommandParser.payment("واریز\nمحسن علیزاده\n0"))
        assertNull(AdminSmsCommandParser.payment("واریز\nمحسن علیزاده\n300ریال"))
        assertNull(AdminSmsCommandParser.payment("واریز\nمحسن علیزاده\n999999999999999999"))
    }

    @Test
    fun recognizes_balance_request_and_keeps_customer_name_exact() {
        assertEquals(AdminSmsCommandParser.Kind.BALANCE, AdminSmsCommandParser.kind("مانده\nعلی رضایی"))
        assertEquals("علی رضایی", AdminSmsCommandParser.balance("مانده\nعلی رضایی")?.customerName)
        assertNull(AdminSmsCommandParser.balance("مانده\n"))
    }

    @Test
    fun keeps_renewal_command_format_three_lines() {
        val command = RenewalSmsCommandParser.parse("تمدید\nمحسن علیزاده\n۵۰")
        assertNotNull(command)
        assertEquals(50.0, command?.gb ?: 0.0, 0.0)
    }

    @Test
    fun formats_renewal_payment_and_balance_reports_as_requested() {
        assertEquals(
            "تمدید شد\nمحسن علیزاده\nیک ماهه 50 گیگ",
            AdminSmsReportFormatter.renewal("محسن علیزاده", "یک ماهه 50 گیگ", 50.0, 30)
        )
        assertEquals(
            "ثبت شد\nمحسن علیزاده\n۳٬۰۰۰٬۰۰۰ ریال\nمانده: ۱٬۰۰۰٬۰۰۰ ریال",
            AdminSmsReportFormatter.payment("محسن علیزاده", 3_000_000, 1_000_000)
        )
        assertEquals(
            "مانده حساب\nمحسن علیزاده\nمانده: ۱٬۰۰۰٬۰۰۰ ریال",
            AdminSmsReportFormatter.balance("محسن علیزاده", 1_000_000)
        )
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
        assertFalse(PanelSyncRules.isRenewal(0, 19000))          // نخستین بار
        assertFalse(PanelSyncRules.isRenewal(19000, 19000))       // بی‌تغییر
        assertFalse(PanelSyncRules.isRenewal(19000, 19000 + 1))   // تغییر ناچیز (ساعت/منطقه)
        assertTrue(PanelSyncRules.isRenewal(19000, 19031))        // ۳۰ روز جلو
        assertFalse(PanelSyncRules.isRenewal(19031, 19000))       // عقب رفتن بدون ریست مصرف = تمدید نیست
    }

    @Test
    fun detects_renewal_when_panel_clears_expiry_and_usage() {
        // تمدید از داخل پنل با start_date = null: انقضا صفر و مصرف صفر می‌شود
        assertTrue(PanelSyncRules.isRenewal(19000, 0, oldUsageGb = 12.5, newUsageGb = 0.0))
        // اگر مصرف صفر نشده بود، تمدید نیست
        assertFalse(PanelSyncRules.isRenewal(19000, 0, oldUsageGb = 12.5, newUsageGb = 12.4))
        // کاربر تازه (انقضای قبلی صفر) هرگز «تمدید» حساب نمی‌شود
        assertFalse(PanelSyncRules.isRenewal(0, 19030, oldUsageGb = 5.0, newUsageGb = 0.0))
    }

    @Test
    fun renewal_means_days_and_volume_are_reset() {
        // ۱) دکمهٔ تمدید پنل/اپ: start_date پاک می‌شود و روزها از نو شمرده می‌شوند
        assertTrue(
            PanelSyncRules.isRenewal(
                oldExpireEpochDay = 20_100,
                newExpireEpochDay = 20_030,
                oldUsageGb = 0.0,
                newUsageGb = 0.0,
                oldStartEpochDay = 19_900,
                newStartEpochDay = 0,
                oldDays = 30,
                newDays = 30
            )
        )

        // ۲) کاربر استفاده‌نشده: انقضای قبلی دورتر است ولی روزها از نو شده → تمدید
        assertTrue(
            PanelSyncRules.isRenewal(
                oldExpireEpochDay = 20_200,
                newExpireEpochDay = 20_030,
                oldUsageGb = 0.0,
                newUsageGb = 0.0,
                oldStartEpochDay = 19_900,
                newStartEpochDay = 0,
                oldDays = 30,
                newDays = 30
            )
        )

        // ۳) ریست هم‌زمان حجم و روز (مصرف صفر + بسته عوض شده) → تمدید
        assertTrue(
            PanelSyncRules.isRenewal(
                oldExpireEpochDay = 20_100,
                newExpireEpochDay = 20_100,
                oldUsageGb = 12.0,
                newUsageGb = 0.0,
                oldStartEpochDay = 19_900,
                newStartEpochDay = 19_900,
                oldDays = 30,
                newDays = 60
            )
        )

        // ۴) فقط مصرف صفر شده، روز/بسته دست‌نخورده → تمدید نیست (ریست مصرف، نه تمدید)
        assertFalse(
            PanelSyncRules.isRenewal(
                oldExpireEpochDay = 20_100,
                newExpireEpochDay = 20_100,
                oldUsageGb = 12.0,
                newUsageGb = 0.0,
                oldStartEpochDay = 19_900,
                newStartEpochDay = 19_900,
                oldDays = 30,
                newDays = 30
            )
        )

        // ۵) بستهٔ شروع‌نشده: انقضای «پیش‌بینی‌شده» هر روز جلو می‌رود؛
        //    تا وقتی خود بسته عوض نشده، تمدید نیست (وگرنه هر روز فاکتور الکی)
        assertFalse(
            PanelSyncRules.isRenewal(
                oldExpireEpochDay = 20_030,
                newExpireEpochDay = 20_033,
                oldUsageGb = 0.0,
                newUsageGb = 0.0,
                oldStartEpochDay = 0,
                newStartEpochDay = 0,
                oldDays = 30,
                newDays = 30,
                oldNotStarted = true,
                newNotStarted = true
            )
        )
        //    ولی اگر بسته عوض شود (تمدید بستهٔ شروع‌نشده) → تمدید
        assertTrue(
            PanelSyncRules.isRenewal(
                oldExpireEpochDay = 20_030,
                newExpireEpochDay = 20_060,
                oldStartEpochDay = 0,
                newStartEpochDay = 0,
                oldDays = 30,
                newDays = 60,
                oldNotStarted = true,
                newNotStarted = true
            )
        )
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
        number = "42",
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
        // شمارهٔ فاکتور فارسی است (خواستهٔ کاربر) ولی شمارهٔ کارت لاتین می‌ماند
        assertTrue(body.contains(Money.toPersian("42")))
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
        // نام فروشگاه و لینک اشتراک از قالب حذف شده‌اند
        assertFalse(t.contains("{business}"))
        assertFalse(t.contains("{link}"))
        // خط اول فقط نام مشتری است
        assertEquals("{customer}", t.lines().first().trim())
    }

    @Test
    fun invoice_sms_starts_with_customer_name_without_shop_or_link() {
        val settings = AppSettings(businessName = "فروشگاه من", invoiceFooter = "با تشکر")
        val body = SmsTemplate.render(
            settings,
            invoice(),
            customer,
            emptyList(),
            "https://srv1.example.com/client987/8f14e45f-1111-2222-3333-444455556666/"
        )
        // ۱) خط اول فقط نام مشتری
        assertEquals("علی رضایی", body.lines().first().trim())
        // ۲) نام فروشگاه هرگز در پیامک نمی‌آید
        assertFalse("نام فروشگاه نباید در پیامک باشد: $body", body.contains("فروشگاه من"))
        // ۳) لینک اشتراک هم نمی‌آید
        assertFalse("لینک اشتراک نباید در پیامک باشد: $body", body.contains("client987"))
        assertFalse(body.contains("لینک اشتراک"))
        assertFalse(body.contains("http"))
        // بقیهٔ اطلاعات فاکتور سر جایشان هستند
        assertTrue(body.contains("6037-9977-1234-5678"))
        assertTrue(body.contains(Money.toPersian("42")))
    }

    @Test
    fun shop_name_and_link_are_dropped_from_old_custom_templates_too() {
        // قالب قدیمی دستی که کاربر ذخیره کرده بود
        val old = "{business}\n{customer} عزیز\nمبلغ: {total}\n{link}\n{footer}"
        val body = SmsTemplate.render(
            AppSettings(businessName = "فروشگاه من", invoiceTemplate = old),
            invoice(),
            customer,
            emptyList(),
            "https://panel.example.com/client987/abc/"
        )
        assertFalse(body.contains("فروشگاه من"))
        assertFalse(body.contains("client987"))
        assertEquals("علی رضایی عزیز", body.lines().first().trim())
    }

    @Test
    fun previous_default_templates_are_replaced_by_the_new_one() {
        // قالب‌های پیش‌فرض نسخه‌های قبل باید با قالب جدید یکی نباشند تا مهاجرت انجام شود
        assertTrue(AppSettings.LEGACY_INVOICE_TEMPLATE.contains("{business}"))
        assertTrue(AppSettings.V03_INVOICE_TEMPLATE.contains("{business}"))
        assertTrue(AppSettings.V03_INVOICE_TEMPLATE.contains("{link}"))
        assertFalse(AppSettings.DEFAULT_INVOICE_TEMPLATE.contains("{business}"))
        assertFalse(AppSettings.DEFAULT_INVOICE_TEMPLATE.contains("{link}"))
    }
}


// ─────────────────────────────────────────────────────────────────────────────
//  شمارهٔ موبایل: نرمال‌سازی + خواندن از «یادداشت/کامنت» کاربر پنل
// ─────────────────────────────────────────────────────────────────────────────

class PhoneTest {

    @Test
    fun normalizes_all_common_formats() {
        assertEquals("09121234567", Phone.normalize("09121234567"))
        assertEquals("09121234567", Phone.normalize("۰۹۱۲۱۲۳۴۵۶۷"))     // ارقام فارسی
        assertEquals("09121234567", Phone.normalize("+989121234567"))
        assertEquals("09121234567", Phone.normalize("00989121234567"))
        assertEquals("09121234567", Phone.normalize("989121234567"))
        assertEquals("09121234567", Phone.normalize("9121234567"))
        assertEquals("09121234567", Phone.normalize("0912-123-4567"))
        assertEquals("09121234567", Phone.normalize(" 0912 123 4567 "))
        assertEquals("", Phone.normalize("021123456"))                  // تلفن ثابت
        assertEquals("", Phone.normalize(""))
    }

    @Test
    fun extracts_phone_from_panel_comment() {
        // یادداشت کاربر در پنل: گاهی فقط شماره، گاهی با متن اضافه
        assertEquals("09121234567", Phone.extract("09121234567"))
        assertEquals("09121234567", Phone.extract("شماره: 09121234567"))
        assertEquals("09121234567", Phone.extract("علی - ۰۹۱۲۱۲۳۴۵۶۷ - یک‌ماهه"))
        assertEquals("09121234567", Phone.extract("+98 912 123 4567"))
        assertEquals("", Phone.extract("cmbtools:12:علی رضایی"))
        assertEquals("", Phone.extract(""))
    }

    @Test
    fun validates_mobile_numbers() {
        assertTrue(Phone.isValid("09121234567"))
        assertTrue(Phone.isValid("+989121234567"))
        assertFalse(Phone.isValid("02188776655"))
        assertFalse(Phone.isValid("12345"))
    }
}

/**
 * تست‌های این دور: تجزیهٔ آدرس پنل، جداکنندهٔ سه‌رقمی مبلغ، و کامل بودن فایل پشتیبان.
 */
class PanelUrlTest {

    @Test
    fun keeps_plain_domain() {
        val p = PanelUrl.normalize("panel.example.com", "AbCdEf")
        assertEquals("panel.example.com", p.host)
        assertEquals("AbCdEf", p.proxyPath)
        assertTrue(p.useHttps)
    }

    @Test
    fun strips_scheme_and_trailing_slash() {
        val p = PanelUrl.normalize("https://panel.example.com/", "AbCdEf/")
        assertEquals("panel.example.com", p.host)
        assertEquals("AbCdEf", p.proxyPath)
        assertTrue(p.useHttps)
    }

    @Test
    fun splits_host_that_contains_proxy_path() {
        // کاربر ممکن است لینک Api را کامل در فیلد دامنه بچسباند
        val p = PanelUrl.normalize("https://panel.example.com/AbCdEf/api/v2/admin/users/", "")
        assertEquals("panel.example.com", p.host)
        assertEquals("AbCdEf", p.proxyPath)
    }

    @Test
    fun removes_api_suffix_from_proxy_path() {
        assertEquals("ZyXw12", PanelUrl.cleanPath("ZyXw12/api/v2/admin"))
        assertEquals("ZyXw12", PanelUrl.cleanPath("/ZyXw12/"))
        assertEquals("", PanelUrl.cleanPath("api/v2/admin"))
    }

    @Test
    fun recognizes_http() {
        val p = PanelUrl.normalize("http://192.168.1.10:8000", "AbC")
        assertEquals("192.168.1.10:8000", p.host)
        assertFalse(p.useHttps)
    }

    @Test
    fun panel_url_with_admin_suffix_gives_same_proxy_path() {
        // آدرسی که در مرورگر با /admin باز می‌شود
        val a = PanelUrl.normalize("https://panel.example.com/AbCdEf12/admin/", "")
        assertEquals("panel.example.com", a.host)
        assertEquals("AbCdEf12", a.proxyPath)
        assertEquals("https://panel.example.com/AbCdEf12", a.panelUrl)
    }

    @Test
    fun browser_url_with_admin_and_hash_is_understood() {
        // آدرسی که معمولاً در مرورگر دیده می‌شود: /AbCdEf12/admin/#/users
        val a = PanelUrl.normalize("https://panel.example.com/AbCdEf12/admin/#/users", "")
        assertEquals("AbCdEf12", a.proxyPath)
        assertEquals("panel.example.com", a.host)
        assertEquals("https://panel.example.com/AbCdEf12", a.panelUrl)
        // و بدون اسلش/کوِری
        assertEquals("AbCdEf12", PanelUrl.cleanPath("AbCdEf12/admin/?x=1"))
    }

    @Test
    fun create_user_url_is_built_from_admin_path() {
        // «آدرس ساخت کاربر» همیشه زیر مسیر ادمین است، نه مسیر اشتراک
        val a = PanelUrl.normalize("https://panel.example.com/AbCdEf12", "")
        assertEquals(
            "https://panel.example.com/AbCdEf12/api/v2/admin/users/",
            a.panelUrl + "/api/v2/admin/users/"
        )
    }

    @Test
    fun subscription_base_is_normalized_separately() {
        // پایهٔ لینک اشتراک چیز مستقلی است و با مسیر ادمین یکی نمی‌شود
        assertEquals("https://sub.example.com/xy12/", PanelUrl.normalizeLinkBase("sub.example.com/xy12"))
        assertEquals("https://sub.example.com/xy12/", PanelUrl.normalizeLinkBase("https://sub.example.com/xy12/"))
        assertEquals("", PanelUrl.normalizeLinkBase("   "))
        assertEquals("http://95.1.2.3:8080/x/", PanelUrl.normalizeLinkBase("http://95.1.2.3:8080/x"))
    }

    @Test
    fun customer_link_is_base_plus_uuid() {
        val uuid = "11111111-2222-3333-4444-555555555555"
        val base = PanelUrl.normalizeLinkBase("https://sub.example.com/xy12/")
        assertEquals("https://sub.example.com/xy12/$uuid/", base + uuid + "/")
    }

    @Test
    fun panel_config_keeps_api_and_link_bases_apart() {
        val config = ir.cmbtools.ledger.data.PanelConfig(
            host = "panel.example.com",
            adminProxyPath = "AbCdEf12",
            apiKey = "key",
            subscriptionBase = "https://sub.example.com/xy12/"
        )
        assertEquals("https://panel.example.com/AbCdEf12", config.panelUrl)
        // مسیر ادمین در لینک اشتراک تکرار نمی‌شود
        assertFalse(config.subscriptionBase.contains("AbCdEf12"))
    }
}

/**
 * تست‌های سازگاری نسخه‌های پنل (۱۱ تا ۱۴): مسیرها، سبک API و لینک اشتراک.
 */
class PanelCompatTest {

    private fun target() = ir.cmbtools.ledger.panel.PanelTarget.of(
        ir.cmbtools.ledger.data.PanelConfig(host = "panel.example.com", adminProxyPath = "AbC123", apiKey = "k")
    )

    @Test
    fun proxy_path_is_always_a_single_segment() {
        // هیدی‌فای فقط تکهٔ اول مسیر را می‌خواند؛ بقیه آدرس را به ۴۰۴ می‌برد
        assertEquals("AbC123", PanelUrl.cleanPath("AbC123/8f14e45f-1111-2222-3333-444455556666/"))
        assertEquals("AbC123", PanelUrl.cleanPath("AbC123/admin/users"))
        assertEquals("AbC123", PanelUrl.cleanPath("/AbC123/"))
        assertEquals("AbC123", PanelUrl.cleanPath("AbC123?x=1#y"))
        assertEquals("", PanelUrl.cleanPath("api/v2/admin/users"))
    }

    @Test
    fun subscription_link_pasted_as_panel_url_keeps_first_segment() {
        val p = PanelUrl.normalize("https://panel.example.com/client987/8f14e45f-1111-2222-3333-444455556666/", "")
        assertEquals("panel.example.com", p.host)
        assertEquals("client987", p.proxyPath)
    }

    @Test
    fun admin_api_urls_are_built_per_version() {
        val t = target()
        assertEquals("https://panel.example.com/AbC123/api/v2/admin/me/", t.url(ir.cmbtools.ledger.panel.PanelRoutes.ME))
        assertEquals("https://panel.example.com/AbC123/api/v2/admin/users/", t.url(ir.cmbtools.ledger.panel.PanelRoutes.USERS_PAGE))
        assertEquals("https://panel.example.com/AbC123/api/v2/admin/user/", t.url(ir.cmbtools.ledger.panel.PanelRoutes.USERS_LEGACY))
        assertEquals("https://panel.example.com/AbC123/api/v1/user/", t.url(ir.cmbtools.ledger.panel.PanelRoutes.V1_USERS))
        assertEquals(
            "https://panel.example.com/AbC123/api/v2/admin/users/u-1/",
            t.url(ir.cmbtools.ledger.panel.PanelRoutes.userPage("u-1"))
        )
        assertEquals(
            "https://panel.example.com/AbC123/api/v2/admin/user/u-1/",
            t.url(ir.cmbtools.ledger.panel.PanelRoutes.userLegacy("u-1"))
        )
    }

    @Test
    fun flavors_round_trip_from_stored_string() {
        assertEquals(ir.cmbtools.ledger.panel.ApiFlavor.PAGE, ir.cmbtools.ledger.panel.ApiFlavor.from("PAGE"))
        assertEquals(ir.cmbtools.ledger.panel.ApiFlavor.LEGACY, ir.cmbtools.ledger.panel.ApiFlavor.from("LEGACY"))
        assertEquals(ir.cmbtools.ledger.panel.ApiFlavor.V1, ir.cmbtools.ledger.panel.ApiFlavor.from("V1"))
        assertNull(ir.cmbtools.ledger.panel.ApiFlavor.from(""))
        assertNull(ir.cmbtools.ledger.panel.ApiFlavor.from(null))
    }

    @Test
    fun subscription_link_is_base_plus_uuid() {
        val uuid = "8f14e45f-1111-2222-3333-444455556666"
        assertEquals(
            "https://panel.example.com/client987/$uuid/",
            PanelUrl.subscriptionLink("panel.example.com/client987", uuid)
        )
        // مسیر ادمین هرگز در لینک مشتری تکرار نمی‌شود
        assertFalse(PanelUrl.subscriptionLink("https://sub.example.com/x/", uuid).contains("AbC123"))
    }

    @Test
    fun expire_is_computed_when_panel_does_not_send_it() {
        // نسخه‌های ۱۱ تا ۱۳ فیلد expire_date / remaining_days / status ندارند
        val start = LocalDate.now().minusDays(10)
        val legacyRow = ir.cmbtools.ledger.panel.PanelUserDto(
            uuid = "u1",
            name = "ali",
            packageDays = 30,
            startDate = start.toString(),
            remainingDays = 0,
            status = "",
            usageLimitGb = 30.0,
            currentUsageGb = 3.0
        )
        assertEquals(start.toEpochDay() + 30, legacyRow.expireEpochDay)
        assertEquals(20, legacyRow.remainingDaysOrComputed)
        assertEquals("active", legacyRow.statusOrComputed)

        // مصرف بیشتر از حجم → no_data
        assertEquals("no_data", legacyRow.copy(currentUsageGb = 31.0).statusOrComputed)
        // غیرفعال‌شده
        assertEquals("disabled", legacyRow.copy(enable = false).statusOrComputed)
        // تاریخ گذشته
        assertEquals("expired", legacyRow.copy(startDate = LocalDate.now().minusDays(60).toString()).statusOrComputed)

        // نسخهٔ ۱۴ این فیلدها را مستقیم می‌دهد
        val v14Row = ir.cmbtools.ledger.panel.PanelUserDto(
            uuid = "u2",
            name = "reza",
            packageDays = 30,
            startDate = "2026-01-01",
            expireDate = "2026-02-15",
            remainingDays = 5,
            status = "active"
        )
        assertEquals(LocalDate.parse("2026-02-15").toEpochDay(), v14Row.expireEpochDay)
        assertEquals(5, v14Row.remainingDaysOrComputed)
        assertEquals("active", v14Row.statusOrComputed)
    }

    @Test
    fun legacy_panel_answers_unknown_route_with_500_and_404_text() {
        // این دقیقاً همان پاسخی است که پنل v11.0.13 برای /api/v2/admin/users/ داد:
        //   HTTP 500  →  {"msg":"404 Not Found: The requested URL was not found on the server..."}
        // errorhandler(Exception) در hiddifypanel/panel/common.py این را ۵۰۰ می‌کند؛
        // پس «مسیر نیست» را نباید با کد ۴۰۴ تنها تشخیص داد.
        val legacyMissing = ir.cmbtools.ledger.panel.HttpResult(
            url = "https://srv1.example.com/wD8/api/v2/admin/users/",
            code = 500,
            body = """{"msg":"404 Not Found: The requested URL was not found on the server. If you entered the URL manually please check your spelling and try again."}""",
            contentType = "application/json"
        )
        assertTrue(legacyMissing.isMissingRoute)
        assertFalse(legacyMissing.isEmptyUserList)
        assertFalse(legacyMissing.ok)

        // ۴۰۴ خالص و ۴۰۵ هم یعنی «این مسیر در این نسخه نیست»
        assertTrue(legacyMissing.copy(code = 404, body = """{"message":"Not Found"}""").isMissingRoute)
        assertTrue(legacyMissing.copy(code = 405, body = "").isMissingRoute)

        // «شما کاربری ندارید» خطا نیست: فهرست خالی است
        val emptyList = ir.cmbtools.ledger.panel.HttpResult(
            url = "https://srv1.example.com/wD8/api/v2/admin/user/",
            code = 404,
            body = """{"message":"You have no user"}""",
            contentType = "application/json"
        )
        assertTrue(emptyList.isEmptyUserList)
        assertFalse(emptyList.isMissingRoute)

        // ۵۰۰ معمولی (یعنی واقعاً خطای سرور) نباید «مسیر نیست» شمرده شود
        val realError = legacyMissing.copy(code = 500, body = """{"msg":"database is locked"}""")
        assertFalse(realError.isMissingRoute)

        // ۴۰۰ invalid request = مسیر پروکسی ادمین نیست (خطای تنظیمات، نه نسخه)
        val wrongPath = legacyMissing.copy(code = 400, body = """{"msg":"invalid request"}""")
        assertTrue(wrongPath.isInvalidProxyPath)
        assertFalse(wrongPath.isMissingRoute)
    }

    @Test
    fun not_started_package_follows_panel_semantics() {
        // نسخه‌های ۱۱–۱۳ تا اولین اتصال کاربر start_date را null می‌گذارند و
        // طبق User.remaining_days در models/user.py: «روزهای باقی‌مانده = package_days»
        val fresh = ir.cmbtools.ledger.panel.PanelUserDto(
            uuid = "u3",
            name = "new user",
            packageDays = 30,
            startDate = null,
            usageLimitGb = 30.0,
            currentUsageGb = 0.0
        )
        assertTrue(fresh.notStarted)
        assertEquals(30, fresh.remainingDaysOrComputed)
        assertEquals(LocalDate.now().toEpochDay() + 30, fresh.expireEpochDay)
        assertEquals("active", fresh.statusOrComputed)

        // بعد از اولین اتصال، پنل start_date می‌دهد و حساب دقیق می‌شود
        val started = fresh.copy(startDate = LocalDate.now().minusDays(7).toString())
        assertFalse(started.notStarted)
        assertEquals(23, started.remainingDaysOrComputed)
        assertEquals(LocalDate.now().minusDays(7).toEpochDay() + 30, started.expireEpochDay)
    }

    @Test
    fun panel_config_exposes_full_url_for_each_flavor() {
        val cfg = ir.cmbtools.ledger.data.PanelConfig(
            host = "panel.example.com",
            adminProxyPath = "AbC123",
            apiKey = "key",
            apiFlavor = "LEGACY",
            subscriptionBase = "https://panel.example.com/client987/"
        )
        assertEquals("https://panel.example.com/AbC123", cfg.panelUrl)
        assertTrue(cfg.apiFlavor == "LEGACY")
    }
}

class MoneyGroupingTest {

    @Test
    fun groups_every_three_digits() {
        assertEquals("۱٬۲۳۴٬۵۶۷", Money.groupDigits("1234567"))
        assertEquals("۱۲۳", Money.groupDigits("123"))
        assertEquals("۱٬۰۰۰", Money.groupDigits("1000"))
        assertEquals("۱۲٬۳۴۵٬۶۷۸٬۹۰۱", Money.groupDigits("12345678901"))
    }

    @Test
    fun accepts_persian_input_and_ignores_separators() {
        assertEquals("۱۲٬۳۴۵", Money.groupDigits("۱۲٬۳۴۵"))
        assertEquals("۱۲٬۳۴۵", Money.groupDigits("12 345"))
    }

    @Test
    fun trims_leading_zeros_and_handles_empty() {
        assertEquals("۵۰۰", Money.groupDigits("0500"))
        assertEquals("", Money.groupDigits(""))
        assertEquals("۰", Money.groupDigits("0"))
    }
}

class BackupPayloadTest {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun backup_includes_settings_and_panel_config() {
        val payload = ir.cmbtools.ledger.data.BackupPayload(
            customers = listOf(Customer(name = "علی", phone = "09121234567")),
            panelConfig = ir.cmbtools.ledger.data.PanelConfig(
                host = "panel.example.com",
                adminProxyPath = "AbCdEf",
                apiKey = "0000-1111",
                useHttps = true
            ),
            settings = AppSettings(businessName = "فروشگاه من", paymentCardNumber = "6037-9977-1234-5678"),
            invoiceNumberHighWatermark = 9
        )
        val text = json.encodeToString(ir.cmbtools.ledger.data.BackupPayload.serializer(), payload)
        assertTrue(text.contains("\"settings\""))
        assertTrue(text.contains("panel.example.com"))
        assertTrue(text.contains("6037-9977-1234-5678"))

        val back = json.decodeFromString(ir.cmbtools.ledger.data.BackupPayload.serializer(), text)
        assertEquals("panel.example.com", back.panelConfig?.host)
        assertEquals("فروشگاه من", back.settings?.businessName)
        assertEquals(9L, back.invoiceNumberHighWatermark)
        assertEquals(1, back.customers.size)
    }

    @Test
    fun old_v1_backup_without_settings_still_loads() {
        // فایل پشتیبان نسخهٔ ۱ (بدون settings/panelConfig) نباید خطا بدهد
        val v1 = """{"version":1,"customers":[],"plans":[]}"""
        val back = json.decodeFromString(ir.cmbtools.ledger.data.BackupPayload.serializer(), v1)
        assertNull(back.settings)
        assertNull(back.panelConfig)
        assertEquals(1, back.version) // همان نسخه‌ای که در فایل نوشته شده
    }
}

class InvoiceNumberingTest {
    @Test
    fun numbers_are_positive_plain_integers_and_migration_uses_chronological_order() {
        val refs = listOf(
            ir.cmbtools.ledger.core.InvoiceNumbering.InvoiceRef(7, 20),
            ir.cmbtools.ledger.core.InvoiceNumbering.InvoiceRef(2, 10),
            ir.cmbtools.ledger.core.InvoiceNumbering.InvoiceRef(3, 10)
        )
        val assignments = ir.cmbtools.ledger.core.InvoiceNumbering.assignSequentially(refs)
        assertEquals(listOf(2L, 3L, 7L), assignments.map { it.id })
        assertEquals(listOf("1", "2", "3"), assignments.map { it.number })
        assertTrue(assignments.all { assignment ->
            assignment.number.toLong() > 0L && assignment.number.all { it in '0'..'9' }
        })
        assertEquals(101L, ir.cmbtools.ledger.core.InvoiceNumbering.next(100, 8))
    }
}

class PanelCustomerNamesTest {
    @Test
    fun first_name_never_matches_a_longer_customer_name() {
        val ids = ir.cmbtools.ledger.core.PanelCustomerNames.exactMatchIds(
            "رضا عباسی",
            listOf(1L to "رضا", 2L to "رضا عباسی")
        )
        assertEquals(listOf(2L), ids)
    }

    @Test
    fun normalizes_arabic_letters_extra_spaces_and_zwnj_but_keeps_full_name_distinct() {
        val ids = ir.cmbtools.ledger.core.PanelCustomerNames.exactMatchIds(
            "رضا   عباسي",
            listOf(1L to "رضا", 2L to "رضا\u200c عباسی")
        )
        assertEquals(listOf(2L), ids)
    }

    @Test
    fun removes_legacy_invoice_and_plan_suffix_before_exact_match() {
        val ids = ir.cmbtools.ledger.core.PanelCustomerNames.exactMatchIds(
            "رضا عباسی 1405-0041 - بستهٔ یک‌ماهه ۳۰ گیگ",
            listOf(2L to "رضا عباسی")
        )
        assertEquals(listOf(2L), ids)
        assertEquals("رضا-عباسی", ir.cmbtools.ledger.core.PanelCustomerNames.cleanLegacyPanelName("رضا-عباسی"))
    }

    @Test
    fun duplicate_full_names_are_reported_as_ambiguous_not_silently_selected() {
        val ids = ir.cmbtools.ledger.core.PanelCustomerNames.exactMatchIds(
            "رضا عباسی",
            listOf(2L to "رضا عباسی", 3L to "رضا  عباسی")
        )
        assertEquals(listOf(2L, 3L), ids)
    }

    @Test
    fun reliable_full_name_repairs_an_old_wrong_link_and_ambiguous_name_disables_old_link() {
        val matcher = ir.cmbtools.ledger.core.PanelCustomerNames
        assertEquals(2L, matcher.chooseCustomerId(resolvedId = 2, previousId = 1, ambiguousName = false))
        assertEquals(0L, matcher.chooseCustomerId(resolvedId = 0, previousId = 1, ambiguousName = true))
        assertEquals(1L, matcher.chooseCustomerId(resolvedId = 0, previousId = 1, ambiguousName = false))
    }

    @Test
    fun app_link_id_is_read_after_customer_phone_without_reading_id_as_phone() {
        val comment = "09121234567 | cmbtools:42"
        assertEquals(42L, ir.cmbtools.ledger.core.PanelCustomerNames.customerIdFromComment(comment))
        assertEquals(
            "09121234567",
            Phone.extract(ir.cmbtools.ledger.core.PanelCustomerNames.commentBeforeLink(comment))
        )
        assertEquals(42L, ir.cmbtools.ledger.core.PanelCustomerNames.customerIdFromComment("cmbtools:42:رضا"))
    }
}
