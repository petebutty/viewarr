package io.github.petebutty.viewarr.ui.media

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.playback.FrameRateMatcher
import io.github.petebutty.viewarr.playback.HdmiSoundGuard
import kotlinx.coroutines.delay

/**
 * Match frame rate for the built-in film player (2026-11-15: kept simple): only when the server tells Viewarr
 * the file's frame rate (Plex, Jellyfin / Emby: [VodStream.fps]). Then the TV switches as the film opens, behind the
 * loading screen, with the soundtrack off; once the HDMI link is back (or it didn't drop within 2 s) the sound goes
 * on and the film plays, its sound opening on the new link. No rate from the server: the TV is left as it is (no
 * guessing from the file, no switching mid-film).
 */
class FilmRateStart(private val activity: Activity?, on: Boolean, stream: VodStream) {
    private val fps = stream.fps
    /** The film isn't playing yet: the TV is switching. */
    var holding by mutableStateOf(false)
        private set
    /** The TV's mode before the switch (to put back at the end), null when it didn't switch. */
    var restoreMode: Int? = null
        private set
    private var switchedAt = 0L

    init {
        if (on && activity != null && fps != null) {
            switchedAt = System.currentTimeMillis()
            restoreMode = FrameRateMatcher.match(activity, fps)
            // Already at that rate: nothing to wait for.
            holding = restoreMode != null
        }
    }

    /** Waits for the TV to finish switching, then plays (sound on). */
    suspend fun run(player: ExoPlayer, guard: HdmiSoundGuard) {
        if (!holding) return
        try {
            val since = switchedAt
            // Until the HDMI sound output is back (then a moment for the receiver), at most 5 s; not gone at all within
            // 2 s: this TV's switch leaves the sound alone, so it starts.
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                while (!guard.backSince(since) && (System.currentTimeMillis() - since < 2_000 || guard.goneSince(since))) delay(100)
            }
            if (guard.backSince(since)) delay(800)
            else {
                // No HDMI drop seen (the AM9 over ARC, 2026-11-18): the box gives no sign when the TV and sound
                // system have re-locked, and Dolby started 2 s in came out silent. Wait until the screen really is at
                // the new rate (at most 4 s more), then give the sound system 3 s before the sound starts.
                @Suppress("DEPRECATION")
                val display = if (android.os.Build.VERSION.SDK_INT >= 30) activity?.display else activity?.windowManager?.defaultDisplay
                val want = fps ?: 0f
                fun at() = display?.refreshRate?.let { hz -> want > 0f && kotlin.math.abs(hz / want - kotlin.math.round(hz / want)) < 0.01f } ?: true
                kotlinx.coroutines.withTimeoutOrNull(4_000) { while (!at()) delay(100) }
                // (Settings → Playback → Sound after a frame-rate switch, 1–5 s; 3 to start.)
                val wait = (io.github.petebutty.viewarr.ui.theme.AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.AFR_SOUND_DELAY).coerceIn(0, 4) + 1) * 1_000L
                android.util.Log.i("FilmRate", "no HDMI drop: screen at ${display?.refreshRate} Hz, sound in ${wait / 1000} s")
                delay(wait)
            }
        } finally {
            holding = false
            runCatching {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false).build()
                player.playWhenReady = true
            }
        }
        // (The automatic pause and play after a switch, to wake a silent Dolby track, was taken out again:
        // 2026-11-19.)
    }
}
