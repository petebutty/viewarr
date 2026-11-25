package io.github.petebutty.viewarr.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.ui.media.MediaViewModel

/**
 * The streaming services' own look on the launcher's home screen: each service's tile in its colours with its wordmark
 * filling it ("NETFLIX" in red on black, "prime video" in white on blue…), and the wordmark in the banner in place of
 * its name. Wordmarks are TMDB's network logos ([networks]: candidate ids, used only when TMDB's name for it matches,
 * so a wrong id can't show another channel's logo); without one, the service's app icon.
 */
internal object LauncherBrands {
    /** [bg]: the tile's colours (top to bottom); [keepColour]: the wordmark as it is (else drawn white). */
    class Brand(val bg: List<Color>, val keepColour: Boolean, val networks: List<Int>, val names: List<String>)

    private fun b(bg: Long, bg2: Long, keep: Boolean, networks: List<Int>, vararg names: String) =
        Brand(listOf(Color(bg), Color(bg2)), keep, networks, names.toList())

    private val brands = mapOf(
        "netflix" to b(0xFF000000, 0xFF141414, true, listOf(213), "netflix"),
        "prime video" to b(0xFF00A8E1, 0xFF0070A8, false, listOf(1024), "prime video", "amazon"),
        "disney+" to b(0xFF0B1F5C, 0xFF04103A, false, listOf(2739), "disney+", "disney plus"),
        "apple tv+" to b(0xFF1C1C1E, 0xFF000000, false, listOf(2552), "apple tv"),
        "hbo max" to b(0xFF002BE7, 0xFF001A8C, false, listOf(3186), "max"),
        "paramount+" to b(0xFF0064FF, 0xFF0034A3, false, listOf(4330), "paramount"),
        "hulu" to b(0xFF0B0C0F, 0xFF1A1D23, true, listOf(453), "hulu"),
        "peacock" to b(0xFF000000, 0xFF161616, false, listOf(3353), "peacock"),
        "now" to b(0xFF0B0B0B, 0xFF222222, false, listOf(4223), "now"),
        "bbc iplayer" to b(0xFFF54997, 0xFF8A1A7A, false, listOf(3311), "iplayer"),
        "itvx" to b(0xFF101010, 0xFF2A2A2A, false, listOf(5994), "itvx"),
        "channel 4" to b(0xFF101010, 0xFF2B2B2B, false, listOf(26), "channel 4"),
        "crunchyroll" to b(0xFFF47521, 0xFFC4550E, false, listOf(1112), "crunchyroll"),
        "discovery+" to b(0xFF0A2AF0, 0xFF06168C, false, listOf(4353), "discovery"),
        "shudder" to b(0xFF000000, 0xFF1A0000, false, listOf(2690), "shudder"),
        // TV channels: TMDB's network logo when its name matches, else their see-through logo from the channel's own
        // row, drawn white. (2026-11-25: FX and Sky Atlantic had neither, and showed an empty tile.)
        "hbo" to b(0xFF000000, 0xFF2A2A2A, false, listOf(49), "hbo"),
        "fx" to b(0xFF0A0A0A, 0xFF262626, false, listOf(88), "fx"),
        "amc" to b(0xFF0A0A0A, 0xFF262626, false, listOf(174), "amc"),
        "fox" to b(0xFF003B7A, 0xFF00224A, false, listOf(19), "fox"),
        "sky atlantic" to b(0xFF0A1A3A, 0xFF050C1E, false, listOf(1063), "sky atlantic"),
    )

    fun brand(service: String): Brand? = brands[service.substringBefore(" · ").trim().lowercase()]

    /**
     * The service's wordmark (a see-through picture), or null: its brand's TMDB network logo, else the see-through logo
     * Viewarr already has for a TV channel's row (HBO, FX, AMC, FOX…: CatalogRepository.serviceLogo). Drawn white:
     * those were black on the dark tile, so the tile looked empty (2026-11-25).
     */
    @Composable
    fun wordmark(service: String, vm: MediaViewModel): String? {
        val br = brand(service)
        val url by produceState<String?>(null, service) {
            value = br?.networks?.firstNotNullOfOrNull { id ->
                runCatching { vm.networkLogo(id) }.getOrNull()?.takeIf { (name, _) -> br.names.any { name.lowercase().contains(it) } }?.second
            } ?: runCatching { vm.serviceLogo(service) }.getOrNull()?.takeIf { !it.second }?.first
        }
        return url
    }

    /**
     * A service's tile face: its colours, its wordmark filling the tile. No wordmark: its app icon at the tile's full
     * height, on the icon's own background colour (read from its corner), so it still fills the tile (2026-11-25: NOW,
     * iPlayer and ITVX showed a small icon in an empty tile); [fallback] while there's no icon either.
     */
    @Composable
    fun TileFace(service: String, vm: MediaViewModel, fallback: @Composable () -> Unit) {
        if (LauncherUkLogos.has(service)) { LauncherUkLogos.TileFace(service); return }
        val br = brand(service)
        val mark = wordmark(service, vm)
        if (mark != null) {
            Box(Modifier.fillMaxSize().then(if (br != null) Modifier.background(Brush.verticalGradient(br.bg)) else Modifier), contentAlignment = Alignment.Center) {
                AsyncImage(mark, null, contentScale = ContentScale.Fit,
                    colorFilter = if (br?.keepColour == true) null else ColorFilter.tint(Color.White),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 20.dp))
            }
            return
        }
        val icon by produceState<Pair<String, Boolean>?>(null, service) { value = runCatching { vm.serviceLogo(service) }.getOrNull() }
        val url = icon?.first
        if (url == null) { fallback(); return }
        var edge by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf<Color?>(null) }
        val context = androidx.compose.ui.platform.LocalContext.current
        val request = androidx.compose.runtime.remember(url) {
            coil.request.ImageRequest.Builder(context).data(url).allowHardware(false).build()
        }
        Box(Modifier.fillMaxSize().background(edge ?: br?.bg?.first() ?: Color(0xFF1E2230)), contentAlignment = Alignment.Center) {
            AsyncImage(request, null, contentScale = ContentScale.Fit,
                onSuccess = { st ->
                    runCatching {
                        val bmp = (st.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@runCatching
                        // Its background colour: the most common of the four corners' pixels (an icon on white, a
                        // coloured square…).
                        val px = listOf(bmp.getPixel(2, 2), bmp.getPixel(bmp.width - 3, 2), bmp.getPixel(2, bmp.height - 3), bmp.getPixel(bmp.width - 3, bmp.height - 3))
                        val c = px.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
                        if (android.graphics.Color.alpha(c) > 200) edge = Color(c)
                    }
                },
                modifier = Modifier.fillMaxSize())
        }
    }

    /** The wordmark in the banner, [height] tall; false: none (the name is written instead). */
    @Composable
    fun BannerMark(service: String, vm: MediaViewModel, height: Dp): Boolean {
        if (LauncherUkLogos.has(service)) { LauncherUkLogos.Mark(service, height * 0.8f); return true }
        val br = brand(service)
        val mark = wordmark(service, vm) ?: return false
        AsyncImage(mark, null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            colorFilter = if (br?.keepColour == true) null else ColorFilter.tint(Color.White),
            modifier = Modifier.padding(vertical = 4.dp).height(height).width(380.dp))
        return true
    }
}
