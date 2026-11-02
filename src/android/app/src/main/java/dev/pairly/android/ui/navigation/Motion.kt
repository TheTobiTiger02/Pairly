package dev.pairly.android.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import dev.pairly.android.ui.theme.PairlyStyle

/**
 * One motion for every screen change: the new screen glides in from the side it lies on (a quarter
 * of the width, with a fade) while the old one slides the other way and settles back slightly.
 * "Forward" means to the right: a tab further right in the bottom bar, a deeper screen, the next
 * setup step. With "Remove animations" turned on, it becomes a quick fade.
 */
object Motion {
    fun slideIn(style: PairlyStyle, forward: Boolean): EnterTransition =
        if (style.reduceMotion) fadeIn(tween(120))
        else slideInHorizontally(style.offsetSpring()) { width -> if (forward) width / 4 else -width / 4 } + fadeIn()

    fun slideOut(style: PairlyStyle, forward: Boolean): ExitTransition =
        if (style.reduceMotion) fadeOut(tween(120))
        else slideOutHorizontally(style.offsetSpring()) { width -> if (forward) -width / 4 else width / 4 } +
            scaleOut(targetScale = 0.98f) + fadeOut()
}
