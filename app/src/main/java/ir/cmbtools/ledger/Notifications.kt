package ir.cmbtools.ledger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class Notifications(private val context: Context) {

    private val channelId = "ledger_events"

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            "رویدادهای مالی",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "ثبت خودکار پرداخت و پیامک‌های در انتظار تطبیق" }
        manager.createNotificationChannel(channel)
    }

    fun show(title: String, text: String, id: Int = (System.currentTimeMillis() % 10_000).toInt()) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
