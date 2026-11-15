package com.abhranil.stashd.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhranil.stashd.R

// ---------- Colour tokens (build plan, section 3) ----------

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF5EE6C5),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF0E3A32),
    onPrimaryContainer = Color(0xFFA8F6E3),
    secondary = Color(0xFF8EA9FF),
    onSecondary = Color(0xFF0A1A44),
    secondaryContainer = Color(0xFF1E2A4D),
    onSecondaryContainer = Color(0xFFD5DFFF),
    error = Color(0xFFFF8B85),
    onError = Color(0xFF3A0A0D),
    errorContainer = Color(0xFF3D1519),
    onErrorContainer = Color(0xFFFFD9D6),
    background = Color(0xFF0A0E13),
    onBackground = Color(0xFFE7ECF3),
    surface = Color(0xFF10151C),
    onSurface = Color(0xFFE7ECF3),
    surfaceVariant = Color(0xFF1B222C),
    onSurfaceVariant = Color(0xFF9BA7B7),
    surfaceContainerLowest = Color(0xFF0A0E13),
    surfaceContainerLow = Color(0xFF10151C),
    surfaceContainer = Color(0xFF151B23),
    surfaceContainerHigh = Color(0xFF1B222C),
    surfaceContainerHighest = Color(0xFF232B37),
    outline = Color(0xFF2B3441),
    outlineVariant = Color(0xFF1F2731),
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF00A383),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFF5EA),
    onPrimaryContainer = Color(0xFF00382C),
    secondary = Color(0xFF3E63DD),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E9FF),
    onSecondaryContainer = Color(0xFF0A1A44),
    error = Color(0xFFD93A35),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE7E6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF0E1620),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0E1620),
    surfaceVariant = Color(0xFFEEF2F5),
    onSurfaceVariant = Color(0xFF4F5B69),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F8FA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF2F5),
    surfaceContainerHighest = Color(0xFFE3E9EE),
    outline = Color(0xFFC9D2DB),
    outlineVariant = Color(0xFFE1E7EC),
)

/** Roles Material 3 has no slot for: paused/warning state, muted text, progress gradient. */
@Immutable
data class ExtendedColors(
    val warning: Color,
    val warningContainer: Color,
    val muted: Color,
    val progressGradient: List<Color>,
) {
    val progressBrush: Brush get() = Brush.horizontalGradient(progressGradient)
}

private val DarkExtended = ExtendedColors(
    warning = Color(0xFFF4C36A),
    warningContainer = Color(0xFF3A2C10),
    muted = Color(0xFF6B7787),
    progressGradient = listOf(Color(0xFF5EE6C5), Color(0xFF7FC4F0), Color(0xFF8EA9FF)),
)

private val LightExtended = ExtendedColors(
    warning = Color(0xFFB7791F),
    warningContainer = Color(0xFFFFF1D6),
    muted = Color(0xFF7D8895),
    progressGradient = listOf(Color(0xFF00B38F), Color(0xFF2E9BD6), Color(0xFF4C6FFF)),
)

val LocalExtendedColors = staticCompositionLocalOf { DarkExtended }

// ---------- Type tokens: Outfit for display/headline/title-large, Inter for the rest ----------

@OptIn(ExperimentalTextApi::class)
private fun outfit(weight: Int) = Font(
    R.font.outfit,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Outfit = FontFamily(outfit(500), outfit(600))
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

val StashdTypography = Typography(
    displayLarge = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 50.sp, letterSpacing = (-0.8).sp),
    displayMedium = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    // Overline: caps applied at call site.
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.1.sp),
)

// ---------- Shape tokens: 8 / 12 / 16 / 24 / 28 dp ----------

val StashdShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Component-specific radii from the mockups. */
object StashdRadii {
    val listCard = RoundedCornerShape(20.dp)
    val heroCard = RoundedCornerShape(26.dp)
    val sheetTop = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val iconTile = RoundedCornerShape(14.dp)
    val checkbox = RoundedCornerShape(7.dp)
}

@Composable
fun StashdTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtendedColors provides if (darkTheme) DarkExtended else LightExtended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = StashdTypography,
            shapes = StashdShapes,
            content = content,
        )
    }
}

object StashdThemeExt {
    val colors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}
