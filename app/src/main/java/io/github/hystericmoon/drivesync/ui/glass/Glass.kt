package io.github.hystericmoon.drivesync.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

val GlassShape = RoundedCornerShape(28.dp)

/**
 * Frosted "liquid glass" surface: translucent tint, a top sheen, and a light-catching edge that is
 * bright at the top-left and fades toward the bottom-right. [glow] (0..1) adds a coloured rim,
 * used to show activity.
 */
fun Modifier.glass(
    shape: Shape = GlassShape,
    strong: Boolean = false,
    glow: Float = 0f,
    glowColor: Color = Color.Unspecified,
): Modifier = composed {
    val g = LocalGlass.current
    val rim = if (glowColor == Color.Unspecified) MaterialTheme.colorScheme.primary else glowColor
    this
        .clip(shape)
        .background(if (strong) g.tintStrong else g.tint)
        .background(Brush.verticalGradient(0f to g.sheen, 0.45f to Color.Transparent))
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(g.edgeHighlight, g.edgeShade, g.edgeShade, g.edgeHighlight.copy(alpha = g.edgeHighlight.alpha * 0.5f)),
                start = Offset.Zero,
                end = Offset.Infinite,
            ),
            shape = shape,
        )
        .then(
            if (glow > 0f) {
                Modifier.border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        listOf(rim.copy(alpha = 0.85f * glow), rim.copy(alpha = 0.15f * glow), rim.copy(alpha = 0.6f * glow)),
                    ),
                    shape = shape,
                )
            } else {
                Modifier
            },
        )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    glow: Float = 0f,
    contentPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.glass(shape, glow = glow).padding(contentPadding), content = content)
}

/**
 * The colour field glass sits on: three soft blobs in the theme's colours drifting slowly
 * (frozen when animations are off). Soft radial gradients stand in for a backdrop blur.
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalGlass.current.dark
    val still = LocalReducedMotion.current
    val t = rememberInfiniteTransition(label = "ambient")
    fun drift(ms: Int) = infiniteRepeatable<Float>(tween(ms, easing = LinearEasing), RepeatMode.Reverse)
    val a by t.animateFloat(0f, if (still) 0f else 1f, drift(21_000), label = "a")
    val b by t.animateFloat(0f, if (still) 0f else 1f, drift(27_000), label = "b")
    val c by t.animateFloat(0f, if (still) 0f else 1f, drift(33_000), label = "c")

    val strength = if (dark) 0.30f else 0.34f
    val blobs = listOf(scheme.primary, scheme.tertiary, if (dark) Color(0xFF1E8E86) else Color(0xFF3CC4B4))

    Box(
        modifier
            .fillMaxSize()
            .background(scheme.background)
            .drawBehind {
                val r = max(size.width, size.height) * 0.62f
                fun blob(color: Color, x: Float, y: Float, alpha: Float) = drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent),
                        center = Offset(x, y),
                        radius = r,
                    ),
                    radius = r,
                    center = Offset(x, y),
                )
                val w = size.width
                val h = size.height
                blob(blobs[0], w * (0.05f + 0.35f * a), h * (0.08f + 0.18f * b), strength)
                blob(blobs[1], w * (0.95f - 0.30f * b), h * (0.42f + 0.20f * c), strength * 0.9f)
                blob(blobs[2], w * (0.20f + 0.40f * c), h * (0.95f - 0.15f * a), strength * 0.7f)
            },
    ) {
        // No Surface sits between the theme and the screens, so set the default text/icon colour here.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) { content() }
    }
}

/** Fades scrolling content out behind floating bottom chrome so text never collides with the nav bar. */
@Composable
fun BottomScrim(height: Dp, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Brush.verticalGradient(listOf(Color.Transparent, bg.copy(alpha = 0.75f), bg.copy(alpha = 0.92f)))),
    )
}
