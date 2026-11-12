package io.github.petebutty.viewarr.ui.media

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.petebutty.viewarr.ui.common.glass
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.media.SkipMarker
import io.github.petebutty.viewarr.data.settings.SkipMode

/**
 * The episode after the one playing (found as it starts), for the players: Skip credits plays it, and its countdown
 * starts over the credits (Settings → Playback → Episodes). Null episode: none (a film, the last episode, unknown).
 */
object NextUp {
    var episode by mutableStateOf<io.github.petebutty.viewarr.data.media.EpisodeInfo?>(null)
    /** For which stream (its address), so a late answer for the previous episode is ignored. */
    var forUrl: String? = null
    var play: () -> Unit = {}
}

/**
 * Skip intro / Skip credits, for both players (Settings → Playback → Episodes). The server says where they are
 * (VodStream.markers). Button: a "Skip intro" pill while it's on; OK skips to its end. Auto: skipped as it starts,
 * with a few seconds to press OK and go back. Skipping the credits jumps to the end, so the next episode's countdown
 * starts (or a film ends).
 */
class IntroSkip {
    /** The marker whose Skip button is showing, or null. */
    var offered by mutableStateOf<SkipMarker?>(null)
        private set
    /** Skipped by itself: the "skipped · OK to watch it" note, until [undoUntil]. */
    var skipped by mutableStateOf<SkipMarker?>(null)
        private set
    /** The next episode's countdown over the end of this one: seconds left, or null when it isn't showing. */
    var nextIn by mutableStateOf<Int?>(null)
        private set
    /** Whether the credits pill means the next episode (there is one) rather than jumping to the end. */
    var creditsToNext by mutableStateOf(false)
        private set
    private var undoUntil = 0L
    private var undoFrom = 0L
    private var nextLeftMs = -1L
    private var lastTick = 0L
    private var nextCancelled = false
    private var nextDone = false
    // Each marker is skipped by itself once: going back to watch it (or seeking into it) doesn't skip it again.
    private val autoDone = HashSet<Long>()

    private fun mode(m: SkipMarker, intro: SkipMode, credits: SkipMode) = if (m.kind == SkipMarker.Kind.Intro) intro else credits

    /**
     * Called a few times a second while it plays. [hasNext]: the episode after this one is known (NextUp). [countdownMs]:
     * the next episode countdown (0 = Play next episode automatically is off); it starts with the credits, or that long
     * before the end when nothing marks them, and plays it ([onNext]) when it runs out.
     */
    fun update(
        markers0: List<SkipMarker>, positionMs: Long, durationMs: Long, intro: SkipMode, credits: SkipMode, seek: (Long) -> Unit,
        hasNext: Boolean = false, countdownMs: Long = 0, onNext: () -> Unit = {},
    ) {
        val now = System.currentTimeMillis()
        val tick = if (lastTick == 0L) 0L else (now - lastTick).coerceIn(0, 1_000)
        lastTick = now
        if (skipped != null && now > undoUntil) skipped = null
        creditsToNext = hasNext
        // A marker running "to the end" (no end given): the end of the file.
        val markers = markers0.map { if (it.endMs <= 0 || (durationMs > 0 && it.endMs > durationMs)) it.copy(endMs = durationMs.coerceAtLeast(it.startMs + 1)) else it }
        // The next episode's countdown: from the credits (or near the end), counting only while it plays.
        if (hasNext && countdownMs > 0 && durationMs > 0 && !nextCancelled && !nextDone) {
            val creditsAt = markers.firstOrNull { it.kind == SkipMarker.Kind.Credits }?.startMs
            val startAt = creditsAt ?: (durationMs - countdownMs - 3_000).coerceAtLeast(0)
            if (positionMs >= startAt) {
                // Never longer than what's left (short credits): it has to finish before the episode does.
                if (nextLeftMs < 0) nextLeftMs = minOf(countdownMs, (durationMs - positionMs - 1_500).coerceAtLeast(1)) else nextLeftMs -= tick
                if (nextLeftMs <= 0) { nextDone = true; nextIn = null; offered = null; onNext(); return }
                nextIn = ((nextLeftMs + 999) / 1_000).toInt()
            } else { nextLeftMs = -1; nextIn = null }
        } else nextIn = null
        // In a marked part (not its last second, where skipping would do nothing).
        val here = markers.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 1_000 && mode(it, intro, credits) != SkipMode.Off }
        if (here == null) { offered = null; return }
        // The countdown is up: it takes the place of the credits button (OK plays the next episode there too).
        if (here.kind == SkipMarker.Kind.Credits && nextIn != null) { offered = null; return }
        if (mode(here, intro, credits) == SkipMode.Auto && here.startMs !in autoDone) {
            autoDone += here.startMs
            offered = null
            // The credits with an episode after this one: straight on to it.
            if (here.kind == SkipMarker.Kind.Credits && hasNext && !nextDone) { nextDone = true; onNext(); return }
            undoFrom = positionMs
            seek(here.endMs)
            // The credits end the episode, so there's nothing to go back to: only the intro offers it.
            if (here.kind == SkipMarker.Kind.Intro) { skipped = here; undoUntil = now + 6_000 }
            return
        }
        offered = if (mode(here, intro, credits) == SkipMode.Button) here else null
    }

    /**
     * OK pressed with nothing else open: the next episode now (countdown up, or Skip credits with one after), skip
     * what's offered, or go back to an intro skipped by itself. True if used.
     */
    fun ok(seek: (Long) -> Unit, onNext: () -> Unit = {}): Boolean {
        if (nextIn != null && !nextDone) { nextDone = true; nextIn = null; onNext(); return true }
        offered?.let { m ->
            offered = null
            if (m.kind == SkipMarker.Kind.Credits && creditsToNext && !nextDone) { nextDone = true; onNext() } else seek(m.endMs)
            return true
        }
        if (skipped != null && System.currentTimeMillis() <= undoUntil) { skipped = null; seek(undoFrom); return true }
        return false
    }

    /**
     * The episode ended with the next one known: play it (true), rather than just closing, whenever Play next episode
     * automatically is on ([countdownOn]), even if Back hid the countdown (that only hides it for this episode).
     */
    fun nextAtEnd(hasNext: Boolean, countdownOn: Boolean): Boolean {
        if (!hasNext || !countdownOn || nextDone) return false
        nextDone = true; nextIn = null
        return true
    }

    /** Back while the countdown is up: hide it and keep watching (the next one still plays when this one ends). */
    fun cancelNext(): Boolean {
        if (nextIn == null) return false
        nextCancelled = true; nextIn = null
        return true
    }
}

/**
 * The pill at the bottom right: "Skip intro · OK", "Next episode · OK" (the credits, with one after), "Intro skipped ·
 * OK to watch it", or the next episode's countdown card ("Next episode in 18", its name, OK / Back).
 */
@Composable
fun IntroSkipPill(skip: IntroSkip, modifier: Modifier = Modifier) {
    val next = NextUp.episode
    skip.nextIn?.let { secs ->
        androidx.compose.foundation.layout.Column(
            modifier
                .glass(16.dp)
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) {
            Text("Next episode in $secs", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            next?.let { Text("S${it.season} · E${it.number}  ${it.name}", fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1) }
            Text("OK: play now   ·   Back: hide", fontSize = 13.sp, color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 4.dp))
        }
        return
    }
    val text = skip.offered?.let { if (it.kind == SkipMarker.Kind.Intro) "Skip intro" else if (skip.creditsToNext) "Next episode" else "Skip credits" }
        ?: skip.skipped?.let { "Intro skipped" }
        ?: return
    val hint = if (skip.offered != null) "OK" else "OK to watch it"
    Row(
        modifier
            .glass(24.dp)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text("   ·   $hint", fontSize = 15.sp, color = Color.White.copy(alpha = 0.75f))
    }
}
