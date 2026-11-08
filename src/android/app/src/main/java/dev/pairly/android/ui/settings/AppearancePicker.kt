package dev.pairly.android.ui.settings

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pairly.android.R
import dev.pairly.android.ui.theme.Accent
import dev.pairly.android.ui.theme.AppStyle
import dev.pairly.android.ui.theme.Pairly
import dev.pairly.android.ui.theme.Space
import dev.pairly.android.ui.theme.palette
import dev.pairly.android.ui.theme.pickableAccents
import dev.pairly.android.ui.theme.style
import dev.pairly.android.ui.theme.swatchColor

/** One preview per style, each drawn in that style's own colors and shapes with the chosen accent. */
@Composable
fun StylePicker(selected: AppStyle, accent: Accent, onSelect: (AppStyle) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        StyleOption(AppStyle.Glass, accent, selected == AppStyle.Glass, R.string.style_glass, R.string.style_glass_detail, onSelect, Modifier.weight(1f))
        StyleOption(AppStyle.Clay, accent, selected == AppStyle.Clay, R.string.style_clay, R.string.style_clay_detail, onSelect, Modifier.weight(1f))
    }
}

@Composable
private fun StyleOption(
    value: AppStyle,
    accent: Accent,
    isSelected: Boolean,
    title: Int,
    detail: Int,
    onSelect: (AppStyle) -> Unit,
    modifier: Modifier
) {
    val current = Pairly.style
    val context = LocalContext.current
    val dynamic = if (accent == Accent.System && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (current.dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else null
    val scheme = palette(value, accent, current.dark, dynamic)
    val look = style(value, scheme, current.dark, reduceMotion = true)
    val clay = value == AppStyle.Clay
    val outline by animateColorAsState(if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, label = "style-outline")
    val width by animateDpAsState(if (isSelected) 2.dp else 1.dp, label = "style-width")
    val shape = RoundedCornerShape(if (clay) 26.dp else 18.dp)
    Column(
        modifier
            .clip(shape)
            .border(width, outline, shape)
            .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(value) }
            .padding(Space.s)
    ) {
        // A tiny home screen: ground, a card with a line of text, the action and the tab pill.
        val ground = scheme.background
        val card = look.cardColor.compositeOver(ground)
        Column(
            Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(if (clay) 20.dp else 12.dp)).background(ground).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.width(54.dp).height(7.dp).clip(CircleShape).background(scheme.onBackground))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(if (clay) 14.dp else 9.dp))
                    .background(if (clay) scheme.primaryContainer else card)
                    .then(if (clay) Modifier else Modifier.border(1.dp, look.cardBorder?.brush ?: androidx.compose.ui.graphics.SolidColor(card), RoundedCornerShape(9.dp)))
                    .padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(Modifier.fillMaxWidth(0.7f).height(5.dp).clip(CircleShape).background(if (clay) scheme.onPrimaryContainer else scheme.onSurfaceVariant))
                Box(Modifier.width(38.dp).height(12.dp).clip(CircleShape).background(look.buttonColor))
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.align(Alignment.CenterHorizontally).clip(CircleShape).background(look.navContainer.compositeOver(ground)).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(Modifier.width(22.dp).height(9.dp).clip(CircleShape).background(look.navActive))
                repeat(3) { Box(Modifier.size(9.dp).clip(CircleShape).background(look.navInactive.copy(alpha = 0.35f))) }
            }
        }
        Spacer(Modifier.height(Space.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** Accent swatches in the current style's tones; "Wallpaper" on Android 12 and newer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentPicker(appStyle: AppStyle, selected: Accent, onSelect: (Accent) -> Unit, modifier: Modifier = Modifier) {
    val dark = Pairly.style.dark
    val options = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Accent.System)
        addAll(pickableAccents)
    }
    FlowRow(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s)
    ) {
        options.forEach { accent ->
            val name = stringResource(accentName(accent))
            val color = if (accent == Accent.System) MaterialTheme.colorScheme.surfaceContainerHighest else swatchColor(appStyle, accent, dark)
            val active = accent == selected
            val ring by animateColorAsState(if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent, label = "swatch-ring")
            Box(
                Modifier.size(48.dp).clip(CircleShape).border(2.dp, ring, CircleShape).padding(5.dp).clip(CircleShape).background(color)
                    .selectable(selected = active, role = Role.RadioButton) { onSelect(accent) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center
            ) {
                val mark = if (color.luminance() > 0.45f) Color(0xFF17161F) else Color.White
                when {
                    accent == Accent.System -> Icon(Icons.Rounded.Wallpaper, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    active -> Icon(Icons.Rounded.Check, contentDescription = null, tint = mark, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    Text(
        stringResource(accentName(selected)),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.s, start = Space.xs)
    )
}

fun accentName(accent: Accent): Int = when (accent) {
    Accent.System -> R.string.accent_system
    Accent.Indigo -> R.string.accent_indigo
    Accent.Violet -> R.string.accent_violet
    Accent.Blue -> R.string.accent_blue
    Accent.Teal -> R.string.accent_teal
    Accent.Green -> R.string.accent_green
    Accent.Amber -> R.string.accent_amber
    Accent.Coral -> R.string.accent_coral
    Accent.Pink -> R.string.accent_pink
}
