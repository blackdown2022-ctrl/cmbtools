package ir.cmbtools.ledger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.cmbtools.ledger.AppContainer
import ir.cmbtools.ledger.LedgerApp
import ir.cmbtools.ledger.core.AppSettings
import ir.cmbtools.ledger.core.InvoiceKind
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.PaymentMethod
import ir.cmbtools.ledger.core.SmsMode
import ir.cmbtools.ledger.data.BankSms
import ir.cmbtools.ledger.data.Customer
import ir.cmbtools.ledger.data.CustomerRow
import ir.cmbtools.ledger.data.DashboardSummary
import ir.cmbtools.ledger.data.DailyPoint
import ir.cmbtools.ledger.data.Expense
import ir.cmbtools.ledger.data.Invoice
import ir.cmbtools.ledger.data.InvoiceLine
import ir.cmbtools.ledger.data.LedgerEntry
import ir.cmbtools.ledger.data.NewInvoiceLine
import ir.cmbtools.ledger.data.PanelAccount
import ir.cmbtools.ledger.data.PanelConfig
import ir.cmbtools.ledger.data.Payment
import ir.cmbtools.ledger.data.Plan
import ir.cmbtools.ledger.data.SmsOutbox
import ir.cmbtools.ledger.panel.PanelConnection
import ir.cmbtools.ledger.panel.PanelException
import ir.cmbtools.ledger.sms.SmsSender
import ir.cmbtools.ledger.sms.SmsTemplate
import ir.cmbtools.ledger.sync.AutoSyncController
import ir.cmbtools.ledger.sync.SyncReport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ActionResult(
    val ok: Boolean,
    val message: String = "",
    val invoiceId: Long = 0,
    val subscriptionUrl: String = ""
)

data class UiEvent(val message: String, val isError: Boolean = false)

/**
 * یک ViewModel مرکزی: برای اپ تک‌کاربره و محلی، ساده‌تر و کم‌خطاتر از چند ViewModel است.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container: AppContainer = (application as LedgerApp).container
    private val c get() = container

    val settings: StateFlow<AppSettings> = c.prefs.settings

    private val _events = MutableStateFlow<UiEvent?>(null)
    val events: StateFlow<UiEvent?> = _events.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _panelStatus = MutableStateFlow("")
    val panelStatus: StateFlow<String> = _panelStatus.asStateFlow()

    private val _lastSync = MutableStateFlow<SyncReport?>(null)
    val lastSync: StateFlow<SyncReport?> = _lastSync.asStateFlow()

    private val _navIntent = MutableStateFlow<String?>(null)
    val navIntent: StateFlow<String?> = _navIntent.asStateFlow()

    /** درخواست باز شدن فرم فاکتور جدید (از داشبورد یا صفحهٔ مشتری) */
    private val _newInvoiceRequest = MutableStateFlow(0L)
    val newInvoiceRequest: StateFlow<Long> = _newInvoiceRequest.asStateFlow()

    fun requestNewInvoice(customerId: Long = 0L) { _newInvoiceRequest.value = if (customerId > 0) customerId else -1L }
    fun consumeNewInvoiceRequest() { _newInvoiceRequest.value = 0L }

    // ---------------------------------------------------------------- داده‌های خواندنی

    val dashboard: StateFlow<DashboardSummary> = c.reports.observeDashboard()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardSummary())

    val dailySales: StateFlow<List<DailyPoint>> = c.reports.observeDailySales()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aging = c.reports.observeAging()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ir.cmbtools.ledger.data.AgingReport(emptyList()))

    val customers: StateFlow<List<CustomerRow>> = c.customers.observeRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val plans: StateFlow<List<Plan>> = c.plans().observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPlans: StateFlow<List<Plan>> = c.plans().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val invoices: StateFlow<List<Invoice>> = c.invoices.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentPayments: StateFlow<List<Payment>> = c.payments.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentLedger: StateFlow<List<LedgerEntry>> = c.payments.observeRecentLedger()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unmatchedSms: StateFlow<List<BankSms>> = c.bankSmsDao2().observeUnmatched()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allBankSms: StateFlow<List<BankSms>> = c.bankSmsDao2().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val smsOutbox: StateFlow<List<SmsOutbox>> = c.outboxDao2().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val panelAccounts: StateFlow<List<PanelAccount>> = c.panelDao2().observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val panelConfig: StateFlow<PanelConfig?> = c.panelDao2().observeConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val expenses: StateFlow<List<Expense>> = c.expensesDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun customerFlow(id: Long): Flow<Customer?> = c.customers.observeById(id)
    fun customerBalance(id: Long): Flow<Long> = c.customers.observeBalance(id)
    fun customerInvoices(id: Long): Flow<List<Invoice>> = c.invoices.observeByCustomer(id)
    fun customerPayments(id: Long): Flow<List<Payment>> = c.payments.observeByCustomer(id)
    fun customerLedger(id: Long): Flow<List<LedgerEntry>> = c.payments.observeLedger(id)
    fun customerAccounts(id: Long): Flow<List<PanelAccount>> = c.panelDao2().observeAccountsOf(id)
    fun invoiceFlow(id: Long): Flow<Invoice?> = c.invoices.observeById(id)
    fun invoiceLines(id: Long): Flow<List<InvoiceLine>> = c.invoices.observeLines(id)

    val expiringAccounts: StateFlow<List<PanelAccount>> = panelAccounts
        .map { list ->
            val soon = LocalDate.now().plusDays(7).toEpochDay()
            list.filter { it.enabled && it.expireEpochDay in 1..soon }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---------------------------------------------------------------- پیام‌ها

    fun clearEvent() { _events.value = null }
    fun consumeNavIntent() { _navIntent.value = null }

    private fun success(message: String) { _events.value = UiEvent(message, false) }
    private fun failure(message: String) { _events.value = UiEvent(message, true) }

    // ---------------------------------------------------------------- مشتری

    fun saveCustomer(
        id: Long,
        name: String,
        phone: String,
        cardLast4: String,
        note: String,
        creditLimitRial: Long,
        onDone: (Long) -> Unit = {}
    ) = viewModelScope.launch {
        runCatching {
            if (name.isBlank()) error("نام مشتری را وارد کنید")
            if (phone.isNotBlank() && !SmsSender.isValidIranMobile(phone)) error("شمارهٔ موبایل نامعتبر است")
            if (id == 0L) {
                c.customers.create(name, phone, cardLast4, note, creditLimitRial)
            } else {
                val existing = c.customers.get(id) ?: error("مشتری پیدا نشد")
                c.customers.update(
                    existing.copy(
                        name = name.trim(),
                        phone = phone.trim(),
                        cardLast4 = cardLast4.trim().takeLast(4),
                        note = note.trim(),
                        creditLimitRial = creditLimitRial.coerceAtLeast(0)
                    )
                )
                id
            }
        }.onSuccess {
            success("مشتری ذخیره شد")
            onDone(it)
        }.onFailure { failure(it.message ?: "خطا در ذخیرهٔ مشتری") }
    }

    fun archiveCustomer(id: Long) = viewModelScope.launch {
        runCatching { c.customers.archive(id) }
            .onSuccess { success("مشتری بایگانی شد") }
            .onFailure { failure(it.message ?: "خطا") }
    }

    fun adjustBalance(customerId: Long, deltaRial: Long, memo: String, date: LocalDate) = viewModelScope.launch {
        runCatching {
            if (deltaRial == 0L) error("مبلغ تعدیل صفر است")
            c.customers.adjustBalance(customerId, deltaRial, memo.ifBlank { "تعدیل دستی" }, date.toEpochDay())
        }.onSuccess { success("تعدیل ثبت شد") }
            .onFailure { failure(it.message ?: "خطا") }
    }

    // ---------------------------------------------------------------- بسته‌ها

    fun savePlan(id: Long, name: String, gb: Int, days: Int, priceRial: Long, onDone: () -> Unit = {}) =
        viewModelScope.launch {
            runCatching {
                if (name.isBlank()) error("نام بسته را وارد کنید")
                if (gb <= 0 || days <= 0) error("حجم و روز باید بزرگ‌تر از صفر باشند")
                if (id == 0L) {
                    c.plans().insert(Plan(name = name.trim(), gb = gb, days = days, priceRial = priceRial))
                } else {
                    val existing = c.plans().byId(id) ?: error("بسته پیدا نشد")
                    c.plans().update(existing.copy(name = name.trim(), gb = gb, days = days, priceRial = priceRial))
                }
            }.onSuccess {
                success("بسته ذخیره شد")
                onDone()
            }.onFailure { failure(it.message ?: "خطا") }
        }

    fun deletePlan(id: Long) = viewModelScope.launch {
        runCatching { c.plans().delete(id) }
            .onSuccess { success("بسته حذف شد") }
            .onFailure { failure(it.message ?: "خطا") }
    }

    // ---------------------------------------------------------------- فاکتور + اکانت

    /**
     * یک‌کلیک: فاکتور صادر می‌شود، اگر انتخاب شده باشد اکانت در پنل ساخته می‌شود،
     * و در صورت موفقیت لینک اشتراک روی فاکتور ثبت می‌شود. پیامک اختیاری است.
     */
    fun issueInvoice(
        customerId: Long,
        planId: Long,
        description: String,
        gb: Double,
        days: Int,
        priceRial: Long,
        discountRial: Long,
        createPanelAccount: Boolean,
        sendSms: Boolean,
        dueInDays: Long,
        note: String,
        onDone: (ActionResult) -> Unit
    ) = viewModelScope.launch {
        _busy.value = true
        var result = ActionResult(false)
        try {
            if (customerId <= 0) error("مشتری را انتخاب کنید")
            if (priceRial < 0) error("مبلغ نامعتبر است")

            val planName = plans.value.firstOrNull { it.id == planId }?.name ?: description
            val packageLabel = description.ifBlank { planName.ifBlank { "سرویس" } }
            val invoiceId = c.invoices.create(
                customerId = customerId,
                lines = listOf(
                    NewInvoiceLine(
                        description = packageLabel,
                        qty = 1,
                        unitPriceRial = priceRial,
                        planId = planId
                    )
                ),
                discountRial = discountRial,
                dueAt = if (dueInDays > 0) LocalDate.now().plusDays(dueInDays) else null,
                note = note,
                planId = planId,
                status = InvoiceStatus.ISSUED,
                packageName = packageLabel,
                cardNumber = settings.value.paymentCardNumber,
                kind = if (createPanelAccount) InvoiceKind.NEW else InvoiceKind.MANUAL
            )
            result = ActionResult(true, "فاکتور صادر شد", invoiceId)

            if (createPanelAccount) {
                if (!c.panelService.isConfigured()) {
                    result = result.copy(ok = true, message = "فاکتور صادر شد؛ اکانت پنل ساخته نشد (اتصال پنل تنظیم نشده)")
                } else {
                    try {
                        val invoice = c.invoices.get(invoiceId)
                        val provision = c.panelService.createAccount(
                            customerId = customerId,
                            planName = planName,
                            gb = gb,
                            days = days,
                            invoiceNumber = invoice?.number ?: ""
                        )
                        c.invoices.setPanelInfo(invoiceId, provision.uuid, 1, provision.subscriptionUrl)
                        result = result.copy(
                            message = "اکانت ساخته شد (${provision.expireInDays} روز اعتبار)",
                            subscriptionUrl = provision.subscriptionUrl
                        )
                    } catch (e: PanelException) {
                        result = result.copy(
                            message = "فاکتور صادر شد، اما ساخت اکانت ناموفق بود: ${e.message}. می‌توانید از صفحهٔ فاکتور دوباره تلاش کنید."
                        )
                    }
                }
            }

            if (sendSms) {
                val smsResult = sendInvoiceSmsInternal(invoiceId)
                result = result.copy(message = result.message + " | " + smsResult.message)
            }
            success(result.message)
        } catch (e: Exception) {
            result = ActionResult(false, e.message ?: "خطای صدور فاکتور")
            failure(result.message)
        } finally {
            _busy.value = false
            onDone(result)
        }
    }

    /** ساخت/تلاش دوبارهٔ اکانت پنل برای فاکتور موجود */
    fun createAccountForInvoice(invoiceId: Long, gb: Double, days: Int) = viewModelScope.launch {
        _busy.value = true
        try {
            val invoice = c.invoices.get(invoiceId) ?: error("فاکتور پیدا نشد")
            val provision = c.panelService.createAccount(
                customerId = invoice.customerId,
                planName = invoice.note,
                gb = gb,
                days = days,
                invoiceNumber = invoice.number
            )
            c.invoices.setPanelInfo(invoiceId, provision.uuid, 1, provision.subscriptionUrl)
            success("اکانت ساخته شد")
        } catch (e: Exception) {
            failure(e.message ?: "خطا در ساخت اکانت")
        } finally {
            _busy.value = false
        }
    }

    /** تمدید اکانت موجود + صدور فاکتور تمدید */
    fun renewAccount(
        customerId: Long,
        panelUuid: String,
        planId: Long,
        description: String,
        gb: Double,
        days: Int,
        priceRial: Long,
        sendSms: Boolean,
        onDone: (ActionResult) -> Unit
    ) = viewModelScope.launch {
        _busy.value = true
        var result = ActionResult(false)
        try {
            val packageLabel = description.ifBlank { "تمدید سرویس" }
            val invoiceId = c.invoices.create(
                customerId = customerId,
                lines = listOf(
                    NewInvoiceLine(
                        description = packageLabel,
                        unitPriceRial = priceRial,
                        planId = planId
                    )
                ),
                planId = planId,
                note = "تمدید",
                status = InvoiceStatus.ISSUED,
                packageName = packageLabel,
                cardNumber = settings.value.paymentCardNumber,
                kind = InvoiceKind.RENEW
            )
            result = ActionResult(true, "فاکتور تمدید صادر شد", invoiceId)
            try {
                val provision = c.panelService.renew(panelUuid, gb, days)
                c.invoices.setPanelInfo(
                    invoiceId,
                    provision.uuid,
                    1,
                    provision.subscriptionUrl
                )
                result = result.copy(
                    message = "تمدید انجام شد (${provision.expireInDays} روز باقی‌مانده)",
                    subscriptionUrl = provision.subscriptionUrl
                )
            } catch (e: PanelException) {
                result = result.copy(message = "فاکتور صادر شد، اما تمدید پنل ناموفق بود: ${e.message}")
            }
            if (sendSms) {
                val smsResult = sendInvoiceSmsInternal(invoiceId)
                result = result.copy(message = result.message + " | " + smsResult.message)
            }
            success(result.message)
        } catch (e: Exception) {
            result = ActionResult(false, e.message ?: "خطای تمدید")
            failure(result.message)
        } finally {
            _busy.value = false
            onDone(result)
        }
    }

    fun voidInvoice(id: Long) = viewModelScope.launch {
        runCatching { c.invoices.void(id) }
            .onSuccess { success("فاکتور ابطال شد") }
            .onFailure { failure(it.message ?: "خطا") }
    }

    // ---------------------------------------------------------------- پرداخت

    fun recordPayment(
        customerId: Long,
        amountRial: Long,
        method: String,
        date: LocalDate,
        refNo: String,
        note: String,
        preferredInvoiceId: Long = 0,
        onDone: (String) -> Unit = {}
    ) = viewModelScope.launch {
        runCatching {
            if (amountRial <= 0) error("مبلغ را وارد کنید")
            c.payments.record(
                customerId = customerId,
                amountRial = amountRial,
                method = method,
                at = date,
                refNo = refNo,
                note = note,
                preferredInvoiceId = preferredInvoiceId
            )
        }.onSuccess { r ->
            val msg = buildString {
                if (r.allocatedRial > 0) append("${Money.format(r.allocatedRial)} به فاکتورها تخصیص یافت")
                if (r.unallocatedRial > 0) {
                    if (isNotEmpty()) append(" | ")
                    append("${Money.format(r.unallocatedRial)} به‌عنوان اعتبار مشتری ثبت شد")
                }
            }
            success(msg.ifBlank { "پرداخت ثبت شد" })
            onDone(msg)
        }.onFailure { failure(it.message ?: "خطا در ثبت پرداخت") }
    }

    fun confirmBankSms(smsId: Long, customerId: Long, invoiceId: Long) = viewModelScope.launch {
        runCatching {
            if (customerId <= 0) error("مشتری را انتخاب کنید")
            c.matcher.confirmManually(smsId, customerId, invoiceId)
        }.onSuccess { success("پرداخت از پیامک ثبت و تطبیق شد") }
            .onFailure { failure(it.message ?: "خطا در تطبیق") }
    }

    fun ignoreBankSms(smsId: Long) = viewModelScope.launch {
        runCatching { c.bankSmsDao2().markIgnored(smsId) }
            .onSuccess { success("پیامک نادیده گرفته شد") }
            .onFailure { failure(it.message ?: "خطا") }
    }

    // ---------------------------------------------------------------- پیامک

    private suspend fun sendInvoiceSmsInternal(invoiceId: Long): ActionResult {
        // یک مسیر واحد برای پیامک: همان چیزی که همگام‌سازی خودکار هم استفاده می‌کند
        val r = c.billing.sendInvoiceSms(invoiceId)
        return ActionResult(r.ok, r.message, invoiceId, c.invoices.get(invoiceId)?.subscriptionUrl ?: "")
    }

    fun sendInvoiceSms(invoiceId: Long) = viewModelScope.launch {
        _busy.value = true
        val r = sendInvoiceSmsInternal(invoiceId)
        if (r.ok) success(r.message) else failure(r.message)
        _busy.value = false
    }

    fun sendReminder(customerId: Long) = viewModelScope.launch {
        _busy.value = true
        try {
            val customer = c.customers.get(customerId) ?: error("مشتری پیدا نشد")
            if (customer.phone.isBlank()) error("شمارهٔ مشتری ثبت نشده")
            val balanceRial = c.customers.observeBalance(customerId).first()
            val openCount = c.invoices.observeByCustomer(customerId).first()
                .count { it.status != InvoiceStatus.VOID && it.remainingRial > 0 }
            if (balanceRial <= 0 && openCount == 0) error("این مشتری بدهی ندارد")
            val body = SmsTemplate.renderReminder(settings.value, customer, balanceRial.coerceAtLeast(0), openCount)
            val result = c.smsSender.send(settings.value, customer.phone, body, customerId = customerId)
            if (result.ok) success("یادآوری ارسال شد") else failure(result.message)
        } catch (e: Exception) {
            failure(e.message ?: "خطا در ارسال یادآوری")
        } finally {
            _busy.value = false
        }
    }

    fun previewInvoiceSms(invoice: Invoice, customer: Customer, lines: List<InvoiceLine>): String =
        SmsTemplate.render(settings.value, invoice, customer, lines, invoice.subscriptionUrl)

    // ---------------------------------------------------------------- پنل

    fun savePanelConfig(host: String, path: String, apiKey: String, https: Boolean, name: String, onDone: () -> Unit = {}) =
        viewModelScope.launch {
            runCatching {
                if (host.isBlank()) error("دامنهٔ پنل را وارد کنید")
                if (path.isBlank()) error("مسیر پروکسی ادمین را وارد کنید")
                if (apiKey.isBlank()) error("کلید API (UUID ادمین) را وارد کنید")
                c.panelService.saveConfig(host, path, apiKey, https, name.ifBlank { "پنل اصلی" })
            }.onSuccess {
                success("تنظیمات پنل ذخیره شد")
                // پنل تازه تنظیم شد → همگام‌سازی خودکار را راه بینداز
                runCatching { AutoSyncController.start(getApplication<Application>()) }
                onDone()
            }.onFailure { failure(it.message ?: "خطا") }
        }

    fun testPanel() = viewModelScope.launch {
        _busy.value = true
        _panelStatus.value = "در حال بررسی..."
        try {
            val r: PanelConnection = c.panelService.test()
            _panelStatus.value = "متصل • نسخهٔ ${r.version.ifBlank { "?" }} • " +
                (if (r.readOnly) "پنل read-only است" else "قابل نوشتن") +
                (if (!r.canAddUsers) " • سقف کاربران پر است" else "")
            success("اتصال پنل برقرار است (نسخه ${r.version})")
        } catch (e: Exception) {
            _panelStatus.value = "اتصال ناموفق"
            failure(e.message ?: "اتصال برقرار نشد")
        } finally {
            _busy.value = false
        }
    }

    fun syncPanel() = viewModelScope.launch {
        _busy.value = true
        try {
            val report = c.syncEngine.sync()
            _lastSync.value = report
            success(report.message)
            if (report.problems.isNotEmpty()) {
                _events.value = UiEvent(
                    report.message + "\n" + report.problems.take(3).joinToString("\n"),
                    report.invoicesIssued == 0 && report.newUsers > 0
                )
            }
        } catch (e: Exception) {
            failure(e.message ?: "همگام‌سازی ناموفق")
        } finally {
            _busy.value = false
        }
    }

    /** روشن/خاموش کردن همگام‌سازی خودکار (سرویس را هم شروع/متوقف می‌کند) */
    fun setAutoSyncEnabled(enabled: Boolean) {
        updateSettings { it.copy(autoSyncEnabled = enabled) }
        runCatching {
            val app = getApplication<Application>()
            if (enabled) AutoSyncController.start(app) else AutoSyncController.stop(app)
        }
        success(if (enabled) "همگام‌سازی خودکار فعال شد" else "همگام‌سازی خودکار خاموش شد")
    }

    fun toggleAccount(account: PanelAccount) = viewModelScope.launch {
        runCatching { c.panelService.setEnabled(account.panelUserUuid, !account.enabled) }
            .onSuccess { success(if (account.enabled) "اکانت غیرفعال شد" else "اکانت فعال شد") }
            .onFailure { failure(it.message ?: "خطا") }
    }

    fun extendAccount(account: PanelAccount, addGb: Double, addDays: Int) = viewModelScope.launch {
        _busy.value = true
        try {
            c.panelService.extend(account.panelUserUuid, addGb, addDays)
            success("حجم/روز اضافه شد")
        } catch (e: Exception) {
            failure(e.message ?: "خطا")
        } finally {
            _busy.value = false
        }
    }

    // ---------------------------------------------------------------- هزینه

    fun addExpense(title: String, category: String, amountRial: Long, date: LocalDate, note: String) =
        viewModelScope.launch {
            runCatching {
                if (title.isBlank()) error("عنوان هزینه را وارد کنید")
                if (amountRial <= 0) error("مبلغ هزینه را وارد کنید")
                c.expensesDao().insert(
                    Expense(
                        title = title.trim(),
                        category = category.ifBlank { "عمومی" },
                        amountRial = amountRial,
                        atEpochDay = date.toEpochDay(),
                        note = note
                    )
                )
            }.onSuccess { success("هزینه ثبت شد") }
                .onFailure { failure(it.message ?: "خطا") }
        }

    fun deleteExpense(id: Long) = viewModelScope.launch {
        runCatching { c.expensesDao().delete(id) }
            .onSuccess { success("هزینه حذف شد") }
            .onFailure { failure(it.message ?: "خطا") }
    }

    // ---------------------------------------------------------------- تنظیمات

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        c.prefs.update(transform)
    }

    fun setSmsMode(mode: String) = updateSettings { it.copy(smsMode = mode) }

    fun quickRenewWithoutInvoiceCheck(): Boolean = settings.value.smsMode == SmsMode.SIM

    fun navigate(target: String) { _navIntent.value = target }

    fun appContainer(): AppContainer = c

    fun report(message: String, isError: Boolean = false) {
        if (isError) failure(message) else success(message)
    }

    fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val clipboard = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("cmbtools", text))
        success("در کلیپ‌بورد کپی شد")
    }
}
