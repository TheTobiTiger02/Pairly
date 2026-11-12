package dev.pairly.android.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pairly.android.ui.theme.Pairly

class NavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/**
 * Floating tab bar. Every tab keeps its icon and label; a filled marker slides behind the selected
 * one and carries the pair corner, turned towards the side it sits on.
 */
@Composable
fun PairlyNavBar(items: List<NavItem>, modifier: Modifier = Modifier) {
    val style = Pairly.style
    val selected = items.indexOfFirst { it.selected }.coerceAtLeast(0)
    var bar = modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 12.dp).fillMaxWidth().height(56.dp)
        .clip(CircleShape).background(style.navContainer)
    style.navBorder?.let { bar = bar.border(it, CircleShape) }
    BoxWithConstraints(bar.padding(4.dp)) {
        val slot = maxWidth / items.size
        val x by animateDpAsState(slot * selected, style.spring(), label = "nav-marker")
        // The tight corner points away from the neighbouring tab.
        val last = selected == items.lastIndex && items.size > 1
        val marker = RoundedCornerShape(
            topStart = 24.dp, topEnd = 24.dp,
            bottomEnd = if (last) 7.dp else 24.dp,
            bottomStart = if (last) 24.dp else 7.dp
        )
        Box(Modifier.offset(x = x).width(slot).fillMaxHeight().clip(marker).background(style.navActive))
        Row(Modifier.fillMaxSize().selectableGroup()) {
            items.forEach { item ->
                val content by animateColorAsState(if (item.selected) style.onNavActive else style.navInactive, label = "nav-fg")
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                        .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // At large font sizes the label alone fills the tab, so the icon makes room for it.
                    val large = LocalDensity.current.fontScale > 1.3f
                    if (!large) {
                        Icon(item.icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        item.label,
                        style = if (large) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                        color = content, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
