package io.github.petebutty.viewarr.data.cast

import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.data.music.Song
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What this device is playing, for Play on TV's Devices page on a phone: kind, title, how far in, paused, and a link
 * to carry on with it elsewhere ("Take over"). Kept from the same moments Home Assistant hears about (HaPlayback),
 * whether or not Home Assistant is set up; live TV from the playing channel (NowPlayingWatch).
 */
object NowPlaying {
    data class State(
        val kind: String, val title: String, val sub: String?, val image: String?,
        val positionMs: Long, val durationMs: Long, val paused: Boolean, val link: String?,
    )

    @Volatile var state: State? = null
        private set
    private var vodUrl: String? = null

    /** A film, episode or recording (VodHost's progress). */
    fun vod(s: VodStream, st: String, positionMs: Long, durationMs: Long) {
        if (st == "stopped") { if (s.url == vodUrl) { vodUrl = null; state = null }; return }
        vodUrl = s.url
        val recId = io.github.petebutty.viewarr.data.dvr.RecordingProgress.idOf(s.url)
        val w = s.watch
        val episode = recId == null && w != null && (w.season != null || w.episode != null || w.type == MediaType.Show)
        val item = w?.item
        state = State(
            kind = if (recId != null) "recording" else if (episode) "episode" else "film",
            title = s.title.removeSuffix(" (converted by Plex)"),
            sub = if (episode && w?.season != null && w.episode != null) "S${w.season} · E${w.episode}" else null,
            image = item?.backdrop ?: item?.poster,
            positionMs = positionMs, durationMs = durationMs, paused = st == "paused",
            link = recId?.let { PlayOnTv.recordingLink(it) } ?: item?.let { PlayOnTv.titleLink(it, w.season, w.episode) },
        )
    }

    /** Music: a song playing or paused; stopped clears it. */
    fun music(song: Song?, st: String, positionMs: Long, durationMs: Long) {
        if (st == "stopped" || song == null) { if (state?.kind == "music") state = null; return }
        if (state != null && state?.kind != "music" && vodUrl != null) return
        // (No link kept: music's is made when it's sent, its queue saved on the server then: MusicHandoff.)
        state = State("music", song.title, listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null },
            song.art?.large, positionMs, durationMs, st == "paused", null)
    }

    /** Live TV: the channel playing (null: none), paused or not. A film or song playing comes first. */
    fun live(uuid: String?, name: String?, programme: String?, logo: String?, paused: Boolean) {
        val cur = state
        if (uuid == null || name == null) { if (cur?.kind == "live") state = null; return }
        if (cur != null && cur.kind != "live") return
        state = State("live", name, programme, logo, 0, 0, paused, PlayOnTv.channelLink(uuid))
    }

    /** Something here that can be sent elsewhere (music's link is made at that moment: MusicHandoff). */
    val sendable: Boolean get() = state?.let { it.link != null || it.kind == "music" } == true

    /** What's playing as a link to send elsewhere; music: its queue saved on its server now, at the current point. */
    suspend fun linkNow(library: io.github.petebutty.viewarr.data.music.MusicLibrary): String? {
        val s = state ?: return null
        return if (s.kind == "music") MusicHandoff.link(library) else s.link
    }

    /** The Devices page's answer: this device's name and what it's playing. */
    fun json(name: String): String = buildJsonObject {
        put("name", name)
        val s = state
        if (s != null) {
            put("kind", s.kind); put("title", s.title)
            s.sub?.let { put("sub", it) }; s.image?.let { put("image", it) }
            put("position", s.positionMs); put("duration", s.durationMs); put("paused", s.paused)
            s.link?.let { put("link", it) }
            // (Music can be taken over: its link is asked for then, POST /control "handoff".)
            if (s.kind == "music") put("handoff", true)
        }
    }.toString()
}

/**
 * Play, pause, stop, skip and change channel on this device, from Play on TV's Devices page (on the main thread). A
 * film or episode first, then music, then live TV ([live]: the playing channel's own controls, set by NowPlayingWatch).
 */
object PlaybackControl {
    class Live(val togglePause: () -> Unit, val stop: () -> Unit, val step: ((Int) -> Unit)?)
    @Volatile var live: Live? = null

    fun run(cmd: String) {
        val vod = io.github.petebutty.viewarr.ui.media.VodHost
        val music = io.github.petebutty.viewarr.playback.MusicPlayer
        when {
            vod.active -> when (cmd) {
                "playpause" -> vod.remote?.togglePause()
                "pause" -> if (vod.remote?.playing == true) vod.remote?.togglePause()
                "play" -> if (vod.remote?.playing == false) vod.remote?.togglePause()
                "forward", "next" -> vod.remote?.skip(30_000)
                "back", "previous" -> vod.remote?.skip(-10_000)
                "stop" -> { vod.close(); vod.afterStop() }
            }
            music.active -> when (cmd) {
                "playpause" -> music.toggle()
                "pause" -> if (music.isPlaying) music.toggle()
                "play" -> if (!music.isPlaying) music.toggle()
                "next" -> music.next()
                "previous" -> music.previous()
                "forward" -> music.seekBy(30_000)
                "back" -> music.seekBy(-10_000)
                "stop" -> music.stop()
            }
            else -> live?.let { l ->
                when (cmd) {
                    "playpause", "pause", "play" -> l.togglePause()
                    "stop" -> l.stop()
                    "next" -> l.step?.invoke(1)
                    "previous" -> l.step?.invoke(-1)
                }
            }
        }
    }
}

/**
 * Music handed to another device: the queue saved on its music server (Navidrome's play queue, a Plex play queue) with
 * the song playing and how far in, and a link naming it; from Jellyfin / Emby, or a queue mixing servers, the songs'
 * ids instead (the other device fetches them from its own servers).
 */
object MusicHandoff {
    suspend fun link(library: io.github.petebutty.viewarr.data.music.MusicLibrary): String? {
        val (queue, index, pos) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val mp = io.github.petebutty.viewarr.playback.MusicPlayer
            Triple(mp.queue, mp.index, mp.positionMs)
        }
        if (queue.isEmpty() || index !in queue.indices) return null
        val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { library.saveQueue(queue, index, pos) }
        return saved?.let { (src, q) -> PlayOnTv.musicLink(src, q, pos) } ?: PlayOnTv.musicIdsLink(queue.drop(index).map { it.id }, pos)
    }
}
