package ir.cmbtools.ledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ir.cmbtools.ledger.core.InvoiceNumbering

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
        InvoiceNumberCounter::class,
        BankSms::class,
        SmsOutbox::class
    ],
    version = 6,
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
    abstract fun invoiceNumberCounter(): InvoiceNumberCounterDao
    abstract fun bankSms(): BankSmsDao
    abstract fun smsOutbox(): SmsOutboxDao
    abstract fun backup(): BackupDao

    companion object {

        /**
         * ۱ → ۲ : فیلدهای «سند فاکتور» (تاریخ و ساعت، بسته، کارت، مانده قبلی و جمع بدهی)
         * و نشانگرهای تشخیص تمدید در اکانت‌های پنل.
         * همه ستون‌ها مقدار پیش‌فرض دارند، پس دادهٔ قبلی حفظ می‌شود.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoices ADD COLUMN issuedAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE invoices ADD COLUMN packageName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE invoices ADD COLUMN cardNumber TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE invoices ADD COLUMN prevBalanceRial INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE invoices ADD COLUMN balanceAfterRial INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE invoices ADD COLUMN kind TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE invoices ADD COLUMN autoIssued INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE panel_accounts ADD COLUMN prevExpireEpochDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE panel_accounts ADD COLUMN lastInvoicedExpireEpochDay INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE panel_configs ADD COLUMN baselineAtMillis INTEGER NOT NULL DEFAULT 0")

                // فاکتورهای قدیمی زمان دقیق ندارند؛ از روز صدور بازسازی می‌شود (ظهر همان روز).
                db.execSQL("UPDATE invoices SET issuedAtMillis = issuedAtEpochDay * 86400000 + 43200000 WHERE issuedAtMillis = 0")
                // جمع بدهی فاکتورهای قدیمی از مبلغ خود فاکتور پر می‌شود.
                db.execSQL("UPDATE invoices SET balanceAfterRial = totalRial - paidRial WHERE balanceAfterRial = 0")
            }
        }


        /**
         * ۲ → ۳ : پایهٔ لینک اشتراک به‌عنوان فیلد مستقل پنل (جدا از مسیر ادمین).
         * ستون مقدار پیش‌فرض دارد، پس دادهٔ قبلی حفظ می‌شود و تا اولین اتصال موفق،
         * اپ از `fallbackSubscriptionBase` تنظیمات استفاده می‌کند.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE panel_configs ADD COLUMN subscriptionBase TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * ۳ → ۴ : «سبک API» پنل (نسخهٔ ۱۱ تا ۱۴) به‌صورت ستون ذخیره می‌شود تا
         * بعد از اولین اتصال، از همان مسیرِ درست استفاده شود.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE panel_configs ADD COLUMN apiFlavor TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * ۴ → ۵ : زمان آخرین تمدید هر اکانت پنل.
         * ستون مقدار پیش‌فرض دارد؛ دادهٔ قبلی حفظ می‌شود (تا اولین تمدید بعدی خالی می‌ماند).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE panel_accounts ADD COLUMN lastRenewedAtMillis INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * ۵ → ۶: همهٔ شماره‌های قدیمی به ترتیب تاریخ از ۱ به بعد عددی می‌شوند و
         * شمارندهٔ دائمی ساخته می‌شود تا حذف فاکتور باعث استفادهٔ دوباره از شماره نشود.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS invoice_number_counter " +
                        "(id INTEGER NOT NULL, lastNumber INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                val refs = mutableListOf<InvoiceNumbering.InvoiceRef>()
                db.query("SELECT id, issuedAtEpochDay FROM invoices").use { cursor ->
                    while (cursor.moveToNext()) {
                        refs += InvoiceNumbering.InvoiceRef(cursor.getLong(0), cursor.getLong(1))
                    }
                }
                val assignments = InvoiceNumbering.assignSequentially(refs)
                val tempPrefix = "__tiznet_invoice_migration_${System.currentTimeMillis()}_"
                db.execSQL("UPDATE invoices SET number = '$tempPrefix' || id")
                assignments.forEach { assignment ->
                    val number = assignment.number
                    val id = assignment.id
                    db.execSQL("UPDATE invoices SET number = ? WHERE id = ?", arrayOf<Any>(number, id))
                    db.execSQL(
                        "UPDATE ledger_entries SET memo = CASE " +
                            "WHEN refType = 'INVOICE_VOID' THEN ? ELSE ? END " +
                            "WHERE refId = ? AND refType IN ('INVOICE', 'INVOICE_VOID')",
                        arrayOf<Any>("ابطال فاکتور $number", "فاکتور $number", id)
                    )
                }
                db.execSQL(
                    "INSERT OR REPLACE INTO invoice_number_counter (id, lastNumber) VALUES (1, ${assignments.size})"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "cmb-ledger.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
