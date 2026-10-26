package ir.cmbtools.ledger

import ir.cmbtools.ledger.core.AppSettings
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.InvoiceKind
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.PanelSyncRules
import ir.cmbtools.ledger.core.Phone
import ir.cmbtools.ledger.sms.BankSmsParser
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
        // شمارهٔ فاکتور فارسی است (خواستهٔ کاربر) ولی شمارهٔ کارت لاتین می‌ماند
        assertTrue(body.contains(Money.toPersian("1405-0042")))
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
            settings = AppSettings(businessName = "فروشگاه من", paymentCardNumber = "6037-9977-1234-5678")
        )
        val text = json.encodeToString(ir.cmbtools.ledger.data.BackupPayload.serializer(), payload)
        assertTrue(text.contains("\"settings\""))
        assertTrue(text.contains("panel.example.com"))
        assertTrue(text.contains("6037-9977-1234-5678"))

        val back = json.decodeFromString(ir.cmbtools.ledger.data.BackupPayload.serializer(), text)
        assertEquals("panel.example.com", back.panelConfig?.host)
        assertEquals("فروشگاه من", back.settings?.businessName)
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
