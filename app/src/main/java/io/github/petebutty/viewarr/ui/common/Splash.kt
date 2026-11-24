package io.github.petebutty.viewarr.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Opening splash, the "Play Stack" logo animated (2026-11-18: "Play press, more glow", from the splash options
 * page): the mark is there at once; the front play button presses in and springs back with a wide two-tone glow
 * (emerald, then cyan) that lingers; the two layers behind ripple outward from it; "viewarr" slides in beside it.
 * While the app is still getting ready (the splash can stay up to ~11 s on a slow box), the button gives a smaller
 * press every few seconds, so it never looks stuck. AppRoot fades it.
 */
@Composable
fun ViewarrSplash() {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t = (it - start) / 1e9f }
    }
    val ease = remember { CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f) }
    val spring = remember { CubicBezierEasing(0.3f, 1.4f, 0.4f, 1f) }
    // 0 → 1 between [a] and [b] seconds, eased.
    fun span(a: Float, b: Float, e: CubicBezierEasing = ease) = e.transform(((t - a) / (b - a)).coerceIn(0f, 1f))
    // The mark fades in quickly, whole.
    val appear = span(0f, 0.25f)
    // The press: 0.2 s in, down to 84 %, springing back past full to 106 %, then settling; a smaller one every 2.6 s after.
    val idle = t >= 1.6f
    val pT = if (!idle) t - 0.2f else (t - 1.6f) % 2.6f
    val depth = if (idle) 0.08f else 0.16f
    val frontScale = when {
        pT < 0f -> 1f
        pT < 0.28f -> 1f - depth * (pT / 0.28f)
        pT < 0.52f -> (1f - depth) + (depth + 0.06f) * spring.transform((pT - 0.28f) / 0.24f)
        pT < 0.8f -> 1.06f - 0.06f * ease.transform((pT - 0.52f) / 0.28f)
        else -> 1f
    }
    // The glow: up with the spring, then down to a soft glow that stays.
    val glowPeak = if (idle) 0.6f else 1f
    val glow = when {
        pT < 0.25f -> if (idle) 0.35f else 0f
        pT < 0.52f -> (if (idle) 0.35f else 0f) + (glowPeak - (if (idle) 0.35f else 0f)) * ((pT - 0.25f) / 0.27f)
        pT < 1.2f -> glowPeak - (glowPeak - 0.35f) * ease.transform((pT - 0.52f) / 0.68f)
        else -> 0.35f
    }
    // The layers behind ripple outward from the press, middle first.
    fun ripple(a: Float): Float { val r = ((t - a) / 0.6f).coerceIn(0f, 1f); return if (r < 0.45f) 10f * ease.transform(r / 0.45f) else 10f * (1f - ease.transform((r - 0.45f) / 0.55f)) }
    val dxMid = ripple(0.62f); val dxBack = ripple(0.72f)
    val word = span(1.0f, 1.5f)
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0D14)), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Canvas(Modifier.size(170.dp)) {
                // The two-tone glow behind the front button: a wide cyan halo, a tighter emerald core.
                val c = Offset(size.width * 0.32f, size.height / 2f)
                if (glow > 0f) {
                    val outer = size.minDimension * 0.85f
                    drawCircle(Brush.radialGradient(0f to ViewarrBrand.To.copy(alpha = 0.55f * glow), 1f to Color.Transparent, center = c, radius = outer), radius = outer, center = c)
                    val inner = size.minDimension * 0.5f
                    drawCircle(Brush.radialGradient(0f to ViewarrBrand.From.copy(alpha = 0.9f * glow), 1f to Color.Transparent, center = c, radius = inner), radius = inner, center = c)
                }
                drawViewarrMark(
                    dx = floatArrayOf(dxBack, dxMid, 0f),
                    alpha = FloatArray(3) { appear },
                    frontScale = frontScale,
                )
            }
            Text(
                "viewarr", fontFamily = ViewarrBrand.Font, fontWeight = FontWeight.ExtraBold, fontSize = 80.sp,
                letterSpacing = (-0.03).em, color = Color.White, maxLines = 1, softWrap = false,
                // (Slides in from the mark's side.)
                modifier = Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = word; translationX = -(1f - word) * 18.dp.toPx() },
            )
        }
    }
}
