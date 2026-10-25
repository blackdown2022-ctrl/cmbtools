package ir.cmbtools.ledger.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * پس از روشن شدن گوشی، اگر همگام‌سازی خودکار روشن است، سرویس را دوباره راه می‌اندازد.
 * (بدون این، بعد از هر ری‌استارت باید اپ را دستی باز می‌کردید.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            runCatching { AutoSyncController.refresh(context) }
        }
    }
}
