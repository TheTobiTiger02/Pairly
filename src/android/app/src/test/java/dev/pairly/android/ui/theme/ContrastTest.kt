package dev.pairly.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG AA: 4.5:1 for text, 3:1 for large text and UI parts, in light and dark mode. */
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
    fun auroraLightMeetsAa() {
        val scheme = auroraLight()
        check("aurora light", scheme, style(AppStyle.Aurora, scheme, dark = false, reduceMotion = false))
    }

    @Test
    fun auroraDarkMeetsAa() {
        val scheme = auroraDark()
        check("aurora dark", scheme, style(AppStyle.Aurora, scheme, dark = true, reduceMotion = false))
    }

    @Test
    fun daybreakLightMeetsAa() {
        val scheme = daybreakLight()
        check("daybreak light", scheme, style(AppStyle.Daybreak, scheme, dark = false, reduceMotion = false))
    }

    @Test
    fun daybreakDarkMeetsAa() {
        val scheme = daybreakDark()
        check("daybreak dark", scheme, style(AppStyle.Daybreak, scheme, dark = true, reduceMotion = false))
    }

    @Test
    fun heroTextMeetsAaOnBothEndsOfEveryGradient() {
        // The hero gradients' ends, white text on top.
        listOf(0xFF4B3FD8, 0xFF7C4DEB, 0xFF3B32B8, 0xFF6A41D6, 0xFFC2462A, 0xFFE8603F, 0xFFB4431F, 0xFFD0583A).forEach {
            val value = ratio(Color.White, Color(it))
            assertTrue("hero %08X is %.2f:1".format(it, value), value >= 3.0)
        }
    }
}
