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
        if (!settings.autoSyncEnabled || !app.container.panelService.isConfigured()) return Result.success()
        return try {
            app.container.syncEngine.sync()
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
