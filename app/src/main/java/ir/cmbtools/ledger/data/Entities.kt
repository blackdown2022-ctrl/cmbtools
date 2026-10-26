package ir.cmbtools.ledger.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ir.cmbtools.ledger.core.InvoiceKind
import kotlinx.serialization.Serializable

@Entity(tableName = "customers", indices = [Index(value = ["name"])])
@Serializable
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    /** ۴ رقم آخر کارت، برای تطبیق پیامک بانکی */
    val cardLast4: String = "",
    val note: String = "",
    /** سقف اعتبار (ریال). صفر = بدون سقف */
    val creditLimitRial: Long = 0,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "plans")
@Serializable
data class Plan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val gb: Int,
    val days: Int,
    val priceRial: Long,
    val active: Boolean = true,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "invoices",
    indices = [Index(value = ["customerId"]), Index(value = ["number"], unique = true)]
)
@Serializable
data class Invoice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** شمارهٔ یکتا به صورت عدد صحیح مثبت؛ از ۱ شروع می‌شود */
    val number: String,
    val customerId: Long,
    val issuedAtEpochDay: Long,
    /** سررسید (۰ = بدون سررسید) */
    val dueAtEpochDay: Long = 0,
    val status: String = "ISSUED",
    val subtotalRial: Long = 0,
    val discountRial: Long = 0,
    val totalRial: Long = 0,
    val paidRial: Long = 0,
    val note: String = "",
    /** UUID اکانت ساخته‌شده در پنل هیدی‌فای (اگر ساخته شده باشد) */
    val panelUserUuid: String = "",
    val panelId: Long = 0,
    /** لینک اشتراک اکانت (برای کپی/ارسال دستی؛ دیگر داخل پیامک فاکتور نمی‌آید) */
    val subscriptionUrl: String = "",
    val planId: Long = 0,
    // ── عکس فوری سند فاکتور (تا بعداً با تغییر تنظیمات/قیمت‌ها عوض نشود) ──
    /** زمان دقیق صدور برای نمایش «تاریخ و ساعت» در فاکتور */
    val issuedAtMillis: Long = System.currentTimeMillis(),
    /** نوع بستهٔ ساخته یا تمدید شده در لحظهٔ صدور (مثلاً «یک‌ماهه ۳۰ گیگ») */
    val packageName: String = "",
    /** شمارهٔ کارتی که هنگام صدور در فاکتور درج شد (کارت فروشگاه) */
    val cardNumber: String = "",
    /** ماندهٔ مشتری «قبل از» این فاکتور (مثبت = بدهکار، منفی = بستانکار) */
    val prevBalanceRial: Long = 0,
    /** جمع کل بدهی مشتری «بعد از» این فاکتور */
    val balanceAfterRial: Long = 0,
    /** NEW = اکانت جدید، RENEW = تمدید، MANUAL = فاکتور دستی */
    val kind: String = InvoiceKind.MANUAL,
    /** آیا اپ خودکار صادر کرده است؟ */
    val autoIssued: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val remainingRial: Long get() = (totalRial - paidRial).coerceAtLeast(0)

    /** مبلغی که از قبل بدهکار بوده و در این فاکتور هم آمده است */
    val carriedDebtRial: Long get() = prevBalanceRial.coerceAtLeast(0)
}

@Entity(
    tableName = "invoice_lines",
    foreignKeys = [
        ForeignKey(
            entity = Invoice::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["invoiceId"])]
)
@Serializable
data class InvoiceLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val planId: Long = 0,
    val description: String,
    val qty: Int = 1,
    val unitPriceRial: Long,
    val amountRial: Long
)

@Entity(tableName = "payments", indices = [Index(value = ["customerId"])])
@Serializable
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val amountRial: Long,
    val method: String,
    val receivedAtEpochDay: Long,
    val refNo: String = "",
    val note: String = "",
    /** اگر از پیامک بانکی آمده باشد */
    val bankSmsId: Long = 0,
    /** مبلغی که به فاکتورها تخصیص نیافت و به‌عنوان اعتبار مشتری ماند */
    val unallocatedRial: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "payment_allocations",
    foreignKeys = [
        ForeignKey(entity = Payment::class, parentColumns = ["id"], childColumns = ["paymentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Invoice::class, parentColumns = ["id"], childColumns = ["invoiceId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["paymentId"]), Index(value = ["invoiceId"])]
)
@Serializable
data class PaymentAllocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paymentId: Long,
    val invoiceId: Long,
    val amountRial: Long
)

/**
 * دفتر ثبت‌محور: هر رویداد مالی یک سطر تغییرناپذیر است و مانده از جمع سطرها می‌آید.
 * DEBIT = بدهکار شدن مشتری، CREDIT = بستانکار شدن (پرداخت/تخفیف/تعدیل).
 */
@Entity(tableName = "ledger_entries", indices = [Index(value = ["customerId"]), Index(value = ["atEpochDay"])])
@Serializable
data class LedgerEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val atEpochDay: Long,
    val atMillis: Long = System.currentTimeMillis(),
    val direction: String,
    val amountRial: Long,
    val account: String,
    val refType: String,
    val refId: Long = 0,
    val memo: String = ""
)

@Entity(tableName = "expenses", indices = [Index(value = ["atEpochDay"])])
@Serializable
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String = "عمومی",
    val amountRial: Long,
    val atEpochDay: Long,
    val note: String = ""
)

/**
 * اتصال پنل هیدی‌فای.
 *
 * مدل بازطراحی‌شده (نسخهٔ ۰.۳.۱) — سه چیز از سه جای متفاوت می‌آید و
 * دیگر با هم اشتباه نمی‌شوند:
 *
 *  ۱) **آدرس پنل** (`host` + `adminProxyPath` + `useHttps`): همان آدرسی که ادمین در
 *     مرورگر باز می‌کند، مثل `https://panel.example.com/AbCdEf12/`.
 *     از همین آدرس، مسیر پروکسی ادمین استخراج می‌شود و **همهٔ** درخواست‌های API روی
 *     `…/api/v2/admin/…` ساخته می‌شوند (از جمله «ساخت کاربر» = `…/api/v2/admin/users/`).
 *
 *  ۲) **کلید API**: UUID ادمین، در هدر `Hiddify-API-Key`.
 *
 *  ۳) **پایهٔ لینک اشتراک** (`subscriptionBase`): آدرس مستقلی است که مشتری با آن
 *     کانفیگ می‌گیرد، مثل `https://sub.example.com/xy12/`. در هیدی‌فای ۱۴ این آدرس از
 *     `proxy_path_client` ساخته می‌شود و **با مسیر ادمین یکی نیست**. اگر خالی باشد،
 *     اپ آن را خودکار از `link_domains` پنل پر می‌کند.
 */
@Entity(tableName = "panel_configs")
@Serializable
data class PanelConfig(
    @PrimaryKey val id: Long = 1,
    val name: String = "پنل اصلی",
    /** دامنه (و پورت) بدون https — مثل panel.example.com */
    val host: String = "",
    /** مسیر پروکسی ادمین (همان بخشی که در آدرس مرورگر پنل دیده می‌شود) */
    val adminProxyPath: String = "",
    /** UUID ادمین برای هدر Hiddify-API-Key */
    val apiKey: String = "",
    val useHttps: Boolean = true,
    /**
     * پایهٔ لینک اشتراک مشتری — مثلاً `https://panel.example.com/x7Yz12/`.
     * جدا از مسیر ادمین؛ خالی = خودکار از پنل خوانده شود.
     */
    val subscriptionBase: String = "",
    /**
     * سبک API که در این پنل جواب داد (`PAGE` / `LEGACY` / `V1`).
     * خالی = هنوز تشخیص داده نشده؛ اپ نسخه‌های ۱۱ تا ۱۴ را خودکار امتحان می‌کند.
     */
    val apiFlavor: String = "",
    /** آخرین نسخهٔ خوانده‌شده از پنل */
    val panelVersion: String = "",
    val lastSyncAt: Long = 0,
    /**
     * زمان نخستین همگام‌سازی موفق. تا وقتی صفر است، اپ فقط «وضعیت موجود» را ثبت می‌کند
     * و برای کاربران قدیمی پنل فاکتور نمی‌سازد (جلوگیری از سیل فاکتور در اولین اجرا).
     */
    val baselineAtMillis: Long = 0
) {
    /** آدرس پنل به‌شکل کامل؛ همان چیزی که در فیلد «آدرس پنل» نشان داده می‌شود */
    val panelUrl: String
        get() {
            val scheme = if (useHttps) "https" else "http"
            val path = adminProxyPath.trim().trim('/')
            return "$scheme://${host.trim()}" + if (path.isBlank()) "" else "/$path"
        }

    val isConfigured: Boolean get() = host.isNotBlank() && adminProxyPath.isNotBlank() && apiKey.isNotBlank()
}

@Entity(tableName = "panel_accounts", indices = [Index(value = ["customerId"]), Index(value = ["panelUserUuid"])])
@Serializable
data class PanelAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val panelId: Long = 1,
    val customerId: Long,
    val panelUserUuid: String,
    val name: String,
    val gb: Double = 0.0,
    val days: Int = 0,
    val startEpochDay: Long = 0,
    val expireEpochDay: Long = 0,
    val usageGb: Double = 0.0,
    val enabled: Boolean = true,
    val status: String = "",
    val subscriptionUrl: String = "",
    /** مقدار تاریخ انقضا در همگام‌سازی قبلی — پایهٔ تشخیص «تمدید شده» */
    val prevExpireEpochDay: Long = 0,
    /** آخرین انقضایی که برایش فاکتور صادر شد (ضد فاکتور تکراری) */
    val lastInvoicedExpireEpochDay: Long = 0,
    /**
     * زمان آخرین «تمدید» (ریست روز و حجم).
     * هم وقتی اپ تمدید می‌کند و هم وقتی همگام‌سازی از پنل تشخیص می‌دهد پر می‌شود.
     */
    val lastRenewedAtMillis: Long = 0,
    /** Auto-renew on depleted quota or expired days; intentionally off for all existing accounts. */
    val autoRenewEnabled: Boolean = false,
    val syncedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "bank_sms",
    indices = [Index(value = ["rawHash"], unique = true), Index(value = ["matchStatus"])]
)
@Serializable
data class BankSms(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val receivedAtMillis: Long,
    val amountRial: Long = 0,
    val last4: String = "",
    val balanceRial: Long = -1,
    val bank: String = "",
    val isCredit: Boolean = false,
    val rawHash: String,
    val matchStatus: String = "UNMATCHED",
    val paymentId: Long = 0,
    val note: String = ""
)

@Entity(tableName = "sms_outbox", indices = [Index(value = ["createdAt"])])
@Serializable
data class SmsOutbox(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val to: String,
    val body: String,
    val provider: String,
    val status: String = "PENDING",
    val error: String = "",
    val parts: Int = 1,
    val invoiceId: Long = 0,
    val customerId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val sentAt: Long = 0
) {
    companion object {
        const val PENDING = "PENDING"
        const val SENT = "SENT"
        const val FAILED = "FAILED"
    }
}
