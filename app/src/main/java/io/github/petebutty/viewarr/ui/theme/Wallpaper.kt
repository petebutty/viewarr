package io.github.petebutty.viewarr.ui.theme

import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.max
import kotlin.math.roundToInt

/** The built-in Christmas wallpaper's name (falling snow goes with it). */
const val CHRISTMAS = "Christmas"

/** A built-in wallpaper: drawn, not a picture, so it's sharp at any size and costs nothing to load. */
class WallpaperStyle(val name: String, val draw: DrawScope.(highlight: Color) -> Unit)

val WallpaperStyles = listOf(
    WallpaperStyle("Aurora") { _ ->
        drawRect(Brush.linearGradient(listOf(Color(0xFF0B3D4A), Color(0xFF1B1446), Color(0xFF3B0F4A)), Offset.Zero, Offset(size.width, size.height)))
        drawRect(Brush.radialGradient(listOf(Color(0x8834D399), Color.Transparent), Offset(size.width * 0.2f, size.height * 0.15f), size.width * 0.55f))
        drawRect(Brush.radialGradient(listOf(Color(0x66A855F7), Color.Transparent), Offset(size.width * 0.85f, size.height * 0.8f), size.width * 0.5f))
    },
    WallpaperStyle("Sunset") { _ ->
        drawRect(Brush.verticalGradient(listOf(Color(0xFF2B1055), Color(0xFF7A1F4B), Color(0xFFD9653B))))
        drawRect(Brush.radialGradient(listOf(Color(0x88FFB347), Color.Transparent), Offset(size.width * 0.5f, size.height * 1.05f), size.width * 0.45f))
    },
    WallpaperStyle("Deep sea") { _ ->
        drawRect(Brush.verticalGradient(listOf(Color(0xFF02111F), Color(0xFF053B50), Color(0xFF0B6E77))))
        drawRect(Brush.radialGradient(listOf(Color(0x5538BDF8), Color.Transparent), Offset(size.width * 0.7f, 0f), size.width * 0.6f))
    },
    WallpaperStyle("Forest") { _ ->
        drawRect(Brush.linearGradient(listOf(Color(0xFF07170F), Color(0xFF14402A), Color(0xFF2F6B3A)), Offset(0f, size.height), Offset(size.width, 0f)))
        drawRect(Brush.radialGradient(listOf(Color(0x44FDE68A), Color.Transparent), Offset(size.width * 0.8f, size.height * 0.1f), size.width * 0.5f))
    },
    WallpaperStyle("Nebula") { _ ->
        drawRect(Color(0xFF07060F))
        drawRect(Brush.radialGradient(listOf(Color(0x99C026D3), Color.Transparent), Offset(size.width * 0.3f, size.height * 0.4f), size.width * 0.45f))
        drawRect(Brush.radialGradient(listOf(Color(0x882563EB), Color.Transparent), Offset(size.width * 0.7f, size.height * 0.6f), size.width * 0.5f))
        drawRect(Brush.radialGradient(listOf(Color(0x5522D3EE), Color.Transparent), Offset(size.width * 0.55f, size.height * 0.2f), size.width * 0.3f))
    },
    WallpaperStyle("Highlight glow") { h ->
        drawRect(Color(0xFF05060A))
        drawRect(Brush.radialGradient(listOf(h.copy(alpha = 0.55f), Color.Transparent), Offset(size.width, 0f), size.width * 0.8f))
        drawRect(Brush.radialGradient(listOf(h.copy(alpha = 0.25f), Color.Transparent), Offset(0f, size.height), size.width * 0.6f))
    },
    // (Added 0.27.3: dark, low-contrast and glassy, so the see-through panels and the highlight colour read well over them.)
    WallpaperStyle("Midnight") { _ ->
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0A1024), Color(0xFF060914), Color(0xFF030408))))
        drawRect(Brush.radialGradient(listOf(Color(0x333B82F6), Color.Transparent), Offset(size.width * 0.5f, -size.height * 0.1f), size.width * 0.6f))
        vignette()
    },
    WallpaperStyle("Ember") { _ ->
        drawRect(Brush.linearGradient(listOf(Color(0xFF0C0A09), Color(0xFF1C1210), Color(0xFF0C0A09)), Offset.Zero, Offset(size.width, size.height)))
        drawRect(Brush.radialGradient(listOf(Color(0x66F97316), Color.Transparent), Offset(size.width * 0.05f, size.height * 0.95f), size.width * 0.55f))
        drawRect(Brush.radialGradient(listOf(Color(0x33DC2626), Color.Transparent), Offset(size.width * 0.3f, size.height * 1.1f), size.width * 0.4f))
        vignette()
    },
    WallpaperStyle("Frosted glass") { _ ->
        drawRect(Color(0xFF111827))
        drawRect(Brush.radialGradient(listOf(Color(0x4494A3B8), Color.Transparent), Offset(size.width * 0.15f, size.height * 0.2f), size.width * 0.35f))
        drawRect(Brush.radialGradient(listOf(Color(0x3364748B), Color.Transparent), Offset(size.width * 0.75f, size.height * 0.3f), size.width * 0.4f))
        drawRect(Brush.radialGradient(listOf(Color(0x3394A3B8), Color.Transparent), Offset(size.width * 0.5f, size.height * 0.95f), size.width * 0.45f))
        drawRect(Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color.Transparent, Color(0x33000000))))
    },
    WallpaperStyle("Twilight") { _ ->
        drawRect(Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF172554), Color(0xFF042F2E)), Offset(0f, 0f), Offset(size.width, size.height)))
        drawRect(Brush.radialGradient(listOf(Color(0x44818CF8), Color.Transparent), Offset(size.width * 0.85f, size.height * 0.1f), size.width * 0.45f))
        vignette()
    },
    WallpaperStyle("Cinema") { _ ->
        drawRect(Color(0xFF050505))
        drawRect(Brush.radialGradient(listOf(Color(0x2EFFFFFF), Color.Transparent), Offset(size.width * 0.5f, -size.height * 0.2f), size.width * 0.55f))
        drawRect(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0x66B91C1C)))
        vignette(0.85f)
    },
    WallpaperStyle("Graphite") { h ->
        drawRect(Brush.linearGradient(listOf(Color(0xFF1F2023), Color(0xFF111113), Color(0xFF09090B)), Offset(0f, 0f), Offset(size.width * 0.6f, size.height)))
        drawRect(Brush.radialGradient(listOf(h.copy(alpha = 0.18f), Color.Transparent), Offset(size.width, size.height * 0.5f), size.width * 0.45f))
        vignette()
    },
    WallpaperStyle("Highlight mesh") { h ->
        drawRect(Color(0xFF07080C))
        drawRect(Brush.radialGradient(listOf(h.copy(alpha = 0.35f), Color.Transparent), Offset(size.width * 0.1f, size.height * 0.1f), size.width * 0.45f))
        drawRect(Brush.radialGradient(listOf(h.copy(alpha = 0.22f), Color.Transparent), Offset(size.width * 0.9f, size.height * 0.4f), size.width * 0.4f))
        drawRect(Brush.radialGradient(listOf(h.copy(alpha = 0.28f), Color.Transparent), Offset(size.width * 0.4f, size.height * 1.0f), size.width * 0.45f))
        vignette()
    },
    WallpaperStyle("Plum & gold") { _ ->
        drawRect(Brush.linearGradient(listOf(Color(0xFF1A0B1F), Color(0xFF2E1035), Color(0xFF14081A)), Offset(0f, size.height), Offset(size.width, 0f)))
        drawRect(Brush.radialGradient(listOf(Color(0x44EAB308), Color.Transparent), Offset(size.width * 0.8f, size.height * 0.85f), size.width * 0.45f))
        vignette()
    },
    WallpaperStyle("Arctic") { _ ->
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF0B2533), Color(0xFF071318))))
        drawRect(Brush.radialGradient(listOf(Color(0x4467E8F9), Color.Transparent), Offset(size.width * 0.25f, size.height * 0.05f), size.width * 0.5f))
        drawRect(Brush.radialGradient(listOf(Color(0x22E0F2FE), Color.Transparent), Offset(size.width * 0.9f, size.height * 0.9f), size.width * 0.35f))
        vignette()
    },
    WallpaperStyle("Emerald night") { _ ->
        drawRect(Brush.linearGradient(listOf(Color(0xFF021C17), Color(0xFF052E26), Color(0xFF020F0C)), Offset(0f, 0f), Offset(size.width, size.height)))
        drawRect(Brush.radialGradient(listOf(Color(0x4410B981), Color.Transparent), Offset(size.width * 0.7f, size.height * 0.2f), size.width * 0.45f))
        vignette()
    },
    // (2026-11-21: a winter night with warm fairy lights; with it, snow falls behind the pages: ChristmasSnow.)
    WallpaperStyle(CHRISTMAS) { _ ->
        drawRect(Brush.verticalGradient(listOf(Color(0xFF071226), Color(0xFF0B1D3A), Color(0xFF14284A))))
        drawRect(Brush.radialGradient(listOf(Color(0x55D4A64B), Color.Transparent), Offset(size.width * 0.15f, size.height * 1.05f), size.width * 0.45f))
        drawRect(Brush.radialGradient(listOf(Color(0x44B91C1C), Color.Transparent), Offset(size.width * 0.9f, size.height * 0.95f), size.width * 0.4f))
        drawRect(Brush.radialGradient(listOf(Color(0x3310B981), Color.Transparent), Offset(size.width * 0.6f, size.height * 1.1f), size.width * 0.35f))
        // Fairy lights along the top: soft warm dots on a gentle curve.
        val n = 26
        for (k in 0 until n) {
            val x = size.width * (k + 0.5f) / n
            val y = size.height * (0.035f + 0.025f * kotlin.math.sin(k * 0.7f))
            val c = when (k % 4) { 0 -> Color(0xFFFFD27A); 1 -> Color(0xFFFF6B6B); 2 -> Color(0xFF7CE0A3); else -> Color(0xFF8EC5FF) }
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.5f), Color.Transparent), Offset(x, y), size.minDimension * 0.03f), size.minDimension * 0.03f, Offset(x, y))
            drawCircle(c, size.minDimension * 0.004f, Offset(x, y))
        }
        // Snow lying along the bottom.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x22FFFFFF)), startY = size.height * 0.85f, endY = size.height))
        vignette()
    },
)

/** Darker towards the edges, so the middle (where pages put their text) stays calm. */
private fun DrawScope.vignette(strength: Float = 0.7f) {
    drawRect(Brush.radialGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = strength), center = Offset(size.width / 2f, size.height / 2f), radius = max(size.width, size.height) * 0.75f))
}

/** Setting value → name. ("From your library", 2, was tried and removed: treated as None.) */
val WallpaperModes = listOf(0 to "None", 1 to "Built-in", 3 to "What's selected")

/** One picture with a cross-fade from the one before. */
internal class PictureLayer {
    var image by mutableStateOf<ImageBitmap?>(null)
    var previous by mutableStateOf<ImageBitmap?>(null)
    var fade by mutableFloatStateOf(1f)
    fun clear() { image = null; previous = null }
}

/** What the picture wallpapers show. Pages report what's highlighted and the library's artwork here. */
object Wallpaper {
    /** Artwork of the highlighted programme in the guide ("What's selected"). */
    var selected by mutableStateOf<String?>(null)
    /** Artwork of the highlighted title on the Movies / Shows pages (always their background). */
    var title by mutableStateOf<String?>(null)

    /** Movies and Shows pages open: their artwork is loaded. */
    var titlePages by mutableStateOf(0)

    /**
     * Movies or Shows is the page on screen: the app's own background (behind the menu's glass) shows its artwork too,
     * not the guide's programme picture (it kept that behind the menu while the page beside it showed the film's.
     * Peter, 2026-11-17). Set by the home screen.
     */
    var titleBehind by mutableStateOf(false)

    /** The TV guide is the page on screen (Wallpaper in the TV guide: Off draws a plain background there). Set by the home screen. */
    var guideOnScreen by mutableStateOf(false)

    /** Movies / Shows use the highlighted title's artwork as their background (Settings → Appearance → Wallpaper). */
    val titleArtOn get() = AppColors.palette.opt(UiOpts.TITLE_BG) == 0

    /** The wallpaper (setting) and the Movies / Shows artwork are separate pictures: a page over Movies (e.g. Settings)
     *  shows the wallpaper, not the film. */
    internal val wall = PictureLayer()
    internal val titleArt = PictureLayer()

}

/** Loads the pictures for the wallpaper and the Movies / Shows pages. Once, at the root. */
@Composable
fun WallpaperLoader() {
    val context = LocalContext.current
    // The Movies / Shows artwork's finished pictures, drawn into again for the next title (each new one was two fresh
    // 3.6 MB bitmaps, the decoded one and the shaded one, so moving along a row kept the memory cleaner busy).
    val spare = androidx.compose.runtime.remember { ArrayList<android.graphics.Bitmap>(3) }
    suspend fun show(layer: PictureLayer, url: String?) {
        if (url == null) return
        val titleArt = layer === Wallpaper.titleArt
        val result = runCatching {
            context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(1280, 720).allowHardware(false)
                // (The artwork's decoded copy is only an ingredient of the shaded one: kept out of the picture cache, where
                // each 3.6 MB copy pushed out a dozen posters that then had to load again.)
                .apply { if (titleArt) memoryCachePolicy(coil.request.CachePolicy.DISABLED) }
                .build())
        }.getOrNull() as? SuccessResult ?: return
        val raw = runCatching { result.drawable.toBitmap() }.getOrNull() ?: return
        // The Movies / Shows artwork comes ready-shaded (one picture, drawn once a frame): at 4K the backdrop plus its
        // background colour and two shading gradients were four full-screen passes on every frame of a row's slide,
        // and the graphics chip ran at ~45 ms a frame (Peter, 2026-11-19: Cinema's slide looked jerky).
        // (Shaded away from the main thread, into a spare picture not on screen: neither showing nor fading out.)
        val baked: android.graphics.Bitmap? = if (titleArt) {
            val main = AppColors.palette.main; val shade = AppColors.palette.shade
            val inUse = listOfNotNull(layer.image?.asAndroidBitmap(), layer.previous?.asAndroidBitmap())
            val into = spare.firstOrNull { b -> inUse.none { it === b } }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { bakeTitleArt(raw, main, shade, into) }.getOrNull()
            }?.also { out ->
                if (spare.none { it === out } && spare.size < 3) spare += out
                if (out !== raw) raw.recycle()
            }
        } else null
        val bmp = (baked ?: raw).asImageBitmap()
        // A soft cross-fade from the last picture (it only runs once the highlight has rested, so it doesn't cost
        // frames while moving through the guide; a newer pick cancels it and fades on from where it was).
        layer.previous = layer.image ?: layer.previous
        layer.image = bmp
        layer.fade = 0f
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (layer.fade < 1f) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            // (Cinema: a slower, eased cross-fade between backdrops.)
            val dur = if (AppColors.palette.opt(UiOpts.HERO_STYLE) == 1) 1_000_000_000f else 700_000_000f
            val t = ((now - start) / dur).coerceIn(0f, 1f)
            layer.fade = if (AppColors.palette.opt(UiOpts.HERO_STYLE) == 1) androidx.compose.animation.core.FastOutSlowInEasing.transform(t) else t
        }
        layer.previous = null
    }
    LaunchedEffect(Unit) {
        snapshotFlow { AppColors.palette.wallpaperMode to Wallpaper.selected }.collectLatest { (mode, selected) ->
            when (mode) {
                3 -> {
                    // Focus moves quickly through the guide: only settle on what you stop at.
                    delay(500)
                    show(Wallpaper.wall, selected)
                }
                else -> Wallpaper.wall.clear()
            }
        }
    }
    LaunchedEffect(Unit) {
        // Low-memory mode: no full-screen artwork behind title pages.
        snapshotFlow { (Wallpaper.titlePages > 0 && AppColors.palette.heavyExtras && Wallpaper.titleArtOn) to Wallpaper.title }.collectLatest { (open, url) ->
            if (!open) { Wallpaper.titleArt.clear(); spare.clear(); return@collectLatest }
            delay(300)
            show(Wallpaper.titleArt, url)
        }
    }
}

/**
 * A page's background: the background colour, or the wallpaper with the background colour over it (how much
 * depends on the dimming setting). Use on full-screen pages instead of a plain background. [titleArt]: the Movies /
 * Shows pages, whose background is always the highlighted title's artwork.
 */
fun Modifier.appBackground(titleArt: Boolean = false, readable: Boolean = false): Modifier = drawBehind {
    val p = AppColors.palette
    val sh = p.shade
    // Settings pages: a picture wallpaper (e.g. the highlighted programme's) darkened further, most behind the text.
    if (readable && p.wallpaperMode == 3) {
        if (Wallpaper.wall.image == null) { drawRect(p.main); return@drawBehind }
        drawRect(Color.Black)
        drawLayer(Wallpaper.wall)
        // Darker than the pages for the text, but in step with Dimming (a 70% floor made 30–70% all look alike).
        drawRect(sh.copy(alpha = 0.55f + 0.45f * p.wallpaperDim))
        drawRect(Brush.horizontalGradient(0f to sh.copy(alpha = 0.6f), 0.45f to sh.copy(alpha = 0.35f), 1f to sh.copy(alpha = 0.15f)))
        return@drawBehind
    }
    // TV guide with "Wallpaper in the TV guide" off: the plain background colour.
    if (!titleArt && Wallpaper.guideOnScreen && p.opt(UiOpts.GUIDE_WALL) == 1) { drawRect(p.main); return@drawBehind }
    // Movies and Shows background: None (a tester, 2026-11-19: no picture there at all, whatever the wallpaper).
    if (titleArt && p.opt(UiOpts.TITLE_BG) == 2) { drawRect(p.main); return@drawBehind }
    if (titleArt && Wallpaper.titleArtOn) {
        // The title's artwork, already shaded (bakeTitleArt): darker on the left behind the text and toward the rows
        // below, clearer to the right. One picture over the background colour; just the picture once it's there.
        // Only the top right of the page (about half the width, 60% of the height), fading into the plain
        // background on its left and lower edges; the rest of the page is just the background colour (Peter,
        // 2026-11-19: as WuPlay, far less to paint than full-screen artwork at 4K).
        val layer = Wallpaper.titleArt
        drawRect(p.main)
        val img = layer.image
        if (img != null) {
            val left = size.width * ART_LEFT
            val w = size.width - left
            val h = w * 9f / 16f
            layer.previous?.takeIf { layer.fade < 1f }?.let { drawImage(it, dstOffset = IntOffset(left.roundToInt(), 0), dstSize = IntSize(w.roundToInt(), h.roundToInt())) }
            drawImage(img, dstOffset = IntOffset(left.roundToInt(), 0), dstSize = IntSize(w.roundToInt(), h.roundToInt()), alpha = layer.fade)
        }
        return@drawBehind
    }
    when (p.wallpaperMode) {
        1 -> {
            WallpaperStyles.getOrElse(p.wallpaperStyle) { WallpaperStyles[0] }.draw(this, p.highlight)
            drawRect(sh.copy(alpha = p.wallpaperDim))
            // (Christmas: the falling snow, read from its clock here, so only this background is drawn again.)
            if (Snow.running) drawSnow()
        }
        3, 4 -> {
            if (Wallpaper.wall.image == null) { drawRect(p.main); return@drawBehind }
            drawRect(Color.Black)
            drawLayer(Wallpaper.wall)
            drawRect(sh.copy(alpha = p.wallpaperDim))
        }
        else -> drawRect(p.main)
    }
}

private fun DrawScope.drawLayer(layer: PictureLayer) {
    val img = layer.image ?: return
    layer.previous?.takeIf { layer.fade < 1f }?.let { drawCover(it, 1f) }
    drawCover(img, layer.fade)
}

/**
 * The title's artwork as the Movies / Shows background, shaded once: cropped to 16:9 at 1280×720 on the background
 * colour, darkened on the left (behind the text) and toward the bottom (behind the rows), clearer to the right.
 * The same look the page used to paint with two gradients on every frame.
 */
private fun bakeTitleArt(src: android.graphics.Bitmap, main: Color, shade: Color, reuse: android.graphics.Bitmap? = null): android.graphics.Bitmap {
    val w = 1280; val h = 720
    // (A spare one of the right kind is painted over whole: the background colour first covers what it held.)
    val out = reuse?.takeIf { it.isMutable && !it.isRecycled && it.width == w && it.height == h && it.config == android.graphics.Bitmap.Config.ARGB_8888 }
        ?: android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(main.toArgbInt(), android.graphics.PorterDuff.Mode.SRC)
    val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
    val sw = (w / scale).roundToInt().coerceAtMost(src.width); val sh = (h / scale).roundToInt().coerceAtMost(src.height)
    val sx = (src.width - sw) / 2; val sy = (src.height - sh) / 2
    c.drawBitmap(src, android.graphics.Rect(sx, sy, sx + sw, sy + sh), android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    // Melting into the page's own colour at the left and bottom edges (so the picture has no visible border), with a
    // touch of shade over the rest.
    fun m(f: Float) = main.copy(alpha = f).toArgbInt()
    fun a(f: Float) = shade.copy(alpha = f).toArgbInt()
    c.drawColor(a(0.15f))
    val across = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(m(1f), m(0.75f), m(0.2f), m(0f)), floatArrayOf(0f, 0.18f, 0.45f, 0.7f), android.graphics.Shader.TileMode.CLAMP)
    }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), across)
    val down = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(m(0f), m(0f), m(0.7f), m(1f)), floatArrayOf(0f, 0.5f, 0.82f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), down)
    return out
}

private fun Color.toArgbInt(): Int = this.toArgb()

/** Where the Movies / Shows artwork starts across the page (the rest to the left is the plain background). */
private const val ART_LEFT = 0.42f

/** Fills the area with the picture, cropped to fit (like ContentScale.Crop). */
private fun DrawScope.drawCover(img: ImageBitmap, alpha: Float) {
    val scale = max(size.width / img.width, size.height / img.height)
    val w = (size.width / scale).roundToInt().coerceAtMost(img.width)
    val h = (size.height / scale).roundToInt().coerceAtMost(img.height)
    drawImage(
        img,
        srcOffset = IntOffset((img.width - w) / 2, (img.height - h) / 2),
        srcSize = IntSize(w, h),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha,
    )
}

/**
 * Falling snow, part of the Christmas wallpaper itself: every page that paints the wallpaper (the guide, Settings,
 * Movies…) draws it behind its own content, so it falls everywhere and never over text (Peter, 2026-11-21: it only
 * showed on the TV guide, as every other page covered it with its own copy of the wallpaper). One clock for the app
 * ([ChristmasSnow] runs it, 30 frames a second); each page only draws, nothing is laid out again. It stops while a film
 * or live TV is full screen and when Viewarr is in the background, and isn't there with Low-memory mode.
 */
object Snow {
    val time = androidx.compose.runtime.mutableFloatStateOf(0f)
    @Volatile var running = false
    internal val flakes: List<FloatArray> = java.util.Random(7).let { r ->
        // x, y (0..1), size, fall speed (screens a second), sway phase
        List(90) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.4f + r.nextFloat() * 1.1f, 0.03f + r.nextFloat() * 0.05f, r.nextFloat() * 6.28f) }
    }
    fun wanted(p: Palette = AppColors.palette) = p.wallpaperMode == 1 && WallpaperStyles.getOrNull(p.wallpaperStyle)?.name == CHRISTMAS && p.opt(UiOpts.SNOW) == 0
}

internal fun DrawScope.drawSnow() {
    if (!Snow.running) return
    val t = Snow.time.floatValue
    val w = size.width; val h = size.height; val unit = size.minDimension / 540f
    for (f in Snow.flakes) {
        val y = ((f[1] + t * f[3]) % 1.1f) * h - 0.05f * h
        val x = ((f[0] + 0.02f * kotlin.math.sin(t * 0.6f + f[4])) % 1f) * w
        drawCircle(Color.White.copy(alpha = 0.35f + 0.4f * (f[2] / 1.5f)), f[2] * 1.6f * unit, Offset(x, y))
    }
}

/** The snow's clock (one for the app): runs only while it's wanted and something's to see. Draws nothing itself. */
@androidx.compose.runtime.Composable
fun ChristmasSnow() {
    val on = Snow.wanted() && !io.github.petebutty.viewarr.ui.media.VodHost.covering && !io.github.petebutty.viewarr.ui.common.LiveBehind.fullScreen
    Snow.running = on
    if (!on) return
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    if (now - last >= 33_000_000L) { Snow.time.floatValue = now / 1_000_000_000f; last = now }
                }
            }
        }
    }
}
