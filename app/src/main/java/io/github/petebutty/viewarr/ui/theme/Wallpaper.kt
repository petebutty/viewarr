package io.github.petebutty.viewarr.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
)

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

    /** The wallpaper (setting) and the Movies / Shows artwork are separate pictures: a page over Movies (e.g. Settings)
     *  shows the wallpaper, not the film. */
    internal val wall = PictureLayer()
    internal val titleArt = PictureLayer()
}

/** Loads the pictures for the wallpaper and the Movies / Shows pages. Once, at the root. */
@Composable
fun WallpaperLoader() {
    val context = LocalContext.current
    suspend fun show(layer: PictureLayer, url: String?) {
        if (url == null) return
        val result = runCatching {
            context.imageLoader.execute(ImageRequest.Builder(context).data(url).size(1280, 720).allowHardware(false).build())
        }.getOrNull() as? SuccessResult ?: return
        val bmp = runCatching { result.drawable.toBitmap().asImageBitmap() }.getOrNull() ?: return
        layer.previous = layer.image
        layer.image = bmp
        val fade = Animatable(0f)
        layer.fade = 0f
        fade.animateTo(1f, tween(900)) { layer.fade = value }
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
        snapshotFlow { (Wallpaper.titlePages > 0 && AppColors.palette.heavyExtras) to Wallpaper.title }.collectLatest { (open, url) ->
            if (!open) { Wallpaper.titleArt.clear(); return@collectLatest }
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
    // Settings pages: a picture wallpaper (e.g. the highlighted programme's) darkened further, most behind the text.
    if (readable && p.wallpaperMode == 3) {
        if (Wallpaper.wall.image == null) { drawRect(p.main); return@drawBehind }
        drawRect(Color.Black)
        drawLayer(Wallpaper.wall)
        // Darker than the pages for the text, but in step with Dimming (a 70% floor made 30–70% all look alike).
        drawRect(p.main.copy(alpha = 0.55f + 0.45f * p.wallpaperDim))
        drawRect(Brush.horizontalGradient(0f to p.main.copy(alpha = 0.6f), 0.45f to p.main.copy(alpha = 0.35f), 1f to p.main.copy(alpha = 0.15f)))
        return@drawBehind
    }
    if (titleArt) {
        // The title's artwork, darker on the left behind the text and toward the rows below, clearer to the right.
        val layer = Wallpaper.titleArt
        drawRect(p.main)
        if (layer.image != null) {
            drawLayer(layer)
            drawRect(Brush.horizontalGradient(0f to p.main.copy(alpha = 0.9f), 0.35f to p.main.copy(alpha = 0.65f), 0.75f to p.main.copy(alpha = 0.2f), 1f to p.main.copy(alpha = 0.1f)))
            drawRect(Brush.verticalGradient(0.35f to Color.Transparent, 1f to p.main.copy(alpha = 0.85f)))
        }
        return@drawBehind
    }
    when (p.wallpaperMode) {
        1 -> {
            WallpaperStyles.getOrElse(p.wallpaperStyle) { WallpaperStyles[0] }.draw(this, p.highlight)
            drawRect(p.main.copy(alpha = p.wallpaperDim))
        }
        3 -> {
            if (Wallpaper.wall.image == null) { drawRect(p.main); return@drawBehind }
            drawRect(Color.Black)
            drawLayer(Wallpaper.wall)
            drawRect(p.main.copy(alpha = p.wallpaperDim))
        }
        else -> drawRect(p.main)
    }
}

private fun DrawScope.drawLayer(layer: PictureLayer) {
    val img = layer.image ?: return
    layer.previous?.takeIf { layer.fade < 1f }?.let { drawCover(it, 1f) }
    drawCover(img, layer.fade)
}

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
