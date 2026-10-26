package ir.cmbtools.ledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ir.cmbtools.ledger.core.InvoiceNumbering

/**
 * خواندن/نوشتن کل جداول برای پشتیبان‌گیری JSON.
 * فقط برای اپ تک‌کاربرهٔ محلی؛ در بازیابی، جداول قبلی پاک می‌شوند.
 *
 * ⚠ اگر جدول تازه‌ای اضافه شد، همین‌جا both all.../put.../clear... را اضافه کنید؛
 * وگرنه آن داده در پشتیبان نمی‌آید (همین اشکال قبلاً برای تنظیمات پنل وجود داشت).
 */
@Dao
interface BackupDao {

    @Query("SELECT * FROM customers") suspend fun allCustomers(): List<Customer>
    @Query("SELECT * FROM plans") suspend fun allPlans(): List<Plan>
    @Query("SELECT * FROM invoices") suspend fun allInvoices(): List<Invoice>
    @Query("SELECT * FROM invoice_lines") suspend fun allLines(): List<InvoiceLine>
    @Query("SELECT * FROM payments") suspend fun allPayments(): List<Payment>
    @Query("SELECT * FROM payment_allocations") suspend fun allAllocations(): List<PaymentAllocation>
    @Query("SELECT * FROM ledger_entries") suspend fun allLedger(): List<LedgerEntry>
    @Query("SELECT * FROM expenses") suspend fun allExpenses(): List<Expense>
    @Query("SELECT * FROM panel_configs") suspend fun allPanelConfigs(): List<PanelConfig>
    @Query("SELECT * FROM panel_accounts") suspend fun allPanelAccounts(): List<PanelAccount>
    @Query("SELECT * FROM bank_sms") suspend fun allBankSms(): List<BankSms>
    @Query("SELECT * FROM sms_outbox") suspend fun allOutbox(): List<SmsOutbox>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCustomers(items: List<Customer>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPlans(items: List<Plan>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putInvoices(items: List<Invoice>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLines(items: List<InvoiceLine>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPayments(items: List<Payment>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAllocations(items: List<PaymentAllocation>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLedger(items: List<LedgerEntry>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putExpenses(items: List<Expense>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPanelConfigs(items: List<PanelConfig>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPanelAccounts(items: List<PanelAccount>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBankSms(items: List<BankSms>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOutbox(items: List<SmsOutbox>)

    @Query("DELETE FROM customers") suspend fun clearCustomers()
    @Query("DELETE FROM plans") suspend fun clearPlans()
    @Query("DELETE FROM invoices") suspend fun clearInvoices()
    @Query("DELETE FROM invoice_lines") suspend fun clearLines()
    @Query("DELETE FROM payments") suspend fun clearPayments()
    @Query("DELETE FROM payment_allocations") suspend fun clearAllocations()
    @Query("DELETE FROM ledger_entries") suspend fun clearLedger()
    @Query("DELETE FROM expenses") suspend fun clearExpenses()
    @Query("DELETE FROM panel_configs") suspend fun clearPanelConfigs()
    @Query("DELETE FROM panel_accounts") suspend fun clearPanelAccounts()
    @Query("DELETE FROM bank_sms") suspend fun clearBankSms()
    @Query("DELETE FROM sms_outbox") suspend fun clearOutbox()

    /**
     * جایگزینی کامل داده‌ها. تنظیمات پنل فقط وقتی پاک/بازنویسی می‌شود که فایل
     * پشتیبان واقعاً تنظیمات پنل داشته باشد (فایل‌های نسخهٔ ۱ آن را ندارند و نباید
     * اتصال فعلی کاربر را نابود کنند).
     */
    suspend fun replaceAll(payload: BackupPayload) {
        clearAllocations()
        clearLines()
        clearPayments()
        clearInvoices()
        clearLedger()
        clearCustomers()
        clearPlans()
        clearExpenses()
        clearPanelAccounts()
        clearBankSms()
        clearOutbox()
        putCustomers(payload.customers)
        putPlans(payload.plans)
        val assignments = InvoiceNumbering.assignSequentially(
            payload.invoices.map { InvoiceNumbering.InvoiceRef(it.id, it.issuedAtEpochDay) }
        ).associate { it.id to it.number }
        putInvoices(payload.invoices.map { invoice -> invoice.copy(number = assignments[invoice.id] ?: invoice.number) })
        putLines(payload.lines)
        putPayments(payload.payments)
        putAllocations(payload.allocations)
        putLedger(payload.ledger.map { entry ->
            val number = assignments[entry.refId]
            if (number == null || entry.refType !in setOf("INVOICE", "INVOICE_VOID")) entry
            else entry.copy(memo = if (entry.refType == "INVOICE_VOID") "ابطال فاکتور $number" else "فاکتور $number")
        })
        putExpenses(payload.expenses)
        putPanelAccounts(payload.panelAccounts)
        putBankSms(payload.bankSms)
        putOutbox(payload.smsOutbox)
        payload.panelConfig?.let {
            clearPanelConfigs()
            putPanelConfigs(listOf(it))
        }
    }
}
