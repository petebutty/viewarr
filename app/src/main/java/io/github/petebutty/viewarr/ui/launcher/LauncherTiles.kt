package io.github.petebutty.viewarr.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.home.MenuIcons

/**
 * The launcher's own tiles (Live TV, Movies, TV Shows, My Libraries, Music, Recordings) given a look of their own, as the
 * streaming services have theirs (2026-11-25: "plain and boring"): each its colours, its symbol large and faint across
 * the tile, the symbol and name in front; Live TV with a LIVE badge.
 */
internal object LauncherTiles {
    private class Look(val from: Color, val to: Color)

    private val looks = mapOf(
        "Tv" to Look(Color(0xFFE11D48), Color(0xFF5B0A2E)),
        "Movies" to Look(Color(0xFFF59E0B), Color(0xFF6B2108)),
        "Shows" to Look(Color(0xFF8B5CF6), Color(0xFF2A2470)),
        "Library" to Look(Color(0xFF14B8A6), Color(0xFF0F3D3A)),
        "Music" to Look(Color(0xFF22C55E), Color(0xFF0E4224)),
        "Recordings" to Look(Color(0xFF3B82F6), Color(0xFF12245C)),
    )

    /** [id]'s tile face; [plain] for a tile without a look of its own. */
    @Composable
    fun Face(id: String, label: String, plain: @Composable () -> Unit) {
        if (id == "Sports") return SportsFace(label)
        val look = looks[id] ?: return plain()
        val icon = MenuIcons.vector(id)
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(look.from, look.to), start = Offset.Zero, end = Offset(420f, 260f)))) {
            // The symbol, large and faint, running off the right edge.
            if (icon != null) androidx.compose.material3.Icon(icon, null, tint = Color.White.copy(alpha = 0.16f),
                modifier = Modifier.align(Alignment.CenterEnd).offset(x = 22.dp).size(96.dp).rotate(-12f))
            // A soft light from the top left.
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), center = Offset(40f, 20f), radius = 260f)))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (icon != null) androidx.compose.material3.Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
                Text(label, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, letterSpacing = 0.2.sp)
            }
            // Recordings: a red record dot, top right.
            if (id == "Recordings") Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(16.dp).background(Color.White.copy(alpha = 0.95f), CircleShape),
                contentAlignment = Alignment.Center) { Box(Modifier.size(10.dp).background(Color(0xFFE11D48), CircleShape)) }
            if (id == "Tv") Row(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp)
                .background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(6.dp).background(Color(0xFFE11D48), CircleShape))
                Text("LIVE", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color(0xFFB0102F), letterSpacing = 0.8.sp)
            }
        }
    }

    /** Sports: a football pitch from above, striped grass and white lines, a ball on the centre spot, and the name. */
    @Composable
    private fun SportsFace(label: String) {
        Box(Modifier.fillMaxSize()) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                // Mown stripes, light and dark green.
                val stripes = 8
                for (i in 0 until stripes) drawRect(if (i % 2 == 0) Color(0xFF2E9E4A) else Color(0xFF268A40),
                    topLeft = Offset(w * i / stripes, 0f), size = androidx.compose.ui.geometry.Size(w / stripes + 1f, h))
                val line = Color.White.copy(alpha = 0.8f)
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.025f)
                val m = h * 0.1f
                // Touchlines, halfway line, centre circle, penalty boxes.
                drawRect(line, topLeft = Offset(m, m), size = androidx.compose.ui.geometry.Size(w - 2 * m, h - 2 * m), style = stroke)
                drawLine(line, Offset(w / 2, m), Offset(w / 2, h - m), strokeWidth = stroke.width)
                drawCircle(line, radius = h * 0.2f, center = Offset(w / 2, h / 2), style = stroke)
                val boxW = w * 0.12f; val boxH = h * 0.44f
                drawRect(line, topLeft = Offset(m, (h - boxH) / 2), size = androidx.compose.ui.geometry.Size(boxW, boxH), style = stroke)
                drawRect(line, topLeft = Offset(w - m - boxW, (h - boxH) / 2), size = androidx.compose.ui.geometry.Size(boxW, boxH), style = stroke)
                // The ball on the centre spot: white with a dark pentagon and patches, a soft shadow.
                val c = Offset(w / 2, h / 2); val r = h * 0.15f
                drawCircle(Color.Black.copy(alpha = 0.3f), radius = r, center = c + Offset(r * 0.15f, r * 0.2f))
                drawCircle(Color.White, radius = r, center = c)
                fun pent(cx: Float, cy: Float, rr: Float, rot: Float) = androidx.compose.ui.graphics.Path().apply {
                    for (k in 0 until 5) {
                        val a = Math.toRadians((rot + k * 72 - 90).toDouble())
                        val x = cx + rr * kotlin.math.cos(a).toFloat(); val y = cy + rr * kotlin.math.sin(a).toFloat()
                        if (k == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                val dark = Color(0xFF1B1B1B)
                drawPath(pent(c.x, c.y, r * 0.38f, 0f), dark)
                for (k in 0 until 5) {
                    val a = Math.toRadians((k * 72 - 90).toDouble())
                    val px = c.x + r * 0.86f * kotlin.math.cos(a).toFloat(); val py = c.y + r * 0.86f * kotlin.math.sin(a).toFloat()
                    clipPath(androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }) {
                        drawPath(pent(px, py, r * 0.3f, 36f), dark)
                    }
                }
            }
            // A shade at the foot so the name reads.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, letterSpacing = 0.2.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp))
        }
    }
}
