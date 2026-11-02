package dev.pairly.android.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import dev.pairly.android.ui.theme.PairlyStyle

/**
 * The app's two motions, used everywhere so every screen change feels the same:
 * - fade-through between peers (bottom-bar tabs, onboarding ↔ app),
 * - a short horizontal slide for going deeper or back (detail screens, setup steps, pairing).
 * Tweens rather than springs so Android's predictive back gesture can scrub them.
 * With "Remove animations" turned on, both become a quick fade.
 */
object Motion {
    private const val DURATION = 300
    private const val FADE_OUT = 90

    fun fadeThroughIn(style: PairlyStyle): EnterTransition =
        if (style.reduceMotion) fadeIn(tween(120))
        else fadeIn(tween(210, delayMillis = FADE_OUT, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(210, delayMillis = FADE_OUT, easing = LinearOutSlowInEasing), initialScale = 0.96f)

    fun fadeThroughOut(style: PairlyStyle): ExitTransition =
        if (style.reduceMotion) fadeOut(tween(120)) else fadeOut(tween(FADE_OUT, easing = FastOutSlowInEasing))

    /** forward = going deeper (the new screen comes from the end side). */
    fun slideIn(style: PairlyStyle, forward: Boolean): EnterTransition =
        if (style.reduceMotion) fadeIn(tween(120))
        else slideInHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { width -> if (forward) width / 5 else -width / 5 } +
            fadeIn(tween(DURATION, easing = LinearOutSlowInEasing))

    fun slideOut(style: PairlyStyle, forward: Boolean): ExitTransition =
        if (style.reduceMotion) fadeOut(tween(120))
        else slideOutHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { width -> if (forward) -width / 5 else width / 5 } +
            fadeOut(tween(DURATION / 2, easing = FastOutSlowInEasing))
}
