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
import dev.pairly.android.ui.theme.Pairly
import dev.pairly.android.ui.theme.Space
import dev.pairly.android.ui.theme.palette
import dev.pairly.android.ui.theme.pickableAccents
import dev.pairly.android.ui.theme.style
import dev.pairly.android.ui.theme.swatchColor

/** One preview per style, each drawn in that style's own colors and shapes with the chosen accent. */
/** Accent swatches in the current mode's tones; "Wallpaper" on Android 12 and newer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentPicker(selected: Accent, onSelect: (Accent) -> Unit, modifier: Modifier = Modifier) {
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
            val color = if (accent == Accent.System) MaterialTheme.colorScheme.surfaceContainerHighest else swatchColor(accent, dark)
            val active = accent == selected
            val ring by animateColorAsState(if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent, label = "swatch-ring")
            Box(
                Modifier.size(Space.touch).clip(CircleShape).border(2.dp, ring, CircleShape).padding(8.dp).clip(CircleShape).background(color)
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
