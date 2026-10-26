package ir.cmbtools.ledger.data

import ir.cmbtools.ledger.core.InvoiceNumbering
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/** Persistent high-water mark: deleting invoices never reuses an old number. */
@Entity(tableName = "invoice_number_counter")
data class InvoiceNumberCounter(
    @PrimaryKey val id: Int = 1,
    val lastNumber: Long = 0
)

@Dao
interface InvoiceNumberCounterDao {
    @Query("SELECT lastNumber FROM invoice_number_counter WHERE id = 1")
    suspend fun lastNumber(): Long?

    @Query("""
        SELECT COALESCE(MAX(CASE
            WHEN number GLOB '[0-9]*' AND number NOT GLOB '*[^0-9]*' THEN CAST(number AS INTEGER)
            ELSE 0 END), 0)
        FROM invoices
    """)
    suspend fun maximumInvoiceNumber(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun store(counter: InvoiceNumberCounter)

    @Transaction
    suspend fun reserveNext(): Long {
        val next = InvoiceNumbering.next(lastNumber() ?: 0L, maximumInvoiceNumber())
        store(InvoiceNumberCounter(lastNumber = next))
        return next
    }

    @Transaction
    suspend fun ensureAtLeast(number: Long) {
        val maximum = maxOf(lastNumber() ?: 0L, maximumInvoiceNumber(), number)
        store(InvoiceNumberCounter(lastNumber = maximum))
    }
}
