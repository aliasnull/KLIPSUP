package alias.klips.net.core.designsystem

import alias.klips.net.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object KlipsupTokens {
    val StudioObsidian = Color(0xFF0A0D14)
    val PanelCharcoal = Color(0xFF121722)
    val PanelElevated = Color(0xFF1A2232)
    val BorderSubtle = Color(0xFF263147)
    val AccentCyan = Color(0xFF38BDF8)
    val AccentAmber = Color(0xFFF59E0B)
    val AccentEmerald = Color(0xFF10B981)
    val AccentViolet = Color(0xFF8B5CF6)
    val AccentRose = Color(0xFFF43F5E)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.Bold)
)

val InterFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold)
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Bold)
)

private val KlipsupDarkColors = darkColorScheme(
    primary = KlipsupTokens.AccentCyan,
    onPrimary = Color(0xFF04131D),
    primaryContainer = Color(0xFF0C364E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = KlipsupTokens.AccentAmber,
    onSecondary = Color(0xFF1E1302),
    secondaryContainer = Color(0xFF3F2B07),
    onSecondaryContainer = Color(0xFFFEF3C7),
    tertiary = KlipsupTokens.AccentEmerald,
    background = KlipsupTokens.StudioObsidian,
    onBackground = KlipsupTokens.TextPrimary,
    surface = KlipsupTokens.PanelCharcoal,
    onSurface = KlipsupTokens.TextPrimary,
    surfaceVariant = KlipsupTokens.PanelElevated,
    onSurfaceVariant = KlipsupTokens.TextSecondary,
    outline = KlipsupTokens.BorderSubtle,
    error = KlipsupTokens.AccentRose
)

private val KlipsupLightColors = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color.White,
    secondary = Color(0xFFD97706),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

val KlipsupTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

val KlipsupShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp)
)

@Composable
fun KlipsupTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) KlipsupDarkColors else KlipsupLightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = KlipsupTypography,
        shapes = KlipsupShapes,
        content = content
    )
}
