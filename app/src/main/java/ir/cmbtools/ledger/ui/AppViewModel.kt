package ir.cmbtools.ledger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.room.withTransaction
import androidx.lifecycle.viewModelScope
import ir.cmbtools.ledger.AppContainer
import ir.cmbtools.ledger.LedgerApp
import ir.cmbtools.ledger.core.AppSettings
import ir.cmbtools.ledger.core.InvoiceKind
import ir.cmbtools.ledger.core.InvoiceStatus
import ir.cmbtools.ledger.core.Jalali
import ir.cmbtools.ledger.core.Money
import ir.cmbtools.ledger.core.PaymentMethod
import ir.cmbtools.ledger.core.Phone
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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

    /** نتیجهٔ عیب‌یابی گام‌به‌گام پنل (تنظیمات → عیب‌یابی) */
    private val _panelChecks = MutableStateFlow<List<ir.cmbtools.ledger.panel.PanelCheck>>(emptyList())
    val panelChecks: StateFlow<List<ir.cmbtools.ledger.panel.PanelCheck>> = _panelChecks.asStateFlow()

    /** آدرس‌های دقیق پنل که اپ استفاده می‌کند (برای نمایش در تنظیمات) */
    private val _panelEndpoints = MutableStateFlow(ir.cmbtools.ledger.panel.PanelEndpoints())
    val panelEndpoints: StateFlow<ir.cmbtools.ledger.panel.PanelEndpoints> = _panelEndpoints.asStateFlow()

    /** گزارش متنی عیب‌یابی، آمادهٔ کپی/ارسال */
    private val _panelReport = MutableStateFlow("")
    val panelReport: StateFlow<String> = _panelReport.asStateFlow()

    /** پیشرفت کارهای طولانی (همگام‌سازی/ساخت کاربر) برای نمایش متن مرحله */
    private val _busyText = MutableStateFlow("")
    val busyText: StateFlow<String> = _busyText.asStateFlow()

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
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val aging = c.reports.observeAging()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ir.cmbtools.ledger.data.AgingReport(emptyList()))

    val customers: StateFlow<List<CustomerRow>> = c.customers.observeRows()
        // محاسبهٔ مانده هر مشتری (combine روی فاکتورها) روی رشتهٔ پس‌زمینه انجام شود؛
        // وگرنه روی گوشی ضعیف، بازترکیب صفحه‌ها کند می‌شود.
        .flowOn(Dispatchers.Default)
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
        .flowOn(Dispatchers.Default)
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
            // شماره همیشه یکسان ذخیره می‌شود (09xxxxxxxxx) تا تطبیق پنل/پیامک بانکی به هم نخورد
            val cleanPhone = Phone.normalize(phone).ifBlank { phone.trim() }
            if (id == 0L) {
                c.customers.create(name, cleanPhone, cardLast4, note, creditLimitRial)
            } else {
                val existing = c.customers.get(id) ?: error("مشتری پیدا نشد")
                c.customers.update(
                    existing.copy(
                        name = name.trim(),
                        phone = cleanPhone,
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
        autoRenewEnabled: Boolean = false,
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
            if (createPanelAccount && autoRenewEnabled && c.billing.priceFor(gb, days) == null) {
                error("برای تمدید خودکار، بستهٔ این حجم/روز یا قیمت هر گیگ را در تنظیمات تعریف کنید")
            }

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
                        val provision = c.panelService.createAccount(
                            customerId = customerId,
                            gb = gb,
                            days = days,
                            autoRenewEnabled = autoRenewEnabled
                        )
                        c.invoices.setPanelInfo(invoiceId, provision.uuid, 1, provision.subscriptionUrl)
                        result = result.copy(
                            message = "اکانت ساخته شد (${provision.expireInDays} روز اعتبار)",
                            subscriptionUrl = provision.subscriptionUrl
                        )
                    } catch (e: PanelException) {
                        result = result.copy(
                            message = "فاکتور صادر شد، اما ساخت اکانت ناموفق بود: ${e.message}. " +
                                "از تنظیمات، «تست ساخت کاربر» را بزنید تا علت دقیق معلوم شود."
                        )
                    } catch (e: Exception) {
                        result = result.copy(
                            message = "فاکتور صادر شد، اما ساخت اکانت ناموفق بود: ${e.message ?: "خطای نامشخص"}"
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
                gb = gb,
                days = days
            )
            c.invoices.setPanelInfo(invoiceId, provision.uuid, 1, provision.subscriptionUrl)
            success("اکانت ساخته شد")
        } catch (e: Exception) {
            failure(e.message ?: "خطا در ساخت اکانت")
        } finally {
            _busy.value = false
        }
    }

    /**
     * تمدید اکانت موجود + صدور فاکتور تمدید.
     *
     * تمدید = ریست کردن حجم و روز (مصرف صفر، start_date پاک، سقف حجم/روز جدید)،
     * یعنی همان کاری که پنل با دکمهٔ تمدید انجام می‌دهد.
     */
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
                    message = "تمدید انجام شد — حجم و روز ریست شد (${provision.expireInDays} روز باقی‌مانده)",
                    subscriptionUrl = provision.subscriptionUrl
                )
            } catch (e: Exception) {
                result = result.copy(message = "فاکتور صادر شد، اما تمدید پنل ناموفق بود: ${e.message ?: "خطای نامشخص"}")
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

    /** حذف فاکتور؛ اکانت متصل در پنل اول حذف می‌شود، سپس داده‌های محلی اتمیک پاک می‌شوند. */
    fun deleteInvoice(id: Long, onDeleted: () -> Unit = {}) = viewModelScope.launch {
        _busy.value = true
        try {
            val invoice = c.invoices.get(id) ?: error("فاکتور پیدا نشد")
            if (invoice.panelUserUuid.isNotBlank()) {
                c.panelService.deletePanelAccount(invoice.panelUserUuid)
            }
            c.db.withTransaction {
                c.invoices.delete(id)
                if (invoice.panelUserUuid.isNotBlank()) c.panelDao2().deleteAccount(invoice.panelUserUuid)
            }
            success("فاکتور حذف شد" + if (invoice.panelUserUuid.isNotBlank()) " و اکانت پنل هم همان لحظه حذف شد" else "")
            onDeleted()
        } catch (e: Exception) {
            failure(e.message ?: "حذف فاکتور ناموفق بود؛ چیزی از سوابق محلی پاک نشد")
        } finally {
            _busy.value = false
        }
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

    /**
     * ذخیرهٔ اتصال پنل از سه فیلد مستقل:
     *  آدرس کامل پنل (همان آدرس مرورگر)، کلید API، و پایهٔ لینک اشتراک.
     */
    fun savePanelConfig(panelUrl: String, apiKey: String, subscriptionBase: String, name: String, onDone: () -> Unit = {}) =
        viewModelScope.launch {
            runCatching {
                if (panelUrl.isBlank()) error("آدرس پنل را وارد کنید (همان آدرسی که در مرورگر باز می‌شود)")
                if (apiKey.isBlank()) error("کلید API (UUID ادمین) را وارد کنید")
                // اول اعتبارسنجی، بعد ذخیره — تا تنظیم ناقص در پایگاه‌داده نماند
                val parts = ir.cmbtools.ledger.panel.PanelUrl.normalize(panelUrl, "")
                if (parts.host.isBlank()) error("آدرس پنل معتبر نیست؛ مثل https://panel.example.com/AbCdEf12")
                if (parts.proxyPath.isBlank()) {
                    error("در آدرس پنل، مسیر پروکسی ادمین پیدا نشد. آدرس را کامل بچسبانید؛ مثل https://panel.example.com/AbCdEf12")
                }
                if (apiKey.trim().length < 8) error("کلید API کوتاه است؛ UUID کامل ادمین را کپی کنید")
                c.panelService.saveConfig(panelUrl, apiKey, subscriptionBase, name.ifBlank { "پنل اصلی" })
            }.onSuccess {
                refreshPanelEndpoints()
                success("تنظیمات پنل ذخیره شد")
                // پنل تازه تنظیم شد → همگام‌سازی خودکار را راه بینداز
                runCatching { AutoSyncController.start(getApplication<Application>()) }
                onDone()
            }.onFailure { failure(it.message ?: "خطا") }
        }

    /** آدرس‌های دقیق (API، ساخت کاربر، لینک اشتراک) را برای نمایش تازه می‌کند */
    fun refreshPanelEndpoints() = viewModelScope.launch {
        _panelEndpoints.value = runCatching { c.panelService.endpoints() }
            .getOrDefault(ir.cmbtools.ledger.panel.PanelEndpoints())
    }

    /**
     * آزمون واقعی «ساخت کاربر»: یک کاربر موقت در پنل ساخته و پاک می‌شود.
     * اگر این تست سبز باشد، ساختِ واقعی هم قطعاً کار می‌کند.
     */
    fun probePanelCreate() = viewModelScope.launch {
        _busy.value = true
        _busyText.value = "در حال ساخت کاربر آزمایشی در پنل..."
        try {
            val check = c.panelService.probeCreate()
            _panelChecks.value = _panelChecks.value.filterNot { it.title == check.title } + check
            _panelReport.value = runCatching { c.panelService.report(_panelChecks.value) }.getOrDefault("")
            if (check.ok) success("ساخت کاربر در پنل کار می‌کند ✅") else failure("ساخت کاربر ناموفق: " + check.detail)
        } catch (e: Exception) {
            failure(e.message ?: "آزمون ساخت کاربر ناموفق بود")
        } finally {
            _busy.value = false
            _busyText.value = ""
        }
    }

    /** کپی گزارش عیب‌یابی برای ارسال */
    fun copyPanelReport() = viewModelScope.launch {
        val report = runCatching { c.panelService.report(_panelChecks.value) }.getOrDefault("")
        _panelReport.value = report
        if (report.isBlank()) failure("اول «عیب‌یابی» را اجرا کنید") else copyToClipboard(report)
    }

    fun testPanel() = viewModelScope.launch {
        _busy.value = true
        _panelStatus.value = "در حال بررسی..."
        try {
            val r: PanelConnection = c.panelService.test()
            refreshPanelEndpoints()
            _panelStatus.value = buildString {
                append("متصل • نسخهٔ پنل ").append(Money.toPersian(r.version.ifBlank { "?" }))
                append(" • ").append(Money.toPersian(r.usersCount.toString())).append(" کاربر")
                if (r.adminName.isNotBlank()) append(" • ادمین: ").append(r.adminName)
                append(" • ").append(if (r.readOnly) "پنل read-only" else "قابل نوشتن")
                if (!r.canAddUsers) append(" • سقف کاربران/ترافیک پر است")
                if (r.flavor.isNotBlank()) append("\nسبک API: ").append(r.flavor)
                if (r.endpoint.isNotBlank()) append("\n").append(r.endpoint)
                if (r.linkBase.isNotBlank()) append("\nلینک اشتراک: ").append(r.linkBase)
            }
            success("اتصال پنل برقرار است (نسخه ${r.version})")
        } catch (e: Exception) {
            val kind = (e as? PanelException)?.kind
            _panelStatus.value = when (kind) {
                PanelException.Kind.PATH -> "مسیر ادمین اشتباه است"
                PanelException.Kind.AUTH -> "کلید API یا مسیر اشتباه است"
                PanelException.Kind.NETWORK -> "شبکه/دامنه در دسترس نیست"
                PanelException.Kind.NOT_FOUND -> "سرویس موردنیاز در این نسخهٔ پنل پیدا نشد"
                else -> "اتصال ناموفق"
            }
            failure(e.message ?: "اتصال برقرار نشد")
        } finally {
            _busy.value = false
        }
    }

    /**
     * «جستجوی خودکار»: همهٔ مسیرها و سبک‌های API نسخه‌های ۱۱ تا ۱۴ را امتحان می‌کند
     * و جدول نتیجه (آدرس + کد وضعیت) را نشان می‌دهد.
     */
    fun scanPanel() = viewModelScope.launch {
        _busy.value = true
        _busyText.value = "در حال جستجوی مسیر و نسخهٔ پنل..."
        try {
            val checks = c.panelService.scan()
            _panelChecks.value = checks
            refreshPanelEndpoints()
            val ok = checks.count { it.ok }
            success(
                if (ok > 0) "${Money.toPersian(ok.toString())} مرحله موفق بود — جزئیات را ببینید"
                else "هیچ مسیری جواب نداد — جزئیات را ببینید"
            )
        } catch (e: Exception) {
            failure(e.message ?: "جستجو ناموفق بود")
        } finally {
            _busy.value = false
            _busyText.value = ""
        }
    }

    /** عیب‌یابی کامل اتصال پنل: هر مرحله را جدا گزارش می‌دهد */
    fun diagnosePanel() = viewModelScope.launch {
        _busy.value = true
        _busyText.value = "در حال بررسی اتصال پنل..."
        try {
            val checks = c.panelService.diagnose()
            _panelChecks.value = checks
            _panelReport.value = runCatching { c.panelService.report(checks) }.getOrDefault("")
            val failed = checks.count { !it.ok }
            success(
                if (failed == 0) "همهٔ مراحل اتصال درست است ✅"
                else Money.toPersian(failed.toString()) + " مرحله مشکل دارد — جزئیات را ببینید"
            )
        } catch (e: Exception) {
            failure(e.message ?: "عیب‌یابی ناموفق")
        } finally {
            _busy.value = false
            _busyText.value = ""
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
                    report.problems.isNotEmpty()
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

    fun setAccountAutoRenew(account: PanelAccount, enabled: Boolean) = viewModelScope.launch {
        runCatching {
            if (enabled && c.billing.priceFor(account.gb, account.days) == null) {
                error("برای تمدید خودکار، بستهٔ فعلی یا قیمت هر گیگ را در تنظیمات تعریف کنید")
            }
            c.panelService.setAutoRenew(account.panelUserUuid, enabled)
        }
            .onSuccess {
                success(if (enabled) "تمدید خودکار برای این اکانت فعال شد" else "تمدید خودکار خاموش شد")
            }
            .onFailure { failure(it.message ?: "ذخیرهٔ تنظیم تمدید خودکار ناموفق بود") }
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
