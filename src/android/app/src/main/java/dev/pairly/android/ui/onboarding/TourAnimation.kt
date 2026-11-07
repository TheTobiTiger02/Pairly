package dev.pairly.android.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pairly.android.ui.theme.Pairly
import kotlin.math.PI
import kotlin.math.sin

/** What a tour page's little animation shows. */
enum class TourScene {
    /** Something leaves the phone and lands on the PC (notifications, photos). */
    PhoneToPc,
    /** Back and forth between phone and PC (clipboard). */
    BothWays,
    /** The phone rings, the PC shows it (calls). */
    Ring,
    /** The phone's screen inside the PC, with taps (mirroring). */
    Mirror,
    /** A badge with calm pulses (privacy, Power mode). */
    Shield
}

/**
 * A small looping scene drawn from shapes and icons in the current style's colors, so it works in
 * both styles and in light and dark without bitmap art. Still when [playing] is false (an off-screen
 * pager page) or when the system asks for less motion.
 */
@Composable
fun TourAnimation(icon: ImageVector, scene: TourScene, playing: Boolean, modifier: Modifier = Modifier) {
    val style = Pairly.style
    val animate = playing && !style.reduceMotion
    val transition = rememberInfiniteTransition(label = "tour")
    val loop by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (scene == TourScene.Shield) 2_400 else 2_600, easing = LinearEasing), RepeatMode.Restart),
        label = "loop"
    )
    val t = if (animate) loop else 0.5f

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val span = minOf(maxWidth, 340.dp)
        when (scene) {
            TourScene.Shield -> Pulses(icon, t)
            TourScene.Ring -> {
                Box(Modifier.width(span), contentAlignment = Alignment.Center) {
                    Box(Modifier.align(Alignment.CenterStart)) { Rings(t, 64.dp) { Phone(Modifier.graphicsLayer { rotationZ = if (animate) sin(t * 2 * PI * 6).toFloat() * 6f * (1f - t) else 0f }) } }
                    Box(Modifier.align(Alignment.CenterEnd)) { Pc { Token(icon, Modifier.graphicsLayer { val v = ((t - 0.15f) * 4f).coerceIn(0f, 1f); scaleX = v; scaleY = v; alpha = v }) } }
                }
            }
            TourScene.Mirror -> Pc(width = 180.dp) {
                Box(Modifier.size(44.dp, 76.dp).clip(RoundedCornerShape(8.dp)).background(style.heroBrush)) {
                    val tap = (t * 2f) % 1f
                    Box(
                        Modifier.offset(14.dp, 30.dp).size(16.dp).graphicsLayer { scaleX = 0.5f + tap; scaleY = 0.5f + tap; alpha = 1f - tap }
                            .clip(CircleShape).background(style.onHero)
                    )
                }
            }
            TourScene.PhoneToPc, TourScene.BothWays -> Box(Modifier.width(span), contentAlignment = Alignment.Center) {
                Phone(Modifier.align(Alignment.CenterStart))
                Pc(Modifier.align(Alignment.CenterEnd))
                // The token travels on an arc; BothWays sends it back on every other loop half.
                val forward = scene == TourScene.PhoneToPc || t < 0.5f
                val leg = if (scene == TourScene.PhoneToPc) t else (t * 2f) % 1f
                // Fly for the first 70% of a leg, then rest a moment on arrival.
                val p = FastOutSlowInEasing.transform((leg / 0.7f).coerceIn(0f, 1f))
                val x = if (forward) p else 1f - p
                val travel = span - 96.dp
                val fade = when {
                    !animate -> 1f
                    leg < 0.1f -> leg / 0.1f
                    leg > 0.9f -> (1f - leg) / 0.1f
                    else -> 1f
                }
                Token(
                    icon,
                    Modifier.align(Alignment.CenterStart).offset(x = 26.dp + travel * x, y = (-56).dp * sin(PI * x).toFloat() - 40.dp * x)
                        .graphicsLayer { alpha = fade; val pop = if (p >= 1f) 1.1f else 1f; scaleX = pop; scaleY = pop }
                )
            }
        }
    }
}

@Composable
private fun Phone(modifier: Modifier = Modifier) {
    val style = Pairly.style
    Box(
        modifier.size(64.dp, 112.dp).clip(RoundedCornerShape(16.dp)).background(style.cardColor)
            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Rounded.Smartphone, contentDescription = null, tint = style.onIconContainer, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun Pc(modifier: Modifier = Modifier, width: Dp = 120.dp, content: @Composable () -> Unit = {
    Icon(Icons.Rounded.Laptop, contentDescription = null, tint = Pairly.style.onIconContainer, modifier = Modifier.size(28.dp))
}) {
    val style = Pairly.style
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(width, width * 0.66f).clip(RoundedCornerShape(12.dp)).background(style.cardColor)
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) { content() }
        Box(Modifier.padding(top = 4.dp).size(width + 16.dp, 8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun Token(icon: ImageVector, modifier: Modifier = Modifier) {
    val style = Pairly.style
    Box(modifier.size(44.dp).clip(CircleShape).background(style.heroBrush), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = style.onHero, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Rings(t: Float, size: Dp, content: @Composable () -> Unit) {
    val accent = Pairly.style.accents.first()
    Box(contentAlignment = Alignment.Center) {
        repeat(2) { index ->
            val phase = (t + index * 0.5f) % 1f
            Box(
                Modifier.size(size * 1.6f).graphicsLayer { scaleX = 0.6f + phase; scaleY = 0.6f + phase; alpha = (1f - phase) * 0.35f }
                    .clip(CircleShape).background(accent)
            )
        }
        content()
    }
}

@Composable
private fun Pulses(icon: ImageVector, t: Float) {
    val style = Pairly.style
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        repeat(3) { index ->
            val phase = (t + index / 3f) % 1f
            Box(
                Modifier.size(120.dp).graphicsLayer { scaleX = 0.8f + phase * 1.1f; scaleY = 0.8f + phase * 1.1f; alpha = (1f - phase) * 0.28f }
                    .clip(CircleShape).background(style.accents[index % style.accents.size])
            )
        }
        Box(
            Modifier.size(104.dp).graphicsLayer { val breathe = 1f + sin(t * 2 * PI).toFloat() * 0.03f; scaleX = breathe; scaleY = breathe }
                .clip(if (style.daybreak) RoundedCornerShape(36.dp) else CircleShape).background(style.heroBrush),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = style.onHero, modifier = Modifier.size(52.dp)) }
    }
}
