package ir.cmbtools.ledger.panel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DTOها دقیقاً مطابق اسکیمای API v2 نسخه ۱۴ هیدی‌فای‌پنل.
 * منبع: hiddifypanel/panel/commercial/restapi/v2/admin/schema.py و users_page_api.py
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

@Serializable
data class UsersMetaDto(
    @SerialName("me_uuid") val meUuid: String = "",
    @SerialName("can_add") val canAdd: Boolean = true,
    @SerialName("read_only") val readOnly: Boolean = false,
    @SerialName("link_domains") val linkDomains: List<LinkDomainDto> = emptyList(),
    @SerialName("admins") val admins: List<AdminRefDto> = emptyList()
)

/**
 * ردیف صفحهٔ کاربران پنل (`GET /api/v2/admin/users/`).
 * این اندپوینت برخلاف `/admin/user/`، expire_date و remaining_days و مالک کاربر را می‌دهد.
 */
@Serializable
data class PanelUserDto(
    @SerialName("id") val id: Long = 0,
    @SerialName("uuid") val uuid: String,
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
)

@Serializable
data class UsersPageDto(
    @SerialName("users") val users: List<PanelUserDto> = emptyList(),
    @SerialName("meta") val meta: UsersMetaDto = UsersMetaDto()
)

/** بدنهٔ ساخت کاربر (`POST /api/v2/admin/users/`) */
@Serializable
data class CreateUserRequest(
    @SerialName("name") val name: String,
    @SerialName("usage_limit_GB") val usageLimitGb: Double,
    @SerialName("package_days") val packageDays: Int,
    @SerialName("comment") val comment: String? = null,
    @SerialName("enable") val enable: Boolean = true,
    @SerialName("mode") val mode: String = "no_reset",
    @SerialName("uuid") val uuid: String? = null
)

/**
 * بدنهٔ ویرایش (`PATCH /api/v2/admin/users/{uuid}/`).
 * فیلدهای null به‌صورت صریح فرستاده می‌شوند (exclude_unset در پنل)، پس `start_date = null`
 * یعنی «شروع دوره از اتصال بعدی» که همان معنای تمدید در پنل است.
 */
@Serializable
data class PatchUserRequest(
    @SerialName("name") val name: String? = null,
    @SerialName("usage_limit_GB") val usageLimitGb: Double? = null,
    @SerialName("package_days") val packageDays: Int? = null,
    @SerialName("current_usage_GB") val currentUsageGb: Double? = null,
    @SerialName("enable") val enable: Boolean? = null,
    @SerialName("comment") val comment: String? = null,
    @SerialName("start_date") val startDate: JsonElement? = null
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
