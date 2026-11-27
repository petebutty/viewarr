package io.github.petebutty.viewarr.ui.mobile

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * The first time Live TV opens on a phone or tablet: a few cards over the page showing how to get around (tap a
 * channel, swipe it left or right, swipe in from the edge to go back, Devices), each with a moving finger. Next / Got
 * it / Skip; once seen, not again (Settings → About → Show the tips brings them back).
 */
internal object MobileTips {
    private const val PREFS = "mobile_tips"
    private const val KEY = "live_v1"
    /** Asked for from About (shown even when seen). */
    var requested by mutableStateOf(false)
    fun seen(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun markSeen(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

/** How a tip's finger moves. */
private enum class Gesture { Tap, SwipeLeft, SwipeRight, EdgeSwipe, TapTopRight }

private class Tip(val title: String, val body: String, val gesture: Gesture)

private val tips = listOf(
    Tip("Tap a channel", "Opens its page: the picture at the top (tap it to watch), what's on now, and its schedule day by day.", Gesture.Tap),
    Tip("Swipe a channel left", "Slides in that channel's TV guide. Swipe it back the other way to close it.", Gesture.SwipeLeft),
    Tip("Swipe right", "Slides in your channel groups, to jump to another group of channels.", Gesture.SwipeRight),
    Tip("Swipe in from the left edge to go back", "Works on every page, as well as the phone's own Back. Let go once the arrow turns orange.", Gesture.EdgeSwipe),
    Tip("Your TVs", "The TV button at the top lists your TVs with Previewarr: what each is playing, its controls, and sending things to it.", Gesture.TapTopRight),
)

@Composable
internal fun MobileTipsHost() {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(false) }
    val show = MobileTips.requested || (!dismissed && !MobileTips.seen(context))
    if (!show) return
    var i by remember { mutableIntStateOf(0) }
    fun close() { MobileTips.markSeen(context); MobileTips.requested = false; dismissed = true; i = 0 }
    BackHandler { close() }
    val tip = tips[i]
    // Over the page, catching every touch (the page underneath isn't usable while the tips are up).
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        GestureDemo(tip.gesture, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 110.dp).widthIn(max = 520.dp).fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)).background(Color(0xFF1E1E1E)).padding(22.dp),
        ) {
            Text("${i + 1} of ${tips.size}", color = AppColors.Accent, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(tip.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(tip.body, color = AppColors.TextSecondary, fontSize = 16.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Where you are in the tips.
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tips.indices.forEach { d -> Box(Modifier.size(8.dp).clip(CircleShape).background(if (d == i) AppColors.Accent else Color(0xFF4A4A4A))) }
                }
                if (i < tips.lastIndex) Text("Skip", color = AppColors.TextSecondary, fontSize = 16.sp,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { close() }.padding(horizontal = 12.dp, vertical = 10.dp))
                val last = i == tips.lastIndex
                Text(if (last) "Got it" else "Next", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(AppColors.Accent).clickable { if (last) close() else i++ }
                        .padding(horizontal = 22.dp, vertical = 10.dp).width(70.dp))
            }
        }
    }
}

/** A finger (a white circle with a trail) acting out the tip, over where it happens on the page. */
@Composable
private fun GestureDemo(g: Gesture, modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "tip").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val r = 22.dp.toPx()
        val cardY = h * 0.32f
        // Moving part of the cycle (0.15–0.75), resting either side.
        val m = ((t - 0.15f) / 0.6f).coerceIn(0f, 1f)
        val (from, to) = when (g) {
            Gesture.Tap -> Offset(w * 0.5f, cardY) to Offset(w * 0.5f, cardY)
            Gesture.SwipeLeft -> Offset(w * 0.78f, cardY) to Offset(w * 0.22f, cardY)
            Gesture.SwipeRight -> Offset(w * 0.22f, cardY) to Offset(w * 0.78f, cardY)
            Gesture.EdgeSwipe -> Offset(4.dp.toPx(), h * 0.45f) to Offset(w * 0.42f, h * 0.45f)
            Gesture.TapTopRight -> Offset(w - 86.dp.toPx(), 30.dp.toPx()) to Offset(w - 86.dp.toPx(), 30.dp.toPx())
        }
        val at = Offset(from.x + (to.x - from.x) * m, from.y + (to.y - from.y) * m)
        // A channel card outline for the card gestures, so the finger has something to act on.
        if (g == Gesture.Tap || g == Gesture.SwipeLeft || g == Gesture.SwipeRight) {
            drawRoundRect(Color.White.copy(alpha = 0.18f), topLeft = Offset(w * 0.06f, cardY - 44.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(w * 0.88f, 88.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                style = Stroke(2.dp.toPx()))
        }
        if (from != to && m > 0f) drawLine(AppColors.Accent.copy(alpha = 0.6f), from, at, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        // A tap: a ring that grows from the finger.
        val tapping = (g == Gesture.Tap || g == Gesture.TapTopRight)
        if (tapping && t in 0.3f..0.8f) {
            val k = (t - 0.3f) / 0.5f
            drawCircle(Color.White.copy(alpha = 0.6f * (1 - k)), radius = r + 26.dp.toPx() * k, center = at, style = Stroke(3.dp.toPx()))
        }
        val visible = if (tapping) 1f else if (t < 0.85f) 1f else (1f - (t - 0.85f) / 0.15f)
        drawCircle(Color.White.copy(alpha = 0.9f * visible), radius = r * (if (tapping && t in 0.3f..0.45f) 0.85f else 1f), center = at)
    }
}
