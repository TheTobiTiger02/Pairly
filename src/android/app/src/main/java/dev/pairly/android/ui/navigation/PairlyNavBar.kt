package dev.pairly.android.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pairly.android.ui.theme.Pairly

class NavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/**
 * Floating tab pill. The selected tab grows into a filled pill with its label: dark ink in Glass,
 * the soft accent tint in Clay. Other tabs show only their icon (the label is still announced).
 */
@Composable
fun PairlyNavBar(items: List<NavItem>, modifier: Modifier = Modifier) {
    val style = Pairly.style
    val height = if (style.clay) 68.dp else 60.dp
    val inset = if (style.clay) 20.dp else 32.dp
    var bar = modifier.navigationBarsPadding().padding(start = inset, end = inset, bottom = 12.dp).fillMaxWidth().height(height)
    bar = bar.shadow(if (style.dark) 0.dp else 16.dp, CircleShape, ambientColor = style.shadowColor, spotColor = style.shadowColor)
        .clip(CircleShape).background(style.navContainer)
    style.navBorder?.let { bar = bar.border(it, CircleShape) }
    Row(
        bar.padding(horizontal = 6.dp).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEach { item -> NavPill(item, Modifier) }
    }
}

@Composable
private fun NavPill(item: NavItem, modifier: Modifier) {
    val style = Pairly.style
    val background by animateColorAsState(if (item.selected) style.navActive else Color.Transparent, style.spring(), label = "nav-bg")
    val content by animateColorAsState(if (item.selected) style.onNavActive else style.navInactive, style.spring(), label = "nav-fg")
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(background)
                .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick)
                .semantics { contentDescription = item.label }
                .animateContentSize(style.spring())
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(item.icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            AnimatedVisibility(
                item.selected,
                enter = fadeIn(style.spring()) + expandHorizontally(style.spring()),
                exit = fadeOut(style.spring()) + shrinkHorizontally(style.spring())
            ) {
                Row {
                    Spacer(Modifier.width(8.dp))
                    Text(item.label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
