package io.github.petebutty.viewarr.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Logos drawn here for the UK channels and players whose pictures from TMDB don't work on a tile (BBC One, BBC Two,
 * BBC iPlayer, ITV1, ITVX: an app icon or a logo that drew as a blank or a blob; 2026-11-25). Each is its own colours
 * and lettering, in the style of the real one, at any size ([h]: the logo's height).
 */
internal object LauncherUkLogos {
    private class Drawn(val bg: List<Color>, val mark: @Composable (Dp) -> Unit)

    @Composable
    private fun BbcBlocks(h: Dp, box: Color = Color.White, letter: Color = Color.Black) {
        Row(horizontalArrangement = Arrangement.spacedBy(h * 0.12f)) {
            listOf("B", "B", "C").forEach { c ->
                Box(Modifier.size(h).background(box), contentAlignment = Alignment.Center) {
                    Text(c, fontSize = (h.value * 0.74f).sp, fontWeight = FontWeight.Black, color = letter, maxLines = 1)
                }
            }
        }
    }

    @Composable
    private fun BbcChannel(h: Dp, word: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(h * 0.25f)) {
            BbcBlocks(h * 0.55f)
            Text(word, fontSize = (h.value * 0.62f).sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, letterSpacing = (h.value * 0.02f).sp)
        }
    }

    private val logos: Map<String, Drawn> = mapOf(
        "bbc one" to Drawn(listOf(Color(0xFFE5002E), Color(0xFF7A0019))) { h -> BbcChannel(h, "ONE") },
        "bbc two" to Drawn(listOf(Color(0xFF00A99D), Color(0xFF005A54))) { h -> BbcChannel(h, "TWO") },
        "bbc iplayer" to Drawn(listOf(Color(0xFFF54997), Color(0xFF8C1F8F))) { h ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(h * 0.06f)) {
                BbcBlocks(h * 0.34f)
                Text("iPlayer", fontSize = (h.value * 0.52f).sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
            }
        },
        "itv1" to Drawn(listOf(Color(0xFF1C2D6E), Color(0xFF0B1433))) { h ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text("itv", fontSize = (h.value * 0.78f).sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, letterSpacing = (-h.value * 0.02f).sp)
                Text("1", fontSize = (h.value * 0.78f).sp, fontWeight = FontWeight.Black, color = Color(0xFF4FD1C5), maxLines = 1)
            }
        },
        "itvx" to Drawn(listOf(Color(0xFFDEEB52), Color(0xFFB9C93A))) { h ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text("itv", fontSize = (h.value * 0.78f).sp, fontWeight = FontWeight.Black, color = Color(0xFF1A1A1A), maxLines = 1, letterSpacing = (-h.value * 0.02f).sp)
                Text("X", fontSize = (h.value * 0.78f).sp, fontWeight = FontWeight.Black, color = Color(0xFF1A1A1A), maxLines = 1)
            }
        },
    )

    fun has(service: String) = key(service) in logos
    private fun key(service: String) = service.substringBefore(" · ").trim().lowercase()

    /** The logo filling a tile, on its colours. */
    @Composable
    fun TileFace(service: String) {
        val d = logos[key(service)] ?: return
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(d.bg)), contentAlignment = Alignment.Center) { d.mark(34.dp) }
    }

    /** The logo alone (the banner), [h] tall. */
    @Composable
    fun Mark(service: String, h: Dp) { logos[key(service)]?.mark?.invoke(h) }
}
