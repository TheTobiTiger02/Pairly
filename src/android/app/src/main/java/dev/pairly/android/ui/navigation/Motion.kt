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
 * Screen changes: the new screen fades in and rises a little, the old one fades out. With "Remove
 * animations" turned on, both become a quick fade.
 */
object Motion {
    fun slideIn(style: PairlyStyle, @Suppress("UNUSED_PARAMETER") forward: Boolean): EnterTransition =
        if (style.reduceMotion) fadeIn(tween(120))
        else slideInVertically(style.offsetSpring()) { height -> height / 28 } + fadeIn(tween(220, delayMillis = 60))

    fun slideOut(style: PairlyStyle, @Suppress("UNUSED_PARAMETER") forward: Boolean): ExitTransition =
        fadeOut(tween(if (style.reduceMotion) 120 else 140))
}
