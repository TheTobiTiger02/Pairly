package dev.pairly.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG AA for every style × accent × light/dark: 4.5:1 for text, 3:1 for status marks. */
class ContrastTest {
    private fun ratio(foreground: Color, background: Color): Double {
        val fg = foreground.compositeOver(background).luminance() + 0.05
        val bg = background.luminance() + 0.05
        return maxOf(fg, bg) / minOf(fg, bg)
    }

    private fun check(name: String, scheme: ColorScheme, style: PairlyStyle): List<String> {
        val background = scheme.background
        val card = style.cardColor.compositeOver(background)
        val nav = style.navContainer.compositeOver(background)
        val pairs = listOf(
            Triple("text on background", scheme.onBackground to background, 4.5),
            Triple("text on card", scheme.onSurface to card, 4.5),
            Triple("secondary text on background", scheme.onSurfaceVariant to background, 4.5),
            Triple("secondary text on card", scheme.onSurfaceVariant to card, 4.5),
            Triple("links on background", scheme.primary to background, 4.5),
            Triple("links on card", scheme.primary to card, 4.5),
            Triple("filled button text", scheme.onPrimary to scheme.primary, 4.5),
            Triple("main button text", style.onButton to style.buttonColor, 4.5),
            Triple("hero text", style.onHero to (style.heroBrush as androidx.compose.ui.graphics.SolidColor).value, 4.5),
            Triple("chip text", scheme.onPrimaryContainer to scheme.primaryContainer, 4.5),
            Triple("icon on tile", style.onIconContainer to style.iconContainer.compositeOver(card), 3.0),
            Triple("active tab", style.onNavActive to style.navActive.compositeOver(nav), 4.5),
            Triple("inactive tab", style.navInactive to nav, 4.5),
            Triple("success status", style.success to card, 3.0),
            Triple("warning icon", style.warning to card, 3.0),
            Triple("error text", scheme.error to card, 4.5)
        )
        return pairs.mapNotNull { (what, colors, minimum) ->
            val value = ratio(colors.first, colors.second)
            if (value >= minimum) null else "$name: $what is %.2f:1, needs %.1f:1".format(value, minimum)
        }
    }

    @Test
    fun everyAccentMeetsAa() {
        val failures = pickableAccents.flatMap { accent ->
            listOf(false, true).flatMap { dark ->
                val scheme = palette(accent, dark)
                check("$accent ${if (dark) "dark" else "light"}", scheme, style(scheme, dark, reduceMotion = false))
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
