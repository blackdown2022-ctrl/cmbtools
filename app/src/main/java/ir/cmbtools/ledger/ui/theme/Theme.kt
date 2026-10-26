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

// Tiznet palette: deep navy + saturated electric blue, balanced with high-contrast surfaces.
val Navy = Color(0xFFEAF1FF)
val NavyDeep = Color(0xFFF5F8FF)
val CardDark = Color(0xFFD6E2F5)
val PrimaryCyan = Color(0xFF155EEF)
val AccentGreen = Color(0xFF16804A)
val AccentAmber = Color(0xFFB45F06)
val AccentRed = Color(0xFFCB3348)
val TextMuted = Color(0xFF52627A)

private val LightColors = lightColorScheme(
    primary = Color(0xFF155EEF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF082B68),
    secondary = Color(0xFF174AA6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EDFF),
    onSecondaryContainer = Color(0xFF102E66),
    tertiary = Color(0xFF0F4DB8),
    onTertiary = Color.White,
    background = Color(0xFFF5F8FF),
    onBackground = Color(0xFF101B31),
    surface = Color.White,
    onSurface = Color(0xFF101B31),
    surfaceVariant = Color(0xFFEAF1FF),
    onSurfaceVariant = Color(0xFF52627A),
    outline = Color(0xFFB8C8E2),
    outlineVariant = Color(0xFFD9E4F4),
    error = Color(0xFFCB3348),
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E8),
    onErrorContainer = Color(0xFF781D2C)
)

private fun base(size: Float, weight: FontWeight = FontWeight.Normal, lineHeightExtra: Float = 6f) =
    TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size + lineHeightExtra).sp
    )

private val AppTypography = Typography(
    displaySmall = base(34f, FontWeight.Bold),
    headlineMedium = base(29f, FontWeight.Bold),
    headlineSmall = base(25f, FontWeight.Bold),
    titleLarge = base(23f, FontWeight.Bold),
    titleMedium = base(19f, FontWeight.SemiBold),
    titleSmall = base(17f, FontWeight.SemiBold),
    bodyLarge = base(18f),
    bodyMedium = base(16f),
    bodySmall = base(14.5f),
    labelLarge = base(16f, FontWeight.SemiBold),
    labelMedium = base(14f),
    labelSmall = base(13f)
)

@Composable
fun CmbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content
    )
}
