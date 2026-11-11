package io.github.petebutty.viewarr.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.R
import kotlinx.coroutines.launch

private val Poppins = FontFamily(Font(R.font.poppins_bold))

/**
 * Opening splash: a soft cyan-pink glow, the aperture turning one full turn into place (an animated vector: drawn on
 * the render thread, so the start page loading underneath can't make it stutter) with sparks drifting off it, then
 * the glossy "Viewarr" wordmark sliding out from behind it and a shine crossing it once.
 */
@Composable
fun ViewarrSplash() {
    val reveal = remember { Animatable(0f) }
    val shine = remember { Animatable(-0.4f) }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { glow.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
        // The word waits for the spin to settle (and the start page's first heavy frames to pass), then the whole
        // logo holds a moment before the splash fades (AppRoot: 3.8 s in all).
        kotlinx.coroutines.delay(1200)
        reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        shine.animateTo(1.4f, tween(1000, easing = androidx.compose.animation.core.LinearEasing))
    }
    // The glow breathes a little once it's in.
    val breathe by androidx.compose.animation.core.rememberInfiniteTransition(label = "glow").animateFloat(initialValue = 
        0.85f, targetValue = 1f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(1400), androidx.compose.animation.core.RepeatMode.Reverse), label = "breathe",
    )
    // Sparks: fixed at the start (seeded), each drifting outward from the mark and fading. Time drives them all.
    val sparks = remember {
        val r = java.util.Random(7)
        List(40) {
            Spark(
                angle = r.nextFloat() * 6.2832f, speed = 0.35f + r.nextFloat() * 0.65f,
                delay = r.nextFloat() * 2.2f, life = 1.3f + r.nextFloat() * 1.1f,
                size = 1.5f + r.nextFloat() * 3f, pink = r.nextBoolean(),
            )
        }
    }
    var t by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (t < 4.5f) androidx.compose.runtime.withFrameNanos { t = (it - start) / 1e9f }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF080808)), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(contentAlignment = Alignment.Center) {
                // Glow and sparks behind the mark (they spill well past it; drawn, not laid out).
                androidx.compose.foundation.Canvas(Modifier.size(260.dp)) {
                    val c = center
                    val g = glow.value * breathe
                    drawCircle(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            0f to Color(0xFF00E5FF).copy(alpha = 0.28f * g), 0.55f to Color(0xFFFF3366).copy(alpha = 0.12f * g), 1f to Color.Transparent,
                            center = c, radius = size.minDimension * 1.25f,
                        ),
                        radius = size.minDimension * 1.25f, center = c,
                    )
                    val time = t
                    sparks.forEach { sp ->
                        val age = time - sp.delay
                        if (age <= 0f || age >= sp.life) return@forEach
                        val f = age / sp.life
                        val dist = size.minDimension * (0.42f + f * 0.75f * sp.speed)
                        val p = androidx.compose.ui.geometry.Offset(c.x + kotlin.math.cos(sp.angle) * dist, c.y + kotlin.math.sin(sp.angle) * dist - f * 30f)
                        val a = (if (f < 0.2f) f / 0.2f else 1f - (f - 0.2f) / 0.8f).coerceIn(0f, 1f)
                        val col = if (sp.pink) Color(0xFFFF3366) else Color(0xFF00E5FF)
                        drawCircle(col.copy(alpha = 0.35f * a), radius = sp.size.dp.toPx() * 2.2f, center = p)
                        drawCircle(Color.White.copy(alpha = 0.9f * a), radius = sp.size.dp.toPx() * 0.6f, center = p)
                    }
                }
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply {
                            val avd = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.splash_logo)
                            setImageDrawable(avd)
                            (avd as? android.graphics.drawable.Animatable)?.start()
                        }
                    },
                    modifier = Modifier.size(260.dp),
                )
            }
            // The glossy wordmark (tools/brand/wordmark.py), sliding out from behind the mark.
            Box(Modifier.graphicsLayer { clip = true }) {
                Image(
                    painter = painterResource(R.drawable.splash_wordmark),
                    contentDescription = "Viewarr",
                    modifier = Modifier
                        .height(150.dp)
                        .graphicsLayer {
                            val r = reveal.value
                            translationX = (r - 1f) * size.width * 0.6f
                            alpha = (r * 1.6f).coerceAtMost(1f)
                        }
                        // The shine: a light band drawn over the letters only (SrcAtop keeps it inside them).
                        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val x = shine.value * size.width
                            drawRect(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.6f), 1f to Color.Transparent,
                                    start = androidx.compose.ui.geometry.Offset(x - size.height * 0.5f, 0f),
                                    end = androidx.compose.ui.geometry.Offset(x + size.height * 0.5f, size.height),
                                ),
                                blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop,
                            )
                        },
                )
            }
        }
    }
}

private class Spark(val angle: Float, val speed: Float, val delay: Float, val life: Float, val size: Float, val pink: Boolean)
