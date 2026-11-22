package io.github.petebutty.viewarr.ui.player

import io.github.petebutty.viewarr.ui.common.videoBleed
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.jdtech.mpv.MPVLib
import io.github.petebutty.viewarr.data.settings.LocalPlayback
import io.github.petebutty.viewarr.data.settings.MpvDecoding
import io.github.petebutty.viewarr.data.settings.Passthrough
import io.github.petebutty.viewarr.ui.media.MpvHandle

private const val TAG = "LiveMpv"

/**
 * Live TV through mpv, for a channel the built-in player can't start (Sky Witness on one provider: empty sound
 * tracks it waits on for ever; odd containers, broken timestamps). The same idea as OwnTV's player ladder (GPL-3):
 * built-in first, then mpv once. [onStarted] when its first picture time comes in; [onFailed] if it ends before
 * playing, or the stream ends. mpv is one per app: a film starting replaces it (live stops for the film anyway).
 */
@Composable
fun LiveMpvPicture(url: String, apiKey: String?, onStarted: () -> Unit, onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = LocalPlayback.current
    val started = rememberUpdatedState(onStarted)
    val failed = rememberUpdatedState(onFailed)
    val main = remember { Handler(Looper.getMainLooper()) }
    val direct = settings.mpvDecoding == MpvDecoding.Hardware
    val mpv = remember(url) {
        MpvHandle(context).apply {
            fun opt(k: String, v: String) { if (setOptionString(k, v) < 0) Log.w(TAG, "option $k rejected") }
            opt("config", "no")
            opt("config-dir", context.filesDir.absolutePath)
            // (No video output until the picture's surface exists: the surface callbacks below set the real one. Starting
            // with mediacodec_embed and no surface, a stream that opened first hit mpv's "WinID != 0" assertion and
            // closed the app; tester log 2026-11-22.)
            opt("vo", "null")
            opt("gpu-context", "android")
            opt("opengl-es", "yes")
            opt("hwdec", when (settings.mpvDecoding) { MpvDecoding.Hardware -> "mediacodec"; MpvDecoding.Gpu -> "mediacodec,mediacodec-copy"; MpvDecoding.Software -> "no" })
            opt("hwdec-codecs", "h264,hevc,mpeg2video,vp9,av1")
            opt("ao", "audiotrack,opensles")
            opt("audio-buffer", "0.5")
            opt("input-default-bindings", "no")
            opt("osd-level", "0")
            opt("keep-open", "no")
            opt("cache", "yes")
            opt("demuxer-max-bytes", "32MiB")
            opt("demuxer-max-back-bytes", "8MiB")
            opt("user-agent", "Viewarr/0.9")
            // (Optional for Dispatcharr's proxy, but attributes the stream to this user, as the built-in player does.)
            if (!apiKey.isNullOrEmpty()) opt("http-header-fields", "X-API-Key: $apiKey")
            opt("deinterlace", "auto")
            if (settings.passthrough == Passthrough.On) opt("audio-spdif", "ac3,eac3,dts")
            init()
        }
    }
    DisposableEffect(mpv) {
        val gone = booleanArrayOf(false)
        var playing = false
        fun post(block: () -> Unit) { main.post { if (!gone[0]) block() } }
        val observer = object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {}
            override fun eventProperty(property: String, value: Long) {}
            override fun eventProperty(property: String, value: Boolean) {}
            override fun eventProperty(property: String, value: String) {}
            override fun eventProperty(property: String, value: Double) {
                if (property == "time-pos" && value > 0 && !playing) {
                    playing = true
                    post { Log.i(TAG, "playing"); started.value() }
                }
            }
            override fun event(eventId: Int) {
                // MPV_EVENT_END_FILE = 7: before it played, it couldn't open; after, the stream ended.
                if (eventId == 7) post { Log.i(TAG, if (playing) "stream ended" else "couldn't open"); failed.value() }
            }
        }
        mpv.addObserver(observer)
        mpv.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
        mpv.command(arrayOf("loadfile", io.github.petebutty.viewarr.data.net.Reach.map(url)))
        onDispose {
            gone[0] = true
            mpv.removeObserver(observer)
            runCatching { mpv.detachSurface() }
            runCatching { mpv.destroy() }
        }
    }
    AndroidView(
        factory = { ctx ->
            android.view.SurfaceView(ctx).apply {
                val dm = ctx.resources.displayMetrics
                val bw = maxOf(dm.widthPixels, dm.heightPixels)
                val bh = minOf(dm.widthPixels, dm.heightPixels)
                holder.setFixedSize(bw, bh)
                keepScreenOn = true
                isFocusable = false
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(h: android.view.SurfaceHolder) {
                        mpv.attachSurface(h.surface)
                        mpv.setOptionString("force-window", "yes")
                        mpv.setPropertyString("android-surface-size", "${bw}x$bh")
                        mpv.setOptionString("vo", if (direct) "mediacodec_embed" else "gpu")
                    }
                    override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, w: Int, hh: Int) {
                        mpv.setPropertyString("android-surface-size", "${w}x$hh")
                    }
                    override fun surfaceDestroyed(h: android.view.SurfaceHolder) {
                        mpv.setOptionString("vo", "null")
                        mpv.setOptionString("force-window", "no")
                        runCatching { mpv.detachSurface() }
                    }
                })
            }
        },
        modifier = modifier.videoBleed(),
    )
}
