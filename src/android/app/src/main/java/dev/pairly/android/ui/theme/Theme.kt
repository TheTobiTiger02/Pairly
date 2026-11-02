package dev.pairly.android.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pairly's look is "Glass": frosted cards on a calm background; dark mode is a deep navy. */

enum class ThemeMode { System, Light, Dark }

object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val touch = 48.dp
    val button = 52.dp
}

/** Details Material's theme has no slot for. */
@Immutable
data class PairlyStyle(
    val dark: Boolean,
    val cardRadius: Dp,
    val cardColor: Color,
    val cardBorder: BorderStroke?,
    /** Drawn behind every screen on top of the background color; null means plain. */
    val backgroundBrush: Brush?,
    /** Fill for the PC "hero" card. */
    val heroBrush: Brush,
    val onHero: Color,
    val iconContainer: Color,
    val onIconContainer: Color,
    val success: Color,
    val warning: Color,
    val reduceMotion: Boolean
) {
    fun <T> spring(): FiniteAnimationSpec<T> =
        if (reduceMotion) tween(0) else spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

    fun offsetSpring(): FiniteAnimationSpec<IntOffset> = spring()
}

val LocalPairlyStyle = staticCompositionLocalOf {
    PairlyStyle(
        dark = false, cardRadius = 24.dp, cardColor = Color.White, cardBorder = null, backgroundBrush = null,
        heroBrush = Brush.linearGradient(listOf(Color.White, Color.White)),
        onHero = Color.Black, iconContainer = Color.LightGray, onIconContainer = Color.Black,
        success = Color(0xFF1E8E3E), warning = Color(0xFFB26A00), reduceMotion = false
    )
}

object Pairly {
    val style: PairlyStyle
        @Composable get() = LocalPairlyStyle.current
}

// Brand indigo #4F5BE8; the dark accent is a lighter, slightly desaturated tint of it.
private val Indigo = Color(0xFF4F5BE8)
private val IndigoLight = Color(0xFF9AA2FF)

internal fun glassLight(): ColorScheme = lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E4FF), onPrimaryContainer = Color(0xFF141A66),
    secondary = Color(0xFF5B6078), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E8F4), onSecondaryContainer = Color(0xFF1A1D2E),
    tertiary = Color(0xFF5B6078), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6E8F4), onTertiaryContainer = Color(0xFF1A1D2E),
    background = Color(0xFFF2F4FA), onBackground = Color(0xFF171A24),
    surface = Color(0xFFF7F8FC), onSurface = Color(0xFF171A24),
    surfaceVariant = Color(0x99FFFFFF), onSurfaceVariant = Color(0xFF4A4F63),
    surfaceContainerLowest = Color(0xCCFFFFFF), surfaceContainerLow = Color(0xA6FFFFFF),
    surfaceContainer = Color(0xFFF0F2F8), surfaceContainerHigh = Color(0xFFE8EBF3),
    surfaceContainerHighest = Color(0xFFE3E6F0),
    outline = Color(0xFF80869C), outlineVariant = Color(0x66FFFFFF),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)

internal fun glassDark(): ColorScheme {
    val base = Color(0xFF0D1220)
    val raised = Color(0xFF141A2B)
    return darkColorScheme(
        primary = IndigoLight, onPrimary = Color(0xFF111650),
        primaryContainer = Color(0xFF2B3170), onPrimaryContainer = Color(0xFFE0E2FF),
        secondary = Color(0xFFB9BDCC), onSecondary = Color(0xFF242734),
        secondaryContainer = Color(0x1FFFFFFF), onSecondaryContainer = Color(0xFFE6E8F0),
        tertiary = Color(0xFFB9BDCC), onTertiary = Color(0xFF242734),
        tertiaryContainer = Color(0x1FFFFFFF), onTertiaryContainer = Color(0xFFE6E8F0),
        background = base, onBackground = Color(0xFFE8EAF0),
        surface = base, onSurface = Color(0xFFE8EAF0),
        surfaceVariant = Color(0x1AFFFFFF), onSurfaceVariant = Color(0xFFA9AEBE),
        surfaceContainerLowest = Color(0x0AFFFFFF), surfaceContainerLow = Color(0x10FFFFFF),
        surfaceContainer = raised, surfaceContainerHigh = Color(0x1FFFFFFF),
        surfaceContainerHighest = Color(0x29FFFFFF),
        outline = Color(0xFF6E7385), outlineVariant = Color(0x1FFFFFFF),
        error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
    )
}

/** Wallpaper colors only replace the accent; the glass surfaces stay. */
private fun withAccent(scheme: ColorScheme, accent: ColorScheme): ColorScheme = scheme.copy(
    primary = accent.primary, onPrimary = accent.onPrimary,
    primaryContainer = accent.primaryContainer, onPrimaryContainer = accent.onPrimaryContainer
)

private val shapes = Shapes(RoundedCornerShape(12.dp), RoundedCornerShape(16.dp), RoundedCornerShape(20.dp), RoundedCornerShape(26.dp), RoundedCornerShape(32.dp))

private fun typography(): Typography {
    val heading = FontWeight.SemiBold
    val tracking = (-0.2).sp
    fun style(size: Int, line: Int, weight: FontWeight, letter: androidx.compose.ui.unit.TextUnit = 0.sp) =
        // Hyphenation keeps long German words ("Zwischenablage") from breaking mid-syllable in tight spots.
        TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = letter, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)
    return Typography(
        displayLarge = style(48, 56, heading, tracking),
        displayMedium = style(40, 48, heading, tracking),
        displaySmall = style(34, 42, heading, tracking),
        headlineLarge = style(30, 38, heading, tracking),
        headlineMedium = style(26, 34, heading, tracking),
        headlineSmall = style(22, 30, heading),
        titleLarge = style(20, 28, FontWeight.SemiBold),
        titleMedium = style(16, 24, FontWeight.SemiBold, 0.1.sp),
        titleSmall = style(14, 20, FontWeight.SemiBold, 0.1.sp),
        bodyLarge = style(16, 24, FontWeight.Normal),
        bodyMedium = style(14, 20, FontWeight.Normal),
        bodySmall = style(12, 16, FontWeight.Normal),
        labelLarge = style(14, 20, FontWeight.SemiBold, 0.1.sp),
        labelMedium = style(12, 16, FontWeight.Medium, 0.4.sp),
        labelSmall = style(11, 16, FontWeight.Medium, 0.4.sp)
    )
}

internal fun style(scheme: ColorScheme, dark: Boolean, reduceMotion: Boolean): PairlyStyle {
    val success = if (dark) Color(0xFF7FD69B) else Color(0xFF1E7E3A)
    val warning = if (dark) Color(0xFFF2C46B) else Color(0xFF9A5B00)
    return if (dark) PairlyStyle(
        dark = true, cardRadius = 24.dp,
        cardColor = Color.White.copy(alpha = 0.06f),
        cardBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        backgroundBrush = null,
        heroBrush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.06f))),
        onHero = scheme.onSurface,
        iconContainer = Color.White.copy(alpha = 0.08f), onIconContainer = scheme.primary,
        success = success, warning = warning, reduceMotion = reduceMotion
    ) else PairlyStyle(
        dark = false, cardRadius = 24.dp,
        cardColor = Color.White.copy(alpha = 0.72f),
        cardBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.9f)),
        backgroundBrush = Brush.linearGradient(listOf(Color(0xFFE3E9FA), Color(0xFFF2F4FA), Color(0xFFEDEBFA))),
        heroBrush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.6f))),
        onHero = scheme.onSurface,
        iconContainer = scheme.primaryContainer.copy(alpha = 0.7f), onIconContainer = scheme.primary,
        success = success, warning = warning, reduceMotion = reduceMotion
    )
}

@Composable
fun isDark(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.System -> isSystemInDarkTheme()
}

@Composable
fun PairlyTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark = isDark(themeMode)
    val reduceMotion = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val base = if (dark) glassDark() else glassLight()
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        withAccent(base, if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
    } else base
    val pairlyStyle = style(scheme, dark, reduceMotion)
    CompositionLocalProvider(LocalPairlyStyle provides pairlyStyle) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography(), content = content)
    }
}
