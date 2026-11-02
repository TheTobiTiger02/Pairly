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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The four looks the Design preview can switch between while the final one is chosen. */
enum class DesignVariant { Expressive, Minimal, DarkVibrant, Glass }

/** Bottom navigation bar (Home / Features / Help / Settings) or one dashboard with tiles. */
enum class NavStyle { BottomBar, Dashboard }

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

/** Variant-specific details Material's theme has no slot for. */
@Immutable
data class PairlyStyle(
    val variant: DesignVariant,
    val dark: Boolean,
    val cardRadius: Dp,
    val cardColor: Color,
    val cardBorder: BorderStroke?,
    val cardElevation: Dp,
    /** Drawn behind every screen; null means the plain background color. */
    val backgroundBrush: Brush?,
    /** Fill for the PC "hero" card and highlighted tiles. */
    val heroBrush: Brush,
    val onHero: Color,
    val iconContainer: Color,
    val onIconContainer: Color,
    val success: Color,
    val warning: Color,
    val reduceMotion: Boolean
) {
    fun <T> spring(): FiniteAnimationSpec<T> = when {
        reduceMotion -> tween(0)
        variant == DesignVariant.Expressive -> spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
        variant == DesignVariant.Minimal -> tween(220)
        else -> spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    }

    fun offsetSpring(): FiniteAnimationSpec<IntOffset> = spring()
}

val LocalPairlyStyle = staticCompositionLocalOf {
    PairlyStyle(
        variant = DesignVariant.Expressive, dark = false, cardRadius = 24.dp, cardColor = Color.White,
        cardBorder = null, cardElevation = 0.dp, backgroundBrush = null, heroBrush = Brush.linearGradient(listOf(Color.White, Color.White)),
        onHero = Color.Black, iconContainer = Color.LightGray, onIconContainer = Color.Black,
        success = Color(0xFF1E8E3E), warning = Color(0xFFB26A00), reduceMotion = false
    )
}

object Pairly {
    val style: PairlyStyle
        @Composable get() = LocalPairlyStyle.current
}

// Brand: the logo runs from blue #3B82F6 to violet #7C3AED; indigo #4F5BE8 sits between.
private val Blue = Color(0xFF3B82F6)
private val Indigo = Color(0xFF4F5BE8)
private val Violet = Color(0xFF7C3AED)

private fun expressive(dark: Boolean): ColorScheme = if (!dark) lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE1FF), onPrimaryContainer = Color(0xFF0E1561),
    secondary = Color(0xFF5B5D72), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E0F9), onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Violet, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEADDFF), onTertiaryContainer = Color(0xFF25005A),
    background = Color(0xFFFBF8FF), onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC), onSurfaceVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F2FD),
    surfaceContainer = Color(0xFFEFEDF8), surfaceContainerHigh = Color(0xFFE9E7F2),
    surfaceContainerHighest = Color(0xFFE4E1EC),
    outline = Color(0xFF777680), outlineVariant = Color(0xFFC7C5D0),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
) else darkColorScheme(
    primary = Color(0xFFBCC2FF), onPrimary = Color(0xFF212C8E),
    primaryContainer = Color(0xFF3A44B7), onPrimaryContainer = Color(0xFFDFE1FF),
    secondary = Color(0xFFC4C4DD), onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559), onSecondaryContainer = Color(0xFFE0E0F9),
    tertiary = Color(0xFFD2BBFF), onTertiary = Color(0xFF3F008D),
    tertiaryContainer = Color(0xFF5B21B6), onTertiaryContainer = Color(0xFFEADDFF),
    background = Color(0xFF121318), onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318), onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25), surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
    outline = Color(0xFF91909A), outlineVariant = Color(0xFF46464F),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
)

private fun minimal(dark: Boolean): ColorScheme = if (!dark) lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF0FF), onPrimaryContainer = Color(0xFF1A1F6B),
    secondary = Color(0xFF5F6368), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F3F4), onSecondaryContainer = Color(0xFF202124),
    tertiary = Color(0xFF5F6368), tertiaryContainer = Color(0xFFF1F3F4), onTertiaryContainer = Color(0xFF202124),
    background = Color.White, onBackground = Color(0xFF111318),
    surface = Color.White, onSurface = Color(0xFF111318),
    surfaceVariant = Color(0xFFF2F3F5), onSurfaceVariant = Color(0xFF5F6368),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainer = Color(0xFFF6F7F9), surfaceContainerHigh = Color(0xFFF0F1F4),
    surfaceContainerHighest = Color(0xFFE8EAED),
    outline = Color(0xFFDADCE0), outlineVariant = Color(0xFFE8EAED),
    error = Color(0xFFC5221F), errorContainer = Color(0xFFFCE8E6), onErrorContainer = Color(0xFF5C0B0A)
) else darkColorScheme(
    primary = Color(0xFFA9B1FF), onPrimary = Color(0xFF14185A),
    primaryContainer = Color(0xFF23264A), onPrimaryContainer = Color(0xFFDDE0FF),
    secondary = Color(0xFF9AA0A6), onSecondary = Color(0xFF202124),
    secondaryContainer = Color(0xFF26282C), onSecondaryContainer = Color(0xFFE8EAED),
    tertiary = Color(0xFF9AA0A6), tertiaryContainer = Color(0xFF26282C), onTertiaryContainer = Color(0xFFE8EAED),
    background = Color(0xFF0F1012), onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF0F1012), onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF202124), onSurfaceVariant = Color(0xFF9AA0A6),
    surfaceContainerLowest = Color(0xFF0B0C0E), surfaceContainerLow = Color(0xFF141518),
    surfaceContainer = Color(0xFF18191C), surfaceContainerHigh = Color(0xFF1E1F23),
    surfaceContainerHighest = Color(0xFF26282C),
    outline = Color(0xFF3C4043), outlineVariant = Color(0xFF2A2C30),
    error = Color(0xFFF28B82), errorContainer = Color(0xFF5C1A17), onErrorContainer = Color(0xFFFCE8E6)
)

private fun darkVibrant(): ColorScheme = darkColorScheme(
    primary = Color(0xFF8B95FF), onPrimary = Color(0xFF0A0D2E),
    primaryContainer = Color(0xFF2A2F7A), onPrimaryContainer = Color(0xFFE2E4FF),
    secondary = Color(0xFF5EEAD4), onSecondary = Color(0xFF00201B),
    secondaryContainer = Color(0xFF0F3B37), onSecondaryContainer = Color(0xFFB8FFF2),
    tertiary = Color(0xFFC084FC), onTertiary = Color(0xFF2E0057),
    tertiaryContainer = Color(0xFF4C1D95), onTertiaryContainer = Color(0xFFF3E8FF),
    background = Color(0xFF07091A), onBackground = Color(0xFFEEF0FF),
    surface = Color(0xFF0E1126), onSurface = Color(0xFFEEF0FF),
    surfaceVariant = Color(0xFF1A1F40), onSurfaceVariant = Color(0xFFA3A8D6),
    surfaceContainerLowest = Color(0xFF05061A), surfaceContainerLow = Color(0xFF0B0E22),
    surfaceContainer = Color(0xFF131733), surfaceContainerHigh = Color(0xFF1A1F40),
    surfaceContainerHighest = Color(0xFF22284F),
    outline = Color(0xFF3A3F72), outlineVariant = Color(0xFF262A55),
    error = Color(0xFFFF8A8A), errorContainer = Color(0xFF5A1022), onErrorContainer = Color(0xFFFFDADF)
)

private fun glass(dark: Boolean): ColorScheme = if (!dark) lightColorScheme(
    primary = Color(0xFF5B5BEA), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E3FF), onPrimaryContainer = Color(0xFF17146B),
    secondary = Color(0xFF6B5E8E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE5FF), onSecondaryContainer = Color(0xFF261A45),
    tertiary = Color(0xFFB4479A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8EF), onTertiaryContainer = Color(0xFF3B0030),
    background = Color(0xFFF1EEFF), onBackground = Color(0xFF1B1A2E),
    surface = Color(0xFFF7F5FF), onSurface = Color(0xFF1B1A2E),
    surfaceVariant = Color(0x99FFFFFF), onSurfaceVariant = Color(0xFF4B4F66),
    surfaceContainerLowest = Color(0xCCFFFFFF), surfaceContainerLow = Color(0xA6FFFFFF),
    surfaceContainer = Color(0x99FFFFFF), surfaceContainerHigh = Color(0xB3FFFFFF),
    surfaceContainerHighest = Color(0xCCFFFFFF),
    outline = Color(0xFF8A8AA6), outlineVariant = Color(0x66FFFFFF),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
) else darkColorScheme(
    primary = Color(0xFFB8B5FF), onPrimary = Color(0xFF231E8A),
    primaryContainer = Color(0x664F4BD6), onPrimaryContainer = Color(0xFFE4E3FF),
    secondary = Color(0xFFD3C3FF), onSecondary = Color(0xFF332656),
    secondaryContainer = Color(0x33FFFFFF), onSecondaryContainer = Color(0xFFEDE5FF),
    tertiary = Color(0xFFFFAEDF), onTertiary = Color(0xFF5E0049),
    tertiaryContainer = Color(0x55B4479A), onTertiaryContainer = Color(0xFFFFD8EF),
    background = Color(0xFF120F2C), onBackground = Color(0xFFECE9FF),
    surface = Color(0xFF16133A), onSurface = Color(0xFFECE9FF),
    surfaceVariant = Color(0x1FFFFFFF), onSurfaceVariant = Color(0xFFC3C0E0),
    surfaceContainerLowest = Color(0x0FFFFFFF), surfaceContainerLow = Color(0x14FFFFFF),
    surfaceContainer = Color(0x1AFFFFFF), surfaceContainerHigh = Color(0x24FFFFFF),
    surfaceContainerHighest = Color(0x2EFFFFFF),
    outline = Color(0xFF8E8AB3), outlineVariant = Color(0x29FFFFFF),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
)

private fun shapes(variant: DesignVariant): Shapes = when (variant) {
    DesignVariant.Expressive -> Shapes(RoundedCornerShape(10.dp), RoundedCornerShape(14.dp), RoundedCornerShape(20.dp), RoundedCornerShape(28.dp), RoundedCornerShape(36.dp))
    DesignVariant.Minimal -> Shapes(RoundedCornerShape(6.dp), RoundedCornerShape(8.dp), RoundedCornerShape(12.dp), RoundedCornerShape(16.dp), RoundedCornerShape(20.dp))
    DesignVariant.DarkVibrant -> Shapes(RoundedCornerShape(8.dp), RoundedCornerShape(12.dp), RoundedCornerShape(16.dp), RoundedCornerShape(22.dp), RoundedCornerShape(28.dp))
    DesignVariant.Glass -> Shapes(RoundedCornerShape(12.dp), RoundedCornerShape(16.dp), RoundedCornerShape(22.dp), RoundedCornerShape(28.dp), RoundedCornerShape(36.dp))
}

private fun typography(variant: DesignVariant): Typography {
    val heading = when (variant) {
        DesignVariant.Expressive -> FontWeight.Bold
        DesignVariant.Minimal -> FontWeight.Medium
        else -> FontWeight.SemiBold
    }
    val tracking = if (variant == DesignVariant.Expressive) (-0.6).sp else (-0.2).sp
    fun style(size: Int, line: Int, weight: FontWeight, letter: androidx.compose.ui.unit.TextUnit = 0.sp) =
        TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = letter)
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

private fun style(variant: DesignVariant, scheme: ColorScheme, dark: Boolean, reduceMotion: Boolean): PairlyStyle {
    val success = if (dark) Color(0xFF81C995) else Color(0xFF1E8E3E)
    val warning = if (dark) Color(0xFFFDD663) else Color(0xFFB26A00)
    return when (variant) {
        DesignVariant.Expressive -> PairlyStyle(
            variant, dark, cardRadius = 28.dp, cardColor = scheme.surfaceContainerLow, cardBorder = null, cardElevation = 0.dp,
            backgroundBrush = null,
            heroBrush = Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer)),
            onHero = scheme.onPrimaryContainer,
            iconContainer = scheme.primaryContainer, onIconContainer = scheme.onPrimaryContainer,
            success = success, warning = warning, reduceMotion = reduceMotion
        )
        DesignVariant.Minimal -> PairlyStyle(
            variant, dark, cardRadius = 16.dp, cardColor = scheme.surface, cardBorder = BorderStroke(1.dp, scheme.outlineVariant), cardElevation = 0.dp,
            backgroundBrush = null,
            heroBrush = Brush.linearGradient(listOf(scheme.surfaceContainer, scheme.surfaceContainer)),
            onHero = scheme.onSurface,
            iconContainer = scheme.surfaceContainerHigh, onIconContainer = scheme.primary,
            success = success, warning = warning, reduceMotion = reduceMotion
        )
        DesignVariant.DarkVibrant -> PairlyStyle(
            variant, true, cardRadius = 22.dp, cardColor = scheme.surfaceContainer,
            cardBorder = BorderStroke(1.dp, Brush.linearGradient(listOf(scheme.primary.copy(alpha = 0.55f), scheme.tertiary.copy(alpha = 0.25f), scheme.secondary.copy(alpha = 0.35f)))),
            cardElevation = 0.dp,
            backgroundBrush = Brush.verticalGradient(listOf(Color(0xFF111640), scheme.background, scheme.background)),
            heroBrush = Brush.linearGradient(listOf(Blue, Violet)),
            onHero = Color.White,
            iconContainer = scheme.primary.copy(alpha = 0.16f), onIconContainer = scheme.primary,
            success = Color(0xFF5EEAD4), warning = warning, reduceMotion = reduceMotion
        )
        DesignVariant.Glass -> PairlyStyle(
            variant, dark, cardRadius = 28.dp,
            cardColor = if (dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.62f),
            cardBorder = BorderStroke(1.dp, if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.85f)),
            cardElevation = 0.dp,
            backgroundBrush = if (dark) Brush.linearGradient(listOf(Color(0xFF0E1030), Color(0xFF1E1240), Color(0xFF2A0F2E)))
            else Brush.linearGradient(listOf(Color(0xFFDDE6FF), Color(0xFFF0E4FF), Color(0xFFFFE6F2))),
            heroBrush = if (dark) Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.05f)))
            else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.45f))),
            onHero = scheme.onSurface,
            iconContainer = if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.75f), onIconContainer = scheme.primary,
            success = success, warning = warning, reduceMotion = reduceMotion
        )
    }
}

@Composable
fun isDark(themeMode: ThemeMode, variant: DesignVariant): Boolean = when {
    variant == DesignVariant.DarkVibrant -> true
    themeMode == ThemeMode.Light -> false
    themeMode == ThemeMode.Dark -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun PairlyTheme(
    variant: DesignVariant = DesignVariant.Expressive,
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark = isDark(themeMode, variant)
    val reduceMotion = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && variant != DesignVariant.Glass
    val scheme = when {
        useDynamic && dark -> dynamicDarkColorScheme(context)
        useDynamic -> dynamicLightColorScheme(context)
        variant == DesignVariant.Expressive -> expressive(dark)
        variant == DesignVariant.Minimal -> minimal(dark)
        variant == DesignVariant.DarkVibrant -> darkVibrant()
        else -> glass(dark)
    }
    val pairlyStyle = style(variant, scheme, dark, reduceMotion)
    CompositionLocalProvider(LocalPairlyStyle provides pairlyStyle) {
        MaterialTheme(colorScheme = scheme, shapes = shapes(variant), typography = typography(variant), content = content)
    }
}
