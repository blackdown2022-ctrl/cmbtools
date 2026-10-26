package ir.cmbtools.ledger.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import ir.cmbtools.ledger.AppContainer
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.PaymentMethod
import ir.cmbtools.ledger.data.BackupPayload
import ir.cmbtools.ledger.data.Customer
import ir.cmbtools.ledger.data.CustomerRow
import ir.cmbtools.ledger.data.Invoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * خروجی CSV (قابل باز شدن در Excel) و پشتیبان کامل JSON.
 * فایل‌ها در files/exports ساخته و با FileProvider به برنامهٔ دلخواه ارسال می‌شوند.
 */
class ExportManager(
    private val context: Context,
    private val container: AppContainer
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private fun exportsDir(): File = File(context.filesDir, "exports").apply { mkdirs() }

    private fun stamp(): String {
        val j = Jalali.today()
        return "%04d%02d%02d_%02d%02d".format(
            j.year, j.month, j.day,
            java.time.LocalTime.now().hour, java.time.LocalTime.now().minute
        )
    }

    fun exportInvoicesCsv(invoices: List<Invoice>, customerNames: Map<Long, String>) {
        val header = "شماره,تاریخ,سررسید,مشتری,مبلغ کل,تخفیف,پرداخت‌شده,مانده,وضعیت,UUID اکانت,لینک اشتراک"
        val rows = invoices.map { inv ->
            listOf(
                inv.number,
                Jalali.formatEpochDay(inv.issuedAtEpochDay, persian = false),
                if (inv.dueAtEpochDay > 0) Jalali.formatEpochDay(inv.dueAtEpochDay, persian = false) else "",
                customerNames[inv.customerId] ?: "",
                inv.totalRial.toString(),
                inv.discountRial.toString(),
                inv.paidRial.toString(),
                inv.remainingRial.toString(),
                InvoiceStatus.label(inv.status),
                inv.panelUserUuid,
                inv.subscriptionUrl
            ).joinToString(",") { escape(it) }
        }
        writeAndShare("invoices_${stamp()}.csv", (listOf(header) + rows).joinToString("\r\n"))
    }

    fun exportCustomersCsv(rows: List<CustomerRow>) {
        val header = "نام,موبایل,۴رقم‌کارت,سقف اعتبار,مانده,بدهکار/بستانکار,یادداشت"
        val lines = rows.map {
            listOf(
                it.customer.name,
                it.customer.phone,
                it.customer.cardLast4,
                it.customer.creditLimitRial.toString(),
                it.balanceRial.toString(),
                if (it.balanceRial > 0) "بدهکار" else if (it.balanceRial < 0) "بستانکار" else "تسویه",
                it.customer.note
            ).joinToString(",") { v -> escape(v) }
        }
        writeAndShare("customers_${stamp()}.csv", (listOf(header) + lines).joinToString("\r\n"))
    }

    fun exportPaymentsCsv(payments: List<ir.cmbtools.ledger.data.Payment>, customerNames: Map<Long, String>) {
        val header = "تاریخ,مشتری,مبلغ,روش,پیگیری,اعتبار باقی‌مانده,یادداشت"
        val lines = payments.map {
            listOf(
                Jalali.formatEpochDay(it.receivedAtEpochDay, persian = false),
                customerNames[it.customerId] ?: "",
                it.amountRial.toString(),
                PaymentMethod.label(it.method),
                it.refNo,
                it.unallocatedRial.toString(),
                it.note
            ).joinToString(",") { v -> escape(v) }
        }
        writeAndShare("payments_${stamp()}.csv", (listOf(header) + lines).joinToString("\r\n"))
    }

    /**
     * پشتیبان کامل. عمداً غیر-suspend است تا از دکمه‌های UI مستقیم صدا زده شود؛
     * کار در ترد IO انجام و نتیجه در ترد اصلی تحویل داده می‌شود.
     */
    fun exportBackup(onResult: (Result<File>) -> Unit = {}) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = runCatching { buildBackupFile() }
            withContext(Dispatchers.Main) {
                result.onSuccess { share(it, "application/json") }
                onResult(result)
            }
        }
    }

    /** Write the backup directly to a user-selected document (Downloads or another folder). */
    fun saveBackup(uri: Uri, onResult: (Result<Uri>) -> Unit = {}) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = runCatching {
                val file = buildBackupFile()
                val output = context.contentResolver.openOutputStream(uri, "wt")
                    ?: error("فایل مقصد برای نوشتن باز نشد")
                output.use { destination -> file.inputStream().use { source -> source.copyTo(destination) } }
                uri
            }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    private suspend fun buildBackupFile(): File {
        val dao = container.backupDao()
        val payload = BackupPayload(
            version = 3,
            appVersion = ir.cmbtools.ledger.BuildConfig.VERSION_NAME,
            customers = dao.allCustomers(),
            plans = dao.allPlans(),
            invoices = dao.allInvoices(),
            lines = dao.allLines(),
            payments = dao.allPayments(),
            allocations = dao.allAllocations(),
            ledger = dao.allLedger(),
            expenses = dao.allExpenses(),
            // ↓ مواردی که در نسخهٔ قبل در فایل پشتیبان نبودند
            panelConfig = dao.allPanelConfigs().firstOrNull(),
            panelAccounts = dao.allPanelAccounts(),
            bankSms = dao.allBankSms(),
            smsOutbox = dao.allOutbox(),
            // Secret اتصال ربات هیچ‌وقت به فایل JSON پشتیبانِ بدون رمزگذاری صادر نمی‌شود.
            // پس از بازیابی، اتصال ربات خاموش می‌ماند و Secret را باید دوباره وارد کرد.
            settings = container.prefs.current.copy(botApiSecret = "", botPaymentEnabled = false),
            invoiceNumberHighWatermark = maxOf(
                container.db.invoiceNumberCounter().lastNumber() ?: 0L,
                container.db.invoiceNumberCounter().maximumInvoiceNumber()
            )
        )
        val file = File(exportsDir(), "backup_${stamp()}.json")
        file.writeText(json.encodeToString(BackupPayload.serializer(), payload))
        return file
    }

    suspend fun importBackup(uri: Uri): Result<BackupPayload> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("فایل خوانده نشد")
            val payload = json.decodeFromString(BackupPayload.serializer(), text)
            container.db.withTransaction {
                container.backupDao().replaceAll(payload)
                container.db.invoiceNumberCounter().ensureAtLeast(payload.invoiceNumberHighWatermark)
            }
            // تنظیمات برنامه (پیامک/فاکتور/همگام‌سازی/قفل/...) هم بازیابی می‌شوند
            payload.settings?.let { saved ->
                container.prefs.update { current ->
                    // پین قفل و کلیدهای امنیتی عمداً از فایل پشتیبان می‌آیند تا بازیابی کامل باشد،
                    // ولی اگر فایل قدیمی این فیلدها را نداشت، مقدار فعلی حفظ می‌شود.
                    saved.copy(
                        lockPin = saved.lockPin.ifBlank { current.lockPin },
                        lockEnabled = saved.lockEnabled,
                        // حتی اگر پشتیبان قدیمی Secret ربات داشته باشد، از فایل وارد نمی‌کنیم.
                        botApiSecret = current.botApiSecret,
                        botPaymentEnabled = false
                    )
                }
            }
            // اگر تنظیمات پنل تازه بازیابی شد، سرویس همگام‌سازی از نو راه بیفتد
            withContext(Dispatchers.Main) {
                runCatching { ir.cmbtools.ledger.sync.AutoSyncController.refresh(context) }
            }
            payload
        }
    }

    private fun escape(value: String): String {
        val v = value.replace("\"", "\"\"")
        return if (v.contains(",") || v.contains("\n")) "\"$v\"" else v
    }

    private fun writeAndShare(name: String, content: String) {
        val file = File(exportsDir(), name)
        // BOM برای نمایش صحیح فارسی در Excel ویندوز
        file.writeText("\uFEFF" + content)
        share(file, "text/csv")
    }

    private fun share(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری ${file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
