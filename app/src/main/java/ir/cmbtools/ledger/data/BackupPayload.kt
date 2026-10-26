package ir.cmbtools.ledger.data

import ir.cmbtools.ledger.core.AppSettings
import kotlinx.serialization.Serializable

/**
 * ساختار فایل پشتیبان JSON.
 *
 * نسخهٔ ۳ این موارد را هم اضافه می‌کند:
 *  • تنظیمات برنامه و پنل + پیامک‌های ارسالی (از نسخهٔ ۲؛ Secret ربات از فایل حذف می‌شود)
 *  • `invoiceNumberHighWatermark` → جلوگیری از استفادهٔ دوباره از شماره پس از حذف/بازیابی
 *
 * فیلدهای افزوده مقدار پیش‌فرض دارند، پس فایل‌های پشتیبان قدیمی هم قابل بازیابی‌اند.
 */
@Serializable
data class BackupPayload(
    val version: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    val appVersion: String = "0.5.0",
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
    val settings: AppSettings? = null,
    /** آخرین شمارهٔ مصرف‌شده؛ با حذف فاکتور هم از تکرار شماره جلوگیری می‌کند. */
    val invoiceNumberHighWatermark: Long = 0
) {
    val customersCountText: Int get() = customers.size
}
