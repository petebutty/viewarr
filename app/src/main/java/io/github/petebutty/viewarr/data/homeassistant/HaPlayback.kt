package io.github.petebutty.viewarr.data.homeassistant

import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.data.music.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Settings → Home Assistant → Send what's playing: the event "viewarr_playback" on Home Assistant when something starts,
 * pauses, carries on or stops here, so automations can dim the lights for a film and so on.
 *
 * Data: tv, state (playing / paused / stopped), kind (film / episode / live / recording / music), title, show, season,
 * episode, channel, artist, album, full_screen, position and duration (seconds); anything not known is left out.
 *
 * Called from the players' own hooks (VodHost for films, episodes and recordings; MusicPlayer; live TV's full screen
 * from the Home Assistant host). Only changes are sent: the players' regular "still playing" reports aren't. A pause
 * waits 2 s first (buffering pauses for a moment, and that isn't worth an event), the same event twice within 2 s is
 * sent once, every send is one try on a background thread, and a failure is logged at most once a minute.
 * Trailers aren't sent (they don't go through these players).
 */
object HaPlayback {
    private const val EVENT = "viewarr_playback"

    private val lock = Any()
    private var lastKey = ""
    private var lastAt = 0L
    private var pending: Job? = null
    @Volatile private var failLoggedAt = 0L

    // Films, episodes, recordings: the stream that last reported, and what was last sent for it.
    private var vodUrl: String? = null
    private var vodSent = ""

    /** VodHost's progress for [s]: start, pause, carry on, stop (and the regular ticks, which only count if something changed). */
    fun vod(s: VodStream, state: String, positionMs: Long, durationMs: Long, fullScreen: Boolean) {
        val cfg = HaStore.config.value
        if (!cfg.ready || !cfg.sendPlaying) return
        synchronized(lock) {
            if (state == "stopped") {
                // The last "stopped" of a stream that's already been followed by the next one: not news.
                if (s.url != vodUrl) return
                vodUrl = null; vodSent = ""
            } else {
                val sig = "$state|${s.url}|$fullScreen"
                if (sig == vodSent) return
                vodUrl = s.url; vodSent = sig
            }
        }
        val w = s.watch
        val recording = io.github.petebutty.viewarr.data.dvr.RecordingProgress.idOf(s.url) != null
        val episode = !recording && w != null && (w.season != null || w.episode != null || w.type == MediaType.Show)
        val title = s.title.removeSuffix(" (converted by Plex)")
        val data = buildJsonObject {
            put("kind", if (recording) "recording" else if (episode) "episode" else "film")
            put("title", title)
            if (episode) (w?.item?.title ?: w?.iptv?.title ?: title.substringBefore(" · ").takeIf { " · " in title })?.let { put("show", it) }
            w?.season?.let { put("season", it) }
            w?.episode?.let { put("episode", it) }
            put("full_screen", fullScreen)
            if (positionMs > 0) put("position", positionMs / 1000)
            if (durationMs > 0) put("duration", durationMs / 1000)
        }
        send(cfg, state, title, data)
    }

    // Live TV: the channel on full screen now (null: not on full screen).
    private var liveChannel: String? = null

    /** Live TV entering or leaving full screen, or changing channel while full screen. */
    fun live(fullScreen: Boolean, channel: String?) {
        val cfg = HaStore.config.value
        val now = if (fullScreen) channel else null
        val was = synchronized(lock) { liveChannel.also { liveChannel = now } }
        if (!cfg.ready || !cfg.sendPlaying || now == was) return
        val name = now ?: was ?: return
        send(cfg, if (now != null) "playing" else "stopped", name, buildJsonObject {
            put("kind", "live")
            put("title", name)
            put("channel", name)
            put("full_screen", now != null)
        })
    }

    // Music: the song last sent, and its state.
    private var musicId: String? = null
    private var musicTitle: String? = null
    private var musicState = ""

    /** The music player: a song starts, pauses, carries on; stopped ([song] may be null then). */
    fun music(song: Song?, state: String, positionMs: Long, durationMs: Long) {
        val cfg = HaStore.config.value
        if (!cfg.ready || !cfg.sendPlaying) return
        val title: String
        synchronized(lock) {
            if (state == "stopped") {
                if (musicId == null) return
                title = musicTitle ?: return
                musicId = null; musicTitle = null; musicState = ""
            } else {
                song ?: return
                if (song.id == musicId && state == musicState) return
                musicId = song.id; musicTitle = song.title; musicState = state
                title = song.title
            }
        }
        send(cfg, state, title, buildJsonObject {
            put("kind", "music")
            put("title", title)
            if (state != "stopped" && song != null) {
                if (song.artist.isNotBlank()) put("artist", song.artist)
                if (song.album.isNotBlank()) put("album", song.album)
            }
            if (positionMs > 0) put("position", positionMs / 1000)
            if (durationMs > 0) put("duration", durationMs / 1000)
        })
    }

    private fun send(cfg: HaConfig, state: String, title: String, data: JsonObject) {
        val key = "$state|$title|${data["kind"]}|${data["channel"]}"
        synchronized(lock) {
            // A pause that hadn't been sent yet is dropped (it lasted under 2 s): carrying on is then no news either.
            val dropped = pending?.isActive == true
            pending?.cancel(); pending = null
            val now = System.currentTimeMillis()
            if (key == lastKey && (dropped || now - lastAt < 2_000)) return
            if (state == "paused") {
                pending = HaStore.scope.launch {
                    delay(2_000)
                    synchronized(lock) { lastKey = key; lastAt = System.currentTimeMillis() }
                    post(cfg, state, data)
                }
                return
            }
            lastKey = key; lastAt = now
        }
        HaStore.scope.launch { post(cfg, state, data) }
    }

    private fun post(cfg: HaConfig, state: String, data: JsonObject) {
        val body = buildJsonObject {
            put("tv", HaStore.tvName())
            put("state", state)
            data.forEach { (k, v) -> put(k, v) }
        }
        val ok = runCatching { HaClient.fire(cfg, EVENT, body) }
        if (ok.getOrDefault(false)) return
        val now = System.currentTimeMillis()
        if (now - failLoggedAt > 60_000) {
            failLoggedAt = now
            android.util.Log.w(HaStore.TAG, "playback event not sent: " + (ok.exceptionOrNull()?.javaClass?.simpleName ?: "refused"))
        }
    }
}
