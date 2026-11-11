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
 * Pairly's look is a style plus an accent color (Settings > Appearance), both following the
 * system's light and dark mode:
 * - Glass: neutral and flat. White or graphite panes with hairline edges, no shadows, Manrope,
 *   small radii; the accent only marks actions and state. Calm motion.
 * - Clay: warm and colorful. Cream or warm charcoal ground, cards filled with the accent's soft
 *   tone, colored shadows, heavy Nunito, big round shapes and bouncier motion.
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

/** Icon and text colour on a [PairlyStyle.success] fill: dark on the light green of dark mode. */
val PairlyStyle.onSuccess: Color get() = if (success.luminance() > 0.5f) Color(0xFF0B2A1A) else Color.White

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

/** Glass: the accent on neutral light grounds, and a light tone of it for graphite dark. */
private class GlassTones(val accent: Color, val darkTone: Color)

/** Clay light: soft fill for cards, deep tone for buttons and text on it. Dark: a pastel tone. */
private class ClayTones(val soft: Color, val deep: Color, val darkTone: Color)

private val glassTones = mapOf(
    Accent.Indigo to GlassTones(Color(0xFF4F46E5), Color(0xFFA5B4FC)),
    Accent.Violet to GlassTones(Color(0xFF6D3FD9), Color(0xFFC4B5FD)),
    Accent.Blue to GlassTones(Color(0xFF1F5BD8), Color(0xFF93C5FD)),
    Accent.Teal to GlassTones(Color(0xFF0F766E), Color(0xFF5EEAD4)),
    Accent.Green to GlassTones(Color(0xFF127536), Color(0xFF86EFAC)),
    Accent.Amber to GlassTones(Color(0xFFA14A06), Color(0xFFFCD34D)),
    Accent.Coral to GlassTones(Color(0xFFC2410C), Color(0xFFFDBA9F)),
    Accent.Pink to GlassTones(Color(0xFFBE185D), Color(0xFFF9A8D4))
)

private val clayTones = mapOf(
    Accent.Indigo to ClayTones(Color(0xFFE2E1FF), Color(0xFF3730A3), Color(0xFFB4B4FF)),
    Accent.Violet to ClayTones(Color(0xFFEDE3FF), Color(0xFF5B21B6), Color(0xFFCDB4FF)),
    Accent.Blue to ClayTones(Color(0xFFD9E9FF), Color(0xFF1D4ED8), Color(0xFFA7CCFF)),
    Accent.Teal to ClayTones(Color(0xFFCFF3EC), Color(0xFF115E59), Color(0xFF8EE8DA)),
    Accent.Green to ClayTones(Color(0xFFD6F3DA), Color(0xFF166534), Color(0xFF9EE6A9)),
    Accent.Amber to ClayTones(Color(0xFFFFEBC4), Color(0xFF92400E), Color(0xFFFFD27A)),
    Accent.Coral to ClayTones(Color(0xFFFFE0D3), Color(0xFF9A3412), Color(0xFFFFB49E)),
    Accent.Pink to ClayTones(Color(0xFFFFDDEE), Color(0xFF9D174D), Color(0xFFFFB0D6))
)

/** The accents a user can pick, with the color shown on their swatch. */
val pickableAccents: List<Accent> = Accent.entries.filter { it != Accent.System }

fun swatchColor(style: AppStyle, accent: Accent, dark: Boolean): Color = when (style) {
    AppStyle.Glass -> glassTones.getValue(accent).let { if (dark) it.darkTone else it.accent }
    AppStyle.Clay -> clayTones.getValue(accent).let { if (dark) it.darkTone else it.deep }
}

// Glass: neutral, no tint. Light is near-white with white cards; dark is graphite.
private val GlassPaper = Color(0xFFF6F6F7)
private val GlassHairline = Color(0xFFE6E6E9)
private val GlassInk = Color(0xFF18181B)
private val GlassInkSoft = Color(0xFF52525B)
private val GraphiteNight = Color(0xFF0E0E10)
private val GraphiteCard = Color(0xFF17171A)
private val GraphiteElevated = Color(0xFF1F1F23)
private val GraphiteText = Color(0xFFEDEDEF)
private val GraphiteTextSoft = Color(0xFFA1A1AA)

// Clay: warm. Cream and warm ink in light, warm charcoal in dark.
private val ClayCream = Color(0xFFFBF7F1)
private val ClayInk = Color(0xFF2B2520)
private val ClayInkSoft = Color(0xFF675C52)
private val ClayCharcoal = Color(0xFF1A1714)
private val ClayCharcoalCard = Color(0xFF26221E)
private val ClayCharcoalText = Color(0xFFF5EFE8)
private val ClayCharcoalTextSoft = Color(0xFFC2B8AC)

private fun glassTonesFor(accent: Accent, dynamic: ColorScheme?): GlassTones =
    if (accent == Accent.System && dynamic != null) GlassTones(dynamic.primary, dynamic.primary)
    else glassTones.getValue(if (accent == Accent.System) Accent.Violet else accent)

private fun clayTonesFor(accent: Accent, dynamic: ColorScheme?): ClayTones =
    if (accent == Accent.System && dynamic != null) {
        ClayTones(dynamic.primaryContainer, if (dynamic.primary.luminance() < 0.3f) dynamic.primary else dynamic.onPrimaryContainer, dynamic.primary)
    } else clayTones.getValue(if (accent == Accent.System) Accent.Violet else accent)

private val errorLight = Color(0xFFB3261E)
private val errorDark = Color(0xFFFFB4AB)

/** Clay's card in dark mode: warm charcoal with a touch of the accent. */
private fun clayNightCard(tone: Color) = lerp(ClayCharcoalCard, tone, 0.10f)

/**
 * Material color scheme for a style and accent. [dynamic] is the wallpaper scheme in the same
 * light/dark mode, used only for [Accent.System].
 */
internal fun palette(appStyle: AppStyle, accent: Accent, dark: Boolean, dynamic: ColorScheme? = null): ColorScheme = when (appStyle) {
    AppStyle.Glass -> {
        val t = glassTonesFor(accent, dynamic)
        if (!dark) lightColorScheme(
            primary = t.accent, onPrimary = Color.White,
            primaryContainer = lerp(t.accent, Color.White, 0.88f), onPrimaryContainer = lerp(t.accent, Color.Black, 0.45f),
            secondary = GlassInkSoft, onSecondary = Color.White,
            secondaryContainer = Color(0xFFEDEDEF), onSecondaryContainer = GlassInk,
            tertiary = Color(0xFF157F4C), onTertiary = Color.White,
            tertiaryContainer = Color(0xFFE3F5EA), onTertiaryContainer = Color(0xFF0B3B23),
            background = GlassPaper, onBackground = GlassInk,
            surface = GlassPaper, onSurface = GlassInk,
            surfaceVariant = Color(0xFFEDEDEF), onSurfaceVariant = GlassInkSoft,
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFBFC),
            surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEFEFF1),
            surfaceContainerHighest = Color(0xFFE8E8EB),
            inverseSurface = GlassInk, inverseOnSurface = Color.White,
            outline = Color(0xFF71717A), outlineVariant = GlassHairline,
            error = errorLight, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
        ) else darkColorScheme(
            primary = t.darkTone, onPrimary = GlassInk,
            primaryContainer = lerp(GraphiteCard, t.darkTone, 0.16f), onPrimaryContainer = lerp(t.darkTone, Color.White, 0.4f),
            secondary = Color(0xFFD4D4D8), onSecondary = GlassInk,
            secondaryContainer = GraphiteElevated, onSecondaryContainer = GraphiteText,
            tertiary = Color(0xFF86E0AE), onTertiary = Color(0xFF0B3B23),
            tertiaryContainer = Color(0xFF153325), onTertiaryContainer = Color(0xFFC8F2D9),
            background = GraphiteNight, onBackground = GraphiteText,
            surface = GraphiteNight, onSurface = GraphiteText,
            surfaceVariant = GraphiteElevated, onSurfaceVariant = GraphiteTextSoft,
            surfaceContainerLowest = Color(0xFF09090B), surfaceContainerLow = Color(0xFF131316),
            surfaceContainer = GraphiteCard, surfaceContainerHigh = GraphiteElevated,
            surfaceContainerHighest = Color(0xFF27272A),
            inverseSurface = GraphiteText, inverseOnSurface = GlassInk,
            outline = Color(0xFF71717A), outlineVariant = Color(0xFF27272A),
            error = errorDark, errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
        )
    }
    AppStyle.Clay -> {
        val t = clayTonesFor(accent, dynamic)
        if (!dark) {
            val ground = lerp(ClayCream, t.soft, 0.18f)
            lightColorScheme(
                primary = t.deep, onPrimary = Color.White,
                // A step deeper than the card fill so chips and tiles still show on a soft card.
                primaryContainer = lerp(t.soft, t.deep, 0.12f), onPrimaryContainer = t.deep,
                secondary = ClayInkSoft, onSecondary = Color.White,
                secondaryContainer = lerp(ground, t.soft, 0.6f), onSecondaryContainer = ClayInk,
                tertiary = Color(0xFF166534), onTertiary = Color.White,
                tertiaryContainer = Color(0xFFDAF5DF), onTertiaryContainer = Color(0xFF0E3A1C),
                background = ground, onBackground = ClayInk,
                surface = ground, onSurface = ClayInk,
                surfaceVariant = lerp(ground, t.soft, 0.7f), onSurfaceVariant = ClayInkSoft,
                surfaceContainerLowest = Color.White, surfaceContainerLow = lerp(ground, t.soft, 0.3f),
                surfaceContainer = lerp(ground, t.soft, 0.55f), surfaceContainerHigh = t.soft,
                surfaceContainerHighest = lerp(t.soft, t.deep, 0.08f),
                inverseSurface = ClayInk, inverseOnSurface = Color.White,
                outline = Color(0xFF8A7E73), outlineVariant = lerp(t.soft, ClayInk, 0.12f),
                error = errorLight, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
            )
        } else {
            val card = clayNightCard(t.darkTone)
            darkColorScheme(
                primary = t.darkTone, onPrimary = ClayCharcoal,
                primaryContainer = lerp(card, t.darkTone, 0.85f), onPrimaryContainer = ClayCharcoal,
                secondary = Color(0xFFDDD3C7), onSecondary = ClayCharcoal,
                secondaryContainer = lerp(card, Color.White, 0.06f), onSecondaryContainer = ClayCharcoalText,
                tertiary = Color(0xFF9EE6A9), onTertiary = Color(0xFF0E3A1C),
                tertiaryContainer = Color(0xFF1E4A2A), onTertiaryContainer = Color(0xFFD5F3D3),
                background = ClayCharcoal, onBackground = ClayCharcoalText,
                surface = ClayCharcoal, onSurface = ClayCharcoalText,
                surfaceVariant = card, onSurfaceVariant = ClayCharcoalTextSoft,
                surfaceContainerLowest = Color(0xFF141210), surfaceContainerLow = lerp(ClayCharcoal, card, 0.5f),
                surfaceContainer = card, surfaceContainerHigh = lerp(card, t.darkTone, 0.08f),
                surfaceContainerHighest = lerp(card, t.darkTone, 0.14f),
                inverseSurface = ClayCharcoalText, inverseOnSurface = ClayInk,
                outline = Color(0xFF9C9083), outlineVariant = lerp(card, Color.White, 0.1f),
                error = errorDark, errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
            )
        }
    }
}

private fun shapes(appStyle: AppStyle) = when (appStyle) {
    AppStyle.Glass -> Shapes(RoundedCornerShape(6.dp), RoundedCornerShape(10.dp), RoundedCornerShape(14.dp), RoundedCornerShape(16.dp), RoundedCornerShape(20.dp))
    AppStyle.Clay -> Shapes(RoundedCornerShape(18.dp), RoundedCornerShape(22.dp), RoundedCornerShape(28.dp), RoundedCornerShape(32.dp), RoundedCornerShape(40.dp))
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
    variable(R.font.nunito, 900, FontWeight.ExtraBold),
    variable(R.font.nunito, 1000, FontWeight.Black)
)

private fun typography(appStyle: AppStyle): Typography {
    val glass = appStyle == AppStyle.Glass
    val family = if (glass) Manrope else Nunito
    // Glass: crisp and quiet, bold headings with tight tracking. Clay: heavy, round and a size bigger.
    val heading = if (glass) FontWeight.Bold else FontWeight.Black
    val strong = if (glass) FontWeight.SemiBold else FontWeight.ExtraBold
    val medium = if (glass) FontWeight.Medium else FontWeight.Bold
    val tracking = if (glass) (-0.4).sp else (-0.2).sp
    val up = if (glass) 0 else 2
    fun style(size: Int, line: Int, weight: FontWeight, letter: TextUnit = 0.sp) =
        // Hyphenation keeps long German words ("Zwischenablage") from breaking mid-syllable in tight spots.
        TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = letter, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)
    return Typography(
        displayLarge = style(48 + up, 54 + up, heading, tracking),
        displayMedium = style(40 + up, 46 + up, heading, tracking),
        displaySmall = style(34 + up, 40 + up, heading, tracking),
        headlineLarge = style(30 + up, 36 + up, heading, tracking),
        headlineMedium = style(26 + up, 32 + up, heading, tracking),
        headlineSmall = style(22 + up, 28 + up, heading, (-0.2).sp),
        titleLarge = style(20, 26, strong, (-0.1).sp),
        titleMedium = style(16, 22, strong),
        titleSmall = style(14, 20, strong),
        bodyLarge = style(16, 24, FontWeight.Normal),
        bodyMedium = style(14, 20, FontWeight.Normal),
        bodySmall = style(12, 16, if (glass) FontWeight.Normal else FontWeight.Medium),
        labelLarge = style(14, 20, strong),
        labelMedium = style(12, 16, medium, 0.2.sp),
        labelSmall = style(11, 16, medium, 0.3.sp)
    )
}

internal fun style(appStyle: AppStyle, scheme: ColorScheme, dark: Boolean, reduceMotion: Boolean): PairlyStyle {
    val success = if (dark) Color(0xFF86E0AE) else Color(0xFF157F4C)
    val successContainer = if (dark) Color(0xFF153325) else Color(0xFFE3F5EA)
    val warning = if (dark) Color(0xFFF2C46B) else Color(0xFF8A5300)
    val warningContainer = if (dark) Color(0xFF3D2C0E) else Color(0xFFFFF0D6)
    return when (appStyle) {
        // Flat panes with hairline edges and no shadows; the accent only marks actions and state.
        AppStyle.Glass -> PairlyStyle(
            appStyle = appStyle, dark = dark, cardRadius = 14.dp,
            cardColor = if (dark) GraphiteCard else Color.White,
            cardBorder = BorderStroke(1.dp, if (dark) Color.White.copy(alpha = 0.07f) else GlassHairline),
            cardShadow = 0.dp,
            shadowColor = Color.Transparent,
            heroBrush = SolidColor(scheme.primary), onHero = scheme.onPrimary,
            buttonColor = scheme.primary, onButton = scheme.onPrimary,
            iconContainer = if (dark) Color.White.copy(alpha = 0.06f) else scheme.primaryContainer,
            onIconContainer = if (dark) scheme.primary else scheme.onPrimaryContainer,
            navContainer = if (dark) GraphiteCard else Color.White,
            navBorder = BorderStroke(1.dp, if (dark) Color.White.copy(alpha = 0.08f) else GlassHairline),
            navActive = if (dark) scheme.primary else GlassInk,
            onNavActive = if (dark) scheme.onPrimary else Color.White,
            navInactive = scheme.onSurfaceVariant,
            success = success, successContainer = successContainer, warning = warning, warningContainer = warningContainer,
            accents = listOf(scheme.primary, scheme.onSurfaceVariant, success, scheme.primaryContainer),
            reduceMotion = reduceMotion
        )
        // Cards filled with the accent's soft tone, colored shadows, big deep-toned buttons.
        AppStyle.Clay -> PairlyStyle(
            appStyle = appStyle, dark = dark, cardRadius = 28.dp,
            cardColor = if (dark) scheme.surfaceContainer else scheme.surfaceContainerHigh,
            cardBorder = null,
            cardShadow = if (dark) 0.dp else 12.dp,
            shadowColor = scheme.primary.copy(alpha = 0.22f),
            heroBrush = SolidColor(if (dark) scheme.primaryContainer else scheme.primary),
            onHero = if (dark) scheme.onPrimaryContainer else scheme.onPrimary,
            buttonColor = if (dark) scheme.primaryContainer else scheme.primary,
            onButton = if (dark) scheme.onPrimaryContainer else scheme.onPrimary,
            iconContainer = if (dark) scheme.primary.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.75f),
            onIconContainer = scheme.primary,
            navContainer = scheme.surfaceContainerHigh,
            navBorder = null,
            navActive = if (dark) scheme.primaryContainer else scheme.primary,
            onNavActive = if (dark) scheme.onPrimaryContainer else scheme.onPrimary,
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
