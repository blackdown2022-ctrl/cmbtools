package ir.cmbtools.ledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers WHERE archived = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Customer>>

    @Query("SELECT * FROM customers WHERE id = :id")
    fun observeById(id: Long): Flow<Customer?>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun byId(id: Long): Customer?

    @Query("SELECT * FROM customers WHERE phone = :phone LIMIT 1")
    suspend fun byPhone(phone: String): Customer?

    @Query("SELECT * FROM customers WHERE cardLast4 = :last4 AND archived = 0 LIMIT 1")
    suspend fun byLast4(last4: String): Customer?

    @Query("SELECT * FROM customers WHERE name = :name AND archived = 0 LIMIT 1")
    suspend fun byName(name: String): Customer?

    @Insert
    suspend fun insert(customer: Customer): Long

    @Update
    suspend fun update(customer: Customer)

    @Query("UPDATE customers SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("SELECT COUNT(*) FROM customers WHERE archived = 0")
    suspend fun count(): Int
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans WHERE active = 1 ORDER BY sortOrder, priceRial")
    fun observeActive(): Flow<List<Plan>>

    @Query("SELECT * FROM plans ORDER BY sortOrder, priceRial")
    fun observeAll(): Flow<List<Plan>>

    @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun byId(id: Long): Plan?

    @Insert
    suspend fun insert(plan: Plan): Long

    @Update
    suspend fun update(plan: Plan)

    @Query("DELETE FROM plans WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM plans")
    suspend fun count(): Int

    /** برای تطبیق قیمت در صدور خودکار فاکتور */
    @Query("SELECT * FROM plans WHERE active = 1 ORDER BY sortOrder, priceRial")
    suspend fun activeOnce(): List<Plan>
}

@Dao
interface InvoiceDao {
    @Query("SELECT * FROM invoices ORDER BY issuedAtEpochDay DESC, id DESC")
    fun observeAll(): Flow<List<Invoice>>

    @Query("SELECT * FROM invoices WHERE customerId = :customerId ORDER BY issuedAtEpochDay DESC, id DESC")
    fun observeByCustomer(customerId: Long): Flow<List<Invoice>>

    @Query("SELECT * FROM invoices WHERE id = :id")
    fun observeById(id: Long): Flow<Invoice?>

    @Query("SELECT * FROM invoices WHERE id = :id")
    suspend fun byId(id: Long): Invoice?

    @Query("SELECT * FROM invoices WHERE status != 'VOID' AND customerId = :customerId ORDER BY issuedAtEpochDay ASC, id ASC")
    suspend fun openInvoicesOldestFirst(customerId: Long): List<Invoice>

    @Query("SELECT * FROM invoices WHERE status != 'VOID' AND status != 'PAID' ORDER BY issuedAtEpochDay ASC")
    suspend fun allOpen(): List<Invoice>

    @Query("SELECT * FROM invoice_lines WHERE invoiceId = :invoiceId ORDER BY id")
    fun observeLines(invoiceId: Long): Flow<List<InvoiceLine>>

    @Query("SELECT * FROM invoice_lines WHERE invoiceId = :invoiceId ORDER BY id")
    suspend fun lines(invoiceId: Long): List<InvoiceLine>

    @Query("SELECT COUNT(*) FROM invoices WHERE number LIKE :prefix || '%'")
    suspend fun countByPrefix(prefix: String): Int

    @Insert
    suspend fun insert(invoice: Invoice): Long

    @Update
    suspend fun update(invoice: Invoice)

    @Insert
    suspend fun insertLines(lines: List<InvoiceLine>)

    @Query("DELETE FROM invoice_lines WHERE invoiceId = :invoiceId")
    suspend fun deleteLines(invoiceId: Long)

    @Query("SELECT * FROM invoices WHERE issuedAtEpochDay BETWEEN :from AND :to AND status != 'VOID'")
    suspend fun between(from: Long, to: Long): List<Invoice>

    /** آیا برای این اکانت پنل قبلاً فاکتور صادر شده است؟ (ضد فاکتور تکراری) */
    @Query("SELECT COUNT(*) FROM invoices WHERE panelUserUuid = :uuid AND status != 'VOID'")
    suspend fun countForPanelUser(uuid: String): Int

    /** آخرین فاکتور یک اکانت پنل */
    @Query("SELECT * FROM invoices WHERE panelUserUuid = :uuid AND status != 'VOID' ORDER BY id DESC LIMIT 1")
    suspend fun lastForPanelUser(uuid: String): Invoice?
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments WHERE customerId = :customerId ORDER BY receivedAtEpochDay DESC, id DESC")
    fun observeByCustomer(customerId: Long): Flow<List<Payment>>

    @Query("SELECT * FROM payments ORDER BY receivedAtEpochDay DESC, id DESC LIMIT 100")
    fun observeRecent(): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE id = :id")
    suspend fun byId(id: Long): Payment?

    @Insert
    suspend fun insert(payment: Payment): Long

    @Update
    suspend fun update(payment: Payment)

    @Insert
    suspend fun insertAllocations(allocations: List<PaymentAllocation>)

    @Query("SELECT * FROM payment_allocations WHERE invoiceId = :invoiceId")
    fun observeAllocations(invoiceId: Long): Flow<List<PaymentAllocation>>

    @Query("SELECT COALESCE(SUM(amountRial), 0) FROM payment_allocations WHERE invoiceId = :invoiceId")
    suspend fun paidForInvoice(invoiceId: Long): Long

    @Query("SELECT * FROM payments WHERE receivedAtEpochDay BETWEEN :from AND :to")
    suspend fun between(from: Long, to: Long): List<Payment>

    @Query("SELECT COALESCE(SUM(amountRial), 0) FROM payments WHERE customerId = :customerId")
    suspend fun totalPaid(customerId: Long): Long
}

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_entries WHERE customerId = :customerId ORDER BY atEpochDay DESC, id DESC")
    fun observeByCustomer(customerId: Long): Flow<List<LedgerEntry>>

    @Query("SELECT * FROM ledger_entries ORDER BY atEpochDay DESC, id DESC LIMIT 200")
    fun observeRecent(): Flow<List<LedgerEntry>>

    @Insert
    suspend fun insert(entry: LedgerEntry): Long

    @Insert
    suspend fun insertAll(entries: List<LedgerEntry>)

    /**
     * مانده = بدهکار - بستانکار. مثبت = مشتری بدهکار است.
     */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amountRial ELSE -amountRial END), 0)
        FROM ledger_entries WHERE customerId = :customerId
        """
    )
    fun observeBalance(customerId: Long): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amountRial ELSE -amountRial END), 0)
        FROM ledger_entries WHERE customerId = :customerId
        """
    )
    suspend fun balance(customerId: Long): Long

    @Query(
        """
        SELECT customerId, COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amountRial ELSE -amountRial END), 0) AS balanceRial
        FROM ledger_entries GROUP BY customerId
        """
    )
    fun observeBalances(): Flow<List<CustomerBalance>>

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amountRial ELSE -amountRial END), 0)
        FROM ledger_entries
        """
    )
    fun observeTotalReceivable(): Flow<Long>
}

data class CustomerBalance(
    val customerId: Long,
    val balanceRial: Long
)

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY atEpochDay DESC, id DESC")
    fun observeAll(): Flow<List<Expense>>

    @Insert
    suspend fun insert(expense: Expense): Long

    @Query("SELECT * FROM expenses WHERE atEpochDay BETWEEN :from AND :to")
    suspend fun between(from: Long, to: Long): List<Expense>

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PanelDao {
    @Query("SELECT * FROM panel_configs WHERE id = 1")
    fun observeConfig(): Flow<PanelConfig?>

    @Query("SELECT * FROM panel_configs WHERE id = 1")
    suspend fun config(): PanelConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConfig(config: PanelConfig)

    @Query("SELECT * FROM panel_accounts ORDER BY syncedAt DESC")
    fun observeAccounts(): Flow<List<PanelAccount>>

    @Query("SELECT * FROM panel_accounts WHERE customerId = :customerId")
    fun observeAccountsOf(customerId: Long): Flow<List<PanelAccount>>

    @Query("SELECT * FROM panel_accounts WHERE panelUserUuid = :uuid LIMIT 1")
    suspend fun byPanelUuid(uuid: String): PanelAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: PanelAccount): Long

    @Query("DELETE FROM panel_accounts WHERE panelId = :panelId")
    suspend fun clearAccounts(panelId: Long)

    /** همهٔ اکانت‌ها به‌صورت لیست (همگام‌سازی خودکار) */
    @Query("SELECT * FROM panel_accounts")
    suspend fun allAccounts(): List<PanelAccount>

    /** اتصال اکانت به مشتری بدون دست‌زدن به بقیهٔ فیلدها */
    @Query("UPDATE panel_accounts SET customerId = :customerId WHERE panelUserUuid = :uuid")
    suspend fun linkCustomer(uuid: String, customerId: Long)
}

@Dao
interface BankSmsDao {
    @Query("SELECT * FROM bank_sms ORDER BY receivedAtMillis DESC LIMIT 300")
    fun observeAll(): Flow<List<BankSms>>

    @Query("SELECT * FROM bank_sms WHERE matchStatus = 'UNMATCHED' ORDER BY receivedAtMillis DESC")
    fun observeUnmatched(): Flow<List<BankSms>>

    @Query("SELECT * FROM bank_sms WHERE id = :id")
    suspend fun byId(id: Long): BankSms?

    @Query("SELECT * FROM bank_sms WHERE rawHash = :hash LIMIT 1")
    suspend fun byHash(hash: String): BankSms?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sms: BankSms): Long

    @Query("UPDATE bank_sms SET matchStatus = :status, paymentId = :paymentId, note = :note WHERE id = :id")
    suspend fun markMatched(id: Long, status: String, paymentId: Long, note: String = "")

    @Query("UPDATE bank_sms SET matchStatus = 'IGNORED' WHERE id = :id")
    suspend fun markIgnored(id: Long)
}

@Dao
interface SmsOutboxDao {
    @Query("SELECT * FROM sms_outbox ORDER BY createdAt DESC LIMIT 200")
    fun observeAll(): Flow<List<SmsOutbox>>

    @Insert
    suspend fun insert(sms: SmsOutbox): Long

    @Query("UPDATE sms_outbox SET status = :status, error = :error, sentAt = :sentAt WHERE id = :id")
    suspend fun mark(id: Long, status: String, error: String, sentAt: Long)

    @Query("SELECT * FROM sms_outbox WHERE id = :id")
    suspend fun byId(id: Long): SmsOutbox?
}
