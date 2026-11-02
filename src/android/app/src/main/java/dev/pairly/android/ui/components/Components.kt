package dev.pairly.android.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pairly.android.R
import dev.pairly.android.network.ConnectionPhase
import dev.pairly.android.ui.theme.DesignVariant
import dev.pairly.android.ui.theme.Pairly
import dev.pairly.android.ui.theme.Space

/** Paints the variant's backdrop (gradients for Dark vibrant and Glass) behind a screen. */
@Composable
fun PairlyBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val style = Pairly.style
    val base = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    // Text outside a Material Surface would otherwise default to black, unreadable in dark themes.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(
            modifier.then(style.backgroundBrush?.let { base.background(it) } ?: base),
            content = content
        )
    }
}

/** The variant's card: tonal (Expressive), outlined (Minimal), glowing border (Dark vibrant) or frosted (Glass). */
@Composable
fun PairlyCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    brush: Brush? = null,
    contentPadding: PaddingValues = PaddingValues(Space.l),
    content: @Composable ColumnScope.() -> Unit
) {
    val style = Pairly.style
    val shape = RoundedCornerShape(style.cardRadius)
    var box = modifier.clip(shape)
    box = if (brush != null) box.background(brush) else box.background(style.cardColor)
    style.cardBorder?.let { box = box.border(it, shape) }
    if (onClick != null) box = box.clickable(role = Role.Button, onClick = onClick)
    val contentColor = if (brush != null) style.onHero else MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(box.animateContentSize(style.spring()).padding(contentPadding), content = content)
    }
}

@Composable
fun ScreenList(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = Space.xl, end = Space.xl, top = Space.s, bottom = Space.xxxl),
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Space.m),
        content = content
    )
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = Space.l, bottom = Space.s)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Spacer(Modifier.height(Space.xs))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = Space.l, bottom = Space.xs, start = Space.xs).semantics { heading() }
    )
}

/** A rounded icon tile; colors follow the variant. */
@Composable
fun FeatureIcon(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 44.dp, highlighted: Boolean = false) {
    val style = Pairly.style
    val container = if (highlighted) MaterialTheme.colorScheme.primary else style.iconContainer
    val content = if (highlighted) MaterialTheme.colorScheme.onPrimary else style.onIconContainer
    val shape = if (style.variant == DesignVariant.Minimal) RoundedCornerShape(size / 4) else RoundedCornerShape(size / 3)
    Box(modifier.size(size).clip(shape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

enum class PillTone { Positive, Neutral, Busy, Negative }

@Composable
fun StatusPill(label: String, tone: PillTone, modifier: Modifier = Modifier) {
    val style = Pairly.style
    val dot = when (tone) {
        PillTone.Positive -> style.success
        PillTone.Negative -> MaterialTheme.colorScheme.error
        PillTone.Busy -> MaterialTheme.colorScheme.primary
        PillTone.Neutral -> MaterialTheme.colorScheme.outline
    }
    val background by animateColorAsState(dot.copy(alpha = 0.14f), label = "pill")
    Row(
        modifier.clip(CircleShape).background(background).padding(horizontal = Space.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(dot, pulsing = tone == PillTone.Busy)
        Spacer(Modifier.width(Space.s))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean = false, size: Dp = 8.dp) {
    val reduce = Pairly.style.reduceMotion
    val scale = if (pulsing && !reduce) {
        val transition = rememberInfiniteTransition(label = "dot")
        transition.animateFloat(0.7f, 1.3f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "dot-scale").value
    } else 1f
    Box(Modifier.size(size).scale(scale).clip(CircleShape).background(color))
}

fun ConnectionPhase.tone(): PillTone = when (this) {
    ConnectionPhase.Ready -> PillTone.Positive
    ConnectionPhase.Rejected -> PillTone.Negative
    ConnectionPhase.Disconnected -> PillTone.Neutral
    else -> PillTone.Busy
}

@Composable
fun connectionLabel(phase: ConnectionPhase): String = stringResource(
    when (phase) {
        ConnectionPhase.Disconnected -> R.string.connection_disconnected
        ConnectionPhase.Discovering -> R.string.connection_discovering
        ConnectionPhase.Connecting -> R.string.connection_connecting
        ConnectionPhase.Authenticating -> R.string.connection_authenticating
        ConnectionPhase.Ready -> R.string.connection_ready
        ConnectionPhase.Reconnecting -> R.string.connection_reconnecting
        ConnectionPhase.Rejected -> R.string.connection_rejected
    }
)

/** One tappable row: icon, title, subtitle, and either a trailing widget or a chevron. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).then(clickable).padding(vertical = Space.s, horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            FeatureIcon(icon, size = 40.dp)
            Spacer(Modifier.width(Space.m))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.s))
            trailing()
        } else if (onClick != null) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit, icon: ImageVector? = null) {
    ListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.semantics(mergeDescendants = true) {}
    ) { Switch(checked = checked, onCheckedChange = null) }
}

/** Single-choice chips, e.g. theme or language. */
@Composable
fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        options.forEach { (value, label) ->
            val active = value == selected
            val background by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, label = "choice"
            )
            val foreground by animateColorAsState(
                if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, label = "choice-text"
            )
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(MaterialTheme.shapes.medium).background(background)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .semantics { contentDescription = label + if (active) ", ✓" else "" }
                    .padding(horizontal = Space.s, vertical = Space.m),
                contentAlignment = Alignment.Center
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = foreground, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** Full-width primary action used at the bottom of steps. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val style = Pairly.style
    if (style.variant == DesignVariant.DarkVibrant && enabled) {
        // Gradient pill for the vibrant look.
        Box(
            modifier.fillMaxWidth().height(Space.button).clip(CircleShape).background(style.heroBrush)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Icon(icon, null, tint = Color.White); Spacer(Modifier.width(Space.s)) }
                Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(Space.button)) {
            if (icon != null) { Icon(icon, null); Spacer(Modifier.width(Space.s)) }
            Text(text)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(Space.button)) {
        if (icon != null) { Icon(icon, null); Spacer(Modifier.width(Space.s)) }
        Text(text)
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = Space.touch), colors = ButtonDefaults.textButtonColors(contentColor = color)) {
        Text(text)
    }
}

/** Numbered instructions. */
@Composable
fun NumberedSteps(steps: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(Space.m))
                Text(step, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun CheckBadge(size: Dp = 28.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Pairly.style.success),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f)) }
}

/** Question with an answer that expands on tap. */
@Composable
fun ExpandableItem(question: String, answer: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(question) { mutableStateOf(false) }
    val rotation by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    PairlyCard(modifier.fillMaxWidth(), onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                answer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.s)
            )
        }
    }
}

/** The launcher icon's two layers, cropped like a launcher shows them. */
@Composable
fun AppIcon(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.logo_content_description)
    Box(modifier.clip(RoundedCornerShape(percent = 24)).semantics { contentDescription = description }) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.matchParentSize())
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.matchParentSize().graphicsLayer(scaleX = 1.45f, scaleY = 1.45f)
        )
    }
}

@Composable
fun stringRes(@StringRes id: Int): String = stringResource(id)

/** Back arrow and title for screens below the top level. */
@Composable
fun DetailTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_back)
            )
        }
        Spacer(Modifier.width(Space.xs))
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
    }
}
