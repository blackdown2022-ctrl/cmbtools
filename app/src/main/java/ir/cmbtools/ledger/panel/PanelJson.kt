package ir.cmbtools.ledger.panel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * خواندن پاسخ‌های پنل به‌صورت «بخشنده» (tolerant).
 *
 * چرا لازم است؟ شکل پاسخ در نسخه‌های مختلف هیدی‌فای یکی نیست:
 *  • نسخهٔ ۱۴: `GET /users/` → `{ "users": [...], "meta": {...} }` ولی فیلدهای متا
 *    در سطح بالا (flat) هم می‌آیند؛ `POST /users/` و `PATCH /users/<uuid>/` →
 *    `{ "user": {...} }`.
 *  • نسخه‌های قدیمی‌تر/مسیر کلاسیک: `GET /admin/user/` → آرایهٔ خالص کاربران،
 *    `POST /admin/user/` و `PATCH` → کاربر مسطح.
 * اگر فقط یکی از این شکل‌ها پشتیبانی شود، نتیجه یکی از این دو خطاست:
 * «کاربر ساخته نشد» یا «همگام‌سازی نشد». این شیء هر دو را می‌فهمد.
 */
internal object PanelJson {

    val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // فیلدهای null اصلاً در بدنهٔ درخواست نوشته نشوند
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    // نکتهٔ مهم برای بدنهٔ درخواست‌ها:
    // با این تنظیم (`explicitNulls = false`) مقدار `JsonNull` از بدنه حذف می‌شود. برای
    // نمونه تمدید در نسخه‌های ۱۱–۱۴ با `"start_date": null` انجام می‌شود؛ اگر کلید
    // فرستاده نشود، پنل تاریخ قبلی را نگه می‌دارد و اکانت تمدید نمی‌شود. بنابراین
    // بدنه‌ها با `JsonObject.toString()` ساخته می‌شوند (تنظیمات پیش‌فرض ⇒ null نوشته می‌شود).

    private fun <T> decode(deserializer: kotlinx.serialization.DeserializationStrategy<T>, text: String): T =
        lenient.decodeFromString(deserializer, text)

    /** یک کاربر از پاسخ POST/PATCH/GET (با یا بدون پوشش `user`). */
    fun parseUser(element: JsonElement?): PanelUserDto {
        val obj = element as? JsonObject ?: throw PanelShapeException("پاسخ کاربر از پنل خالی بود")
        val inner: JsonElement = obj["user"] ?: obj
        val dto = runCatching { decode(PanelUserDto.serializer(), inner.toString()) }
            .getOrElse { e ->
                throw PanelShapeException("ساختار کاربر در پاسخ پنل قابل خواندن نبود: ${e.message}")
            }
        if (dto.uuid.isBlank()) throw PanelShapeException("پنل کاربر را بدون شناسه (uuid) برگرداند")
        return dto
    }

    /** فهرست کاربران + متادیتا، از هر دو شکل آرایه‌ای و صفحه‌ای. */
    fun parseUsers(element: JsonElement?): Pair<List<PanelUserDto>, UsersMetaDto> {
        if (element == null) return emptyList<PanelUserDto>() to UsersMetaDto()
        if (element is JsonArray) {
            val list = element.mapNotNull { row -> runCatching { parseUser(row) }.getOrNull() }
            return list to UsersMetaDto()
        }
        val obj = element as? JsonObject
            ?: throw PanelShapeException("پاسخ فهرست کاربران قابل خواندن نبود")
        val usersArray = when (val raw = obj["users"] ?: obj["data"] ?: obj["results"]) {
            null -> JsonArray(emptyList())
            is JsonArray -> raw
            is JsonObject -> (raw["users"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val users = usersArray.mapNotNull { row -> runCatching { parseUser(row) }.getOrNull() }
        // متا در نسخهٔ ۱۴ هم داخل «meta» و هم در سطح بالا هست؛ اول meta بعد خود شیء.
        val metaSource: JsonElement = (obj["meta"] as? JsonObject) ?: obj
        val meta = runCatching { decode(UsersMetaDto.serializer(), metaSource.toString()) }
            .getOrDefault(UsersMetaDto())
        return users to meta
    }

    /** متن خطای پنل (`msg` یا `detail`) از بدنهٔ خطای HTTP؛ در غیر این صورت رشتهٔ خالی. */
    fun errorText(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val text = runCatching {
            val obj = decode(JsonElement.serializer(), body) as? JsonObject ?: return@runCatching ""
            val msg = obj["msg"] ?: obj["detail"] ?: obj["message"] ?: obj["error"]
            when (msg) {
                is JsonPrimitive -> msg.takeIf { it.isString }?.content ?: ""
                // خطای اعتبارسنجی marshmallow: {"detail": {"json": {"field": ["Unknown field."]}}}
                null -> ""
                else -> msg.toString()
            }
        }.getOrDefault("")
        // اگر بدنه HTML صفحهٔ لاگین بود، چیز مفیدی برای کاربر ندارد
        return text.trim().take(180)
    }

    /** مقدار عددی یک فیلد (اختیاری، برای نمایش) */
    fun intField(element: JsonElement?, key: String): Int? =
        runCatching { (element as JsonObject)[key]?.jsonPrimitive?.content?.toInt() }.getOrNull()
}

/** وقتی پاسخ پنل ساختار مورد انتظار را ندارد. پیامش مستقیماً به کاربر نشان داده می‌شود. */
class PanelShapeException(message: String) : Exception(message)
