package dev.pairly.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG AA: 4.5:1 for text, 3:1 for large text and UI parts, in light mode and every dark candidate. */
class ContrastTest {
    private fun ratio(foreground: Color, background: Color): Double {
        val fg = foreground.compositeOver(background).luminance() + 0.05
        val bg = background.luminance() + 0.05
        return maxOf(fg, bg) / minOf(fg, bg)
    }

    private fun check(name: String, scheme: ColorScheme, style: PairlyStyle) {
        val background = scheme.background
        val card = style.cardColor.compositeOver(background)
        val pairs = listOf(
            Triple("text on background", scheme.onBackground to background, 4.5),
            Triple("text on card", scheme.onSurface to card, 4.5),
            Triple("secondary text on card", scheme.onSurfaceVariant to card, 4.5),
            Triple("links and labels on background", scheme.primary to background, 4.5),
            Triple("links on card", scheme.primary to card, 4.5),
            Triple("button text", scheme.onPrimary to scheme.primary, 4.5),
            Triple("chip text", scheme.onPrimaryContainer to scheme.primaryContainer, 4.5),
            Triple("success status", style.success to card, 3.0),
            Triple("warning icon", style.warning to card, 3.0),
            Triple("error text", scheme.error to card, 4.5)
        )
        for ((what, colors, minimum) in pairs) {
            val value = ratio(colors.first, colors.second)
            assertTrue("$name: $what is %.2f:1, needs %.1f:1".format(value, minimum), value >= minimum)
        }
    }

    @Test
    fun lightMeetsAa() {
        val scheme = glassLight()
        check("light", scheme, style(scheme, dark = false, candidate = GlassDark.Graphite, reduceMotion = false))
    }

    @Test
    fun everyDarkCandidateMeetsAa() {
        GlassDark.entries.forEach { candidate ->
            val scheme = glassDark(candidate)
            check(candidate.name, scheme, style(scheme, dark = true, candidate = candidate, reduceMotion = false))
        }
    }
}
