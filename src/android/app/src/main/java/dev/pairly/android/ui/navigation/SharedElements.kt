package dev.pairly.android.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import dev.pairly.android.ui.theme.Pairly

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Lets an element (a feature's icon, the PC card) fly from one screen to the next. Does nothing
 * outside the navigation host, e.g. in onboarding or screenshot tests, or with animations off.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedElement(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val animated = LocalAnimatedScope.current ?: return this
    if (Pairly.style.reduceMotion) return this
    return with(shared) { this@sharedElement.sharedBounds(rememberSharedContentState(key), animated) }
}
