package ir.cmbtools.ledger.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ir.cmbtools.ledger.LedgerApp
import java.util.concurrent.TimeUnit

/** OS-managed fallback for sync when Android pauses/stops the foreground loop. */
class PanelSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LedgerApp ?: return Result.failure()
        val settings = app.container.prefs.current
        if (!settings.autoSyncEnabled) return Result.success()
        return try {
            if (!app.container.panelService.isConfigured()) return Result.success()
            val report = app.container.syncEngine.sync()
            if (report.invoicesIssued > 0 || report.smsSent > 0 || report.problems.isNotEmpty()) {
                val details = report.problems.take(3).joinToString("\n")
                val summary = listOf(report.message, details).filter { it.isNotBlank() }.joinToString("\n")
                app.notifications.show("همگام‌سازی خودکار", summary)
            }
            Result.success()
        } catch (e: Exception) {
            app.notifications.show(
                "همگام‌سازی خودکار انجام نشد",
                e.message ?: "اتصال پنل را بررسی کنید"
            )
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "tiznet-panel-sync-fallback"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<PanelSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
