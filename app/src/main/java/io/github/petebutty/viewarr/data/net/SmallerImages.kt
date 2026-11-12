package io.github.petebutty.viewarr.data.net

import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.size.Dimension
import coil.size.Size

/**
 * Every picture asked for at the size it's shown, not bigger: TMDB's size steps, Plex's and Jellyfin's resize, metahub's
 * small / medium / large. Addons often hand over full-size originals; a poster card is a couple of hundred pixels wide.
 * On weak boxes ([weak]: little memory, or Low-memory mode) a size smaller again, downloaded and decoded.
 */
object SmallerImages : Interceptor {
    @Volatile var weak = false

    private val tmdb = Regex("""(image\.tmdb\.org/t/p/)(w\d+|original)(/)""")
    private val tmdbSteps = listOf(92, 154, 185, 342, 500, 780, 1280)
    private val metahub = Regex("""(images\.metahub\.space/(?:poster|background)/)(large|medium)(/)""")

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val req = chain.request
        val url = req.data as? String ?: return chain.proceed(req)
        val w = (chain.size.width as? Dimension.Pixels)?.px ?: return chain.proceed(req)
        val h = (chain.size.height as? Dimension.Pixels)?.px
        val f = if (weak) 0.75f else 1f
        val tw = (w * f).toInt().coerceAtLeast(64)
        val th = h?.let { (it * f).toInt().coerceAtLeast(64) }
        val smaller = shrink(url, tw, th)
        val sized = if (weak) chain.withSize(Size(tw, th ?: tw)) else chain
        return if (smaller == null || smaller == url) sized.proceed(req) else sized.proceed(req.newBuilder().data(smaller).build())
    }

    /** [url] asking for about [w] × [h] pixels, or null to leave it. */
    internal fun shrink(url: String, w: Int, h: Int?): String? {
        tmdb.find(url)?.let { m ->
            val now = m.groupValues[2].removePrefix("w").toIntOrNull() ?: Int.MAX_VALUE
            val want = tmdbSteps.firstOrNull { it >= w } ?: 1280
            return if (want < now) url.replaceRange(m.groups[2]!!.range, "w$want") else null
        }
        metahub.find(url)?.let { m ->
            val want = if (w <= 200) "small" else if (w <= 500) "medium" else "large"
            val rank = listOf("small", "medium", "large")
            return if (rank.indexOf(want) < rank.indexOf(m.groupValues[2])) url.replaceRange(m.groups[2]!!.range, want) else null
        }
        // Plex's picture transcoder: width / height kept in proportion.
        if ("/photo/:/transcode" in url) {
            val pw = Regex("""[?&]width=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val ph = Regex("""[?&]height=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (w >= pw) return null
            val nh = (ph.toLong() * w / pw).toInt().coerceAtLeast(1)
            return url.replace(Regex("""([?&])width=\d+"""), "$1width=$w").replace(Regex("""([?&])height=\d+"""), "$1height=$nh")
        }
        // Jellyfin / Emby: maxWidth or maxHeight.
        if ("/Images/" in url) {
            var out = url
            Regex("""([?&])maxWidth=(\d+)""").find(out)?.let { m -> if (w < m.groupValues[2].toInt()) out = out.replaceRange(m.range, "${m.groupValues[1]}maxWidth=$w") }
            if (h != null) Regex("""([?&])maxHeight=(\d+)""").find(out)?.let { m -> if (h < m.groupValues[2].toInt()) out = out.replaceRange(m.range, "${m.groupValues[1]}maxHeight=$h") }
            return out.takeIf { it != url }
        }
        return null
    }
}
