package ir.cmbtools.ledger.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import ir.cmbtools.ledger.LedgerApp
import ir.cmbtools.ledger.core.Jalali
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * همگام‌سازی خودکار با پنل در بازهٔ مشخص (پیش‌فرض هر ۵ دقیقه).
 *
 * چرا سرویس Foreground؟ WorkManager حداقل بازهٔ ۱۵ دقیقه دارد و در Doze ممکن است
 * ساعت‌ها دیر شود. برای «هر ۵ دقیقه» فقط سرویس دائمی تضمین واقعی می‌دهد.
 * در عوض یک اعلان کوچک و کم‌اهمیت در نوار وضعیت نشان داده می‌شود و می‌توانید
 * از تنظیمات خاموشش کنید.
 */
class AutoSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as LedgerApp
        startForeground(NOTIFICATION_ID, app.notifications.autoSyncNotification("در حال هم‌گام‌سازی با پنل..."))

        if (loop?.isActive != true) {
            loop = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private suspend fun runLoop() {
        val app = application as LedgerApp
        var lastMessage = "آماده"

        while (scope.isActive) {
            val settings = app.container.prefs.current
            if (!settings.autoSyncEnabled) {
                stopSelf()
                return
            }

            val configured = runCatching { app.container.panelService.isConfigured() }.getOrDefault(false)
            if (configured) {
                lastMessage = runCatching {
                    val report = app.container.syncEngine.sync()
                    val time = Jalali.formatDateTime(System.currentTimeMillis())
                    if (report.invoicesIssued > 0 || report.smsSent > 0) {
                        app.notifications.show("همگام‌سازی خودکار", report.message)
                    }
                    "${report.message} • $time"
                }.getOrElse { e ->
                    "${e.message ?: "خطای همگام‌سازی"} • ${Jalali.formatDateTime(System.currentTimeMillis())}"
                }
            } else {
                lastMessage = "پنل تنظیم نشده است"
            }

            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    app.notifications.autoSyncNotification(lastMessage)
                )
            }

            val minutes = settings.autoSyncMinutes.coerceIn(1, 120)
            delay(minutes * 60_000L)
        }
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 4711
    }
}

/**
 * کنترل روشن/خاموش کردن سرویس همگام‌سازی از هر جای اپ (تنظیمات، شروع اپ، بوت گوشی).
 */
object AutoSyncController {

    fun refresh(context: Context) {
        val app = context.applicationContext as? LedgerApp ?: return
        if (app.container.prefs.current.autoSyncEnabled) start(context) else stop(context)
    }

    fun start(context: Context) {
        val intent = Intent(context, AutoSyncService::class.java)
        runCatching {
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }
    }

    fun stop(context: Context) {
        val ctx = context.applicationContext
        runCatching { ctx.stopService(Intent(ctx, AutoSyncService::class.java)) }
    }
}
