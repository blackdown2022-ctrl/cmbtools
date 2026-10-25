package ir.cmbtools.ledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Customer::class,
        Plan::class,
        Invoice::class,
        InvoiceLine::class,
        Payment::class,
        PaymentAllocation::class,
        LedgerEntry::class,
        Expense::class,
        PanelConfig::class,
        PanelAccount::class,
        BankSms::class,
        SmsOutbox::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customers(): CustomerDao
    abstract fun plans(): PlanDao
    abstract fun invoices(): InvoiceDao
    abstract fun payments(): PaymentDao
    abstract fun ledger(): LedgerDao
    abstract fun expenses(): ExpenseDao
    abstract fun panel(): PanelDao
    abstract fun bankSms(): BankSmsDao
    abstract fun smsOutbox(): SmsOutboxDao
    abstract fun backup(): BackupDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "cmb-ledger.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
