package io.github.hystericmoon.drivesync.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hystericmoon.drivesync.ui.glass.LocalReducedMotion
import io.github.hystericmoon.drivesync.ui.glass.glass

enum class OrbState { Idle, Syncing, Problem }

/**
 * Glass orb in the middle of a pair (and in the summary). Syncing: the icon spins and a soft halo
 * breathes. Idle: a calm check. Problem: an error mark. Decorative; callers describe the state in text.
 */
@Composable
fun SyncOrb(state: OrbState, size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val still = LocalReducedMotion.current
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(
        0f, if (still) 0f else 360f,
        infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin",
    )
    val breathe by t.animateFloat(
        0.85f, if (still) 0.85f else 1.1f,
        infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breathe",
    )
    val active by animateFloatAsState(if (state == OrbState.Syncing) 1f else 0f, tween(400), label = "active")
    val accent = when (state) {
        OrbState.Problem -> scheme.error
        else -> scheme.primary
    }
    Box(
        modifier
            .size(size)
            .drawBehind {
                if (active > 0f) {
                    val r = this.size.minDimension / 2 * (1.25f + 0.2f * (breathe - 0.85f) * 4)
                    drawCircle(
                        Brush.radialGradient(listOf(accent.copy(alpha = 0.35f * active), Color.Transparent), radius = r),
                        radius = r,
                    )
                }
            }
            .glass(CircleShape, glow = 0.6f * active + 0.25f, glowColor = accent),
        contentAlignment = Alignment.Center,
    ) {
        val icon = when (state) {
            OrbState.Syncing -> Icons.Rounded.Sync
            OrbState.Problem -> Icons.Rounded.ErrorOutline
            OrbState.Idle -> Icons.Rounded.CheckCircle
        }
        Icon(
            icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .size(size * 0.5f)
                .rotate(if (state == OrbState.Syncing) -spin else 0f)
                .scale(if (state == OrbState.Syncing && !still) 1f + 0.04f * (breathe - 0.85f) * 4 else 1f),
        )
    }
}

/**
 * Line between the phone and Drive ends of a pair. While syncing, dots stream both ways:
 * primary-coloured ones phone → Drive on the upper lane, tertiary ones Drive → phone below.
 */
@Composable
fun SyncConnector(syncing: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val still = LocalReducedMotion.current
    val t = rememberInfiniteTransition(label = "flow")
    val phase by t.animateFloat(
        0f, if (still) 0f else 1f,
        infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "phase",
    )
    val on by animateFloatAsState(if (syncing) 1f else 0f, tween(450), label = "on")
    val idleLine = scheme.outline.copy(alpha = 0.45f)
    val up = scheme.primary
    val down = scheme.tertiary
    Canvas(modifier) {
        val y = size.height / 2
        val lane = 4.dp.toPx()
        drawLine(
            idleLine.copy(alpha = idleLine.alpha * (1f - 0.6f * on)),
            Offset(0f, y), Offset(size.width, y),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())),
        )
        if (on <= 0f) return@Canvas
        val dots = 3
        for (k in 0 until dots) {
            val p = (phase + k.toFloat() / dots) % 1f
            // Fade in at the start and out at the end of the run.
            val fade = (minOf(p, 1f - p) * 5f).coerceIn(0f, 1f) * on
            drawCircle(up.copy(alpha = fade), 2.6.dp.toPx(), Offset(size.width * p, y - lane))
            drawCircle(down.copy(alpha = fade), 2.6.dp.toPx(), Offset(size.width * (1f - p), y + lane))
        }
    }
}
