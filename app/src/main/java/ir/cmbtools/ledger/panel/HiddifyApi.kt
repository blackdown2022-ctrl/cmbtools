package ir.cmbtools.ledger.panel

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * مسیرهای API v2 هیدی‌فای. `<proxyPath>` همان «مسیر پروکسی ادمین» است.
 *
 * همهٔ پاسخ‌ها `ResponseBody` برمی‌گردانند تا:
 *  • شکل پاسخ (آرایه/صفحه‌بندی/پوشش `user`) در کد خودمان بخشنده خوانده شود؛
 *  • متن خطای پنل برای نمایش به کاربر در دسترس باشد.
 *
 * دو خانوادهٔ مسیر برای کاربران نگه داشته شده‌اند:
 *  • `users/`  → صفحهٔ کاربران نسخهٔ ۱۴ (با متادیتا: لینک‌دامنه‌ها، can_add، read_only)
 *  • `user/`   → مسیر کلاسیک (در نسخه‌های قدیمی‌تر همان چیزی است که وجود دارد)
 * اگر یکی ۴۰۴ بدهد، دیگری امتحان می‌شود.
 */
interface HiddifyApi {

    @GET("{proxyPath}/api/v2/panel/info/")
    suspend fun panelInfo(
        @Path("proxyPath") proxyPath: String,
        @Header("Hiddify-API-Key") apiKey: String
    ): ResponseBody

    // ── خواندن کاربران ────────────────────────────────────────────────────────

    @GET("{proxyPath}/api/v2/admin/users/")
    suspend fun usersPage(
        @Path("proxyPath") proxyPath: String,
        @Header("Hiddify-API-Key") apiKey: String
    ): ResponseBody

    @GET("{proxyPath}/api/v2/admin/user/")
    suspend fun usersClassic(
        @Path("proxyPath") proxyPath: String,
        @Header("Hiddify-API-Key") apiKey: String
    ): ResponseBody

    @GET("{proxyPath}/api/v2/admin/users/{uuid}/")
    suspend fun userPage(
        @Path("proxyPath") proxyPath: String,
        @Path("uuid") uuid: String,
        @Header("Hiddify-API-Key") apiKey: String
    ): ResponseBody

    @GET("{proxyPath}/api/v2/admin/user/{uuid}/")
    suspend fun userClassic(
        @Path("proxyPath") proxyPath: String,
        @Path("uuid") uuid: String,
        @Header("Hiddify-API-Key") apiKey: String
    ): ResponseBody

    // ── نوشتن ─────────────────────────────────────────────────────────────────

    @POST("{proxyPath}/api/v2/admin/users/")
    suspend fun createUserPage(
        @Path("proxyPath") proxyPath: String,
        @Header("Hiddify-API-Key") apiKey: String,
        @Body body: CreateUserRequest
    ): ResponseBody

    @POST("{proxyPath}/api/v2/admin/user/")
    suspend fun createUserClassic(
        @Path("proxyPath") proxyPath: String,
        @Header("Hiddify-API-Key") apiKey: String,
        @Body body: CreateUserRequest
    ): ResponseBody

    @PATCH("{proxyPath}/api/v2/admin/users/{uuid}/")
    suspend fun patchUserPage(
        @Path("proxyPath") proxyPath: String,
        @Path("uuid") uuid: String,
        @Header("Hiddify-API-Key") apiKey: String,
        @Body body: PatchUserRequest
    ): ResponseBody

    @PATCH("{proxyPath}/api/v2/admin/user/{uuid}/")
    suspend fun patchUserClassic(
        @Path("proxyPath") proxyPath: String,
        @Path("uuid") uuid: String,
        @Header("Hiddify-API-Key") apiKey: String,
        @Body body: PatchUserRequest
    ): ResponseBody

    @DELETE("{proxyPath}/api/v2/admin/users/{uuid}/")
    suspend fun deleteUserPage(
        @Path("proxyPath") proxyPath: String,
        @Path("uuid") uuid: String,
        @Header("Hiddify-API-Key") apiKey: String
    ): ResponseBody

    @DELETE("{proxyPath}/api/v2/admin/user/{uuid}/")
    suspend fun deleteUserClassic(
        @Path("proxyPath") proxyPath: String,
        @Path("uuid") uuid: String,
        @Header("Hiddify-API-Key") apiKey: String
    ): ResponseBody

    /**
     * عملیات گروهی: action ∈ enable | disable | delete | reset_usage | reset_days | add_days | add_limits
     * برای «افزودن حجم/روز» از add_limits استفاده می‌کنیم تا رفتار با خود پنل یکی باشد.
     */
    @POST("{proxyPath}/api/v2/admin/users/bulk/")
    suspend fun bulk(
        @Path("proxyPath") proxyPath: String,
        @Header("Hiddify-API-Key") apiKey: String,
        @Body body: BulkRequest
    ): ResponseBody
}
