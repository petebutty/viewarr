package io.github.petebutty.viewarr.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.long
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Pause, rewind and fast-forward live TV on any Dispatcharr channel, from a buffer the Viewarr plugin keeps on the
 * server (the plugin's timeshift.py): while a channel plays, the plugin copies it into a sliding HLS playlist of the
 * last couple of hours, shared by every TV on that channel.
 *
 * Live TV itself plays exactly as before (Dispatcharr's proxy stream). Only when someone pauses, rewinds or seeks back
 * does the player move onto the buffer, at the moment asked for; Go live (or reaching the live edge) moves it back.
 * Without the plugin, with it switched off (on the server, or for this person in Settings → Live TV), or when the
 * buffer isn't ready, nothing changes: catch-up timeshift if the channel has it, else live only.
 *
 * Asking goes through the user's own Dispatcharr app data (plugin actions are admin-only):
 *   this TV writes [REQ_KEY]  {"tvs": {"<this TV>": {"uuid": "<what it plays>" or null, "at": <ms>}}}
 *   the plugin writes [REPLY_KEY] {"on": true, "window_s": 7200, "buffers": {"<uuid>": {"url", "started_at", "ready"}}}
 * The request is written again every [KEEPALIVE_MS] while the channel plays (the keep-alive); a channel change or a
 * catch-up programme lets go at once (null), so the server's copy never holds a provider connection someone needs.
 */
@OptIn(UnstableApi::class)
class ServerTimeshift(private val http: HttpJson) {
    /** False when the plugin is set to buffer only channels without catch-up (from its last reply; true until told). */
    @Volatile var catchupToo = true


    /** What the player wants buffered now. [key]: the channel's uuid, or the stream it plays directly. [playing]:
     *  false while it's stopped or loading (keep-alives pause; the server lets go after a minute). */
    data class Want(val session: Session, val channelId: Int, val key: String, val playing: Boolean)

    /** A ready buffer on the server for [channelId]. Times are the server's (ms). */
    data class Buffer(val channelId: Int, val key: String, val url: String, val startedAt: Long, val windowMs: Long, val seenAt: Long)

    /** Where to start the buffer: the item to play and the position in it. */
    class Plan(val url: String, val item: MediaItem, val startPositionMs: Long)

    private val tvId = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    private var buffer: Buffer? = null
    private var asked: Want? = null
    private var askedAt = 0L
    private var stableSince = 0L
    private var lastKey: String? = null
    private var absentUntil = 0L
    private var broken: String? = null
    private var loop: Job? = null
    private var readAt = 0L

    /** Starts the background loop (once): every second it compares what [want] says with what was last asked. */
    fun attach(scope: CoroutineScope, want: () -> Want?) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                // (Four looks a second: leaving a channel is told to the server within a quarter of a second, so its
                // buffer lets go of the provider's line before the next channel needs it. Nothing is sent unless due.)
                delay(250)
                runCatching { step(want()) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "step failed: ${it.message}") }
            }
        }
    }

    private suspend fun step(raw: Want?) {
        val now = System.currentTimeMillis()
        // (Switched off in the plugin: Live timeshift is off for the house.)
        val want = raw?.takeIf { AppColors.palette.serverTimeshift && !io.github.petebutty.viewarr.data.sync.ServerSports.offInPlugin(io.github.petebutty.viewarr.data.sync.ServerSports.F_TIMESHIFT) && now >= absentUntil }
        val asking = asked
        // Nothing wanted, another channel, or catch-up: let go at once (once).
        if (want == null) {
            if (asking != null) { write(asking.session, null); asked = null; buffer = null; Log.i(TAG, "let go of ${asking.key}") }
            lastKey = null
            return
        }
        if (want.key != lastKey) { lastKey = want.key; stableSince = now; broken = null }
        if (asking != null && asking.key != want.key) { write(asking.session, null); asked = null; buffer = null }
        // Stopped or loading on the same channel: keep-alives pause (the server's copy lasts a minute without them).
        if (!want.playing) {
            if (buffer != null && now - (buffer?.seenAt ?: 0) > 45_000) buffer = null
            return
        }
        // A channel only just started (zapping past it): not asked for until it has played a few seconds.
        if (asked == null && now - stableSince < SETTLE_MS) return
        val due = asked == null || now - askedAt >= KEEPALIVE_MS
        // While waiting for the buffer to be ready: the reply every second (the plugin answers within a second or two).
        val waiting = asked != null && buffer == null && now - askedAt < READY_WAIT_MS
        if (due) {
            if (!write(want.session, want.key)) return
            if (asked == null) Log.i(TAG, "asked for a buffer of ${want.key}")
            asked = want; askedAt = now
        }
        // (The reply at most once a second, however often this looks.)
        if (due || (waiting && now - readAt >= 1_000)) { readAt = now; read(want, firstAsk = asking == null || asking.key != want.key) }
    }

    private fun appData(s: Session, key: String) = s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/$key/"
    private fun headers(s: Session) = mapOf("X-API-Key" to s.apiKey)

    /** This TV's entry in the request (other TVs on the same user keep theirs; very old ones are dropped). */
    private suspend fun write(s: Session, key: String?): Boolean {
        val now = System.currentTimeMillis()
        val r = runCatching { http.get(appData(s, REQ_KEY), headers(s)) }.getOrNull() ?: return false
        val old = (r.body.child("value") ?: r.body).child("tvs") as? JsonObject
        val tvs = buildJsonObject {
            old?.forEach { (id, e) -> if (id != tvId && now - (e.long("at") ?: 0L) < 10 * 60_000L) put(id, e) }
            put(tvId, buildJsonObject { put("uuid", key?.let { JsonPrimitive(it) } ?: JsonNull); put("at", now) })
        }
        val w = runCatching { http.put(appData(s, REQ_KEY), buildJsonObject { put("tvs", tvs) }, headers(s)) }.getOrNull()
        if (w?.ok != true) Log.w(TAG, "couldn't write the request (${w?.code})")
        return w?.ok == true
    }

    private var unanswered = 0

    private suspend fun read(want: Want, firstAsk: Boolean) {
        val r = runCatching { http.get(appData(want.session, REPLY_KEY), headers(want.session)) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val v = r.body.child("value") ?: r.body
        val at = (v.long("at") ?: 0L) * 1000
        // No plugin (or an old one): nothing ever answers. Given up for 10 minutes, quietly.
        if (!r.ok || at == 0L) {
            if (++unanswered >= 12) { Log.i(TAG, "no answer from the Viewarr plugin: timeshift from the server isn't available"); absentUntil = now + 10 * 60_000L; unanswered = 0; asked = null }
            return
        }
        unanswered = 0
        // Plugin setting "Live timeshift: channels": only channels without catch-up (the others keep using catch-up).
        catchupToo = v.str("channels") != "no_catchup"
        if ((v.child("on") as? JsonPrimitive)?.booleanOrNull == false) { buffer = null; return }
        val b = v.child("buffers").child(want.key)
        val ready = (b.child("ready") as? JsonPrimitive)?.booleanOrNull == true
        val url = b.str("url")
        if (b == null || url == null || !ready) {
            v.child("refused").str(want.key)?.let { if (buffer != null || firstAsk) Log.i(TAG, "refused: $it") }
            buffer = null
            return
        }
        if (want.key == broken) return
        val windowMs = (v.long("window_s") ?: 7200L) * 1000
        val fresh = buffer?.url != url
        buffer = Buffer(want.channelId, want.key, want.session.serverUrl.trimEnd('/') + url, (b.long("started_at") ?: 0L) * 1000, windowMs, now)
        if (fresh) Log.i(TAG, "buffer ready for ${want.key}")
    }

    // ---------------------------------------------------------------- what the player asks

    /** A ready buffer for this channel (and this person hasn't switched it off). */
    fun covers(channel: ChannelListItem): Boolean =
        AppColors.palette.serverTimeshift && !io.github.petebutty.viewarr.data.sync.ServerSports.offInPlugin(io.github.petebutty.viewarr.data.sync.ServerSports.F_TIMESHIFT) && buffer?.let { it.channelId == channel.id && it.key != broken } == true

    /** The earliest moment the buffer holds (a few seconds' margin), or null without one. */
    fun earliest(channel: ChannelListItem): Long? {
        val b = buffer?.takeIf { covers(channel) } ?: return null
        return maxOf(b.startedAt, System.currentTimeMillis() - b.windowMs) + 8_000
    }

    /** How close to live a jump back still means "live": the buffer is only seconds behind, catch-up ~45 s. */
    fun liveGap(channel: ChannelListItem) = if (covers(channel)) 12_000L else 45_000L

    /** The first step back from live is at least this far (the catch-up archive runs a minute behind). */
    fun firstStep(channel: ChannelListItem) = if (covers(channel)) 20_000L else 60_000L

    /** Where a jump to [at] should go on the buffer: [at] itself, the buffer's start when it's earlier and the channel
     *  has no catch-up, or null (earlier than the buffer, and catch-up can reach it). */
    fun clamp(channel: ChannelListItem, at: Long): Long? {
        val first = earliest(channel) ?: return null
        return when {
            at >= first -> at
            channel.catchupDays > 0 -> null
            else -> first
        }
    }

    /** The buffer's playlist read once, to place [at] in it (position 0 is its first segment's time). */
    suspend fun plan(at: Long): Plan? {
        val b = buffer ?: return null
        val (code, text) = runCatching { http.text("GET", b.url) }.getOrNull() ?: return null
        if (code !in 200..299) { Log.w(TAG, "playlist answered $code"); return null }
        val times = text.lineSequence().filter { it.startsWith("#EXT-X-PROGRAM-DATE-TIME:") }.mapNotNull { pdt(it.substringAfter(':')) }.toList()
        if (times.size < 2) { Log.i(TAG, "playlist not ready yet (${times.size} segments)"); return null }
        val first = times.first()
        val pos = (at - first).coerceIn(0, maxOf(0, times.last() - first))
        val item = MediaItem.Builder().setUri(b.url).setMimeType(MimeTypes.APPLICATION_M3U8)
            // Played at normal speed always: a live stream otherwise speeds up to win back the time spent paused.
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
            .build()
        Log.i(TAG, "playing the buffer ${(System.currentTimeMillis() - at) / 1000} s behind live")
        return Plan(b.url, item, pos)
    }

    private fun pdt(text: String): Long? = runCatching {
        val t = text.trim().replace(Regex("([+-]\\d\\d):(\\d\\d)$"), "$1$2").replace(Regex("Z$"), "+0000")
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).parse(t)?.time
    }.getOrNull()

    /** The moment on screen while playing the buffer (from the playlist's programme times), or null. */
    fun playingAt(p: ExoPlayer): Long? {
        val tl = p.currentTimeline
        if (tl.isEmpty) return null
        val w = tl.getWindow(p.currentMediaItemIndex, Timeline.Window())
        if (w.windowStartTimeMs == C.TIME_UNSET) return null
        return w.windowStartTimeMs + p.currentPosition
    }

    /** A jump within the buffer, in the player itself (instant). False: [at] is before what the buffer holds. */
    fun seekTo(p: ExoPlayer, at: Long): Boolean {
        val tl = p.currentTimeline
        if (tl.isEmpty) return false
        val w = tl.getWindow(p.currentMediaItemIndex, Timeline.Window())
        if (w.windowStartTimeMs == C.TIME_UNSET) return false
        val pos = at - w.windowStartTimeMs
        if (pos < 0) return false
        val end = if (w.durationMs != C.TIME_UNSET) maxOf(0, w.durationMs - 4_000) else pos
        p.seekTo(minOf(pos, end))
        return true
    }

    /** Playing the buffer, an error: paused so long the window moved past it (back to its start), or the buffer is
     *  given up on for this channel (catch-up or live take over, as without it). True: handled here. */
    fun recover(p: ExoPlayer, error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            Log.i(TAG, "fell behind the buffer's window: from its start")
            p.seekTo(0); p.prepare()
            return true
        }
        Log.w(TAG, "buffer failed (${error.errorCodeName}): not used again for this channel")
        broken = buffer?.key
        return false
    }

    /** The buffer couldn't be used (its playlist didn't answer): catch-up or live instead, as without it. */
    fun markBroken() { broken = buffer?.key }

    /** A stand-in programme (an hour's slot) for a channel with no guide, so the seek bar has something to show. */
    fun standIn(channel: ChannelListItem, at: Long): GuideProgram {
        val start = at - at % 3_600_000L
        return GuideProgram.noGuide(channel.id, start).copy(title = channel.name)
    }

    companion object {
        const val TAG = "ServerTimeshift"
        const val REQ_KEY = "viewarr_timeshift_req"
        const val REPLY_KEY = "viewarr_timeshift"
        const val KEEPALIVE_MS = 20_000L
        const val SETTLE_MS = 3_000L
        const val READY_WAIT_MS = 25_000L
    }
}
