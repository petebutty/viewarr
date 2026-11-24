package io.github.petebutty.viewarr.playback

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The picture's shape from the stream itself when the box's decoder gets it wrong. Media3 takes the decoder's pixel
 * shape ("sar-width/height") over the stream's; some decoders (Amlogic, Ugoos AM9 Pro) always say square, so
 * widescreen SD (720×576 marked 16:9) came out 5:4, narrow, with black bars (TRex BBC One Scotland,
 * 2026-11-16). A square answer is ignored when the stream says otherwise.
 */
@OptIn(UnstableApi::class)
class SarFixVideoRenderer(
    context: Context,
    adapters: MediaCodecAdapter.Factory,
    selector: MediaCodecSelector,
    joiningMs: Long,
    fallback: Boolean,
    handler: Handler?,
    listener: VideoRendererEventListener?,
) : MediaCodecVideoRenderer(context, adapters, selector, joiningMs, fallback, handler, listener, 50) {
    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        if (mediaFormat != null && Build.VERSION.SDK_INT >= 29 && format.pixelWidthHeightRatio != 1f &&
            mediaFormat.containsKey("sar-width") && mediaFormat.containsKey("sar-height") &&
            runCatching { mediaFormat.getInteger("sar-width") == mediaFormat.getInteger("sar-height") }.getOrDefault(false)
        ) {
            mediaFormat.removeKey("sar-width")
            mediaFormat.removeKey("sar-height")
        }
        super.onOutputFormatChanged(format, mediaFormat)
    }

    companion object {
        /** In place of the factory's own video renderer (the rest, such as FFmpeg's, left as they are). */
        fun replaceIn(out: ArrayList<Renderer>, context: Context, adapters: MediaCodecAdapter.Factory, selector: MediaCodecSelector,
                      fallback: Boolean, handler: Handler, listener: VideoRendererEventListener, joiningMs: Long) {
            val i = out.indexOfFirst { it.javaClass == MediaCodecVideoRenderer::class.java }
            if (i >= 0) out[i] = SarFixVideoRenderer(context, adapters, selector, joiningMs, fallback, handler, listener)
        }
    }
}

/** Box-specific decoder settings. */
object DecoderQuirks {
    /**
     * Amlogic boxes (Ugoos, many Android TV boxes): Media3's asynchronous decoder queueing corrupts some 4K HEVC and can
     * freeze the picture there (OwnTV found it, GPL-3, ExoRenderers.kt); the older synchronous way is used instead.
     */
    val amlogic: Boolean by lazy {
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER.orEmpty() else ""
        listOf(soc, android.os.Build.HARDWARE.orEmpty(), android.os.Build.BOARD.orEmpty()).any { it.contains("amlogic", true) } ||
            android.os.Build.HARDWARE.orEmpty().lowercase().let { it.startsWith("aml") || it == "s6" || it.startsWith("sc2") }
    }
}
