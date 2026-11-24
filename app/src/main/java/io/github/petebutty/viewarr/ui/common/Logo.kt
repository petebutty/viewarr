package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.R

/**
 * The Viewarr logo (2026-11-17, picked from the logo concepts: "Play Stack"): three rounded play buttons one behind
 * the other (back to front at 30 %, 60 %, full), emerald to cyan (the colours of Dispatcharr's logo), beside a lowercase
 * "viewarr" in Plus Jakarta Sans ExtraBold. Drawn here rather than as pictures, so the splash can move each part.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object ViewarrBrand {
    val From = Color(0xFF34D399)
    val To = Color(0xFF06B6D4)
    val Font = FontFamily(Font(R.font.plus_jakarta_sans, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))))

    // The three play buttons on a 200 × 200 square (the design's SVG), back to front.
    private fun tri(l: Float, t: Float, tipX: Float, b: Float): Path = Path().apply {
        // Rounded corners: each corner cut by a short curve, as the design's "Q" corners.
        val mid = (t + b) / 2f
        moveTo(l, t + 10f)
        quadraticTo(l, t, l + 9f, t + 5f)
        lineTo(tipX - 8f, mid - 8f)
        quadraticTo(tipX, mid, tipX - 8f, mid + 8f)
        lineTo(l + 9f, b - 5f)
        quadraticTo(l, b, l, b - 10f)
        close()
    }
    val Back: Path = tri(76f, 34f, 178f, 166f)
    val Middle: Path = tri(52f, 40f, 146f, 160f)
    val Front: Path = tri(26f, 46f, 112f, 154f)
}

/**
 * Draws the mark into this square. Each play button can be moved ([dx], in the 200-unit square), faded ([alpha]) and the
 * front one pressed ([frontScale]): the splash's animation; still, it's the logo.
 */
fun DrawScope.drawViewarrMark(
    dx: FloatArray = floatArrayOf(0f, 0f, 0f),
    alpha: FloatArray = floatArrayOf(1f, 1f, 1f),
    frontScale: Float = 1f,
    from: Color = ViewarrBrand.From,
    to: Color = ViewarrBrand.To,
) {
    val k = size.minDimension / 200f
    scale(k, pivot = Offset.Zero) {
        val brush = Brush.linearGradient(listOf(from, to), start = Offset.Zero, end = Offset(200f, 200f))
        val paths = listOf(ViewarrBrand.Back, ViewarrBrand.Middle, ViewarrBrand.Front)
        val base = floatArrayOf(0.3f, 0.6f, 1f)
        for (i in 0..2) {
            val a = (base[i] * alpha[i]).coerceIn(0f, 1f)
            if (a <= 0f) continue
            translate(left = dx[i]) {
                if (i == 2 && frontScale != 1f) scale(frontScale, pivot = Offset(60f, 100f)) { drawPath(paths[i], brush, alpha = a) }
                else drawPath(paths[i], brush, alpha = a)
            }
        }
    }
}

/** The mark on its own (the menu, set-up). */
@Composable
fun ViewarrMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawViewarrMark() }
}

/** Mark and name together: the menu's logo. */
@Composable
fun ViewarrLogo(modifier: Modifier = Modifier, markSize: Dp = 30.dp, textSize: TextUnit = 30.sp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(markSize * 0.3f)) {
        ViewarrMark(markSize)
        Text(
            "viewarr", fontFamily = ViewarrBrand.Font, fontWeight = FontWeight.ExtraBold, fontSize = textSize,
            letterSpacing = (-0.03).em, color = Color.White, maxLines = 1, softWrap = false,
        )
    }
}
