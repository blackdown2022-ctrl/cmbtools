package ir.cmbtools.ledger.panel

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * مسیرها با `<admin_proxy_path>` شروع می‌شوند؛ همان مقداری که در
 * «تنظیمات ← تنظیمات خیلی پیشرفته ← مسیر پروکسی مدیران» پنل است.
 */
interface HiddifyApi {

    @GET("{proxyPath}/api/v2/panel/info/")
    suspend fun panelInfo(
        @Path("proxyPath") proxyPath: String,
        @Header("Hiddify-API-Key") apiKey: String
    ): PanelInfoDto

    /** لیست کاربران + متادیتا (لینک‌دامنه‌ها، دسترسی) */
    @GET("{proxyPath}/api/v2/admin/users/")
    suspend fun usersPage(
        @Path("proxyPath") proxyPath: String,
        @Header("Hiddify-API-Key") apiKey: String
    ): UsersPageDto

    @GET("{proxyPath}/api/v2/admin/users/{uuid}/")
    suspend fun user(
        @Path("proxyPath") proxyPath: String,
        @Path("uuid") uuid: String,
        @Header("Hiddify-API-Key") apiKey: String
    ): PanelUserDto

    @POST("{proxyPath}/api/v2/admin/users/")
    suspend fun createUser(
        @Path("proxyPath") proxyPath: String,
        @Header("Hiddify-API-Key") apiKey: String,
        @Body body: CreateUserRequest
    ): PanelUserDto

    @PATCH("{proxyPath}/api/v2/admin/users/{uuid}/")
    suspend fun patchUser(
        @Path("proxyPath") proxyPath: String,
        @Path("uuid") uuid: String,
        @Header("Hiddify-API-Key") apiKey: String,
        @Body body: PatchUserRequest
    ): PanelUserDto

    @DELETE("{proxyPath}/api/v2/admin/users/{uuid}/")
    suspend fun deleteUser(
        @Path("proxyPath") proxyPath: String,
        @Path("uuid") uuid: String,
        @Header("Hiddify-API-Key") apiKey: String
    )

    /**
     * عملیات گروهی: action ∈ enable | disable | delete | reset_usage | reset_days | add_days | add_limits
     * برای تمدید روز/حجم از add_limits استفاده می‌کنیم تا رفتار با خود پنل یکی باشد.
     */
    @POST("{proxyPath}/api/v2/admin/users/bulk/")
    suspend fun bulk(
        @Path("proxyPath") proxyPath: String,
        @Header("Hiddify-API-Key") apiKey: String,
        @Body body: BulkRequest
    ): BulkResponse
}
