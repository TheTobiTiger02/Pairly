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
import androidx.compose.ui.graphics.luminance
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
 * Pairly's look: one design in light or dark, with an accent color (Settings > Look). Neutral
 * grounds, white or graphite cards with hairline edges, no shadows; the accent only marks actions,
 * selection and state. Dark mode is dark gray rather than black. Titles in Bricolage Grotesque,
 * text in Manrope.
 * The Windows app uses the same names and colors (ThemeManager.cs).
 */

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
    val button = 48.dp
}

/** Details Material's theme has no slot for. */
@Immutable
data class PairlyStyle(
    val dark: Boolean,
    val cardRadius: Dp,
    val cardColor: Color,
    val cardBorder: BorderStroke?,
    /** Fill for the link card and brand marks. */
    val heroBrush: Brush,
    val onHero: Color,
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
    /** Things move into place quickly and settle without overshoot. */
    fun <T> spring(): FiniteAnimationSpec<T> =
        if (reduceMotion) tween(0) else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    fun offsetSpring(): FiniteAnimationSpec<IntOffset> = spring()
}

/** Icon and text colour on a [PairlyStyle.success] fill: dark on the light green of dark mode. */
val PairlyStyle.onSuccess: Color get() = if (success.luminance() > 0.5f) Color(0xFF0B2A1A) else Color.White

val LocalPairlyStyle = staticCompositionLocalOf {
    PairlyStyle(
        dark = false, cardRadius = 14.dp, cardColor = Color.White, cardBorder = null,
        heroBrush = SolidColor(Color.Black), onHero = Color.White,
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

// --- Accent table (keep in sync with ThemeManager.cs) ---------------------------------------------

/** The accent on light grounds, and a light tone of it for dark grounds. */
private class Tones(val light: Color, val dark: Color)

private val tones = mapOf(
    Accent.Indigo to Tones(Color(0xFF4F46E5), Color(0xFFA5B4FC)),
    Accent.Violet to Tones(Color(0xFF6D3FD9), Color(0xFFC4B5FD)),
    Accent.Blue to Tones(Color(0xFF1F5BD8), Color(0xFF93C5FD)),
    Accent.Teal to Tones(Color(0xFF0F766E), Color(0xFF5EEAD4)),
    Accent.Green to Tones(Color(0xFF127536), Color(0xFF86EFAC)),
    Accent.Amber to Tones(Color(0xFFA14A06), Color(0xFFFCD34D)),
    Accent.Coral to Tones(Color(0xFFB03808), Color(0xFFFDBA9F)),
    Accent.Pink to Tones(Color(0xFFBE185D), Color(0xFFF9A8D4))
)

/** The accents a user can pick, with the color shown on their swatch. */
val pickableAccents: List<Accent> = Accent.entries.filter { it != Accent.System }

fun swatchColor(accent: Accent, dark: Boolean): Color = tones.getValue(accent).let { if (dark) it.dark else it.light }

// Neutral grounds: the page, cards on it, hairlines and ink.
private val Paper = Color(0xFFF4F5F7)
private val Hairline = Color(0xFFDFE1E6)
private val Ink = Color(0xFF17181B)
private val InkSoft = Color(0xFF555962)
// Dark gray, not black: the ground, cards and raised surfaces are three clearly separate steps.
private val Night = Color(0xFF1A1B1F)
private val NightCard = Color(0xFF26282D)
private val NightElevated = Color(0xFF2D2F35)
private val NightHairline = Color(0xFF383A41)
private val NightText = Color(0xFFEDEDF0)
private val NightTextSoft = Color(0xFFA6A9B2)
private val DarkInk = Color(0xFF14141A)

/**
 * What buttons, the selected tab and switches are filled with, and the color on top. Dark mode
 * uses the deeper light-mode tone under white, so no pale surface sits on the dark ground; the
 * pale tone ([ColorScheme.primary]) stays for text, icons and marks.
 */
internal fun accentFill(accent: Accent, dark: Boolean, scheme: ColorScheme): Pair<Color, Color> =
    if (dark && accent != Accent.System) tones.getValue(accent).light to Color.White else scheme.primary to scheme.onPrimary

private val errorLight = Color(0xFFB3261E)
private val errorDark = Color(0xFFFFB4AB)

/**
 * Material color scheme for an accent. [dynamic] is the wallpaper scheme in the same light/dark
 * mode, used only for [Accent.System].
 */
internal fun palette(accent: Accent, dark: Boolean, dynamic: ColorScheme? = null): ColorScheme {
    val tone = if (accent == Accent.System && dynamic != null) dynamic.primary
    else tones.getValue(if (accent == Accent.System) Accent.Violet else accent).let { if (dark) it.dark else it.light }
    return if (!dark) lightColorScheme(
        primary = tone, onPrimary = Color.White,
        primaryContainer = lerp(tone, Color.White, 0.88f), onPrimaryContainer = lerp(tone, Color.Black, 0.45f),
        secondary = InkSoft, onSecondary = Color.White,
        secondaryContainer = Color(0xFFE9EBEF), onSecondaryContainer = Ink,
        tertiary = Color(0xFF157F4C), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE3F5EA), onTertiaryContainer = Color(0xFF0B3B23),
        background = Paper, onBackground = Ink,
        surface = Paper, onSurface = Ink,
        surfaceVariant = Color(0xFFE9EBEF), onSurfaceVariant = InkSoft,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFBFC),
        surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFECEEF1),
        surfaceContainerHighest = Color(0xFFE4E6EA),
        inverseSurface = Ink, inverseOnSurface = Color.White,
        outline = Color(0xFF666A74), outlineVariant = Hairline,
        error = errorLight, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
    ) else darkColorScheme(
        primary = tone, onPrimary = DarkInk,
        primaryContainer = lerp(NightCard, tone, 0.16f), onPrimaryContainer = lerp(tone, Color.White, 0.4f),
        secondary = Color(0xFFD4D6DC), onSecondary = DarkInk,
        secondaryContainer = NightElevated, onSecondaryContainer = NightText,
        tertiary = Color(0xFF86E0AE), onTertiary = Color(0xFF0B3B23),
        tertiaryContainer = Color(0xFF153325), onTertiaryContainer = Color(0xFFC8F2D9),
        background = Night, onBackground = NightText,
        surface = Night, onSurface = NightText,
        surfaceVariant = NightElevated, onSurfaceVariant = NightTextSoft,
        surfaceContainerLowest = Color(0xFF151619), surfaceContainerLow = Color(0xFF1F2024),
        surfaceContainer = NightCard, surfaceContainerHigh = NightElevated,
        surfaceContainerHighest = Color(0xFF34363D),
        inverseSurface = NightText, inverseOnSurface = Ink,
        outline = Color(0xFF8B8E97), outlineVariant = NightHairline,
        error = errorDark, errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
    )
}

// Three corner sizes: controls, cards, sheets.
private val PairlyShapes = Shapes(RoundedCornerShape(6.dp), RoundedCornerShape(10.dp), RoundedCornerShape(14.dp), RoundedCornerShape(14.dp), RoundedCornerShape(20.dp))

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

private val Bricolage = FontFamily(
    variable(R.font.bricolage_grotesque, 700, FontWeight.Bold),
    variable(R.font.bricolage_grotesque, 800, FontWeight.ExtraBold)
)

/** Titles in the display face, a size smaller than Material's defaults; everything else in Manrope. */
private val PairlyTypography: Typography = run {
    fun style(family: FontFamily, size: Int, line: Int, weight: FontWeight, letter: TextUnit = 0.sp) =
        // Hyphenation keeps long German words ("Zwischenablage") from breaking mid-syllable in tight spots.
        TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = letter, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)
    fun title(size: Int, line: Int) = style(Bricolage, size, line, FontWeight.Bold, (-0.3).sp)
    fun text(size: Int, line: Int, weight: FontWeight, letter: TextUnit = 0.sp) = style(Manrope, size, line, weight, letter)
    Typography(
        displayLarge = title(36, 42),
        displayMedium = title(30, 36),
        displaySmall = title(26, 32),
        headlineLarge = title(24, 30),
        headlineMedium = title(22, 28),
        headlineSmall = title(20, 26),
        titleLarge = title(18, 24),
        titleMedium = text(15, 20, FontWeight.SemiBold),
        titleSmall = text(14, 20, FontWeight.SemiBold),
        bodyLarge = text(15, 22, FontWeight.Normal),
        bodyMedium = text(14, 20, FontWeight.Normal),
        bodySmall = text(12, 16, FontWeight.Normal),
        labelLarge = text(14, 20, FontWeight.SemiBold),
        labelMedium = text(12, 16, FontWeight.Medium, 0.2.sp),
        labelSmall = text(11, 16, FontWeight.Bold, 0.4.sp)
    )
}

internal fun style(scheme: ColorScheme, dark: Boolean, reduceMotion: Boolean, accent: Accent = Accent.Violet): PairlyStyle {
    val (fill, onFill) = accentFill(accent, dark, scheme)
    val success = if (dark) Color(0xFF86E0AE) else Color(0xFF157F4C)
    val successContainer = if (dark) Color(0xFF153325) else Color(0xFFE3F5EA)
    val warning = if (dark) Color(0xFFF2C46B) else Color(0xFF8A5300)
    val warningContainer = if (dark) Color(0xFF3D2C0E) else Color(0xFFFFF0D6)
    val hairline = BorderStroke(1.dp, if (dark) NightHairline else Hairline)
    return PairlyStyle(
        dark = dark, cardRadius = 14.dp,
        cardColor = if (dark) NightCard else Color.White,
        cardBorder = hairline,
        heroBrush = SolidColor(fill), onHero = onFill,
        buttonColor = fill, onButton = onFill,
        iconContainer = scheme.primaryContainer,
        onIconContainer = if (dark) scheme.primary else scheme.onPrimaryContainer,
        navContainer = if (dark) NightCard else Color.White,
        navBorder = hairline,
        navActive = fill, onNavActive = onFill,
        navInactive = scheme.onSurfaceVariant,
        success = success, successContainer = successContainer, warning = warning, warningContainer = warningContainer,
        accents = listOf(scheme.primary, scheme.onSurfaceVariant, success, scheme.primaryContainer),
        reduceMotion = reduceMotion
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
    accent: Accent = Accent.Violet,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark = isDark(themeMode)
    val reduceMotion = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    // Built once per look, so a connection or permission update does not hand every screen new colors.
    val scheme = remember(accent, dark, context) {
        val dynamic = if (accent == Accent.System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null
        palette(accent, dark, dynamic)
    }
    val pairlyStyle = remember(scheme, dark, reduceMotion, accent) { style(scheme, dark, reduceMotion, accent) }
    CompositionLocalProvider(LocalPairlyStyle provides pairlyStyle) {
        MaterialTheme(colorScheme = scheme, shapes = PairlyShapes, typography = PairlyTypography, content = content)
    }
}
