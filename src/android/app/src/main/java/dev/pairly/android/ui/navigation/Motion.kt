package dev.pairly.android.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import dev.pairly.android.ui.theme.PairlyStyle

/**
 * Screen changes follow the style. Glass: the new screen fades in and lifts slightly, the old one
 * fades out. Clay: screens slide from the side they lie on with a springy settle and a small
 * squash. "Forward" means to the right: a tab further right, a deeper screen, the next setup step.
 * With "Remove animations" turned on, both become a quick fade.
 */
object Motion {
    fun slideIn(style: PairlyStyle, forward: Boolean): EnterTransition = when {
        style.reduceMotion -> fadeIn(tween(120))
        style.clay -> slideInHorizontally(style.offsetSpring()) { width -> if (forward) width / 4 else -width / 4 } +
            scaleIn(style.spring(), initialScale = 0.94f) + fadeIn()
        else -> slideInVertically(style.offsetSpring()) { height -> height / 28 } + fadeIn(tween(220, delayMillis = 60))
    }

    fun slideOut(style: PairlyStyle, forward: Boolean): ExitTransition = when {
        style.reduceMotion -> fadeOut(tween(120))
        style.clay -> slideOutHorizontally(style.offsetSpring()) { width -> if (forward) -width / 4 else width / 4 } +
            scaleOut(targetScale = 0.96f) + fadeOut()
        else -> fadeOut(tween(140))
    }
}
