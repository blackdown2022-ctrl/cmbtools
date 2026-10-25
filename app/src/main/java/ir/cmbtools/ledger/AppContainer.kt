package ir.cmbtools.ledger

import android.content.Context
import ir.cmbtools.ledger.core.Prefs
import ir.cmbtools.ledger.data.AppDatabase
import ir.cmbtools.ledger.data.CustomerRepository
import ir.cmbtools.ledger.data.InvoiceRepository
import ir.cmbtools.ledger.data.PaymentRepository
import ir.cmbtools.ledger.data.ReportingRepository
import ir.cmbtools.ledger.panel.PanelClient
import ir.cmbtools.ledger.panel.PanelService
import ir.cmbtools.ledger.sms.PaymentMatcher
import ir.cmbtools.ledger.sms.SmsSender

/**
 * وابستگی‌ها به‌صورت دستی ساخته می‌شوند (بدون Hilt) تا پروژه سبک و قابل فهم بماند.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val db: AppDatabase = AppDatabase.build(appContext)
    val prefs = Prefs(appContext)

    private val customersDao = db.customers()
    private val plansDao = db.plans()
    private val invoicesDao = db.invoices()
    private val paymentsDao = db.payments()
    private val ledgerDao = db.ledger()
    private val expensesDao = db.expenses()
    private val panelDao = db.panel()
    private val bankSmsDao = db.bankSms()
    private val outboxDao = db.smsOutbox()

    val customers = CustomerRepository(customersDao, ledgerDao, invoicesDao)
    val invoices = InvoiceRepository(invoicesDao, ledgerDao, paymentsDao)
    val payments = PaymentRepository(paymentsDao, invoicesDao, invoices, ledgerDao)
    val reports = ReportingRepository(invoicesDao, paymentsDao, expensesDao, ledgerDao, customersDao, panelDao, bankSmsDao)

    val panelClient = PanelClient(panelDao, prefs)
    val panelService = PanelService(panelClient, panelDao, customersDao, prefs)
    val matcher = PaymentMatcher(bankSmsDao, customersDao, invoicesDao, payments, prefs)
    val smsSender = SmsSender(appContext, outboxDao)
    val seed = SeedData(customersDao, plansDao, panelDao)

    fun plans() = plansDao
    fun expensesDao() = expensesDao
    fun panelDao2() = panelDao
    fun bankSmsDao2() = bankSmsDao
    fun outboxDao2() = outboxDao
    fun invoicesDao() = invoicesDao
    fun paymentsDao() = paymentsDao
    fun ledgerDao() = ledgerDao
    fun customersDao() = customersDao
    fun backupDao() = db.backup()
}
