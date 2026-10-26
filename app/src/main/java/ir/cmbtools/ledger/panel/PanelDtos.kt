package ir.cmbtools.ledger.panel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOهای API v2 هیدی‌فای — مطابق نسخهٔ ۱۴ (tag v14.0.x) و سازگار با نسخه‌های اطراف.
 *
 * دو نکتهٔ مهم که باعث اشکال در نسخه‌های قبل شده بود:
 *  ۱) `POST /admin/users/` و `PATCH /admin/users/<uuid>/` در نسخهٔ ۱۴ پاسخ را داخل
 *     یک پوشش `{"user": {...}}` برمی‌گردانند، نه به‌صورت مسطح. پس پاسخ‌ها به‌صورت
 *     JsonElement خوانده و سپس با [PanelJson.parseUser] باز می‌شوند (هر دو شکل پذیرفته می‌شود).
 *  ۲) همهٔ فیلدها مقدار پیش‌فرض دارند تا یک فیلد ناشناخته/خالی کل فهرست کاربران را
 *     از کار نیندازد (خرابی کامل JSON = «همگام‌سازی انجام نشد»).
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

/** متادیتای فهرست کاربران. در نسخهٔ ۱۴ این فیلدها کنار `users` و در سطح بالا می‌آیند. */
@Serializable
data class UsersMetaDto(
    @SerialName("me_uuid") val meUuid: String = "",
    @SerialName("can_add") val canAdd: Boolean = true,
    @SerialName("read_only") val readOnly: Boolean = false,
    @SerialName("link_domains") val linkDomains: List<LinkDomainDto> = emptyList(),
    @SerialName("admins") val admins: List<AdminRefDto> = emptyList()
)

/**
 * ردیف کاربر پنل (`GET /admin/users/` در نسخهٔ ۱۴ یا `GET /admin/user/` در نسخه‌های قدیمی).
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
    @SerialName("telegram_id") val telegramId: Long? = null,
    @SerialName("tags") val tags: List<Int> = emptyList()
) {
    val usable: Boolean get() = uuid.isNotBlank()
}

@Serializable
data class UsersPageDto(
    @SerialName("users") val users: List<PanelUserDto> = emptyList(),
    @SerialName("meta") val meta: UsersMetaDto = UsersMetaDto()
)

/**
 * بدنهٔ ساخت کاربر. با `explicitNulls = false` در Json کلاینت سریال می‌شود،
 * پس فیلدهای null اصلاً فرستاده نمی‌شوند (پنل آن‌ها را به‌عنوان «مقدار خالی» تفسیر نکند).
 */
@Serializable
data class CreateUserRequest(
    @SerialName("name") val name: String,
    @SerialName("usage_limit_GB") val usageLimitGb: Double,
    @SerialName("package_days") val packageDays: Int,
    @SerialName("comment") val comment: String? = null,
    @SerialName("enable") val enable: Boolean? = null,
    @SerialName("mode") val mode: String? = null,
    @SerialName("uuid") val uuid: String? = null
)

/**
 * بدنهٔ ویرایش (`PATCH`).
 *
 * ⚠ نکتهٔ نسخهٔ ۱۴: پنل هیچ‌کدام از `current_usage_GB` و `start_date` را در PATCH قبول
 * نمی‌کند؛ برای تمدید باید پرچم‌های `reset_usage` و `reset_days` فرستاده شوند.
 * برای سازگاری با نسخه‌های قدیمی هر چهار مورد فرستاده می‌شوند (پنل موارد ناشناخته را
 * نادیده می‌گیرد) و فیلدهای null به‌طور کامل حذف می‌شوند تا پنل نام کاربر را خالی نکند.
 */
@Serializable
data class PatchUserRequest(
    @SerialName("name") val name: String? = null,
    @SerialName("usage_limit_GB") val usageLimitGb: Double? = null,
    @SerialName("package_days") val packageDays: Int? = null,
    @SerialName("current_usage_GB") val currentUsageGb: Double? = null,
    @SerialName("enable") val enable: Boolean? = null,
    @SerialName("comment") val comment: String? = null,
    @SerialName("reset_usage") val resetUsage: Boolean? = null,
    @SerialName("reset_days") val resetDays: Boolean? = null
)

@Serializable
data class BulkRequest(
    @SerialName("action") val action: String,
    @SerialName("uuids") val uuids: List<String>,
    @SerialName("gb") val gb: Double? = null,
    @SerialName("days") val days: Int? = null
)

@Serializable
data class BulkResponse(
    @SerialName("count") val count: Int = 0
)
