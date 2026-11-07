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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pairly.android.R

/**
 * Pairly has two looks the user picks between (Settings > Appearance > Style):
 * - Aurora: the indigo-violet brand, layered rounded cards and a gradient hero; calm, springy motion.
 * - Daybreak: warm off-white with a coral accent, chunkier shapes, rounded type and bouncier motion.
 * Both follow the system's light and dark mode.
 */
enum class AppStyle { Aurora, Daybreak }

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
    val appStyle: AppStyle,
    val dark: Boolean,
    val cardRadius: Dp,
    val cardColor: Color,
    val cardBorder: BorderStroke?,
    /** Drawn behind every screen on top of the background color; null means plain. */
    val backgroundBrush: Brush?,
    /** Fill for the PC "hero" card and other brand moments. */
    val heroBrush: Brush,
    val onHero: Color,
    val iconContainer: Color,
    val onIconContainer: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    /** Accents for illustrations (feature tiles, blobs, rings). */
    val accents: List<Color>,
    val reduceMotion: Boolean
) {
    val daybreak: Boolean get() = appStyle == AppStyle.Daybreak

    fun <T> spring(): FiniteAnimationSpec<T> = when {
        reduceMotion -> tween(0)
        daybreak -> spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
        else -> spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    }

    fun offsetSpring(): FiniteAnimationSpec<IntOffset> = spring()
}

val LocalPairlyStyle = staticCompositionLocalOf {
    PairlyStyle(
        appStyle = AppStyle.Aurora, dark = false, cardRadius = 24.dp, cardColor = Color.White, cardBorder = null, backgroundBrush = null,
        heroBrush = Brush.linearGradient(listOf(Color.White, Color.White)),
        onHero = Color.Black, iconContainer = Color.LightGray, onIconContainer = Color.Black,
        success = Color(0xFF1E8E3E), successContainer = Color(0xFFE3F8EE), warning = Color(0xFFB26A00), warningContainer = Color(0xFFFFF0D6),
        accents = listOf(Color.Gray), reduceMotion = false
    )
}

object Pairly {
    val style: PairlyStyle
        @Composable get() = LocalPairlyStyle.current
}

// --- Aurora ---------------------------------------------------------------------------------------

private val Indigo = Color(0xFF4B3FD8)
private val Violet = Color(0xFF7C4DEB)
private val IndigoLight = Color(0xFFA9A3FF)

internal fun auroraLight(): ColorScheme = lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E4FF), onPrimaryContainer = Color(0xFF1C1477),
    secondary = Color(0xFF5D5A78), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E7F7), onSecondaryContainer = Color(0xFF1C1A2E),
    tertiary = Color(0xFF157F4C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE3F8EE), onTertiaryContainer = Color(0xFF0B3B23),
    background = Color(0xFFF4F3FF), onBackground = Color(0xFF1B1A2E),
    surface = Color(0xFFF7F6FF), onSurface = Color(0xFF1B1A2E),
    surfaceVariant = Color(0xFFECEAF8), onSurfaceVariant = Color(0xFF55536E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFAFF),
    surfaceContainer = Color(0xFFF1EFFC), surfaceContainerHigh = Color(0xFFEAE8F8),
    surfaceContainerHighest = Color(0xFFE3E1F4),
    outline = Color(0xFF7E7B99), outlineVariant = Color(0xFFD9D6EC),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)

internal fun auroraDark(): ColorScheme = darkColorScheme(
    primary = IndigoLight, onPrimary = Color(0xFF1C1477),
    primaryContainer = Color(0xFF3A2FC4), onPrimaryContainer = Color(0xFFE6E4FF),
    secondary = Color(0xFFC7C5DA), onSecondary = Color(0xFF2B2940),
    secondaryContainer = Color(0xFF2A2840), onSecondaryContainer = Color(0xFFE6E4F5),
    tertiary = Color(0xFF7FD6A8), onTertiary = Color(0xFF0B3B23),
    tertiaryContainer = Color(0xFF16402B), onTertiaryContainer = Color(0xFFC5F2D9),
    background = Color(0xFF13121F), onBackground = Color(0xFFF1F0FA),
    surface = Color(0xFF13121F), onSurface = Color(0xFFF1F0FA),
    surfaceVariant = Color(0xFF26243A), onSurfaceVariant = Color(0xFFB3B1C9),
    surfaceContainerLowest = Color(0xFF0E0D18), surfaceContainerLow = Color(0xFF1A1929),
    surfaceContainer = Color(0xFF1E1D30), surfaceContainerHigh = Color(0xFF26243A),
    surfaceContainerHighest = Color(0xFF2E2C45),
    outline = Color(0xFF8C89A6), outlineVariant = Color(0xFF33314A),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
)

// --- Daybreak -------------------------------------------------------------------------------------

private val Coral = Color(0xFFE8603F)
private val CoralBright = Color(0xFFFF7A59)
private val CoralLight = Color(0xFFFFB59E)

internal fun daybreakLight(): ColorScheme = lightColorScheme(
    // Buttons use a deeper coral than the hero so white text keeps 4.5:1.
    primary = Color(0xFFC2462A), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D6), onPrimaryContainer = Color(0xFF5C1806),
    secondary = Color(0xFF6B5249), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7E6DE), onSecondaryContainer = Color(0xFF2B1D17),
    tertiary = Color(0xFF1F6B3A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6F6E4), onTertiaryContainer = Color(0xFF0E3A1C),
    background = Color(0xFFFFF7F1), onBackground = Color(0xFF2B1D17),
    surface = Color(0xFFFFF9F5), onSurface = Color(0xFF2B1D17),
    surfaceVariant = Color(0xFFF5E7DF), onSurfaceVariant = Color(0xFF6B5249),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFCFA),
    surfaceContainer = Color(0xFFFBEFE8), surfaceContainerHigh = Color(0xFFF6E7DF),
    surfaceContainerHighest = Color(0xFFF0DFD5),
    outline = Color(0xFF8E7369), outlineVariant = Color(0xFFEAD5CB),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)

internal fun daybreakDark(): ColorScheme = darkColorScheme(
    primary = CoralLight, onPrimary = Color(0xFF5C1806),
    primaryContainer = Color(0xFF7A2C17), onPrimaryContainer = Color(0xFFFFE3D6),
    secondary = Color(0xFFE3D3CA), onSecondary = Color(0xFF3A2A23),
    secondaryContainer = Color(0xFF3A2A23), onSecondaryContainer = Color(0xFFF3E6DF),
    tertiary = Color(0xFF9BDBA5), onTertiary = Color(0xFF0E3A1C),
    tertiaryContainer = Color(0xFF1E4A2A), onTertiaryContainer = Color(0xFFD5F3D3),
    background = Color(0xFF1C1411), onBackground = Color(0xFFF7ECE6),
    surface = Color(0xFF1C1411), onSurface = Color(0xFFF7ECE6),
    surfaceVariant = Color(0xFF33251F), onSurfaceVariant = Color(0xFFD9C4BA),
    surfaceContainerLowest = Color(0xFF150F0C), surfaceContainerLow = Color(0xFF231915),
    surfaceContainer = Color(0xFF2B1F1A), surfaceContainerHigh = Color(0xFF352721),
    surfaceContainerHighest = Color(0xFF402F28),
    outline = Color(0xFFA48E84), outlineVariant = Color(0xFF4A3830),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
)

/** Wallpaper colors only replace the accent; the style's surfaces stay. */
private fun withAccent(scheme: ColorScheme, accent: ColorScheme): ColorScheme = scheme.copy(
    primary = accent.primary, onPrimary = accent.onPrimary,
    primaryContainer = accent.primaryContainer, onPrimaryContainer = accent.onPrimaryContainer
)

private fun shapes(appStyle: AppStyle) = when (appStyle) {
    AppStyle.Aurora -> Shapes(RoundedCornerShape(12.dp), RoundedCornerShape(16.dp), RoundedCornerShape(22.dp), RoundedCornerShape(28.dp), RoundedCornerShape(32.dp))
    AppStyle.Daybreak -> Shapes(RoundedCornerShape(16.dp), RoundedCornerShape(22.dp), RoundedCornerShape(30.dp), RoundedCornerShape(36.dp), RoundedCornerShape(44.dp))
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(font: Int, weight: Int, as_: FontWeight) =
    Font(font, as_, variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val SpaceGrotesk = FontFamily(
    variable(R.font.space_grotesk, 500, FontWeight.Medium),
    variable(R.font.space_grotesk, 600, FontWeight.SemiBold),
    variable(R.font.space_grotesk, 700, FontWeight.Bold)
)

private val Nunito = FontFamily(
    variable(R.font.nunito, 500, FontWeight.Normal),
    variable(R.font.nunito, 600, FontWeight.Medium),
    variable(R.font.nunito, 700, FontWeight.SemiBold),
    variable(R.font.nunito, 800, FontWeight.Bold),
    variable(R.font.nunito, 900, FontWeight.ExtraBold)
)

private fun typography(appStyle: AppStyle): Typography {
    val aurora = appStyle == AppStyle.Aurora
    val display = if (aurora) SpaceGrotesk else Nunito
    val body = if (aurora) FontFamily.SansSerif else Nunito
    val heading = if (aurora) FontWeight.Bold else FontWeight.ExtraBold
    val strong = if (aurora) FontWeight.SemiBold else FontWeight.Bold
    val medium = if (aurora) FontWeight.Medium else FontWeight.SemiBold
    val tracking = if (aurora) (-0.4).sp else (-0.2).sp
    fun style(family: FontFamily, size: Int, line: Int, weight: FontWeight, letter: TextUnit = 0.sp) =
        // Hyphenation keeps long German words ("Zwischenablage") from breaking mid-syllable in tight spots.
        TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = letter, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)
    return Typography(
        displayLarge = style(display, 48, 56, heading, tracking),
        displayMedium = style(display, 40, 48, heading, tracking),
        displaySmall = style(display, 34, 42, heading, tracking),
        headlineLarge = style(display, 30, 38, heading, tracking),
        headlineMedium = style(display, 26, 34, heading, tracking),
        headlineSmall = style(display, 22, 30, heading),
        titleLarge = style(display, 20, 28, strong),
        titleMedium = style(body, 16, 24, strong, 0.1.sp),
        titleSmall = style(body, 14, 20, strong, 0.1.sp),
        bodyLarge = style(body, 16, 24, FontWeight.Normal),
        bodyMedium = style(body, 14, 20, FontWeight.Normal),
        bodySmall = style(body, 12, 16, FontWeight.Normal),
        labelLarge = style(body, 14, 20, strong, 0.1.sp),
        labelMedium = style(body, 12, 16, medium, 0.4.sp),
        labelSmall = style(body, 11, 16, medium, 0.4.sp)
    )
}

internal fun style(appStyle: AppStyle, scheme: ColorScheme, dark: Boolean, reduceMotion: Boolean): PairlyStyle {
    val success = if (dark) Color(0xFF7FD69B) else Color(0xFF157F4C)
    val successContainer = if (dark) Color(0xFF16402B) else Color(0xFFE3F8EE)
    val warning = if (dark) Color(0xFFF2C46B) else Color(0xFF8A5300)
    val warningContainer = if (dark) Color(0xFF4A3510) else Color(0xFFFFF0D6)
    return when (appStyle) {
        AppStyle.Aurora -> PairlyStyle(
            appStyle = appStyle, dark = dark, cardRadius = 24.dp,
            cardColor = if (dark) scheme.surfaceContainer else Color.White,
            cardBorder = if (dark) BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)) else null,
            backgroundBrush = if (dark) null else Brush.verticalGradient(listOf(Color(0xFFEFEDFF), Color(0xFFF4F3FF), Color(0xFFF7F6FF))),
            heroBrush = Brush.linearGradient(if (dark) listOf(Color(0xFF3B32B8), Color(0xFF6A41D6)) else listOf(Indigo, Violet)),
            onHero = Color.White,
            iconContainer = scheme.primaryContainer.copy(alpha = if (dark) 0.6f else 1f),
            onIconContainer = if (dark) scheme.onPrimaryContainer else scheme.primary,
            success = success, successContainer = successContainer, warning = warning, warningContainer = warningContainer,
            accents = listOf(Color(0xFF5B6CF6), Color(0xFF8B5CF6), Color(0xFF38BDF8), Color(0xFF34D399)),
            reduceMotion = reduceMotion
        )
        AppStyle.Daybreak -> PairlyStyle(
            appStyle = appStyle, dark = dark, cardRadius = 30.dp,
            cardColor = if (dark) scheme.surfaceContainer else Color.White,
            cardBorder = null,
            backgroundBrush = null,
            heroBrush = Brush.linearGradient(if (dark) listOf(Color(0xFFB4431F), Color(0xFFD0583A)) else listOf(Color(0xFFC2462A), Coral)),
            onHero = Color.White,
            iconContainer = scheme.primaryContainer.copy(alpha = if (dark) 0.7f else 1f),
            onIconContainer = scheme.onPrimaryContainer,
            success = success, successContainer = successContainer, warning = warning, warningContainer = warningContainer,
            accents = if (dark) listOf(Color(0xFFFF9C7F), Color(0xFFFFD84D), Color(0xFF7FC8F8), Color(0xFF9BDBA5))
            else listOf(CoralBright, Color(0xFFFFD84D), Color(0xFF7FC8F8), Color(0xFF8ED48A)),
            reduceMotion = reduceMotion
        )
    }
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
    appStyle: AppStyle = AppStyle.Aurora,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark = isDark(themeMode)
    val reduceMotion = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val base = when (appStyle) {
        AppStyle.Aurora -> if (dark) auroraDark() else auroraLight()
        AppStyle.Daybreak -> if (dark) daybreakDark() else daybreakLight()
    }
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        withAccent(base, if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
    } else base
    val pairlyStyle = style(appStyle, scheme, dark, reduceMotion)
    val type = remember(appStyle) { typography(appStyle) }
    CompositionLocalProvider(LocalPairlyStyle provides pairlyStyle) {
        MaterialTheme(colorScheme = scheme, shapes = shapes(appStyle), typography = type, content = content)
    }
}
