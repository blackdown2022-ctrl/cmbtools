package ir.cmbtools.ledger.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** تنظیمات برنامه؛ مقدارهای حساس مانند Secret ربات هنگام پشتیبان‌گیری حذف می‌شوند. */
@Serializable
data class AppSettings(
    val persianDigits: Boolean = true,
    val lockEnabled: Boolean = false,
    val lockPin: String = "",
    /** GATEWAY یا SIM */
    val smsMode: String = SmsMode.GATEWAY,
    val gatewayProvider: String = SmsGatewayProvider.KAVENEGAR,
    val gatewayApiKey: String = "",
    val gatewaySender: String = "",
    val gatewayPattern: String = "",
    val businessName: String = "فروشگاه من",
    val invoiceFooter: String = "",
    val invoiceTemplate: String = DEFAULT_INVOICE_TEMPLATE,
    /** پایهٔ ساخت لینک اشتراک اگر پنل در دسترس نباشد */
    val fallbackSubscriptionBase: String = "",
    val autoMatchBankSms: Boolean = true,
    /** سرشمارهٔ مجاز پیامک بانکی؛ خالی یعنی هیچ پیامکی پذیرفته نشود. */
    val bankSmsAllowedSender: String = "",
    /** مبالغ بالاتر از این مقدار بدون تأیید دستی خودکار تطبیق نشوند (ریال) */
    val autoMatchMaxRial: Long = 20_000_000,

    // ── همگام‌سازی و صدور خودکار فاکتور ──────────────────────────────────────
    /** هر چند دقیقه با پنل همگام‌سازی شود (سرویس پس‌زمینه) */
    val autoSyncEnabled: Boolean = true,
    val autoSyncMinutes: Int = 1,
    /** برای اکانت تازه‌ای که در پنل ساخته می‌شود فاکتور خودکار صادر شود */
    val autoInvoiceOnNewAccount: Boolean = true,
    /** برای اکانتی که تمدید می‌شود فاکتور خودکار صادر شود */
    val autoInvoiceOnRenew: Boolean = true,
    /** فاکتور بلافاصله با پیامک برای مشتری ارسال شود */
    val autoSmsOnInvoice: Boolean = true,
    /** اگر کاربر پنل مشتری محلی نداشت، مشتری خودکار ساخته شود */
    val autoCreateCustomerFromPanel: Boolean = true,
    /** شمارهٔ کارت فروشگاه که در فاکتور درج می‌شود */
    val paymentCardNumber: String = "",
    /** قیمت هر گیگ برای بسته‌های بدون «بستهٔ قیمت‌دار» (ریال) — صفر = غیرفعال */
    val pricePerGbRial: Long = 0,
    /** تأیید واریزهای پیامکی از مبلغ یکتای سفارش‌های ربات فروش */
    val botPaymentEnabled: Boolean = false,
    /** مسیر confirm دقیق سرور salebot؛ مقدار در EncryptedSharedPreferences ذخیره می‌شود */
    val botServerUrl: String = "",
    /** Secret مشترک بین گوشی و سرور salebot */
    val botApiSecret: String = ""
) {
    companion object {
        /**
         * ساختار فاکتور خواسته‌شده:
         *  • خط اول فقط نام مشتری
         *  • بدون نام فروشگاه
         *  • بدون لینک اشتراک
         * بعد از آن: شمارهٔ فاکتور، تاریخ و ساعت، بسته، مبلغ،
         * شمارهٔ کارت و (در صورت بدهی قبلی) مانده قبلی + جمع کل بدهی.
         *
         * متغیرهای قابل استفاده:
         *  {customer} {invoiceNo} {datetime} {date} {package} {items}
         *  {total} {prevBalance} {totalDebt} {debt} {card} {kind} {footer}
         *
         * (`{business}` و `{link}` حذف شده‌اند؛ اگر در قالب دستی مانده باشند،
         *  خالی جایگزین می‌شوند تا نام فروشگاه/لینک در پیامک نیاید.)
         */
        /** قالب نسخهٔ ۰.۲ — برای مهاجرت خودکار */
        const val LEGACY_INVOICE_TEMPLATE =
            "{business}\n" +
                "فاکتور {invoiceNo}\n" +
                "{customer} عزیز\n" +
                "مبلغ: {total} ریال\n" +
                "بابت: {items}\n" +
                "تاریخ: {date}\n" +
                "{link}\n" +
                "{footer}"

        /** قالب نسخه‌های ۰.۳.۰ تا ۰.۳.۳ — برای مهاجرت خودکار به قالب ۰.۴.۰ */
        const val V03_INVOICE_TEMPLATE =
            "{business}\n" +
                "فاکتور {invoiceNo}\n" +
                "مشتری: {customer}\n" +
                "تاریخ: {datetime}\n" +
                "بسته: {package}\n" +
                "مبلغ: {total}\n" +
                "{debt}" +
                "کارت: {card}\n" +
                "{link}\n" +
                "{footer}"

        const val DEFAULT_INVOICE_TEMPLATE =
            "{customer}\n" +
                "فاکتور {invoiceNo}\n" +
                "تاریخ: {datetime}\n" +
                "بسته: {package}\n" +
                "مبلغ: {total}\n" +
                "{debt}" +
                "کارت: {card}\n" +
                "{footer}"
    }
}

object SmsMode {
    const val GATEWAY = "GATEWAY"
    const val SIM = "SIM"
}

object SmsGatewayProvider {
    const val KAVENEGAR = "KAVENEGAR"
    const val SMS_IR = "SMS_IR"

    fun label(v: String) = when (v) {
        KAVENEGAR -> "کاوه‌نگار"
        SMS_IR -> "SMS.ir"
        else -> v
    }
}

data class BotPaymentAudit(
    val atMillis: Long = 0L,
    val state: String = "",
    val bank: String = "",
    val amountRial: Long = 0L,
    val amountUnit: String = "",
    val reference: String = "",
    val orderId: String = "",
    val message: String = "",
    val httpStatus: Int = 0
)

/**
 * تنظیمات و گزارش آخرین وضعیت ربات در EncryptedSharedPreferences ذخیره می‌شوند.
 * برای شخصی‌سازی از Keystore استفاده می‌شود و کلیدها روی دستگاه رمز می‌مانند.
 */
class Prefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sp = EncryptedSharedPreferences.create(
        context,
        "cmb_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings

    private val _botAudit = MutableStateFlow(readBotAudit())
    val botAudit: StateFlow<BotPaymentAudit> = _botAudit

    private fun readBotAudit() = BotPaymentAudit(
        atMillis = sp.getLong("botAuditAtMillis", 0L),
        state = sp.getString("botAuditState", "") ?: "",
        bank = sp.getString("botAuditBank", "") ?: "",
        amountRial = sp.getLong("botAuditAmountRial", 0L),
        amountUnit = sp.getString("botAuditAmountUnit", "") ?: "",
        reference = sp.getString("botAuditReference", "") ?: "",
        orderId = sp.getString("botAuditOrderId", "") ?: "",
        message = sp.getString("botAuditMessage", "") ?: "",
        httpStatus = sp.getInt("botAuditHttpStatus", 0)
    )

    fun recordBotAudit(audit: BotPaymentAudit) {
        sp.edit()
            .putLong("botAuditAtMillis", audit.atMillis)
            .putString("botAuditState", audit.state)
            .putString("botAuditBank", audit.bank)
            .putLong("botAuditAmountRial", audit.amountRial)
            .putString("botAuditAmountUnit", audit.amountUnit)
            .putString("botAuditReference", audit.reference)
            .putString("botAuditOrderId", audit.orderId)
            .putString("botAuditMessage", audit.message)
            .putInt("botAuditHttpStatus", audit.httpStatus)
            .apply()
        _botAudit.value = audit
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            persianDigits = sp.getBoolean("persianDigits", defaults.persianDigits),
            lockEnabled = sp.getBoolean("lockEnabled", defaults.lockEnabled),
            lockPin = sp.getString("lockPin", "") ?: "",
            smsMode = sp.getString("smsMode", defaults.smsMode) ?: defaults.smsMode,
            gatewayProvider = sp.getString("gatewayProvider", defaults.gatewayProvider) ?: defaults.gatewayProvider,
            gatewayApiKey = sp.getString("gatewayApiKey", "") ?: "",
            gatewaySender = sp.getString("gatewaySender", "") ?: "",
            gatewayPattern = sp.getString("gatewayPattern", "") ?: "",
            businessName = sp.getString("businessName", defaults.businessName) ?: defaults.businessName,
            invoiceFooter = sp.getString("invoiceFooter", "") ?: "",
            // اگر کاربر قالب پیش‌فرض قدیمی را (بدون تاریخ‌وساعت/بسته/کارت/بدهی) داشت،
            // خودکار به قالب ساختاریافتهٔ جدید ارتقا می‌یابد. قالب‌های دستی دست‌نخورده می‌مانند.
            invoiceTemplate = (sp.getString("invoiceTemplate", defaults.invoiceTemplate) ?: defaults.invoiceTemplate)
                .let { saved ->
                    // ارتقای قالب‌های پیش‌فرض نسخه‌های قبل (قالب‌های دستی دست‌نخورده می‌مانند)
                    if (saved.trim() == AppSettings.LEGACY_INVOICE_TEMPLATE.trim() ||
                        saved.trim() == AppSettings.V03_INVOICE_TEMPLATE.trim()
                    ) defaults.invoiceTemplate else saved
                },
            fallbackSubscriptionBase = sp.getString("fallbackSubscriptionBase", "") ?: "",
            autoMatchBankSms = sp.getBoolean("autoMatchBankSms", true),
            bankSmsAllowedSender = sp.getString("bankSmsAllowedSender", "") ?: "",
            autoMatchMaxRial = sp.getLong("autoMatchMaxRial", defaults.autoMatchMaxRial),
            autoSyncEnabled = sp.getBoolean("autoSyncEnabled", true),
            // پیش‌تر فاصله فقط پیش‌فرض ۵ دقیقه‌ای بود و در UI قابل تغییر نبود؛
            // با نسخهٔ جدید آن مقدارِ پیش‌فرض به بررسی یک‌دقیقه‌ای ارتقا می‌یابد.
            autoSyncMinutes = sp.getInt("autoSyncMinutes", defaults.autoSyncMinutes).let { if (it == 5) 1 else it.coerceIn(1, 120) },
            autoInvoiceOnNewAccount = sp.getBoolean("autoInvoiceOnNewAccount", true),
            autoInvoiceOnRenew = sp.getBoolean("autoInvoiceOnRenew", true),
            autoSmsOnInvoice = sp.getBoolean("autoSmsOnInvoice", true),
            autoCreateCustomerFromPanel = sp.getBoolean("autoCreateCustomerFromPanel", true),
            paymentCardNumber = sp.getString("paymentCardNumber", "") ?: "",
            pricePerGbRial = sp.getLong("pricePerGbRial", 0),
            botPaymentEnabled = sp.getBoolean("botPaymentEnabled", false),
            botServerUrl = sp.getString("botServerUrl", "") ?: "",
            botApiSecret = sp.getString("botApiSecret", "") ?: ""
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val current = _settings.value
        val next = transform(current)
        sp.edit()
            .putBoolean("persianDigits", next.persianDigits)
            .putBoolean("lockEnabled", next.lockEnabled)
            .putString("lockPin", next.lockPin)
            .putString("smsMode", next.smsMode)
            .putString("gatewayProvider", next.gatewayProvider)
            .putString("gatewayApiKey", next.gatewayApiKey)
            .putString("gatewaySender", next.gatewaySender)
            .putString("gatewayPattern", next.gatewayPattern)
            .putString("businessName", next.businessName)
            .putString("invoiceFooter", next.invoiceFooter)
            .putString("invoiceTemplate", next.invoiceTemplate)
            .putString("fallbackSubscriptionBase", next.fallbackSubscriptionBase)
            .putBoolean("autoMatchBankSms", next.autoMatchBankSms)
            .putString("bankSmsAllowedSender", next.bankSmsAllowedSender)
            .putLong("autoMatchMaxRial", next.autoMatchMaxRial)
            .putBoolean("autoSyncEnabled", next.autoSyncEnabled)
            .putInt("autoSyncMinutes", next.autoSyncMinutes)
            .putBoolean("autoInvoiceOnNewAccount", next.autoInvoiceOnNewAccount)
            .putBoolean("autoInvoiceOnRenew", next.autoInvoiceOnRenew)
            .putBoolean("autoSmsOnInvoice", next.autoSmsOnInvoice)
            .putBoolean("autoCreateCustomerFromPanel", next.autoCreateCustomerFromPanel)
            .putString("paymentCardNumber", next.paymentCardNumber)
            .putLong("pricePerGbRial", next.pricePerGbRial)
            .putBoolean("botPaymentEnabled", next.botPaymentEnabled)
            .putString("botServerUrl", next.botServerUrl)
            .putString("botApiSecret", next.botApiSecret)
            .apply()
        _settings.value = next
    }

    val current: AppSettings get() = _settings.value
}
