package ir.cmbtools.ledger.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** همهٔ تنظیمات برنامه؛ در فایل پشتیبان هم ذخیره می‌شود (نسخهٔ ۲). */
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
    /** مبالغ بالاتر از این مقدار بدون تأیید دستی خودکار تطبیق نشوند (ریال) */
    val autoMatchMaxRial: Long = 20_000_000,

    // ── همگام‌سازی و صدور خودکار فاکتور ──────────────────────────────────────
    /** هر چند دقیقه با پنل همگام‌سازی شود (سرویس پس‌زمینه) */
    val autoSyncEnabled: Boolean = true,
    val autoSyncMinutes: Int = 5,
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
    val pricePerGbRial: Long = 0
) {
    companion object {
        /**
         * ساختار فاکتور خواسته‌شده: نام مشتری، تاریخ و ساعت، شمارهٔ فاکتور،
         * نوع بسته، شمارهٔ کارت و (در صورت بدهی قبلی) مانده قبلی + جمع کل بدهی.
         *
         * متغیرها:
         *  {business} {customer} {invoiceNo} {datetime} {date} {package} {items}
         *  {total} {prevBalance} {totalDebt} {debt} {card} {kind} {link} {footer}
         */
        /** قالب نسخهٔ قبل — برای مهاجرت خودکار به قالب ساختاریافته */
        const val LEGACY_INVOICE_TEMPLATE =
            "{business}\n" +
                "فاکتور {invoiceNo}\n" +
                "{customer} عزیز\n" +
                "مبلغ: {total} ریال\n" +
                "بابت: {items}\n" +
                "تاریخ: {date}\n" +
                "{link}\n" +
                "{footer}"

        const val DEFAULT_INVOICE_TEMPLATE =
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

/**
 * تنظیمات در EncryptedSharedPreferences ذخیره می‌شود (کلید API پنل و درگاه پیامک).
 * برای شخصی‌سازی: از Keystore استفاده می‌کند و کلیدها روی دستگاه رمز می‌مانند.
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
                .let { if (it.trim() == AppSettings.LEGACY_INVOICE_TEMPLATE.trim()) defaults.invoiceTemplate else it },
            fallbackSubscriptionBase = sp.getString("fallbackSubscriptionBase", "") ?: "",
            autoMatchBankSms = sp.getBoolean("autoMatchBankSms", true),
            autoMatchMaxRial = sp.getLong("autoMatchMaxRial", defaults.autoMatchMaxRial),
            autoSyncEnabled = sp.getBoolean("autoSyncEnabled", true),
            autoSyncMinutes = sp.getInt("autoSyncMinutes", 5),
            autoInvoiceOnNewAccount = sp.getBoolean("autoInvoiceOnNewAccount", true),
            autoInvoiceOnRenew = sp.getBoolean("autoInvoiceOnRenew", true),
            autoSmsOnInvoice = sp.getBoolean("autoSmsOnInvoice", true),
            autoCreateCustomerFromPanel = sp.getBoolean("autoCreateCustomerFromPanel", true),
            paymentCardNumber = sp.getString("paymentCardNumber", "") ?: "",
            pricePerGbRial = sp.getLong("pricePerGbRial", 0)
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
            .putLong("autoMatchMaxRial", next.autoMatchMaxRial)
            .putBoolean("autoSyncEnabled", next.autoSyncEnabled)
            .putInt("autoSyncMinutes", next.autoSyncMinutes)
            .putBoolean("autoInvoiceOnNewAccount", next.autoInvoiceOnNewAccount)
            .putBoolean("autoInvoiceOnRenew", next.autoInvoiceOnRenew)
            .putBoolean("autoSmsOnInvoice", next.autoSmsOnInvoice)
            .putBoolean("autoCreateCustomerFromPanel", next.autoCreateCustomerFromPanel)
            .putString("paymentCardNumber", next.paymentCardNumber)
            .putLong("pricePerGbRial", next.pricePerGbRial)
            .apply()
        _settings.value = next
    }

    val current: AppSettings get() = _settings.value
}
