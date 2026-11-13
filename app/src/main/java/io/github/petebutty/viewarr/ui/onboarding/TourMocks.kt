package io.github.petebutty.viewarr.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.theme.AppColors

/*
 * The tour's pictures: Viewarr's screens drawn here, laid out as the real ones are, with made-up channels and titles
 * (no one's own library or guide in them, and in the viewer's own highlight colour). Drawn on a 640 × 360 grid that
 * scales to the space it has.
 */

/** Drawing on the 640 × 360 grid: [u] is one unit. */
internal class MockScope(val u: Dp, private val box: BoxScope) {
    @Composable
    fun Rect(x: Float, y: Float, w: Float, h: Float, color: Color, r: Float = 3f, brush: Brush? = null, border: Color? = null) {
        val shape = RoundedCornerShape(u * r)
        Box(
            Modifier.offset(u * x, u * y).size(u * w, u * h).clip(shape)
                .then(if (brush != null) Modifier.background(brush) else Modifier.background(color))
                .then(if (border != null) Modifier.border(u * 1.2f, border, shape) else Modifier),
        )
    }

    @Composable
    fun Label(x: Float, y: Float, text: String, size: Float = 7f, color: Color = Color.White, bold: Boolean = false, w: Float = 200f) {
        val sp = with(LocalDensity.current) { (u * size).toSp() }
        Text(
            text, fontSize = sp, lineHeight = sp * 1.2f, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.offset(u * x, u * y).width(u * w),
        )
    }

    @Composable
    fun Circle(x: Float, y: Float, d: Float, color: Color, border: Color? = null) {
        Box(Modifier.offset(u * x, u * y).size(u * d).clip(CircleShape).background(color).then(if (border != null) Modifier.border(u, border, CircleShape) else Modifier))
    }
}

private val Bg = Color(0xFF0E1016)
private val Glass = Color(0xFF2A2C38).copy(alpha = 0.92f)
private val Cell = Color(0xFF2E3140)
private val Dim = Color(0xFF9AA0B4)

/** A drawn screen in a 16:9 frame, with pulsing rings and labels on the parts that matter (x, y as 0…1). */
@Composable
internal fun BoxScope.Mock(spots: List<Spot>, pulse: Float, draw: @Composable MockScope.() -> Unit) {
    BoxWithConstraints(
        Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Bg)
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
    ) {
        val u = maxWidth / 640f
        Box(Modifier.fillMaxSize()) { MockScope(u, this).draw() }
        spots.forEach { sp ->
            val cx = maxWidth * sp.x; val cy = maxHeight * sp.y
            val r = 16.dp
            Box(Modifier.offset(cx - r, cy - r).size(r * 2).graphicsLayer { scaleX = 1f + pulse * 0.35f; scaleY = 1f + pulse * 0.35f; alpha = 1f - pulse * 0.4f }
                .border(3.dp, AppColors.Accent, CircleShape))
            Box(Modifier.offset(if (sp.labelLeft) cx - r - 8.dp - 230.dp else cx + r + 8.dp, cy - 12.dp).width(230.dp),
                contentAlignment = if (sp.labelLeft) Alignment.CenterEnd else Alignment.CenterStart) {
                Text(sp.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.OnAccent, maxLines = 1,
                    modifier = Modifier.background(AppColors.Accent, RoundedCornerShape(6.dp)).padding6())
            }
        }
    }
}

private fun Modifier.padding6() = this.then(Modifier.padding(horizontal = 8.dp, vertical = 3.dp))

// ---------------------------------------------------------------- shared parts

private val previewBrush = Brush.linearGradient(listOf(Color(0xFF3A4E8C), Color(0xFF7A4F8E), Color(0xFFC0705A)))

/** The window top left (what's playing) and what's on beside it. */
@Composable
private fun MockScope.PreviewAndInfo(x: Float, title: String, time: String) {
    Rect(x, 12f, 192f, 108f, Color.Black, r = 4f, brush = previewBrush)
    Label(x + 86f, 54f, "▶", size = 16f, color = Color.White.copy(alpha = 0.5f))
    Label(x + 206f, 16f, title, size = 12f, bold = true, w = 260f)
    Label(x + 206f, 34f, time, size = 7f, color = Dim)
    Rect(x + 262f, 37f, 70f, 3f, Color.White.copy(alpha = 0.25f), r = 1.5f)
    Rect(x + 262f, 37f, 46f, 3f, Color.White, r = 1.5f)
    Rect(x + 206f, 50f, 230f, 4f, Dim.copy(alpha = 0.5f), r = 2f)
    Rect(x + 206f, 59f, 190f, 4f, Dim.copy(alpha = 0.5f), r = 2f)
}

private val channels = listOf("Channel One", "Viewarr News", "Sport One", "Movies Plus", "Kids Zone", "Discovery Hub", "Comedy Max", "Music TV", "Drama Box")
private val shows = listOf(
    listOf("Evening News" to 1f, "Quiz Night" to 1f, "City Lights" to 2f),
    listOf("Weather Live" to 0.6f, "World Today" to 1.4f, "Late Report" to 2f),
    listOf("Football Live: Reds v Blues" to 3f, "Highlights" to 1f),
    listOf("The Long Road" to 2f, "Night Shift" to 2f),
    listOf("Cartoon Club" to 1f, "Little Explorers" to 1f, "Story Time" to 2f),
    listOf("Wild Planet" to 2f, "Ocean Deep" to 2f),
    listOf("Stand-Up Hour" to 1.5f, "Sitcom Classics" to 2.5f),
    listOf("Top 40 Countdown" to 2f, "Live Sessions" to 2f),
    listOf("Harbour Town" to 1f, "The Detective" to 2f, "Courtroom" to 1f),
)

/** The guide grid from [x]: times, channel names, programmes (the first row's first programme highlighted). */
@Composable
private fun MockScope.Grid(x: Float, rows: Int = 9) {
    val nameW = 104f; val hour = (640f - x - nameW - 8f) / 2f
    Label(x, 128f, "Fri 14 Nov, 19:12", size = 6.5f, color = Dim)
    listOf("19:00", "19:30", "20:00", "20:30").forEachIndexed { i, t -> Label(x + nameW + 4f + i * hour / 2f, 128f, t, size = 6.5f, color = Dim) }
    for (r in 0 until rows) {
        val y = 142f + r * 23f
        Rect(x, y, nameW, 20f, Cell, r = 2.5f)
        Label(x + 6f, y + 5.5f, channels[r], size = 7f, bold = r == 0, w = nameW - 10f)
        var cx = x + nameW + 3f
        shows[r].forEachIndexed { i, (name, len) ->
            val w = hour / 2f * len - 3f
            val on = r == 0 && i == 0
            Rect(cx, y, w, 20f, if (on) AppColors.Accent else Cell, r = 2.5f)
            Label(cx + 5f, y + 5.5f, name, size = 7f, color = if (on) AppColors.OnAccent else Color.White, w = w - 8f)
            cx += w + 3f
        }
    }
    // Now
    Rect(x + nameW + 3f + hour * 0.4f, 136f, 1f, 224f, AppColors.Accent, r = 0f)
}

/** The glass menu on the left: the logo, Edit menus and the entries ([on]: the one highlighted). */
@Composable
private fun MockScope.MainMenu(on: Int) {
    Rect(0f, 0f, 138f, 360f, Glass, r = 0f)
    Circle(12f, 14f, 15f, Color.Transparent, border = AppColors.Accent)
    Label(32f, 13f, "Viewarr", size = 12f, bold = true, color = Color(0xFFB9E6FF))
    Label(14f, 58f, "✎  Edit menus", size = 7f, color = Dim)
    listOf("Live TV", "Sports", "Movies", "Shows", "StreamFlix", "PrimeView", "Music", "Recordings", "Search", "Settings").forEachIndexed { i, t ->
        val y = 80f + i * 22f
        if (i == on) Rect(6f, y - 4f, 126f, 19f, AppColors.Accent, r = 4f)
        Label(14f, y, t, size = 7.5f, color = if (i == on) AppColors.OnAccent else Color.White)
    }
}

// ---------------------------------------------------------------- the screens

/** The menu open beside the guide. */
@Composable
internal fun MockScope.MenuScreen() {
    MainMenu(on = 0)
    PreviewAndInfo(146f, "Evening News", "19:00 – 19:30   ·   18 min left")
    Grid(146f)
}

/** The guide, full width. */
@Composable
internal fun MockScope.GuideScreen() {
    PreviewAndInfo(8f, "Evening News", "19:00 – 19:30   ·   18 min left")
    Grid(8f)
}

/** The channel groups beside the guide. */
@Composable
internal fun MockScope.GroupsScreen() {
    Rect(0f, 0f, 138f, 360f, Glass, r = 0f)
    listOf("All channels" to 1240, "Entertainment" to 72, "Movies" to 84, "Sports" to 31, "News" to 10, "Kids" to 14, "Music" to 4, "Documentaries" to 22, "Regional" to 18, "4K / UHD" to 35)
        .forEachIndexed { i, (g, n) ->
            val y = 20f + i * 22f
            if (i == 2) Rect(6f, y - 4f, 126f, 19f, AppColors.Accent, r = 4f)
            val c = if (i == 2) AppColors.OnAccent else Color.White
            Label(14f, y, g, size = 7.5f, color = c, w = 90f)
            Label(108f, y, n.toString(), size = 6.5f, color = c, w = 24f)
        }
    PreviewAndInfo(146f, "The Long Road", "18:10 – 20:00   ·   48 min left")
    Grid(146f)
}

private val posterColours = listOf(
    0xFF8E3B46 to 0xFF2B1D3A, 0xFF2F6D8C to 0xFF16263A, 0xFFB7793A to 0xFF3A2414, 0xFF3F7D5C to 0xFF14281F,
    0xFF6C4FA3 to 0xFF221836, 0xFFA8434F to 0xFF2D1218, 0xFF4E6E9E to 0xFF182235, 0xFF9A8A3C to 0xFF2E2912,
)

/** A row of landscape posters at [y] (the first highlighted when [on]). */
@Composable
private fun MockScope.PosterRow(x: Float, y: Float, title: String, names: List<String>, on: Boolean) {
    Label(x, y, title, size = 8f, bold = true)
    names.forEachIndexed { i, n ->
        val px = x + i * 104f
        val (a, b) = posterColours[(i + names.size) % posterColours.size]
        Rect(px, y + 14f, 98f, 56f, Color.Black, r = 4f, brush = Brush.verticalGradient(listOf(Color(a), Color(b))), border = if (on && i == 0) AppColors.Accent else null)
        Label(px + 6f, y + 52f, n, size = 7.5f, bold = true, w = 88f)
    }
}

/** A streaming service's rows, the highlighted title's details at the top. */
@Composable
internal fun MockScope.ServiceScreen() {
    Rect(8f, 12f, 192f, 108f, Color.Black, r = 4f, brush = Brush.linearGradient(listOf(Color(0xFF8E3B46), Color(0xFF2B1D3A))))
    Label(92f, 54f, "▶", size = 16f, color = Color.White.copy(alpha = 0.5f))
    Label(214f, 12f, "Shows", size = 7f, color = AppColors.Accent)
    Label(214f, 24f, "HARBOUR TOWN", size = 18f, bold = true, w = 300f)
    Label(214f, 50f, "2024  ·  3 seasons  ·  Drama", size = 7f, color = Dim, w = 300f)
    Rect(214f, 63f, 26f, 10f, Color(0xFFF5C518), r = 2f); Label(243f, 63.5f, "8.1", size = 6.5f)
    Rect(262f, 63f, 34f, 10f, Color(0xFFE85D75), r = 2f); Label(299f, 63.5f, "82%", size = 6.5f)
    Rect(214f, 80f, 260f, 4f, Dim.copy(alpha = 0.5f), r = 2f); Rect(214f, 89f, 220f, 4f, Dim.copy(alpha = 0.5f), r = 2f)
    PosterRow(8f, 136f, "StreamFlix · Popular", listOf("Harbour Town", "Night Shift", "Wild Planet", "Courtroom", "Ocean Deep", "City Lights"), on = true)
    PosterRow(8f, 222f, "StreamFlix · New", listOf("The Detective", "Story Time", "Quiz Night", "Live Sessions", "Drama Box", "Late Report"), on = false)
}

/** A show's title page: header, Play, seasons, episodes, tabs. */
@Composable
internal fun MockScope.TitleScreen() {
    Rect(24f, 14f, 66f, 99f, Color.Black, r = 4f, brush = Brush.verticalGradient(listOf(Color(0xFF8E3B46), Color(0xFF2B1D3A))))
    Label(104f, 12f, "HARBOUR TOWN", size = 20f, bold = true, w = 320f)
    Label(104f, 42f, "2024  ·  3 seasons  ·  24 episodes  ·  Drama, Mystery", size = 7f, color = Dim, w = 320f)
    Rect(104f, 55f, 26f, 10f, Color(0xFFF5C518), r = 2f); Label(133f, 55.5f, "8.1", size = 6.5f)
    Rect(152f, 55f, 34f, 10f, Color(0xFFE85D75), r = 2f); Label(189f, 55.5f, "82%", size = 6.5f)
    Rect(104f, 71f, 300f, 4f, Dim.copy(alpha = 0.5f), r = 2f); Rect(104f, 80f, 250f, 4f, Dim.copy(alpha = 0.5f), r = 2f)
    Rect(104f, 92f, 76f, 22f, AppColors.Accent, r = 11f)
    Label(116f, 98f, "▶  Play S1 · E1", size = 7.5f, bold = true, color = AppColors.OnAccent, w = 70f)
    Circle(186f, 92f, 22f, Color.Black.copy(alpha = 0.3f), border = Color.White.copy(alpha = 0.5f))
    Circle(214f, 92f, 22f, Color.Black.copy(alpha = 0.3f), border = Color.White.copy(alpha = 0.5f))
    listOf("Season 1", "Season 2", "Season 3").forEachIndexed { i, t ->
        Rect(24f + i * 52f, 128f, 47f, 14f, if (i == 0) AppColors.Accent.copy(alpha = 0.55f) else Cell, r = 7f)
        Label(31f + i * 52f, 131f, t, size = 6.5f, w = 40f)
    }
    listOf("1. Arrival", "2. Low Tide", "3. The Lighthouse", "4. Storm Warning", "5. Undertow").forEachIndexed { i, t ->
        val px = 24f + i * 120f
        val (a, b) = posterColours[(i + 2) % posterColours.size]
        Rect(px, 152f, 112f, 64f, Color.Black, r = 4f, brush = Brush.verticalGradient(listOf(Color(a), Color(b))))
        Label(px, 220f, t, size = 7f, w = 112f)
        Label(px, 230f, "44m  ·  12 Mar 2024", size = 6f, color = Dim, w = 112f)
    }
    listOf("Cast & crew", "Reviews", "More like this", "Details").forEachIndexed { i, t ->
        if (i == 0) Rect(22f, 250f, 58f, 14f, Cell, r = 7f)
        Label(28f + i * 70f, 253f, t, size = 6.5f, color = if (i == 0) Color.White else Dim, w = 66f)
    }
    for (i in 0 until 8) Circle(30f + i * 54f, 272f, 40f, Color(posterColours[i].first).copy(alpha = 0.6f))
}
