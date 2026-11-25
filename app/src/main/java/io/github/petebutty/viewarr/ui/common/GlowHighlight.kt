package io.github.petebutty.viewarr.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * The highlight on every card (2026-11-25, both apps): a light running slowly round the highlighted card's edge, a
 * soft glow of the same colours spilling out round it, in place of the plain ring. One clock turns every ring
 * ([GlowClock], hosted once in AppRoot), and it only runs while a card has the highlight; each ring's shader and
 * outline are made once for its size, and only turned each frame.
 */
object GlowClock {
    /** One turn in this long (slow: 2026-11-25 "make rotation slower"). */
    const val TURN_MS = 4_500L
    var angle by mutableFloatStateOf(0f)
        internal set
    /** Rings showing now: the clock runs only while there's one. */
    internal var users by mutableIntStateOf(0)
}

/** The clock for every ring; put once near the top of the app. */
@Composable
fun GlowClockHost() {
    val on = GlowClock.users > 0
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { t -> GlowClock.angle = ((t - start) % GlowClock.TURN_MS) / GlowClock.TURN_MS.toFloat() * 360f }
    }
}

/**
 * The rotating glow ring over this card while [on], following [shape]. [grow]: how much the card grows when
 * highlighted (drawn here before the card's own growing, so the ring is grown to match).
 */
fun Modifier.glowHighlight(on: Boolean, shape: Shape, grow: Float = 1f): Modifier = composed {
    // (Low-memory mode: the ring stands still, no redraw every frame, as Viewarr's other heavy extras are off there.)
    val moving = AppColors.palette.heavyExtras
    if (on && moving) DisposableEffect(Unit) { GlowClock.users++; onDispose { GlowClock.users-- } }
    val accent = AppColors.Accent
    val ring = remember { GlowRingPaint() }
    drawWithContent {
        drawContent()
        if (on) ring.draw(this, shape, grow, accent, if (moving) GlowClock.angle else 0f)
    }
}

private class GlowRingPaint {
    private val sharp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    private val soft = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    private val matrix = android.graphics.Matrix()
    private var madeFor: Size? = null
    private var madeShape: Shape? = null
    private var madeColour: Color? = null
    private var shader: android.graphics.SweepGradient? = null
    private var path: android.graphics.Path? = null

    fun draw(scope: DrawScope, shape: Shape, grow: Float, accent: Color, angle: Float) = with(scope) {
        val cx = size.width / 2f; val cy = size.height / 2f
        if (madeFor != size || madeShape != shape || madeColour != accent) {
            madeFor = size; madeShape = shape; madeColour = accent
            // A bright head (white-hot), the highlight colour, then a faint trace round the rest of the edge.
            val faint = accent.copy(alpha = 0.22f).toArgb()
            shader = android.graphics.SweepGradient(cx, cy,
                intArrayOf(faint, accent.copy(alpha = 0.6f).toArgb(), accent.toArgb(), lerp(accent, Color.White, 0.75f).toArgb(), accent.toArgb(), faint, faint),
                floatArrayOf(0f, 0.12f, 0.2f, 0.25f, 0.32f, 0.5f, 1f))
            path = when (val o = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Rectangle -> android.graphics.Path().apply { addRect(o.rect.left, o.rect.top, o.rect.right, o.rect.bottom, android.graphics.Path.Direction.CW) }
                is Outline.Rounded -> android.graphics.Path().apply {
                    val r = o.roundRect
                    addRoundRect(android.graphics.RectF(r.left, r.top, r.right, r.bottom),
                        floatArrayOf(r.topLeftCornerRadius.x, r.topLeftCornerRadius.y, r.topRightCornerRadius.x, r.topRightCornerRadius.y,
                            r.bottomRightCornerRadius.x, r.bottomRightCornerRadius.y, r.bottomLeftCornerRadius.x, r.bottomLeftCornerRadius.y),
                        android.graphics.Path.Direction.CW)
                }
                is Outline.Generic -> android.graphics.Path(o.path.asAndroidPath())
            }
            soft.maskFilter = android.graphics.BlurMaskFilter(12.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        val s = shader ?: return@with
        val p = path ?: return@with
        matrix.setRotate(angle, cx, cy)
        s.setLocalMatrix(matrix)
        soft.shader = s; sharp.shader = s
        // (Drawn grown to the card's highlighted size; the strokes kept their width.)
        soft.strokeWidth = 6.dp.toPx() / grow
        sharp.strokeWidth = 2.5.dp.toPx() / grow
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            n.save()
            n.scale(grow, grow, cx, cy)
            n.drawPath(p, soft)
            n.drawPath(p, sharp)
            n.restore()
        }
    }
}
