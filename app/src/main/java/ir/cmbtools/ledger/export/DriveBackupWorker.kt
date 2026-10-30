package ir.cmbtools.ledger.export

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ir.cmbtools.ledger.LedgerApp
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Writes a full JSON backup to a folder explicitly granted by the user via Android's SAF picker. */
class DriveBackupWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LedgerApp ?: return Result.failure()
        val prefs = app.container.prefs
        val manualRequest = inputData.getBoolean(KEY_MANUAL_REQUEST, false)
        if (!manualRequest && !prefs.isDriveBackupEnabled()) return Result.success()

        val rawUri = prefs.driveBackupFolderUri()
        if (rawUri.isNullOrBlank()) {
            prefs.setDriveBackupError("پوشهٔ Google Drive انتخاب نشده است")
            return Result.failure()
        }

        var source: File? = null
        var partial: DocumentFile? = null
        return try {
            val treeUri = Uri.parse(rawUri)
            val permission = applicationContext.contentResolver.persistedUriPermissions
                .firstOrNull { it.uri == treeUri && it.isWritePermission }
            check(permission != null) { "اجازهٔ دسترسی به پوشه لغو شده است؛ پوشه را دوباره انتخاب کنید" }

            val folder = DocumentFile.fromTreeUri(applicationContext, treeUri)
            check(folder != null && folder.isDirectory && folder.canWrite()) {
                "پوشهٔ مقصد در دسترس نیست؛ اتصال Google Drive و مجوز پوشه را بررسی کنید"
            }

            val backupFile = ExportManager(applicationContext, app.container).createBackupFileForAutomation()
            source = backupFile
            val stamp = FILE_STAMP.format(Instant.now())
            val finalName = "tiznet-auto-backup-$stamp.json"
            val stagingDocument = folder.createFile("application/json", "tiznet-auto-backup-$stamp.partial")
                ?: error("ساخت فایل پشتیبان موقت در Drive ممکن نشد")
            partial = stagingDocument

            val output = applicationContext.contentResolver.openOutputStream(stagingDocument.uri, "w")
                ?: error("نوشتن در پوشهٔ انتخاب‌شده ممکن نیست")
            output.use { destination -> backupFile.inputStream().use { it.copyTo(destination) } }

            check(stagingDocument.renameTo(finalName)) { "تأیید نهایی فایل در Drive ناموفق بود" }
            partial = null
            runCatching { retainNewestBackups(folder) }

            prefs.setDriveBackupSuccess()
            Result.success()
        } catch (error: Exception) {
            partial?.delete()
            val safeMessage = error.message
                ?.takeIf { it.isNotBlank() }
                ?.take(180)
                ?: "خطای نامشخص در پشتیبان‌گیری"
            prefs.setDriveBackupError(safeMessage)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } finally {
            source?.delete()
        }
    }

    private fun retainNewestBackups(folder: DocumentFile) {
        val backups = folder.listFiles()
            .filter { file ->
                val name = file.name.orEmpty()
                file.isFile && name.startsWith(BACKUP_PREFIX) && name.endsWith(".json")
            }
            .sortedBy { it.name.orEmpty() }
        backups.take((backups.size - MAX_BACKUPS).coerceAtLeast(0)).forEach { it.delete() }
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "tiznet-google-drive-auto-backup"
        private const val UNIQUE_MANUAL_NAME = "tiznet-google-drive-backup-now"
        private const val KEY_MANUAL_REQUEST = "manual_request"
        private const val BACKUP_PREFIX = "tiznet-auto-backup-"
        private const val MAX_BACKUPS = 30
        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enable(context: android.content.Context) {
            val appContext = context.applicationContext
            val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            backupNow(appContext)
        }

        fun disable(context: android.content.Context) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_NAME)
            workManager.cancelUniqueWork(UNIQUE_MANUAL_NAME)
        }

        fun backupNow(context: android.content.Context) {
            val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setInputData(Data.Builder().putBoolean(KEY_MANUAL_REQUEST, true).build())
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_MANUAL_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
