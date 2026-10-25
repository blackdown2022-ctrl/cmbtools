package ir.cmbtools.ledger

import android.app.Application
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
        }
    }
}
