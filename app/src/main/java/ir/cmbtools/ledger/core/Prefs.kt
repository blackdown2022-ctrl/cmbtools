package ir.cmbtools.ledger.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    val autoMatchMaxRial: Long = 20_000_000
) {
    companion object {
        const val DEFAULT_INVOICE_TEMPLATE =
            "{business}\n" +
                "فاکتور {invoiceNo}\n" +
                "{customer} عزیز\n" +
                "مبلغ: {total} ریال\n" +
                "بابت: {items}\n" +
                "تاریخ: {date}\n" +
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
            invoiceTemplate = sp.getString("invoiceTemplate", defaults.invoiceTemplate) ?: defaults.invoiceTemplate,
            fallbackSubscriptionBase = sp.getString("fallbackSubscriptionBase", "") ?: "",
            autoMatchBankSms = sp.getBoolean("autoMatchBankSms", true),
            autoMatchMaxRial = sp.getLong("autoMatchMaxRial", defaults.autoMatchMaxRial)
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
            .apply()
        _settings.value = next
    }

    val current: AppSettings get() = _settings.value
}
