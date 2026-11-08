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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
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
 * Pairly's look is a style plus an accent color (Settings > Appearance), both following the
 * system's light and dark mode:
 * - Glass: cool tinted ground, translucent white cards with a hairline edge, Manrope, a dark
 *   floating tab pill and calm motion.
 * - Clay: soft pastel fills, big round shapes, Nunito, friendly copy and bouncier motion.
 * The Windows app uses the same names and colors (ThemeManager.cs).
 */
enum class AppStyle { Glass, Clay }

/** System uses the wallpaper colors (Android 12+); older phones fall back to Violet. */
enum class Accent { System, Indigo, Violet, Blue, Teal, Green, Amber, Coral, Pink }

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
    /** Soft drop shadow under cards; 0 for none. */
    val cardShadow: Dp,
    val shadowColor: Color,
    /** Fill for brand moments (onboarding marks, the Clay home card). */
    val heroBrush: Brush,
    val onHero: Color,
    /** Main call to action: Glass fills it with the accent, Clay with the soft tint. */
    val buttonColor: Color,
    val onButton: Color,
    val iconContainer: Color,
    val onIconContainer: Color,
    val navContainer: Color,
    val navBorder: BorderStroke?,
    val navActive: Color,
    val onNavActive: Color,
    val navInactive: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    /** Colors for illustrations (tour scenes). */
    val accents: List<Color>,
    val reduceMotion: Boolean
) {
    val clay: Boolean get() = appStyle == AppStyle.Clay

    fun <T> spring(): FiniteAnimationSpec<T> = when {
        reduceMotion -> tween(0)
        clay -> spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
        else -> spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
    }

    fun offsetSpring(): FiniteAnimationSpec<IntOffset> = spring()
}

val LocalPairlyStyle = staticCompositionLocalOf {
    PairlyStyle(
        appStyle = AppStyle.Glass, dark = false, cardRadius = 22.dp, cardColor = Color.White, cardBorder = null,
        cardShadow = 0.dp, shadowColor = Color.Black, heroBrush = SolidColor(Color.Black), onHero = Color.White,
        buttonColor = Color.Black, onButton = Color.White, iconContainer = Color.LightGray, onIconContainer = Color.Black,
        navContainer = Color.White, navBorder = null, navActive = Color.Black, onNavActive = Color.White, navInactive = Color.Gray,
        success = Color(0xFF1E8E3E), successContainer = Color(0xFFE3F8EE), warning = Color(0xFFB26A00), warningContainer = Color(0xFFFFF0D6),
        accents = listOf(Color.Gray), reduceMotion = false
    )
}

object Pairly {
    val style: PairlyStyle
        @Composable get() = LocalPairlyStyle.current
}

// --- Accent tables (keep in sync with ThemeManager.cs) --------------------------------------------

/** Glass light: the accent and the tinted ground behind everything. Dark: a light tone of the accent. */
private class GlassTones(val accent: Color, val ground: Color, val darkTone: Color)

/** Clay light: soft fill, deep text on it, page background. Dark: a light tone of the accent. */
private class ClayTones(val soft: Color, val deep: Color, val background: Color, val darkTone: Color)

private val glassTones = mapOf(
    Accent.Indigo to GlassTones(Color(0xFF4F46E5), Color(0xFFECEDF6), Color(0xFFA5B4FC)),
    Accent.Violet to GlassTones(Color(0xFF6D3FD9), Color(0xFFEFEDF6), Color(0xFFC4B5FD)),
    Accent.Blue to GlassTones(Color(0xFF1F5BD8), Color(0xFFECEFF6), Color(0xFF93C5FD)),
    Accent.Teal to GlassTones(Color(0xFF0F766E), Color(0xFFEBF2F1), Color(0xFF5EEAD4)),
    Accent.Green to GlassTones(Color(0xFF127536), Color(0xFFEDF2EE), Color(0xFF86EFAC)),
    Accent.Amber to GlassTones(Color(0xFFA14A06), Color(0xFFF4F0EA), Color(0xFFFCD34D)),
    Accent.Coral to GlassTones(Color(0xFFC2410C), Color(0xFFF5EFEC), Color(0xFFFDBA9F)),
    Accent.Pink to GlassTones(Color(0xFFBE185D), Color(0xFFF5EDF1), Color(0xFFF9A8D4))
)

private val clayTones = mapOf(
    Accent.Indigo to ClayTones(Color(0xFFE4E3FF), Color(0xFF2F2C8F), Color(0xFFF7F6FD), Color(0xFFB4B4FF)),
    Accent.Violet to ClayTones(Color(0xFFEDE4FF), Color(0xFF5B21B6), Color(0xFFFAF7FE), Color(0xFFCDB4FF)),
    Accent.Blue to ClayTones(Color(0xFFDCEBFF), Color(0xFF1D4ED8), Color(0xFFF5F9FE), Color(0xFFA7CCFF)),
    Accent.Teal to ClayTones(Color(0xFFD3F5F0), Color(0xFF115E59), Color(0xFFF3FBF9), Color(0xFF8EE8DA)),
    Accent.Green to ClayTones(Color(0xFFDAF5DF), Color(0xFF166534), Color(0xFFF4FAF5), Color(0xFF9EE6A9)),
    Accent.Amber to ClayTones(Color(0xFFFFEFCF), Color(0xFF92400E), Color(0xFFFFFAF1), Color(0xFFFFD27A)),
    Accent.Coral to ClayTones(Color(0xFFFFE3D9), Color(0xFF9A3412), Color(0xFFFFF8F5), Color(0xFFFFB49E)),
    Accent.Pink to ClayTones(Color(0xFFFFE0F0), Color(0xFF9D174D), Color(0xFFFFF7FB), Color(0xFFFFB0D6))
)

/** The accents a user can pick, with the color shown on their swatch. */
val pickableAccents: List<Accent> = Accent.entries.filter { it != Accent.System }

fun swatchColor(style: AppStyle, accent: Accent, dark: Boolean): Color = when (style) {
    AppStyle.Glass -> glassTones.getValue(accent).let { if (dark) it.darkTone else it.accent }
    AppStyle.Clay -> clayTones.getValue(accent).let { if (dark) it.darkTone else it.deep }
}

private val GlassInk = Color(0xFF17161F)
private val GlassInkSoft = Color(0xFF5A5869)
private val GlassNight = Color(0xFF121118)
private val ClayInk = Color(0xFF2A2433)
private val ClayInkSoft = Color(0xFF6A6275)
private val ClayNight = Color(0xFF1D1924)
private val ClayNightCard = Color(0xFF2A2433)

private fun glassTonesFor(accent: Accent, dynamic: ColorScheme?, dark: Boolean): GlassTones =
    if (accent == Accent.System && dynamic != null) {
        GlassTones(dynamic.primary, lerp(Color(0xFFF1F0F5), dynamic.primary, 0.05f), dynamic.primary)
    } else glassTones.getValue(if (accent == Accent.System) Accent.Violet else accent)

private fun clayTonesFor(accent: Accent, dynamic: ColorScheme?, dark: Boolean): ClayTones =
    if (accent == Accent.System && dynamic != null) {
        if (dark) ClayTones(dynamic.primaryContainer, dynamic.onPrimaryContainer, ClayNight, dynamic.primary)
        else ClayTones(dynamic.primaryContainer, dynamic.onPrimaryContainer, lerp(Color.White, dynamic.primaryContainer, 0.25f), dynamic.primary)
    } else clayTones.getValue(if (accent == Accent.System) Accent.Violet else accent)

private val errorLight = Color(0xFFB3261E)
private val errorDark = Color(0xFFFFB4AB)

/**
 * Material color scheme for a style and accent. [dynamic] is the wallpaper scheme in the same
 * light/dark mode, used only for [Accent.System].
 */
internal fun palette(appStyle: AppStyle, accent: Accent, dark: Boolean, dynamic: ColorScheme? = null): ColorScheme = when (appStyle) {
    AppStyle.Glass -> {
        val t = glassTonesFor(accent, dynamic, dark)
        if (!dark) lightColorScheme(
            primary = t.accent, onPrimary = Color.White,
            primaryContainer = lerp(t.accent, Color.White, 0.86f), onPrimaryContainer = lerp(t.accent, Color.Black, 0.45f),
            secondary = GlassInkSoft, onSecondary = Color.White,
            secondaryContainer = lerp(t.ground, GlassInk, 0.07f), onSecondaryContainer = GlassInk,
            tertiary = Color(0xFF157F4C), onTertiary = Color.White,
            tertiaryContainer = Color(0xFFE3F5EA), onTertiaryContainer = Color(0xFF0B3B23),
            background = t.ground, onBackground = GlassInk,
            surface = t.ground, onSurface = GlassInk,
            surfaceVariant = lerp(t.ground, GlassInk, 0.06f), onSurfaceVariant = GlassInkSoft,
            surfaceContainerLowest = Color.White, surfaceContainerLow = lerp(t.ground, Color.White, 0.6f),
            surfaceContainer = lerp(t.ground, Color.White, 0.35f), surfaceContainerHigh = lerp(t.ground, GlassInk, 0.04f),
            surfaceContainerHighest = lerp(t.ground, GlassInk, 0.08f),
            inverseSurface = GlassInk, inverseOnSurface = Color.White,
            outline = Color(0xFF787688), outlineVariant = lerp(t.ground, GlassInk, 0.12f),
            error = errorLight, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
        ) else darkColorScheme(
            primary = t.darkTone, onPrimary = GlassInk,
            primaryContainer = lerp(GlassNight, t.darkTone, 0.22f), onPrimaryContainer = lerp(t.darkTone, Color.White, 0.3f),
            secondary = Color(0xFFC9C7D6), onSecondary = GlassInk,
            secondaryContainer = lerp(GlassNight, Color.White, 0.09f), onSecondaryContainer = Color(0xFFF2F1F7),
            tertiary = Color(0xFF86E0AE), onTertiary = Color(0xFF0B3B23),
            tertiaryContainer = Color(0xFF173C2A), onTertiaryContainer = Color(0xFFC8F2D9),
            background = GlassNight, onBackground = Color(0xFFF2F1F7),
            surface = GlassNight, onSurface = Color(0xFFF2F1F7),
            surfaceVariant = lerp(GlassNight, Color.White, 0.08f), onSurfaceVariant = Color(0xFFADABBC),
            surfaceContainerLowest = Color(0xFF0C0B11), surfaceContainerLow = lerp(GlassNight, Color.White, 0.03f),
            surfaceContainer = lerp(GlassNight, Color.White, 0.05f), surfaceContainerHigh = lerp(GlassNight, Color.White, 0.08f),
            surfaceContainerHighest = lerp(GlassNight, Color.White, 0.11f),
            inverseSurface = Color(0xFFF2F1F7), inverseOnSurface = GlassInk,
            outline = Color(0xFF8E8C9E), outlineVariant = lerp(GlassNight, Color.White, 0.12f),
            error = errorDark, errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
        )
    }
    AppStyle.Clay -> {
        val t = clayTonesFor(accent, dynamic, dark)
        if (!dark) lightColorScheme(
            primary = t.deep, onPrimary = Color.White,
            primaryContainer = t.soft, onPrimaryContainer = t.deep,
            secondary = ClayInkSoft, onSecondary = Color.White,
            secondaryContainer = lerp(t.background, t.soft, 0.6f), onSecondaryContainer = ClayInk,
            tertiary = Color(0xFF166534), onTertiary = Color.White,
            tertiaryContainer = Color(0xFFDAF5DF), onTertiaryContainer = Color(0xFF0E3A1C),
            background = t.background, onBackground = ClayInk,
            surface = t.background, onSurface = ClayInk,
            surfaceVariant = lerp(t.background, ClayInk, 0.06f), onSurfaceVariant = ClayInkSoft,
            surfaceContainerLowest = Color.White, surfaceContainerLow = lerp(t.background, Color.White, 0.6f),
            surfaceContainer = lerp(t.background, t.soft, 0.35f), surfaceContainerHigh = lerp(t.background, t.soft, 0.6f),
            surfaceContainerHighest = t.soft,
            inverseSurface = ClayInk, inverseOnSurface = Color.White,
            outline = Color(0xFF857D90), outlineVariant = lerp(t.background, ClayInk, 0.12f),
            error = errorLight, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
        ) else darkColorScheme(
            primary = t.darkTone, onPrimary = ClayNight,
            primaryContainer = lerp(ClayNightCard, t.darkTone, 0.85f), onPrimaryContainer = ClayNight,
            secondary = Color(0xFFD3CCDC), onSecondary = ClayNight,
            secondaryContainer = lerp(ClayNightCard, Color.White, 0.06f), onSecondaryContainer = Color(0xFFF4F0F8),
            tertiary = Color(0xFF9EE6A9), onTertiary = Color(0xFF0E3A1C),
            tertiaryContainer = Color(0xFF1E4A2A), onTertiaryContainer = Color(0xFFD5F3D3),
            background = ClayNight, onBackground = Color(0xFFF4F0F8),
            surface = ClayNight, onSurface = Color(0xFFF4F0F8),
            surfaceVariant = ClayNightCard, onSurfaceVariant = Color(0xFFBDB5C8),
            surfaceContainerLowest = Color(0xFF16131C), surfaceContainerLow = lerp(ClayNight, ClayNightCard, 0.5f),
            surfaceContainer = ClayNightCard, surfaceContainerHigh = lerp(ClayNightCard, Color.White, 0.05f),
            surfaceContainerHighest = lerp(ClayNightCard, Color.White, 0.09f),
            inverseSurface = Color(0xFFF4F0F8), inverseOnSurface = ClayInk,
            outline = Color(0xFF9A92A6), outlineVariant = lerp(ClayNightCard, Color.White, 0.1f),
            error = errorDark, errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
        )
    }
}

private fun shapes(appStyle: AppStyle) = when (appStyle) {
    AppStyle.Glass -> Shapes(RoundedCornerShape(10.dp), RoundedCornerShape(14.dp), RoundedCornerShape(18.dp), RoundedCornerShape(22.dp), RoundedCornerShape(28.dp))
    AppStyle.Clay -> Shapes(RoundedCornerShape(16.dp), RoundedCornerShape(22.dp), RoundedCornerShape(30.dp), RoundedCornerShape(34.dp), RoundedCornerShape(44.dp))
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(font: Int, weight: Int, as_: FontWeight) =
    Font(font, as_, variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val Manrope = FontFamily(
    variable(R.font.manrope, 400, FontWeight.Normal),
    variable(R.font.manrope, 500, FontWeight.Medium),
    variable(R.font.manrope, 600, FontWeight.SemiBold),
    variable(R.font.manrope, 700, FontWeight.Bold),
    variable(R.font.manrope, 800, FontWeight.ExtraBold)
)

private val Nunito = FontFamily(
    variable(R.font.nunito, 500, FontWeight.Normal),
    variable(R.font.nunito, 600, FontWeight.Medium),
    variable(R.font.nunito, 700, FontWeight.SemiBold),
    variable(R.font.nunito, 800, FontWeight.Bold),
    variable(R.font.nunito, 900, FontWeight.ExtraBold)
)

private fun typography(appStyle: AppStyle): Typography {
    val glass = appStyle == AppStyle.Glass
    val family = if (glass) Manrope else Nunito
    val heading = FontWeight.ExtraBold
    val strong = if (glass) FontWeight.Bold else FontWeight.ExtraBold
    val medium = if (glass) FontWeight.SemiBold else FontWeight.Bold
    val tracking = if (glass) (-0.6).sp else (-0.3).sp
    fun style(size: Int, line: Int, weight: FontWeight, letter: TextUnit = 0.sp) =
        // Hyphenation keeps long German words ("Zwischenablage") from breaking mid-syllable in tight spots.
        TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = letter, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)
    return Typography(
        displayLarge = style(48, 54, heading, tracking),
        displayMedium = style(40, 46, heading, tracking),
        displaySmall = style(34, 40, heading, tracking),
        headlineLarge = style(30, 36, heading, tracking),
        headlineMedium = style(26, 32, heading, tracking),
        headlineSmall = style(22, 28, heading, (-0.2).sp),
        titleLarge = style(20, 26, strong, (-0.1).sp),
        titleMedium = style(16, 22, strong),
        titleSmall = style(14, 20, strong),
        bodyLarge = style(16, 24, FontWeight.Normal),
        bodyMedium = style(14, 20, FontWeight.Normal),
        bodySmall = style(12, 16, if (glass) FontWeight.Medium else FontWeight.Normal),
        labelLarge = style(14, 20, strong),
        labelMedium = style(12, 16, medium, 0.2.sp),
        labelSmall = style(11, 16, medium, 0.3.sp)
    )
}

internal fun style(appStyle: AppStyle, scheme: ColorScheme, dark: Boolean, reduceMotion: Boolean): PairlyStyle {
    val success = if (dark) Color(0xFF86E0AE) else Color(0xFF157F4C)
    val successContainer = if (dark) Color(0xFF173C2A) else Color(0xFFE3F5EA)
    val warning = if (dark) Color(0xFFF2C46B) else Color(0xFF8A5300)
    val warningContainer = if (dark) Color(0xFF4A3510) else Color(0xFFFFF0D6)
    return when (appStyle) {
        AppStyle.Glass -> PairlyStyle(
            appStyle = appStyle, dark = dark, cardRadius = 22.dp,
            // Opaque in light mode so the drop shadow doesn't show through the card.
            cardColor = if (dark) Color.White.copy(alpha = 0.05f) else lerp(scheme.background, Color.White, 0.72f),
            cardBorder = BorderStroke(1.dp, if (dark) Color.White.copy(alpha = 0.09f) else Color.White),
            cardShadow = if (dark) 0.dp else 10.dp,
            shadowColor = GlassInk.copy(alpha = 0.10f),
            heroBrush = SolidColor(scheme.primary), onHero = scheme.onPrimary,
            buttonColor = scheme.primary, onButton = scheme.onPrimary,
            iconContainer = if (dark) Color.White.copy(alpha = 0.07f) else scheme.primaryContainer,
            onIconContainer = if (dark) scheme.primary else scheme.onPrimaryContainer,
            navContainer = if (dark) Color(0xFF1D1C25) else lerp(scheme.background, Color.White, 0.88f),
            navBorder = BorderStroke(1.dp, if (dark) Color.White.copy(alpha = 0.08f) else Color.White),
            navActive = if (dark) scheme.primary else GlassInk,
            onNavActive = if (dark) scheme.onPrimary else Color.White,
            navInactive = scheme.onSurfaceVariant,
            success = success, successContainer = successContainer, warning = warning, warningContainer = warningContainer,
            accents = listOf(scheme.primary, scheme.onSurfaceVariant, success, scheme.primaryContainer),
            reduceMotion = reduceMotion
        )
        AppStyle.Clay -> PairlyStyle(
            appStyle = appStyle, dark = dark, cardRadius = 30.dp,
            cardColor = if (dark) ClayNightCard else Color.White,
            cardBorder = null,
            cardShadow = if (dark) 0.dp else 8.dp,
            shadowColor = ClayInk.copy(alpha = 0.08f),
            heroBrush = SolidColor(scheme.primaryContainer), onHero = scheme.onPrimaryContainer,
            buttonColor = scheme.primaryContainer, onButton = scheme.onPrimaryContainer,
            iconContainer = scheme.primaryContainer, onIconContainer = scheme.onPrimaryContainer,
            navContainer = if (dark) ClayNightCard else Color.White,
            navBorder = null,
            navActive = scheme.primaryContainer, onNavActive = scheme.onPrimaryContainer,
            navInactive = scheme.onSurfaceVariant,
            success = success, successContainer = successContainer, warning = warning, warningContainer = warningContainer,
            accents = listOf(scheme.primary, scheme.primaryContainer, success, warning),
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
    appStyle: AppStyle = AppStyle.Glass,
    accent: Accent = Accent.Violet,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark = isDark(themeMode)
    val reduceMotion = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val dynamic = if (accent == Accent.System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else null
    val scheme = palette(appStyle, accent, dark, dynamic)
    val pairlyStyle = style(appStyle, scheme, dark, reduceMotion)
    val type = remember(appStyle) { typography(appStyle) }
    CompositionLocalProvider(LocalPairlyStyle provides pairlyStyle) {
        MaterialTheme(colorScheme = scheme, shapes = shapes(appStyle), typography = type, content = content)
    }
}
