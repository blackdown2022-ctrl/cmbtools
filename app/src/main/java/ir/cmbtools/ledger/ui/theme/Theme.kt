package ir.cmbtools.ledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Navy = Color(0xFF0F172A)
val NavyDeep = Color(0xFF0B1220)
val CardDark = Color(0xFF16213A)
val PrimaryCyan = Color(0xFF22D3EE)
val AccentGreen = Color(0xFF34D399)
val AccentAmber = Color(0xFFFBBF24)
val AccentRed = Color(0xFFF87171)
val TextMuted = Color(0xFF94A3B8)

private val DarkColors = darkColorScheme(
    primary = PrimaryCyan,
    onPrimary = NavyDeep,
    secondary = AccentGreen,
    onSecondary = NavyDeep,
    tertiary = AccentAmber,
    background = NavyDeep,
    onBackground = Color(0xFFE2E8F0),
    surface = Navy,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = CardDark,
    onSurfaceVariant = TextMuted,
    error = AccentRed
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7490),
    secondary = Color(0xFF047857),
    tertiary = Color(0xFFB45309)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 19.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.5.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp)
)

@Composable
fun CmbTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else DarkColors,
        typography = AppTypography,
        content = content
    )
}
