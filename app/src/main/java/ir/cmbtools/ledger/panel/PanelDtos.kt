package ir.cmbtools.ledger.panel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * DTOهای پاسخ پنل هیدی‌فای — سازگار با نسخه‌های ۱۱ تا ۱۴.
 *
 * تفاوت نسخه‌ها که اینجا پوشش داده شده:
 *  • ۱۴ (صفحهٔ کاربران): فهرست `{"users": [...], ...meta}` با `link_domains`، `can_add`، `read_only`
 *    و ردیف‌ها دارای `expire_date` و `remaining_days`.
 *  • ۱۱ تا ۱۴ (مسیر کلاسیک `/admin/user/`): آرایهٔ خالص ردیف‌ها؛ **بدون** `expire_date`،
 *    `remaining_days`، `status`، `link_domains` — این‌ها در اپ محاسبه می‌شوند.
 *  • همهٔ فیلدها مقدار پیش‌فرض دارند تا یک فیلد ناشناخته کل پاسخ را خراب نکند.
 */

@Serializable
data class PanelInfoDto(
    @SerialName("version") val version: String = "",
    @SerialName("last_version") val lastVersion: String? = null,
    @SerialName("panel_version") val panelVersion: String? = null,
    @SerialName("host") val host: String? = null
)

@Serializable
data class LinkDomainDto(
    @SerialName("domain") val domain: String = "",
    @SerialName("label") val label: String = "",
    @SerialName("kind") val kind: String = "",
    /** مثل https://panel.example.com/AbCdEf/  (مسیر پروکسی کلاینت) */
    @SerialName("base") val base: String = ""
)

@Serializable
data class AdminRefDto(
    @SerialName("uuid") val uuid: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("parent_uuid") val parentUuid: String? = null
)

/** اطلاعات ادمینِ متصل (`GET /api/v2/admin/me/`) — در همهٔ نسخه‌های ۱۱ تا ۱۴ وجود دارد. */
@Serializable
data class AdminMeDto(
    @SerialName("uuid") val uuid: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("mode") val mode: String = "",
    @SerialName("can_add_admin") val canAddAdmin: Boolean = false,
    @SerialName("parent_admin_uuid") val parentAdminUuid: String? = null
)

/** متادیتای فهرست کاربران؛ فقط نسخهٔ ۱۴ (مسیر `/users/`) آن را می‌دهد. */
@Serializable
data class UsersMetaDto(
    @SerialName("me_uuid") val meUuid: String = "",
    @SerialName("can_add") val canAdd: Boolean = true,
    @SerialName("read_only") val readOnly: Boolean = false,
    @SerialName("link_domains") val linkDomains: List<LinkDomainDto> = emptyList(),
    @SerialName("admins") val admins: List<AdminRefDto> = emptyList()
)

/**
 * ردیف کاربر؛ هم شکل نسخهٔ ۱۴ و هم شکل کلاسیک ۱۱–۱۴ را می‌خواند.
 */
@Serializable
data class PanelUserDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("uuid") val uuid: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("comment") val comment: String = "",
    @SerialName("enable") val enable: Boolean = true,
    @SerialName("status") val status: String = "",
    @SerialName("current_usage_GB") val currentUsageGb: Double = 0.0,
    @SerialName("usage_limit_GB") val usageLimitGb: Double = 0.0,
    @SerialName("package_days") val packageDays: Int = 0,
    @SerialName("remaining_days") val remainingDays: Int = 0,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("expire_date") val expireDate: String? = null,
    @SerialName("mode") val mode: String = "no_reset",
    @SerialName("days_to_reset") val daysToReset: Int? = null,
    @SerialName("last_online") val lastOnline: String? = null,
    @SerialName("owner_uuid") val ownerUuid: String? = null,
    @SerialName("owner_name") val ownerName: String = "",
    @SerialName("added_by_uuid") val addedByUuid: String? = null,
    @SerialName("telegram_id") val telegramId: Long? = null,
    @SerialName("tags") val tags: List<Int> = emptyList(),
    @SerialName("is_active") val isActive: Boolean? = null
) {
    val usable: Boolean get() = uuid.isNotBlank()

    /** تبدیل تاریخ ISO پنل به شمارهٔ روز (بدون وابستگی به کلاس‌های اندرویدی) */
    internal fun isoToEpochDay(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0
        return runCatching { LocalDate.parse(iso.take(10)).toEpochDay() }.getOrDefault(0)
    }

    /** نسخه‌های ۱۱–۱۳ `expire_date` ندارند؛ از تاریخ شروع + روزهای بسته محاسبه می‌شود. */
    val expireEpochDay: Long
        get() {
            val direct = isoToEpochDay(expireDate)
            if (direct > 0) return direct
            val start = isoToEpochDay(startDate)
            if (start > 0 && packageDays > 0) return start + packageDays
            return 0
        }

    /** روزهای باقی‌مانده؛ اگر پنل نداده باشد از تاریخ انقضا حساب می‌شود. */
    val remainingDaysOrComputed: Int
        get() {
            if (remainingDays != 0) return remainingDays
            val exp = expireEpochDay
            if (exp <= 0) return 0
            return (exp - LocalDate.now().toEpochDay()).toInt()
        }

    /** وضعیت یکدست برای نسخه‌هایی که فیلد `status` ندارند. */
    val statusOrComputed: String
        get() = when {
            status.isNotBlank() -> status
            !enable -> "disabled"
            remainingDaysOrComputed < 0 -> "expired"
            usageLimitGb > 0 && currentUsageGb >= usageLimitGb -> "no_data"
            else -> "active"
        }
}

/** شکل صفحهٔ کاربران نسخهٔ ۱۴ (برای وقتی پاسخ داخل `{"users": ...}` است) */
@Serializable
data class UsersPageDto(
    @SerialName("users") val users: List<PanelUserDto> = emptyList(),
    @SerialName("meta") val meta: UsersMetaDto = UsersMetaDto()
)

/**
 * سبک API پنل — بین نسخه‌های ۱۱ تا ۱۴ خودکار انتخاب می‌شود.
 */
enum class ApiFlavor(val id: String, val label: String) {
    /** نسخهٔ ۱۴: `/api/v2/admin/users/` — کامل‌ترین (لینک‌دامنه‌ها، دسترسی ساخت، بقیه) */
    PAGE("PAGE", "نسخهٔ ۱۴ — صفحهٔ کاربران (/users/)"),

    /** نسخه‌های ۱۱ تا ۱۴: `/api/v2/admin/user/` — مسیر کلاسیک */
    LEGACY("LEGACY", "نسخه‌های ۱۱ تا ۱۴ — مسیر کلاسیک (/user/)"),

    /** نسخه‌های ۱۰ تا ۱۴: `/api/v1/user/` — فقط ادمین کل؛ آخرین راه‌حل */
    V1("V1", "نسخهٔ ۱۰ تا ۱۴ — API نسخهٔ ۱ (/api/v1/user/)");

    companion object {
        fun from(id: String?): ApiFlavor? = entries.firstOrNull { it.id == id }
    }
}

/** مسیرهای همهٔ نسخه‌ها در یک جا؛ برای عیب‌یابی و انتخاب خودکار. */
object PanelRoutes {
    const val ME = "/api/v2/admin/me/"
    const val INFO = "/api/v2/panel/info/"
    const val SERVER_STATUS = "/api/v2/admin/server_status/"
    const val ALL_CONFIGS = "/api/v2/admin/all-configs/"

    // نسخهٔ ۱۴ — صفحهٔ کاربران
    const val USERS_PAGE = "/api/v2/admin/users/"
    const val BULK_PAGE = "/api/v2/admin/users/bulk/"
    fun userPage(uuid: String) = "/api/v2/admin/users/$uuid/"

    // نسخه‌های ۱۱ تا ۱۴ — مسیر کلاسیک
    const val USERS_LEGACY = "/api/v2/admin/user/"
    fun userLegacy(uuid: String) = "/api/v2/admin/user/$uuid/"

    // نسخه‌های ۱۰ تا ۱۴ — API نسخهٔ ۱
    const val V1_USERS = "/api/v1/user/"
    fun v1Delete(uuid: String) = "/api/v1/user/?uuid=$uuid"
}
