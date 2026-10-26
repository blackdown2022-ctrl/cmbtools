package ir.cmbtools.ledger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * پوستهٔ روشن (سفید/کرم) — خواستهٔ کاربر: «رنگ اپ روشن باشد نه تیره».
 *
 * نام ثابت‌ها از نسخهٔ تیره باقی مانده تا بقیهٔ صفحه‌ها تغییر نکنند، ولی مقدارشان
 * برای پس‌زمینهٔ روشن تنظیم شده است (کنتراست کافی روی سفید).
 */
val Navy = Color(0xFFF1F5F9)          // سطح دوم (کارت‌های کم‌رنگ)
val NavyDeep = Color(0xFFF8FAFC)      // پس‌زمینهٔ کلی
val CardDark = Color(0xFFE2E8F0)      // خط/سطح سوم
val PrimaryCyan = Color(0xFF0E7490)   // رنگ اصلی (فیروزه‌ای تیره، خوانا روی سفید)
val AccentGreen = Color(0xFF15803D)
val AccentAmber = Color(0xFFB45309)
val AccentRed = Color(0xFFDC2626)
val TextMuted = Color(0xFF5B6B7F)     // متن ثانویه — تیره‌تر از قبل تا خوانا باشد

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7490),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF3FA),
    onPrimaryContainer = Color(0xFF06343E),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF5B6B7F),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

/**
 * فونت و اندازه‌ها.
 *
 * چرا بزرگ‌تر؟ شکایت کاربر: «اعداد فارسی خیلی کوچک هستند». اندازهٔ پایهٔ متن‌ها
 * تقریباً یک پله بالا رفته و ارتفاع خط هم بازتر شده تا ارقام فارسی (که در فونت
 * سیستم کوتاه‌تر از لاتین دیده می‌شوند) خوانا باشند.
 */
private fun base(size: Float, weight: FontWeight = FontWeight.Normal, lineHeightExtra: Float = 6f) =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size + lineHeightExtra).sp
    )

private val AppTypography = Typography(
    displaySmall = base(31f, FontWeight.Bold),
    headlineMedium = base(27f, FontWeight.Bold),
    headlineSmall = base(24f, FontWeight.Bold),
    titleLarge = base(21f, FontWeight.Bold),
    titleMedium = base(17.5f, FontWeight.SemiBold),
    titleSmall = base(15.5f, FontWeight.SemiBold),
    bodyLarge = base(16.5f),
    bodyMedium = base(15f),
    bodySmall = base(13.5f),
    labelLarge = base(14.5f, FontWeight.SemiBold),
    labelMedium = base(13f),
    labelSmall = base(12f)
)

/** همیشه روشن. (اگر روزی حالت تیره خواسته شد، همین‌جا یک darkColorScheme اضافه شود.) */
@Composable
fun CmbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content
    )
}
