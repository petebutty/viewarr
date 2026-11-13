package io.github.petebutty.viewarr.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Audio sync (the info bars' button): the sound moved later (+) or earlier (−) against the picture, for live TV and
 * films alike. Starts from Settings → Playback → Lip sync. The built-in player does it by shifting the audio clock the
 * picture follows ([wrap]); mpv by its own audio-delay.
 */
object AvSync {
    /** Read on the audio thread. */
    @Volatile private var offsetUs = 0L
    /** For the button's label and the panel (and mpv, which applies it when it changes). */
    var ms by mutableIntStateOf(0)
        private set
    val steps = (-6..6).map { it * 50 }

    fun set(value: Int) { ms = value; offsetUs = value * 1000L }

    fun label(v: Int = ms) = when {
        v == 0 -> "In sync"
        v > 0 -> "Sound $v ms later"
        else -> "Sound ${-v} ms earlier"
    }

    /** The audio sink with the offset on its clock: ahead by the delay, so the picture waits for the sound. */
    fun wrap(sink: AudioSink): AudioSink = object : ForwardingAudioSink(sink) {
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
            val p = super.getCurrentPositionUs(sourceEnded)
            return if (p == AudioSink.CURRENT_POSITION_NOT_SET) p else (p + offsetUs).coerceAtLeast(0)
        }
    }
}

/**
 * The sleep timer for live TV and films (the info bars' button; music has its own): each press steps through
 * Off → 15 → 30 → 60 → 90 minutes → the end of what's on → Off. When it's up, everything stops (the home screen).
 */
object SleepTimer {
    var endsAt by mutableStateOf<Long?>(null)
        private set
    private var step by mutableIntStateOf(0)
    private val minutes = listOf(0, 15, 30, 60, 90, -1)

    /** The next choice; [endOfShow]: when what's playing ends (the programme, or the film), for the last step. */
    fun cycle(endOfShow: Long?) {
        step = (step + 1) % minutes.size
        if (minutes[step] == -1 && (endOfShow == null || endOfShow <= System.currentTimeMillis())) step = 0
        val m = minutes[step]
        endsAt = when { m == 0 -> null; m == -1 -> endOfShow; else -> System.currentTimeMillis() + m * 60_000L }
    }

    fun clear() { step = 0; endsAt = null }

    fun label(now: Long = System.currentTimeMillis()): String {
        val t = endsAt ?: return "Sleep timer"
        val left = ((t - now) / 60_000).coerceAtLeast(0) + 1
        return if (minutes[step] == -1) "Sleep: at the end ($left min)" else "Sleep: $left min"
    }
}
