package ir.cmbtools.ledger

import android.app.Application
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LedgerApp : Application() {

    lateinit var container: AppContainer
        private set

    lateinit var notifications: Notifications
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        notifications = Notifications(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.seed.ifEmpty()
            // دفتر مشتری مرجع مانده است؛ هر بار که اپ باز می‌شود snapshotهای فاکتور هم‌تراز می‌شوند.
            runCatching {
                container.db.withTransaction {
                    container.customers.reconcileAllOpeningBalanceSnapshots()
                }
            }
            // شماره‌های قدیمی (فارسی/با فاصله/+98) یک‌بار به قالب 09xxxxxxxxx تبدیل می‌شوند
            // تا تطبیق با «یادداشت» کاربران پنل و پیامک بانکی دقیق باشد.
            runCatching { container.billing.normalizeStoredPhones() }
        }
    }
}
