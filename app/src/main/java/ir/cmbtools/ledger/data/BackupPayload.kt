package ir.cmbtools.ledger.data

import kotlinx.serialization.Serializable

/** ساختار فایل پشتیبان JSON. نسخه برای مهاجرت‌های آینده نگه داشته می‌شود. */
@Serializable
data class BackupPayload(
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val appVersion: String = "0.1.0",
    val customers: List<Customer> = emptyList(),
    val plans: List<Plan> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val lines: List<InvoiceLine> = emptyList(),
    val payments: List<Payment> = emptyList(),
    val allocations: List<PaymentAllocation> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val panelAccounts: List<PanelAccount> = emptyList(),
    val bankSms: List<BankSms> = emptyList()
)
