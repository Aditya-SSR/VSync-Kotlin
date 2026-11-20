package com.minimallabs.vsync.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

class TabItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/**
 * The floating capsule tab bar from the Flutter app: icons only, the selected
 * one sitting in a bubble that slides between tabs. Liquid glass when on.
 */
@Composable
fun FloatingTabBar(items: List<TabItem>, selected: Int, content: Backdrop?, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val dark = LocalTones.current.dark
    Box(modifier, contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.width(272.dp).height(64.dp).glass(CircleShape, GlassKind.Bar, backdrop = content)) {
            val slot = (maxWidth - 12.dp) / items.size
            val x by animateDpAsState(slot * selected, spring(dampingRatio = 0.72f, stiffness = 420f), label = "tab")
            // The bubble under the selected icon.
            Box(
                Modifier.padding(6.dp).offset(x = x).width(slot).fillMaxHeight().clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            if (dark) listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.06f))
                            else listOf(Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.10f)),
                        ),
                    )
                    .border(1.dp, if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f), CircleShape),
            )
            Row(Modifier.fillMaxSize().padding(6.dp)) {
                items.forEachIndexed { i, item ->
                    val on = i == selected
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                if (!on) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(i)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (on) item.selectedIcon else item.icon, item.label, Modifier.size(25.dp),
                            tint = if (on) MaterialTheme.colorScheme.onSurface else LocalTones.current.secondaryLabel,
                        )
                    }
                }
            }
        }
    }
}
