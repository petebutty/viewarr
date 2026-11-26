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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.home.MenuIcons

/**
 * A library's tile on the Library page, made from its name: words in it pick the colours and the symbol ("Anime",
 * "Kids", "4K", "Documentaries"…), words like "Subs" or "Dual Audio" become a small badge, and a picture from the
 * library itself sits faintly behind. A name with no word it knows gets the Movies or TV Shows colours.
 */
internal object LibraryTiles {
    class Style(val from: Color, val to: Color, val mark: String?)

    private class Theme(val words: Regex, val style: Style)

    private fun theme(words: String, from: Long, to: Long, mark: String) =
        Theme(Regex("\\b($words)", RegexOption.IGNORE_CASE), Style(Color(from), Color(to), mark))

    // (First match wins: the more particular themes first.)
    private val themes = listOf(
        theme("christmas|xmas|festive|holiday", 0xFFDC2626, 0xFF14532D, "🎄"),
        theme("halloween|horror|scary", 0xFFEA580C, 0xFF1C1917, "🎃"),
        theme("anime|manga", 0xFFEC4899, 0xFF4C1D95, "🌸"),
        theme("kids|children|family|cartoon|animation|animated|disney|pixar", 0xFFFBBF24, 0xFFC2410C, "🎈"),
        theme("documentar|docs\\b|nature|history", 0xFF16A34A, 0xFF14342B, "🏔"),
        theme("concert|music|gigs", 0xFFA855F7, 0xFF3B0764, "🎵"),
        theme("stand-?up|comedy|comedies", 0xFFF97316, 0xFF7C2D12, "🎤"),
        theme("sport|football|soccer|f1\\b|formula|wrestling|ufc|boxing|nba|nfl", 0xFF22C55E, 0xFF14532D, "⚽"),
        theme("sci-?fi|science fiction|space", 0xFF3B82F6, 0xFF0F172A, "🚀"),
        theme("classic|vintage|golden age", 0xFFB45309, 0xFF292524, "🎞"),
        theme("4k|uhd|2160|hdr|dolby|remux", 0xFFCA8A04, 0xFF0A0A0A, "✨"),
        theme("foreign|world|international|korean|japanese|french|german|spanish|italian|hindi|bollywood|nordic|asian", 0xFF14B8A6, 0xFF134E4A, "🌍"),
    )

    private val movies = Style(Color(0xFFF59E0B), Color(0xFF6B2108), null)
    private val shows = Style(Color(0xFF8B5CF6), Color(0xFF2A2470), null)

    /** [name] without its accents ("Animé" reads as "Anime"). */
    private fun plain(name: String) = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun styleOf(name: String, type: MediaType): Style =
        plain(name).let { n -> themes.firstOrNull { it.words.containsMatchIn(n) }?.style } ?: if (type == MediaType.Show) shows else movies

    private val badges = listOf(
        Regex("dual[ -]?audio", RegexOption.IGNORE_CASE) to "DUAL AUDIO",
        Regex("\\bsub(s|bed|titled)?\\b", RegexOption.IGNORE_CASE) to "SUBS",
        Regex("\\bdub(s|bed)?\\b", RegexOption.IGNORE_CASE) to "DUBS",
        Regex("\\b(4k|uhd|2160p?)\\b", RegexOption.IGNORE_CASE) to "4K",
        Regex("\\bforeign\\b", RegexOption.IGNORE_CASE) to "FOREIGN",
    )

    /** The small badge for [name] ("SUBS", "4K"…), if any of its words asks for one. */
    fun badgeOf(name: String): String? = plain(name).let { n -> badges.firstOrNull { it.first.containsMatchIn(n) }?.second }

    /** The tile's face: [art] (a picture from the library) faintly behind its colours, its symbol, name and server. */
    @Composable
    fun Face(name: String, source: String, type: MediaType, art: String?, count: Int?) {
        val style = styleOf(name, type)
        val badge = badgeOf(name)
        Box(Modifier.fillMaxSize().background(style.to)) {
            art?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.55f)) }
            // The library's colours over the picture: strong at the top left, letting it through at the right.
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                listOf(style.from.copy(alpha = 0.92f), style.to.copy(alpha = 0.78f), style.to.copy(alpha = 0.45f)),
                start = Offset.Zero, end = Offset(620f, 360f))))
            // The symbol, large and faint, running off the right edge.
            if (style.mark != null) Text(style.mark, fontSize = 74.sp, modifier = Modifier.align(Alignment.CenterEnd).offset(x = 14.dp).rotate(-12f).alpha(0.5f))
            else MenuIcons.vector(if (type == MediaType.Show) "Shows" else "Movies")?.let {
                androidx.compose.material3.Icon(it, null, tint = Color.White.copy(alpha = 0.18f),
                    modifier = Modifier.align(Alignment.CenterEnd).offset(x = 24.dp).size(120.dp).rotate(-12f))
            }
            // A shade at the foot so the name reads over any picture.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 11.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(name, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    lineHeight = 22.sp, letterSpacing = 0.2.sp)
                Text(listOfNotNull(source, count?.takeIf { it >= 0 }?.let { "%,d".format(it) + if (type == MediaType.Show) " shows" else " films" }).joinToString("  ·  "),
                    fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
            }
            if (badge != null) Row(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)
                .background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(badge, fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color(0xFF111111), letterSpacing = 0.8.sp)
            }
        }
    }
}
