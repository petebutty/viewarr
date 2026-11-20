package io.github.petebutty.viewarr.data.dvr

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * How far you've watched each recording, kept on the box (Dispatcharr doesn't keep a place in a recording): a bar on
 * the Recordings page, a tick once watched, and the next play carries on where you stopped (Peter, 2026-11-20).
 * By recording id: "position|length|when", in ms.
 */
object RecordingProgress {
    private const val FILE = "recording_progress"
    /** Past this share: watched (the tick; plays from the start again). */
    const val WATCHED_AT = 0.92f
    /** Bumped on every change, so the page redraws. */
    val version = MutableStateFlow(0)
    @Volatile private var cache: Map<Int, Triple<Long, Long, Long>>? = null
    private var lastSaved = 0L

    private fun all(context: Context): Map<Int, Triple<Long, Long, Long>> = cache ?: context.getSharedPreferences(FILE, Context.MODE_PRIVATE).all
        .mapNotNull { (k, v) ->
            val id = k.toIntOrNull() ?: return@mapNotNull null
            val p = (v as? String)?.split('|')?.mapNotNull { it.toLongOrNull() } ?: return@mapNotNull null
            if (p.size < 3) null else id to Triple(p[0], p[1], p[2])
        }.toMap().also { cache = it }

    /** The id of the recording a stream address plays (…/recordings/<id>/file/), or null. */
    fun idOf(url: String): Int? = Regex("/recordings/(\\d+)/file").find(url)?.groupValues?.get(1)?.toIntOrNull()

    /** Where playing has got to: kept when paused or stopped, and every 15 s while it plays. */
    fun record(context: Context, id: Int, positionMs: Long, durationMs: Long, state: String) {
        if (durationMs <= 0 || positionMs < 0) return
        val now = System.currentTimeMillis()
        if (state == "playing" && now - lastSaved < 15_000) return
        lastSaved = now
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(id.toString(), "$positionMs|$durationMs|$now").apply()
        cache = all(context) + (id to Triple(positionMs, durationMs, now))
        version.value++
    }

    /** 0..1 watched, or null when never played. */
    fun fraction(context: Context, id: Int): Float? = all(context)[id]?.let { (p, d, _) -> (p.toFloat() / d).coerceIn(0f, 1f) }
    fun watched(context: Context, id: Int) = (fraction(context, id) ?: 0f) >= WATCHED_AT

    /** When it was last watched (0: never). */
    fun lastWatched(context: Context, id: Int): Long = all(context)[id]?.third ?: 0L

    /** Where to carry on from (0: the start, for one never played, barely started or already watched). */
    fun resumeMs(context: Context, id: Int): Long {
        val (p, d, _) = all(context)[id] ?: return 0
        return if (p < 30_000 || p.toFloat() / d >= WATCHED_AT) 0 else (p - 5_000).coerceAtLeast(0)
    }

    /** A deleted recording's place goes too. */
    fun forget(context: Context, id: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
        cache = all(context) - id
        version.value++
    }
}

/** A recording's card picked in a Continue watching row ("rec:<id>"): the home screen plays it (it has no title page). */
object RecordingOpen {
    val pending = MutableStateFlow<Int?>(null)
    const val PREFIX = "rec:"
}
