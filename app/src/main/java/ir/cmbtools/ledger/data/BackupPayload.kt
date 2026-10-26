package ir.cmbtools.ledger.data

import ir.cmbtools.ledger.core.AppSettings
import kotlinx.serialization.Serializable

/**
 * ساختار فایل پشتیبان JSON.
 *
 * نسخهٔ ۲ (این نسخه) نسبت به ۱ این‌ها را اضافه کرده است:
 *  • `settings`  → همهٔ تنظیمات برنامه (پیامک، قالب فاکتور، نام کسب‌وکار، کارت،
 *    همگام‌سازی خودکار، قیمت هر گیگ، قفل، ...)
 *  • `panelConfig` → تنظیمات اتصال پنل هیدی‌فای (دامنه، مسیر پروکسی، کلید API، نسخه)
 *  • `smsOutbox` → تاریخچهٔ پیامک‌های ارسالی
 *
 * همهٔ فیلدهای جدید مقدار پیش‌فرض دارند، پس فایل پشتیبان نسخهٔ ۱ هم بدون خطا
 * بازیابی می‌شود (فقط تنظیماتش خالی است).
 */
@Serializable
data class BackupPayload(
    val version: Int = 2,
    val createdAt: Long = System.currentTimeMillis(),
    val appVersion: String = "0.3.2",
    val customers: List<Customer> = emptyList(),
    val plans: List<Plan> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val lines: List<InvoiceLine> = emptyList(),
    val payments: List<Payment> = emptyList(),
    val allocations: List<PaymentAllocation> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val panelConfig: PanelConfig? = null,
    val panelAccounts: List<PanelAccount> = emptyList(),
    val bankSms: List<BankSms> = emptyList(),
    val smsOutbox: List<SmsOutbox> = emptyList(),
    /** همهٔ تنظیمات برنامه (پیامک، فاکتور، همگام‌سازی، ...) */
    val settings: AppSettings? = null
) {
    val customersCountText: Int get() = customers.size
}
