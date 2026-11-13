package io.github.petebutty.viewarr.ui.player

/**
 * A video's frame rate from counting the frames the decoder puts out, for streams and files that don't say it.
 * The average since a starting point a second in (the first second comes in bursts), so it settles instead of
 * jumping between 23 / 24 / 25 as one-second counts do. Once it's within 0.35 of a rate twice running, that's it:
 * 24 → 23.976, 30 → 29.97, 60 → 59.94 (what video nearly always is), 25, 48 and 50 as they are. Feed it about
 * every half second while playing; [reset] after a pause or rebuffer.
 */
class FpsCounter {
    private val families = listOf(24f to 23.976f, 25f to 25f, 30f to 29.97f, 48f to 48f, 50f to 50f, 60f to 59.94f)
    private var base: Pair<Long, Long>? = null
    private var seen = 0
    private var last = 0f
    private var agreed = 0

    fun reset() { base = null; seen = 0; last = 0f; agreed = 0 }

    /** The rate once it's certain, else null. [frames]: the decoder's frames so far; [at]: elapsedRealtime. */
    fun feed(frames: Long, at: Long): Float? {
        if (++seen < 2) return null
        val b = base ?: run { base = at to frames; return null }
        if (at - b.first < 2_000 || frames <= b.second) return null
        val avg = (frames - b.second) * 1000f / (at - b.first)
        val fam = families.minByOrNull { kotlin.math.abs(it.first - avg) }
            ?.takeIf { kotlin.math.abs(it.first - avg) < 0.35f || kotlin.math.abs(it.second - avg) < 0.35f }?.second ?: 0f
        if (fam > 0f && fam == last) agreed++ else { agreed = if (fam > 0f) 1 else 0; last = fam }
        return if (agreed >= 2) fam else null
    }

    companion object {
        /** An ExoPlayer's decoded frames so far (shown, skipped and dropped), or null before it has any. */
        fun frames(p: androidx.media3.exoplayer.ExoPlayer): Long? =
            p.videoDecoderCounters?.let { it.renderedOutputBufferCount + it.skippedOutputBufferCount + it.droppedBufferCount }?.toLong()
    }
}
