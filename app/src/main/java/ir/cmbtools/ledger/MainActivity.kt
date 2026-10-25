package ir.cmbtools.ledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import ir.cmbtools.ledger.sync.AutoSyncController
import ir.cmbtools.ledger.ui.AppRoot
import ir.cmbtools.ledger.ui.theme.CmbTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LedgerApp
        setContent {
            CmbTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppRoot(app.container, app.notifications)
                }
            }
        }
    }

    /**
     * سرویس همگام‌سازی خودکار از «Activity در پیش‌زمینه» شروع می‌شود؛
     * این کار از خطای محدودیت شروع سرویس در پس‌زمینه (Android 12+) جلوگیری می‌کند.
     */
    override fun onStart() {
        super.onStart()
        runCatching { AutoSyncController.refresh(this) }
    }
}
