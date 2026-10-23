package io.github.hystericmoon.drivesync.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

data class NavItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * Floating glass navigation bar. The selection "lens" slides between items on a soft spring, the
 * liquid part of the look. Each item is a ≥64×56dp tab with Tab role and selected state for TalkBack.
 */
@Composable
fun GlassNavBar(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(68.dp)
            .glass(CircleShape, strong = true)
            .padding(6.dp),
    ) {
        val itemWidth = maxWidth / items.size
        val lensX by animateDpAsState(
            targetValue = itemWidth * selected,
            animationSpec = if (reduced) spring(stiffness = Spring.StiffnessHigh)
            else spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
            label = "lens",
        )
        Box(
            Modifier
                .offset { IntOffset(lensX.roundToPx(), 0) } // lambda: moves at draw time, no recomposition per frame
                .width(itemWidth)
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(listOf(scheme.primary.copy(alpha = 0.22f), scheme.primary.copy(alpha = 0.12f))),
                    CircleShape,
                )
                .border(1.dp, scheme.primary.copy(alpha = 0.25f), CircleShape),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
            items.forEachIndexed { i, item ->
                val isSelected = i == selected
                val tint by animateColorAsState(
                    if (isSelected) scheme.primary else scheme.onSurfaceVariant,
                    label = "tint",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            onClick = { onSelect(i) },
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 36.dp),
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        if (isSelected) item.selectedIcon else item.icon,
                        contentDescription = null, // the label below names the tab
                        tint = tint,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(item.label, style = MaterialTheme.typography.labelMedium, color = tint)
                }
            }
        }
    }
}
