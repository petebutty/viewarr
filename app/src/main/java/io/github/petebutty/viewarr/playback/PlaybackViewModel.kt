package io.github.petebutty.viewarr.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.net.reachOf
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.repository.GuideRepository
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.settings.RemoteStore
import io.github.petebutty.viewarr.data.xtream.XtreamClient
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.str
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Locale
import javax.inject.Inject

data class TrackOption(val label: String, val selected: Boolean, val group: Tracks.Group?, val index: Int)

enum class AspectMode(val label: String) { Fit("Fit"), Zoom("Zoom"), Stretch("Stretch") }

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Buffering : PlaybackState
    data object Playing : PlaybackState
    data class Error(val message: String) : PlaybackState
}

@OptIn(UnstableApi::class)
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteStore: RemoteStore,
    private val xtream: XtreamClient,
    private val http: HttpJson,
    private val guide: GuideRepository,
    private val healthSync: io.github.petebutty.viewarr.data.sync.HealthSync,
    private val channelReports: io.github.petebutty.viewarr.data.report.ChannelReports,
    private val houseSync: io.github.petebutty.viewarr.data.report.HouseSync,
) : ViewModel() {

    // ---------------------------------------------------------------- Timeshift: pause / rewind live TV with catch-up

    /** Live TV paused at this moment (catch-up channels): Play carries on from here through the provider's catch-up. */
    private var livePausedAt: Long? = null

    /** The programme on [channel] at [time], from the stored guide. */
    private suspend fun programAt(channel: ChannelListItem, time: Long): GuideProgram? =
        runCatching { guide.programsFor(channel.id, channel.guideKey, time - 1, time + 1) }.getOrNull()
            ?.firstOrNull { it.startTime <= time && it.endTime > time }

    private fun canTimeshift(channel: ChannelListItem) = channel.catchupDays > 0 || serverTs.covers(channel)

    // Timeshift from the Viewarr plugin's buffer on the server (ServerTimeshift.kt): any channel, not only catch-up ones.
    private val serverTs = ServerTimeshift(http)
    /** Playing the server's buffer (behind live) rather than the live stream or the provider's catch-up. */
    private var onBuffer = false
    private var followJob: Job? = null
    init { serverTs.attach(viewModelScope) { timeshiftWant() } }

    /** Whether Left / Pause can go back in time on [channel] (catch-up, or the server's buffer). */
    fun canRewind(channel: ChannelListItem) = canTimeshift(channel)
    /** How far back [channel] can go: its catch-up days, else the server's buffer. */
    fun rewindEarliest(channel: ChannelListItem): Long =
        if (channel.catchupDays > 0) System.currentTimeMillis() - channel.catchupDays * 86_400_000L
        else serverTs.earliest(channel) ?: System.currentTimeMillis()
    /** The first step back from live (a minute for catch-up, which runs behind; less on the server's buffer). */
    fun firstStepMs(channel: ChannelListItem) = serverTs.firstStep(channel)

    private fun timeshiftWant(): ServerTimeshift.Want? {
        val s = session?.takeIf { !it.isDirect } ?: return null
        val ch = _channel.value ?: return null
        if (_catchup.value != null && !onBuffer) return null // the provider's catch-up: the server's copy lets go
        if (!serverTs.catchupToo && ch.catchupDays > 0) return null // the plugin buffers only channels without catch-up
        val key = direct?.takeIf { it.first == ch.id }?.second?.substringAfterLast('/') ?: ch.uuid
        val playing = onBuffer || livePausedAt != null || (_state.value == PlaybackState.Playing && _mpvLive.value == null)
        return ServerTimeshift.Want(s, ch.id, key, playing)
    }

    /** The moment on screen in [program]: from the buffer's own clock, or catch-up's offset plus the player's position. */
    private fun shiftedAt(p: ExoPlayer, program: GuideProgram) =
        (if (onBuffer) serverTs.playingAt(p) else null) ?: (program.startTime + catchupOffset + p.currentPosition)

    /** On the buffer, playing on into the next programme (or back into an earlier one): the bar follows. */
    private fun followProgramme(channel: ChannelListItem?, at: Long) {
        val c = _catchup.value ?: return
        if (channel == null || at in c.startTime until c.endTime || followJob?.isActive == true) return
        followJob = viewModelScope.launch {
            val next = programAt(channel, at) ?: serverTs.standIn(channel, at)
            if (_channel.value?.id == channel.id && onBuffer) { catchupOffset = at - next.startTime; _catchup.value = next }
        }
    }

    /** Catch-up asked for from [at]: from the server's buffer instead when it holds that moment. False: not on it. */
    private fun shiftToBuffer(channel: ChannelListItem, program: GuideProgram, s: Session, at: Long): Boolean {
        val target = serverTs.clamp(channel, at) ?: return false
        seekJob = viewModelScope.launch {
            val plan = serverTs.plan(target)
            if (plan == null) {
                serverTs.markBroken()
                if (channel.catchupDays > 0) startCatchup(channel, program, s, at - program.startTime) else play(channel, s)
                return@launch
            }
            catchupOffset = target - program.startTime
            start(channel, program, s, plan.url, plan.item, plan.startPositionMs)
            followProgramme(channel, target)
        }
        return true
    }

    /** A seek while on the buffer: within it, in the player straight away; past live, live; before it, catch-up. */
    private fun seekOnBuffer(channel: ChannelListItem, program: GuideProgram, p: ExoPlayer, deltaMs: Long) {
        val at = shiftedAt(p, program) + deltaMs
        if (deltaMs > 0 && at >= System.currentTimeMillis() - serverTs.liveGap(channel)) { goLive(); return }
        if (serverTs.seekTo(p, at)) { _position.value = (at - program.startTime) to (program.endTime - program.startTime); followProgramme(channel, at); return }
        if (channel.catchupDays > 0) playFrom(channel, at) else serverTs.earliest(channel)?.let { serverTs.seekTo(p, it) }
    }

    /** Plays catch-up from the moment [time] (in whichever programme was on then); live if that's now. */
    private fun playFrom(channel: ChannelListItem, time: Long) {
        val s = session ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // Providers' archives run a little behind: within ~45 s of now, just play live.
            if (time >= now - serverTs.liveGap(channel)) { play(channel, s); return@launch }
            val program = programAt(channel, time) ?: serverTs.standIn(channel, time).takeIf { serverTs.covers(channel) } ?: run { play(channel, s); return@launch }
            startCatchup(channel, program, s, time - program.startTime)
        }
    }

    /** How far behind live the picture is (catch-up of something on now, or paused live), else null. */
    fun behindLiveMs(): Long? {
        val now = System.currentTimeMillis()
        livePausedAt?.let { return now - it }
        val program = _catchup.value ?: return null
        if (program.endTime < now - 6 * 3_600_000L) return null
        val at = player?.let { shiftedAt(it, program) } ?: (program.startTime + catchupOffset)
        return (now - at).takeIf { it > 0 }
    }

    // ---------------------------------------------------------------- Dispatcharr: the channel's sources

    /**
     * One of the streams behind a Dispatcharr channel (providers often have several copies of each channel).
     * [hash]: Dispatcharr's own handle for playing this one stream directly (its "Preview Stream"), when it gives one.
     */
    data class LiveSource(
        val streamId: Int, val name: String, val provider: String, val current: Boolean, val hash: String? = null,
        /** Dispatcharr's last check of it: "1080p · 25 fps · H264" (null: never checked). */
        val quality: String? = null,
        /** Why it may not play right now: "busy" (its provider has no connection free), "didn't start just now", "gone from the provider's list". */
        val warning: String? = null,
    )

    /** Live TV through Dispatcharr (not a provider login, not catch-up): sources and stream info are available. */
    val dispatcharrLive get() = session?.isDirect == false && _catchup.value == null

    private val _sources = MutableStateFlow<List<LiveSource>?>(null)
    val sources = _sources.asStateFlow()
    private val _sourceNote = MutableStateFlow<String?>(null)
    /** Why the list is empty, or what a switch did. */
    val sourceNote = _sourceNote.asStateFlow()
    private var providers: Map<Int, String>? = null

    private fun api(s: Session, path: String) = "${s.serverUrl.trimEnd('/')}/$path"
    private fun apiHeaders(s: Session) = mapOf("X-API-Key" to s.apiKey)

    /** A Dispatcharr list answer: a plain list, or paged ({"results": […]}). */
    private fun results(body: JsonElement?): List<JsonElement> = (body as? JsonArray)?.toList() ?: body.array("results")
    /** A channel's stream ids, from its detail. */
    private fun streamIds(detail: JsonElement?): List<Int> = detail.array("streams").mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
    /** Provider account id → name (asked once, then kept). */
    private suspend fun providerNames(s: Session): Map<Int, String> = providers ?: results(http.get(api(s, "api/m3u/accounts/"), apiHeaders(s)).body)
        .mapNotNull { a -> a.int("id")?.let { id -> id to (a.str("name") ?: "Account $id") } }.toMap()
        .also { providers = it }

    /** Loading the Sources list (cancelled by the next load, or leaving: one channel's list never lands on another). */
    private var sourcesJob: Job? = null
    fun loadSources() {
        val s = session ?: return
        val ch = _channel.value ?: return
        _sources.value = null
        _sourceNote.value = null
        sourcesJob?.cancel()
        sourcesJob = viewModelScope.launch {
            runCatching {
                val h = apiHeaders(s)
                val detail = http.get(api(s, "api/channels/channels/${ch.id}/"), h)
                if (detail.code == 401 || detail.code == 403) { _sourceNote.value = "Your Dispatcharr user can't see sources (needs an admin API key)."; _sources.value = emptyList(); return@launch }
                val ids = streamIds(detail.body)
                val names = providerNames(s)
                // Which stream is feeding the channel: the live status says (admin users only); otherwise the stream with viewers.
                val status = http.get(api(s, "proxy/ts/status/${ch.uuid}"), h) // (the channel's own current stream)
                val statusCurrent = status.body.int("stream_id").takeIf { status.code in 200..299 }
                val streams = ids.map { id -> id to http.get(api(s, "api/channels/streams/$id/"), h).body }
                val current = statusCurrent ?: streams.firstOrNull { (_, st) -> (st.int("current_viewers") ?: 0) > 0 }?.first
                // (A source played directly: that one.)
                val playingStream = if (direct?.first == ch.id) direct?.third else current
                // Each provider's free connections (once per provider), for marking sources that can't start now.
                val playingProvider = streams.firstOrNull { (id, _) -> id == playingStream }?.second?.int("m3u_account")?.let { names[it] }
                val freeBy = HashMap<String, Int?>()
                val list = streams.map { (id, st) ->
                    val provider = st.int("m3u_account")?.let { names[it] } ?: ""
                    val isCurrent = id == playingStream
                    val free = if (isCurrent || provider.isEmpty()) null else freeBy.getOrPut(provider) { linesFree(s, provider) }
                    val failedAt = failedSources[id]
                    val warning = when {
                        isCurrent -> null
                        failedAt != null && System.currentTimeMillis() - failedAt < 10 * 60_000L -> "didn't start just now"
                        (st.child("is_stale") as? JsonPrimitive)?.content == "true" -> "gone from the provider's list"
                        // (The same provider as what's playing frees its own connection when switched: not busy.)
                        free != null && free < 1 && provider != playingProvider -> "busy"
                        else -> null
                    }
                    LiveSource(id, st.str("name") ?: "Stream $id", provider, isCurrent, st.str("stream_hash"), qualityOf(st.child("stream_stats")), warning)
                }
                if (_channel.value?.id != ch.id) return@launch
                if (status.code == 401 || status.code == 403) _sourceNote.value = ADMIN_ONLY
                if (list.size < 2) _sourceNote.value = "This channel has only one source."
                // Start with the best source on: the list in that order, the best marked.
                _sources.value = if (!bestSourceOn || list.size < 2) list
                    else sourceRank.order(sourceKey(s, ch), list, ids).mapIndexed { i, src -> if (i > 0) src else src.copy(quality = listOfNotNull(src.quality, "★ best").joinToString("  ·  ")) }
            }.onFailure {
                _sourceNote.value = "Couldn't load the sources."
                _sources.value = emptyList()
            }
        }
    }

    /**
     * Source switching (rewritten 2026-11-16; Peter: "Viewarr should keep memory of how many streams each provider
     * allows"). Decided before switching, from Dispatcharr's own count of each provider's connections (its limit and
     * how many are in use), lowered by what this TV has learned about providers that allow fewer than they claim:
     * - a line free at the new source's provider: gap-free. The new source opens alongside, silently, and swaps in
     *   once it has pictures; the old one is let go (and stopped in Dispatcharr if this TV was its only viewer).
     * - no line free (a one-connection backup in use, an account allowing one): what's playing is let go first,
     *   then the new one starts (a loading screen of a second or two), so the provider never sees two.
     * A gap-free try the provider refuses teaches Viewarr its real limit (kept per provider on this TV) and carries
     * straight on the safe way. If nothing starts, back to the channel with the reason.
     * While on a source you picked, Dispatcharr's own failover doesn't watch it; changing channel goes back to normal.
     */
    fun switchSource(src: LiveSource) {
        val s = session ?: return
        val ch = _channel.value ?: return
        if (switching) { _sourceNote.value = "Still switching…"; return }
        if (src.hash == null || s.isDirect) { _sourceNote.value = "This source can't be picked here."; return }
        if (_catchup.value != null) { _sourceNote.value = "Go back to live TV to change the source."; return }
        if (player == null) return
        // (mpv let go only once the switch goes ahead: a source that can't be picked left the picture black.)
        _mpvLive.value = null
        val url = "${s.serverUrl.trimEnd('/')}/proxy/ts/stream/${src.hash}"
        switching = true
        _sourceNote.value = "Switching to ${src.name}…"
        // Older checks (the start's stall check, retries) mustn't reopen the channel during the switch.
        stallJob?.cancel(); retryJob?.cancel(); rebufferJob?.cancel()
        switchJob = viewModelScope.launch {
            try {
                livePausedAt = null
                val playingId = playingStatusId(ch)
                val viewers = runCatching { http.get(api(s, "proxy/ts/status/$playingId"), apiHeaders(s)) }.getOrNull()
                    ?.takeIf { it.code in 200..299 }?.body?.str("client_count")?.toIntOrNull() ?: 1
                // (Dispatcharr's count leaves out a source played directly, so the connection this TV holds on the same
                // provider is counted here: TRex to TRex tried gap-free and waited 6 s for nothing.)
                val playingFrom = if (direct?.first == ch.id) directProvider else _sources.value?.firstOrNull { it.current }?.provider
                val sameProvider = src.provider.isNotEmpty() && src.provider == playingFrom
                val lines = linesFree(s, src.provider, ownLine = sameProvider, ownUncounted = sameProvider && direct?.first == ch.id)
                if (!isActive || _channel.value?.id != ch.id) return@launch
                Log.i("Playback", "source ${src.streamId} (${src.provider}): ${lines?.let { "$it line(s) free" } ?: "lines unknown"}")
                swapRefused = false
                if (lines != null && lines >= 1) {
                    if (swapIn(src, ch, s, url)) {
                        // The old one let go in Dispatcharr too (it holds the line for a grace period otherwise).
                        if (viewers <= 1) stopInDispatcharr(s, playingId)
                        _sourceNote.value = "Switched to ${src.name}"
                        watchHealth()
                        return@launch
                    }
                    if (!isActive || _channel.value?.id != ch.id) return@launch
                }
                val triedSwap = swapRefused
                swapRefused = false
                // Gap-free refused, then fine once the old one was let go: this provider allows fewer than it says.
                if (stopThenPlay(src, ch, s, url, playingId, viewers) && triedSwap) learnLimit(s, src.provider)
            } finally {
                switching = false
            }
        }
    }

    /** Gap-free: [src] opened alongside, silently; swapped in once it has pictures (up to 6 s). False: not swapped. */
    private suspend fun swapIn(src: LiveSource, ch: ChannelListItem, s: Session, url: String): Boolean {
        val next = buildPlayer(s).apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            playWhenReady = true
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
        pendingPlayer = next
        try {
            val ready = withTimeoutOrNull(6_000) {
                while (true) {
                    if (next.playerError != null) return@withTimeoutOrNull false
                    if (next.playbackState == Player.STATE_READY && next.bufferedPosition - next.currentPosition >= 700) return@withTimeoutOrNull true
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE") false
            } == true
            if (!ready || _channel.value?.id != ch.id || _catchup.value != null) {
                if (next.playerError != null) swapRefused = true
                Log.i("Playback", "source ${src.streamId}: gap-free didn't start (${next.playerError?.errorCodeName ?: "not ready in 6 s"})")
                runCatching { next.release() }; if (pendingPlayer === next) pendingPlayer = null
                stopInDispatcharr(s, src.hash ?: return false)
                return false
            }
            val old = player
            quietUntil = System.currentTimeMillis() + 5_000
            attachListener(next)
            next.trackSelectionParameters = next.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
            next.volume = old?.volume?.takeIf { it > 0f } ?: 1f
            pendingPlayer = null
            player = next
            direct = Triple(ch.id, url, src.streamId)
            directProvider = src.provider.ifEmpty { null }
            _playerVersion.value++
            old?.release()
            _state.value = PlaybackState.Playing
            _sources.value = _sources.value?.map { it.copy(current = it.streamId == src.streamId) }
            sourceJob?.cancel()
            _streamSource.value = directProvider ?: _streamSource.value
            Log.i("Playback", "source ${src.streamId}: swapped in")
            rememberWorkingSource()
            return true
        } catch (e: CancellationException) {
            runCatching { next.release() }; if (pendingPlayer === next) pendingPlayer = null
            // (Changed channel mid-swap: the new stream let go in Dispatcharr too, or it holds the provider's line.)
            src.hash?.let { h -> viewModelScope.launch { stopInDispatcharr(s, h) } }
            throw e
        }
    }

    /** The last gap-free try was refused (not just slow): learnt from only if letting go first then works. */
    private var swapRefused = false

    /** No line to spare: what's playing let go first, then [src] (a second or two of loading). True: it started. */
    private suspend fun stopThenPlay(src: LiveSource, ch: ChannelListItem, s: Session, url: String, playingId: String, viewers: Int): Boolean {
        val p = player ?: return false
        quietUntil = System.currentTimeMillis() + 25_000
        p.stop()
        _state.value = PlaybackState.Buffering
        // Stopped in Dispatcharr at once when this TV is its only viewer: left alone it keeps the provider's
        // connection for a grace period, and the new source is refused as over the limit.
        if (viewers <= 1) stopInDispatcharr(s, playingId)
        val free = withTimeoutOrNull(5_000) {
            delay(250)
            while (true) {
                val gone = runCatching { http.get(api(s, "proxy/ts/status/$playingId"), apiHeaders(s)) }.getOrNull()
                    ?.let { it.code == 404 || it.body.str("state") in setOf(null, "unknown", "stopped", "stopping", "error") } ?: true
                if (gone && (linesFree(s, src.provider) ?: 1) >= 1) break
                delay(250)
            }
            true
        } == true
        delay(400) // (Dispatcharr finishing its close of the old one)
        if (!currentCoroutineContext().isActive || _channel.value?.id != ch.id || _catchup.value != null) return false
        direct = Triple(ch.id, url, src.streamId)
        directProvider = src.provider.ifEmpty { null }
        pictureSinceReconnect = false
        startedAt = SystemClock.elapsedRealtime()
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
        _sources.value = _sources.value?.map { it.copy(current = it.streamId == src.streamId) }
        sourceJob?.cancel()
        _streamSource.value = directProvider ?: _streamSource.value
        Log.i("Playback", "source ${src.streamId}: let go first, playing${if (free) "" else " (line not shown free in 5 s)"}")
        // A picture within 10 s (sound only for a radio channel), else back to the channel with the reason. A quick
        // refusal (HTTP 503: Dispatcharr still tearing down that stream's last run, "teardown or pending shutdown",
        // Peter's AM9 17:38) is asked again a moment later, up to 5 times, rather than taken as final.
        var retries = 0
        val started = withTimeoutOrNull(16_000) {
            while (true) {
                if (pictureSinceReconnect || audioOnly()) return@withTimeoutOrNull true
                val err = p.playerError
                if (err != null) {
                    if (err.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS || retries >= 5) return@withTimeoutOrNull false
                    retries++
                    Log.i("Playback", "source ${src.streamId}: refused for now (${err.errorCodeName}), asking again ($retries)")
                    delay(1_200)
                    p.setMediaItem(MediaItem.fromUri(url)); p.prepare(); p.play()
                }
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") false
        } == true
        if (_channel.value?.id != ch.id || direct?.third != src.streamId) return false
        if (started) { _sourceNote.value = "Switched to ${src.name}"; watchHealth(); return true }
        failedSources[src.streamId] = System.currentTimeMillis()
        failoverTried += src.streamId
        forgetRememberedSource(ch, src.streamId)
        val busy = !free || p.playerError?.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        // (A provider with no line free isn't the stream's fault: not held against it in the best order.)
        if (!busy && !houseSync.quiet(ch.id)) Log.i("Playback", "source ${src.streamId} failed (didn't start): ${sourceRank.recordFail(sourceKey(s, ch), src.streamId)} fails in 7 days")
        Log.i("Playback", "source ${src.streamId}: didn't start (${p.playerError?.errorCodeName ?: "no picture in 10 s"}): back to the channel")
        _sourceNote.value = if (busy) "${src.name} is busy: ${src.provider.ifEmpty { "its provider" }} has no connection free right now. Back to the channel."
            else "${src.name} didn't start. Back to the channel."
        p.stop()
        stopInDispatcharr(s, src.hash ?: "")
        direct = null; directProvider = null
        _sources.value = _sources.value?.map { it.copy(current = false) }
        delay(500)
        restart()
        return false
    }

    /** What's playing in Dispatcharr: the channel, or the source picked (played by its own handle). */
    private fun playingStatusId(ch: ChannelListItem): String =
        direct?.takeIf { it.first == ch.id }?.second?.substringAfterLast('/') ?: ch.uuid

    private val endedAt = ArrayDeque<Long>()
    /** A live stream the server ended: picked up again, at most 4 times in 2 minutes (then the error shows as usual). */
    private fun reconnectAfterEnd() {
        val t = System.currentTimeMillis()
        while (endedAt.isNotEmpty() && t - endedAt.first() > 120_000) endedAt.removeFirst()
        if (endedAt.size >= 4) { _state.value = PlaybackState.Error("The stream keeps ending. Try another source (hold OK → Sources)."); return }
        endedAt.addLast(t)
        Log.i("Playback", "live stream ended by the server: connecting again (${endedAt.size})")
        _state.value = PlaybackState.Buffering
        retryJob?.cancel()
        retryJob = viewModelScope.launch { delay(1_000L * endedAt.size); restart() }
    }

    // ------------------------------------------------------------------ Failover and remembered sources
    /** Sources tried on this channel since it was picked (a failover never goes round in circles). */
    private val failoverTried = LinkedHashSet<Int>()
    private val sourcePrefs by lazy { context.getSharedPreferences("channel_sources", Context.MODE_PRIVATE) }
    private fun sourceKey(s: Session, ch: ChannelListItem) = s.serverUrl.trimEnd('/') + "|" + ch.uuid

    /** The source this channel last played from when it wasn't Dispatcharr's own first choice: started straight on it. */
    private fun useRememberedSource(ch: ChannelListItem, s: Session) {
        if (s.isDirect || direct?.first == ch.id) return
        val v = sourcePrefs.getString(sourceKey(s, ch), null)?.split('|')?.takeIf { it.size >= 3 } ?: return
        val sid = v[0].toIntOrNull() ?: return
        if (sid in failoverTried) return
        direct = Triple(ch.id, "${s.serverUrl.trimEnd('/')}/proxy/ts/stream/${v[1]}", sid)
        directProvider = v.drop(2).joinToString("|").ifEmpty { null }
        Log.i("Playback", "channel ${ch.id}: starting on its remembered source $sid")
    }

    /** A picked (or failed-over-to) source playing: remembered for next time. */
    private fun rememberWorkingSource() {
        val s = session ?: return
        val ch = _channel.value ?: return
        val d = direct?.takeIf { it.first == ch.id } ?: return
        if (s.isDirect || _catchup.value != null) return
        val hash = d.second.substringAfterLast('/')
        val v = "${d.third}|$hash|${directProvider.orEmpty()}"
        if (sourcePrefs.getString(sourceKey(s, ch), null) != v) sourcePrefs.edit().putString(sourceKey(s, ch), v).apply()
    }

    private fun forgetRememberedSource(ch: ChannelListItem, streamId: Int) {
        val s = session ?: return
        val k = sourceKey(s, ch)
        if (sourcePrefs.getString(k, null)?.substringBefore('|')?.toIntOrNull() == streamId) sourcePrefs.edit().remove(k).apply()
    }

    /**
     * This source won't start (nothing in 10 s, or the built-in player can't read it): the channel's next source, as
     * the Sources menu switches (gap-free when its provider has a line free). None left untried: mpv.
     */
    private fun autoFailover(why: String, orElse: (() -> Unit)? = null) {
        val s = session ?: return
        val ch = _channel.value ?: return
        if (switching) return
        viewModelScope.launch {
            val key = sourceKey(s, ch)
            sourcesCache.remove(key) // (a fail: the list and who's playing are asked for afresh)
            val list = runCatching { cachedSources(s, ch) }.getOrNull().orEmpty()
            if (_channel.value?.id != ch.id || switching) return@launch
            val current = direct?.takeIf { it.first == ch.id }?.third ?: list.firstOrNull { it.current }?.streamId
            // (A quiet channel, club TV or an event channel, is off air by nature: tried in turn, never held against.)
            val quiet = houseSync.quiet(ch.id)
            current?.let {
                failoverTried += it; forgetRememberedSource(ch, it); failedSources[it] = System.currentTimeMillis()
                if (!quiet) {
                    Log.i("Playback", "source $it failed ($why): ${sourceRank.recordFail(key, it)} fails in 7 days")
                    healthSync.changed(soon = true)
                }
            }
            // The next by rank (Start with the best source), else Dispatcharr's next.
            val left = list.filter { it.hash != null && it.streamId != current && it.streamId !in failoverTried }
            val next = (if (bestSourceOn) sourceRank.order(key, left, list.map { it.streamId }) else left).firstOrNull()
            if (next == null) {
                // Every source tried and none played: reported to the plugin by itself (Settings → Live TV → Reports
                // lists it), so a dead channel is known in the house without anyone pressing anything.
                if (!quiet) autoReport(ch, current ?: failoverTried.lastOrNull() ?: list.singleOrNull()?.streamId, why)
                if (orElse != null) orElse() else useMpv("$why; no other source left"); return@launch
            }
            val from = list.firstOrNull { it.streamId == current }?.name ?: "This source"
            Log.i("Playback", "channel ${ch.id}: source $current $why: failing over to ${next.streamId} (${next.provider})")
            _sources.value = list
            switchSource(next)
            _sourceNote.value = "$from didn't start: trying ${next.name}"
        }
    }

    /** A channel none of whose sources would play: a "dead" report filed by Viewarr itself (once an hour per channel). */
    private val autoReported = HashMap<Int, Long>()
    private fun autoReport(ch: ChannelListItem, streamId: Int?, why: String) {
        val now = System.currentTimeMillis()
        if (now - (autoReported[ch.id] ?: 0L) < 3_600_000L) return
        autoReported[ch.id] = now
        runCatching {
            channelReports.add(io.github.petebutty.viewarr.data.report.ChannelReport(
                id = java.util.UUID.randomUUID().toString(), at = now / 1000, channelId = ch.id,
                kind = io.github.petebutty.viewarr.data.report.ChannelReport.DEAD, streamId = streamId,
                note = "Automatic: $why on every source", channelName = ch.name,
            ))
        }
        Log.i("Playback", "channel ${ch.id}: no source played ($why): reported")
    }

    /** The channel's sources in Dispatcharr's order, with the one playing marked. */
    private suspend fun fetchSources(s: Session, ch: ChannelListItem): List<LiveSource> {
        val h = apiHeaders(s)
        val detail = http.get(api(s, "api/channels/channels/${ch.id}/"), h)
        if (detail.code !in 200..299) return emptyList()
        val ids = streamIds(detail.body)
        val names = providerNames(s)
        val status = http.get(api(s, "proxy/ts/status/${ch.uuid}"), h)
        val playing = direct?.takeIf { it.first == ch.id }?.third ?: status.body.int("stream_id").takeIf { status.code in 200..299 }
        return ids.map { id ->
            val st = http.get(api(s, "api/channels/streams/$id/"), h).body
            LiveSource(id, st.str("name") ?: "Stream $id", st.int("m3u_account")?.let { names[it] } ?: "", id == playing, st.str("stream_hash"), qualityOf(st.child("stream_stats")))
        }
    }

    // ------------------------------------------------------------------ Best source first (Peter, 2026-11-19)
    /** Each source's record on this box (fails, last good play) and the best order: see SourceRank. */
    // (A fail reaches the plugin within 30 s; a good play waits for the 10-minute push.)
    private val sourceRank by lazy { val sync = healthSync; SourceRank(context).also { it.onChange = { fail -> sync.changed(soon = fail) } } }
    /**
     * The stream [ch] is playing from, when known: the one played directly (best source / a switch), else the one
     * Dispatcharr's status named in the sources list (admin users). For a "dead stream" report (Report a problem).
     */
    fun currentStreamId(ch: ChannelListItem): Int? {
        if (_channel.value?.id != ch.id) return null
        // (A channel that never started has no current stream in Dispatcharr's status: the one tried last, then.)
        return direct?.takeIf { it.first == ch.id }?.third ?: _sources.value?.firstOrNull { it.current }?.streamId ?: failoverTried.lastOrNull()
    }
    /** Someone reported [streamId] dead (Report a problem): noted as a fail, so this box's order learns at once. */
    fun noteSourceFailed(ch: ChannelListItem, streamId: Int) {
        val s = session ?: return
        if (s.isDirect) return
        Log.i("Playback", "source $streamId reported dead: ${sourceRank.recordFail(sourceKey(s, ch), streamId)} fails in 7 days")
    }
    /** Settings → Live TV → Start with the best source. */
    private val bestSourceOn get() = AppColors.palette.bestSource
    /** A channel's sources as last fetched (server|channel → when, the list): a start within 30 minutes doesn't ask again. */
    private val sourcesCache = HashMap<String, Pair<Long, List<LiveSource>>>()
    private suspend fun cachedSources(s: Session, ch: ChannelListItem): List<LiveSource> {
        val k = sourceKey(s, ch)
        sourcesCache[k]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return it.second }
        return fetchSources(s, ch).also { if (it.isNotEmpty()) sourcesCache[k] = System.currentTimeMillis() to it }
    }

    /**
     * A fresh channel start with Start with the best source on: the channel is shown loading while its sources are
     * looked up (kept 30 minutes, so zapping doesn't ask again; 3 s at most), the best is chosen (chooseBest), then
     * the stream is asked for as usual, with the start's own stall watch.
     */
    private var bestJob: Job? = null
    private fun startOnBest(channel: ChannelListItem, s: Session, leaving: Triple<Int, String, Int>?) {
        // The source this TV played directly on the last channel is let go in Dispatcharr now (when nobody else has
        // it), so its provider's line is free for this channel rather than held for a grace period.
        leaving?.let { d -> viewModelScope.launch {
            val hash = d.second.substringAfterLast('/')
            val viewers = runCatching { http.get(api(s, "proxy/ts/status/$hash"), apiHeaders(s)) }.getOrNull()?.takeIf { it.code in 200..299 }?.body?.str("client_count")?.toIntOrNull() ?: 1
            if (viewers <= 1) stopInDispatcharr(s, hash)
        } }
        showLoading(channel)
        bestJob?.cancel()
        bestJob = viewModelScope.launch {
            val list = withTimeoutOrNull(3_000) { runCatching { cachedSources(s, channel) }.getOrNull() }.orEmpty()
            if (!isActive || _channel.value?.id != channel.id || _catchup.value != null) return@launch
            runCatching { chooseBest(s, channel, list) }
            startLive(channel, s)
        }
    }

    /** The channel shown as loading before its stream is asked for, with the last channel's counts cleared (as start does). */
    private fun showLoading(channel: ChannelListItem) {
        if (_channel.value?.id != channel.id) { failoverTried.clear(); retried = false; audioRetries = 0; reconnects = 0; playedAt = 0L; _streamSource.value = null; okJob?.cancel() }
        retryJob?.cancel(); stallJob?.cancel(); rebufferJob?.cancel()
        _channel.value = channel
        _catchup.value = null
        _state.value = PlaybackState.Buffering
        player?.stop()
    }

    /**
     * The best source for the channel (SourceRank: quality, then health, then Dispatcharr's order), set as [direct]:
     * - the remembered source stays when it's healthy and as good as the best;
     * - Dispatcharr's own first stream best: the channel plays as normal, so Dispatcharr's failover still watches it;
     * - the best's provider with no line free (asked once per provider): the next best with one; none: as normal.
     */
    private suspend fun chooseBest(s: Session, ch: ChannelListItem, list: List<LiveSource>) {
        val key = sourceKey(s, ch)
        val ids = list.map { it.streamId }
        val ranked = sourceRank.order(key, list.filter { it.hash != null && it.streamId !in failoverTried }, ids)
        val best = ranked.firstOrNull() ?: return
        val remembered = direct?.takeIf { it.first == ch.id }?.third?.let { id -> ranked.firstOrNull { it.streamId == id } }
        if (remembered != null && !sourceRank.health(key, remembered.streamId).failedRecently() && SourceRank.tier(remembered) >= SourceRank.tier(best)) {
            Log.i("Playback", "channel ${ch.id}: remembered source ${remembered.streamId} is as good as the best (${best.streamId}): keeping it")
            return
        }
        val first = ids.firstOrNull()
        val freeBy = HashMap<String, Boolean>()
        val pick = if (best.streamId == first) best
            else ranked.firstOrNull { src -> src.provider.isEmpty() || freeBy.getOrPut(src.provider) { (linesFree(s, src.provider) ?: 1) >= 1 } }
        Log.i("Playback", "channel ${ch.id}: best source ${best.streamId} (${best.provider}, ${SourceRank.label(best)})" +
            (if (pick == null) "; every provider busy" else if (pick.streamId != best.streamId) "; busy, so ${pick.streamId} (${pick.provider})" else "") +
            "; dispatcharr first is $first")
        remembered?.let { forgetRememberedSource(ch, it.streamId) }
        if (pick == null || pick.streamId == first) { direct = null; directProvider = null; return }
        direct = Triple(ch.id, "${s.serverUrl.trimEnd('/')}/proxy/ts/stream/${pick.hash}", pick.streamId)
        directProvider = pick.provider.ifEmpty { null }
    }

    /** Playing for a minute without a break: the source noted as good (SourceRank), for the best order next time. */
    private var okJob: Job? = null
    private fun watchOk() {
        okJob?.cancel()
        val s = session ?: return
        val ch = _channel.value ?: return
        if (s.isDirect || _catchup.value != null) return
        okJob = viewModelScope.launch {
            delay(60_000)
            if (_channel.value?.id != ch.id || _catchup.value != null || _state.value != PlaybackState.Playing) return@launch
            // The source played directly, else the stream Dispatcharr is feeding the channel from (admin users see it).
            val id = direct?.takeIf { it.first == ch.id }?.third
                ?: runCatching { http.get(api(s, "proxy/ts/status/${ch.uuid}"), apiHeaders(s)) }.getOrNull()
                    ?.takeIf { it.code in 200..299 }?.body?.int("stream_id") ?: return@launch
            sourceRank.recordOk(sourceKey(s, ch), id)
        }
    }

    // ------------------------------------------------------------------ Live TV reliability (Batch 2, 2026-11-16)
    /**
     * The channel handed to mpv (its address), when the built-in player can't start it: still nothing with no other
     * source left to try (an IPTV login: after its second try), or it can't read / decode the stream. Cleared by any
     * new channel, source or stop.
     */
    private val _mpvLive = MutableStateFlow<String?>(null)
    val mpvLive = _mpvLive.asStateFlow()
    /** For mpv's request header (Dispatcharr attributes the stream to this user). */
    fun mpvApiKey(): String? = session?.takeIf { !it.isDirect }?.apiKey
    private fun useMpv(why: String) {
        val s = session ?: return
        val ch = _channel.value ?: return
        if (_mpvLive.value != null) return
        Log.i("Playback", "channel ${ch.id}: the built-in player couldn't start it ($why): trying mpv")
        retryJob?.cancel(); stallJob?.cancel(); rebufferJob?.cancel(); frozenJob?.cancel()
        player?.stop()
        _state.value = PlaybackState.Buffering
        _mpvLive.value = liveUrl(s, ch)
    }
    fun mpvStarted() { if (_mpvLive.value != null) _state.value = PlaybackState.Playing }
    fun mpvFailed() {
        if (_mpvLive.value == null) return
        _mpvLive.value = null
        _state.value = PlaybackState.Error("This channel won't play here, in either player. Try another source (hold OK → Sources).")
    }
    private val CONTAINER_ERRORS = setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    )
    /** Servers (host) whose live links turned out to be HLS (remembered on the box). */
    private val hlsPrefs by lazy { context.getSharedPreferences("hls_hosts", Context.MODE_PRIVATE) }
    private var hlsTriedFor: Int? = null
    /** The live item: as HLS for a server known to send HLS behind its links (plain Xtream / M3U only). */
    private fun liveItem(s: Session, url: String): MediaItem {
        val host = Uri.parse(url).host
        return if (s.isDirect && _catchup.value == null && host != null && hlsPrefs.getBoolean(host, false))
            MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        else MediaItem.fromUri(url)
    }
    /** Once per channel: the same link again as HLS. True: trying. */
    private fun tryAsHls(): Boolean {
        val s = session ?: return false
        val ch = _channel.value ?: return false
        if (hlsTriedFor == ch.id) return false
        hlsTriedFor = ch.id
        val url = liveUrl(s, ch)
        val host = Uri.parse(url).host ?: return false
        if (hlsPrefs.getBoolean(host, false)) return false
        Log.i("Playback", "channel ${ch.id}: not readable as TS: trying it as HLS")
        hlsPrefs.edit().putBoolean(host, true).apply()
        hlsConfirm = host
        restart()
        return true
    }
    /** The host being tried as HLS: kept as HLS if it then plays, forgotten if it doesn't. */
    private var hlsConfirm: String? = null
    private val UNREADABLE = setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    )
    /** When the stream last became ready (0: not playing); a minute of playing resets the reconnect ladder. */
    private var readySince = 0L
    private var ladderTries = 0
    private var ladderStart = 0L

    /**
     * A live stream failed: tried again after 1.5, 3, 6, 10 then 15 s, up to 8 times or 2 minutes, before the error
     * shows; a minute of good playing starts the count again. A refusal that won't change (wrong login, not found)
     * isn't retried; the network being down doesn't use up tries. Falling behind the live window: back to live.
     * True: handled.
     */
    private fun liveLadder(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player?.let { it.seekToDefaultPosition(); it.prepare() }
            return true
        }
        val http = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (http != null && http in setOf(400, 401, 403, 404)) return false
        val t = System.currentTimeMillis()
        if (readySince > 0 && t - readySince > 60_000) { ladderTries = 0; ladderStart = 0 }
        readySince = 0
        if (ladderStart == 0L) ladderStart = t
        val offline = !networkUp()
        if (!offline && (ladderTries >= 8 || t - ladderStart > 120_000)) return false
        val wait = if (offline) 3_000L else longArrayOf(1_500, 3_000, 6_000, 10_000, 15_000)[ladderTries.coerceAtMost(4)]
        if (!offline) ladderTries++
        Log.i("Playback", "live stream failed (${error.errorCodeName}${http?.let { " $it" } ?: ""}): again in ${wait / 1000.0} s (try $ladderTries)${if (offline) ", network down" else ""}")
        _state.value = PlaybackState.Buffering
        retryJob?.cancel()
        retryJob = viewModelScope.launch { delay(wait); restart() }
        return true
    }

    private fun networkUp(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /**
     * A frozen picture while it says playing (the stream still "arriving" but no new frames shown): checked every 2 s
     * on live TV. 6 s without a new frame: connected again; again within 2 minutes: a fresh player; a third time: the
     * error (OwnTV's frozen-picture ladder, GPL-3). Not for radio channels, a paused channel or during a switch.
     */
    private var frozenJob: Job? = null
    private val frozenAt = ArrayDeque<Long>()
    private fun watchFrozen() {
        frozenJob?.cancel()
        val ch = _channel.value ?: return
        frozenJob = viewModelScope.launch {
            var last = -1
            var still = 0
            while (isActive && _channel.value?.id == ch.id) {
                delay(2_000)
                val p = player ?: continue
                if (_catchup.value != null || livePausedAt != null || switching || !p.isPlaying || audioOnly() ||
                    !p.currentTracks.containsType(C.TRACK_TYPE_VIDEO)) { last = -1; still = 0; continue }
                val counters = p.videoDecoderCounters ?: continue
                counters.ensureUpdated()
                val n = counters.renderedOutputBufferCount
                if (n == last) still++ else { still = 0; last = n }
                if (still < 3) continue
                still = 0; last = -1
                val t = System.currentTimeMillis()
                while (frozenAt.isNotEmpty() && t - frozenAt.first() > 120_000) frozenAt.removeFirst()
                frozenAt.addLast(t)
                Log.i("Playback", "picture frozen for 6 s on channel ${ch.id} (${frozenAt.size})")
                when (frozenAt.size) {
                    1 -> restart()
                    2 -> { player?.release(); player = null; restart() }
                    else -> { frozenAt.clear(); _state.value = PlaybackState.Error("The picture keeps freezing. Try another source (hold OK → Sources).") }
                }
                return@launch
            }
        }
    }

    /** Sources that didn't start lately (stream id → when), marked in the list for a while. */
    private val failedSources = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** "1080p · 25 fps · H264" from Dispatcharr's stream_stats (its last check of the stream). */
    private fun qualityOf(stats: JsonElement?): String? {
        if (stats !is JsonObject) return null
        val res = stats.str("resolution")?.substringAfter('x', "")?.toIntOrNull()?.let { h -> when { h >= 2000 -> "4K"; h >= 1000 -> "1080p"; h >= 700 -> "720p"; else -> "${h}p" } }
        val fps = stats.str("source_fps")?.toDoubleOrNull()?.let { "${kotlin.math.round(it).toInt()} fps" }
        val codec = stats.str("video_codec")?.uppercase()?.replace("HEVC", "H265")
        return listOfNotNull(res, fps, codec).joinToString(" · ").ifEmpty { null }
    }

    /**
     * Stream health while watching: Dispatcharr asked every 10 s about the channel (or the source picked); no new data
     * twice running (its buffer not moving on), or it saying unhealthy: a note that the source is struggling and how
     * to try another. Once per bad spell.
     */
    private var healthJob: Job? = null
    private fun watchHealth() {
        healthJob?.cancel()
        val s = session ?: return
        val ch = _channel.value ?: return
        if (s.isDirect) return
        healthJob = viewModelScope.launch {
            var lastIdx: Long? = null
            var bad = 0
            while (isActive && _channel.value?.id == ch.id) {
                delay(10_000)
                if (_catchup.value != null || livePausedAt != null || switching) { lastIdx = null; bad = 0; continue }
                val id = playingStatusId(ch)
                val st = runCatching { http.get(api(s, "proxy/ts/status/$id"), apiHeaders(s)) }.getOrNull()?.takeIf { it.code in 200..299 } ?: continue
                val idx = st.body.str("buffer_index")?.toLongOrNull()
                val unhealthy = st.body.child("local_manager")?.str("healthy") == "false" || (idx != null && lastIdx != null && idx <= lastIdx)
                lastIdx = idx
                bad = if (unhealthy) bad + 1 else 0
                if (bad == 2) {
                    Log.i("Playback", "source struggling on channel ${ch.id} (no new data)")
                    val from = directProvider ?: _streamSource.value
                    _connectionNote.value = "This source is struggling" + (from?.let { " ($it)" } ?: "") + ": no new picture coming in. Hold OK → Sources to try another."
                }
            }
        }
    }

    private suspend fun stopInDispatcharr(s: Session, id: String) {
        if (id.isEmpty()) return
        runCatching { http.post(api(s, "proxy/ts/stop/$id"), buildJsonObject { }, apiHeaders(s)) }
    }

    /** What this TV has learned: providers' real connection limits, where lower than Dispatcharr's (kept on the box). */
    private val providerPrefs by lazy { context.getSharedPreferences("provider_limits", Context.MODE_PRIVATE) }
    /** (Forgotten after a week: a provider can add connections, and one refusal may have had another cause.) */
    private fun learnedLimit(provider: String): Int? = providerPrefs.getInt(provider, -1).takeIf {
        it > 0 && System.currentTimeMillis() - providerPrefs.getLong("$provider@", 0L) < 7 * 86_400_000L
    }
    /** Refused with a line showing free: its real limit is what was in use then (at least one). */
    private suspend fun learnLimit(s: Session, provider: String) {
        if (provider.isEmpty()) return
        val used = providerCounts(s, provider)?.second ?: return
        val limit = used.coerceAtLeast(1)
        if (learnedLimit(provider)?.let { it <= limit } == true) return
        providerPrefs.edit().putInt(provider, limit).putLong("$provider@", System.currentTimeMillis()).apply()
        Log.i("Playback", "learned: $provider allows $limit connection(s)")
    }
    /**
     * Lines free at [provider] now: its limit (Dispatcharr's, or the lower one this TV learned) less those in use.
     * Null: can't tell (not an admin login, or not found).
     */
    private suspend fun linesFree(s: Session, provider: String, ownLine: Boolean = false, ownUncounted: Boolean = false): Int? {
        val (max, counted) = providerCounts(s, provider) ?: return null
        // A channel's stream is in Dispatcharr's count; a source played directly isn't: added then.
        val used = when { ownUncounted -> counted + 1; ownLine -> maxOf(counted, 1); else -> counted }
        val limit = listOfNotNull(max.takeIf { it > 0 }, learnedLimit(provider)).minOrNull() ?: return 99
        return limit - used
    }
    /** Dispatcharr's count for [provider]: its limit (0: none) and connections in use, over its active profiles. */
    private suspend fun providerCounts(s: Session, provider: String): Pair<Int, Int>? = runCatching {
        if (provider.isEmpty()) return null
        val r = http.get(api(s, "api/m3u/accounts/"), apiHeaders(s))
        if (r.code !in 200..299) return null
        val acc = results(r.body).firstOrNull { it.str("name") == provider } ?: return null
        val profiles = acc.array("profiles").filter { (it.child("is_active") as? JsonPrimitive)?.content != "false" }
        if (profiles.isEmpty()) return null
        val unlimited = profiles.any { (it.int("max_streams") ?: 0) <= 0 }
        (if (unlimited) 0 else profiles.sumOf { it.int("max_streams") ?: 0 }) to profiles.sumOf { it.int("current_viewers") ?: 0 }
    }.getOrNull()

    @Volatile private var switching = false

    /** Playing a channel's source directly (channel id, its address, stream id), else null. */
    private var direct: Triple<Int, String, Int>? = null
    private var directProvider: String? = null
    /** The new source's player getting ready out of sight during a gap-free switch (swapIn); released by the clean-ups. */
    private var pendingPlayer: ExoPlayer? = null


    /**
     * Where the channel's stream comes from, for the info bar, shown all the time (Settings → Appearance → Info bar:
     * source; Peter, 2026-11-15): through Dispatcharr with an admin login, the provider account of the stream
     * playing right now ("Happy TV", "TRex (backup)"), checked again every 30 s so Dispatcharr's own switch to a backup
     * shows too; otherwise the Live TV source itself (its name, else its address).
     */
    private val _streamSource = MutableStateFlow<String?>(null)
    val streamSource = _streamSource.asStateFlow()
    private var sourceJob: Job? = null
    private fun sourceLabel(s: Session) = s.name?.takeIf { it.isNotBlank() }
        ?: runCatching { Uri.parse(s.serverUrl).host }.getOrNull()?.removePrefix("www.") ?: "Live TV"
    private fun refreshStreamSource() {
        val s = session ?: run { _streamSource.value = null; return }
        val ch = _channel.value ?: return
        watchHealth()
        // On a directly played source: its provider, as picked (the channel's own status is about the channel).
        if (direct?.first == ch.id) { sourceJob?.cancel(); _streamSource.value = directProvider ?: sourceLabel(s); return }
        sourceJob?.cancel()
        sourceJob = viewModelScope.launch {
            while (true) {
                if (_channel.value?.id != ch.id) return@launch
                val account = if (s.isDirect) null else {
                    val st = runCatching { http.get(api(s, "proxy/ts/status/${ch.uuid}"), apiHeaders(s)) }.getOrNull()?.takeIf { it.code in 200..299 }
                    fun str(k: String) = (st?.body?.child(k) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
                    str("m3u_profile_name")?.removeSuffix(" Default")?.trim() ?: str("stream_name")
                }
                _streamSource.value = account ?: sourceLabel(s)
                // Only Dispatcharr can change it underneath: look again now and then.
                if (s.isDirect || account == null) return@launch
                delay(30_000)
            }
        }
    }

    /** A picture has come since the last seamless reconnect (false from a reconnect until its first picture). */
    @Volatile private var pictureSinceReconnect = true
    /** Playing with sound but no picture track (a radio channel): no picture is expected. */
    private fun audioOnly(): Boolean = player?.let { it.playbackState == Player.STATE_READY && !it.currentTracks.containsType(C.TRACK_TYPE_VIDEO) } == true
    private var switchJob: Job? = null
    /** The rebuffer watch (watchRebuffer) leaves the stream alone until then (just after a source switch). */
    private var quietUntil = 0L

    // ---------------------------------------------------------------- Dispatcharr: live stream info

    /**
     * The channel's frame rate as Dispatcharr's own probe of the stream found it (its source_fps), for the info bar.
     * Null for non-admin users (Dispatcharr only tells admins), IPTV logins and playlists, or before it's probed.
     */
    suspend fun sourceFps(): Float? {
        val s = session?.takeIf { !it.isDirect } ?: return null
        val ch = _channel.value ?: return null
        val r = runCatching { http.get(api(s, "proxy/ts/status/${playingStatusId(ch)}"), apiHeaders(s)) }.getOrNull()?.takeIf { it.code in 200..299 } ?: return null
        return (r.body.child("source_fps") as? JsonPrimitive)?.content?.toFloatOrNull()?.takeIf { it > 0 }
    }

    /**
     * What the player on this box is getting: picture, frame rate ([fps]: the certain reading, as the badge has it),
     * sound, bitrate, how far ahead it has buffered and frames dropped. Works for every source, not just Dispatcharr.
     */
    fun playerInfo(fps: Float?): List<Pair<String, String>> {
        val p = player ?: return emptyList()
        val v = p.videoFormat
        val a = p.audioFormat
        fun codec(f: Format?) = (f?.codecs?.substringBefore('.') ?: f?.sampleMimeType?.substringAfter('/'))?.uppercase()
        val rate = v?.frameRate?.takeIf { it > 0 } ?: fps
        val counters = p.videoDecoderCounters
        val ahead = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
        return listOfNotNull(
            listOfNotNull(v?.takeIf { it.width > 0 }?.let { "${it.width}×${it.height}" }, rate?.let { "%.3f".format(it).trimEnd('0').trimEnd('.') + " fps" + (if (v?.frameRate?.takeIf { f -> f > 0 } == null) " (counted)" else "") })
                .joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let { "Picture" to it },
            codec(v)?.let { "Video" to it },
            listOfNotNull(codec(a), a?.channelCount?.takeIf { it > 0 }?.let { when (it) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$it ch" } }, a?.sampleRate?.takeIf { it > 0 }?.let { "${it / 1000} kHz" })
                .joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let { "Audio" to it },
            (v?.bitrate ?: 0).takeIf { it > 0 }?.let { "Bitrate" to "%.1f Mbps".format(it / 1_000_000f) },
            "Buffered" to "%.1f s ahead".format(ahead / 1000f),
            counters?.let { "Dropped" to "${it.droppedBufferCount} frames" },
        )
    }

    /** What Dispatcharr knows about the stream feeding this channel right now, as label → value lines. */
    suspend fun streamInfo(): List<Pair<String, String>> {
        val s = session ?: return emptyList()
        val ch = _channel.value ?: return emptyList()
        val r = runCatching { http.get(api(s, "proxy/ts/status/${playingStatusId(ch)}"), apiHeaders(s)) }.getOrNull()
            ?: return listOf("Status" to "No answer from Dispatcharr")
        if (r.code == 401 || r.code == 403) return listOf("Status" to ADMIN_ONLY)
        if (r.code !in 200..299) return listOf("Status" to "Not running on the server")
        val b = r.body
        fun d(k: String) = (b.child(k) as? JsonPrimitive)?.content?.toDoubleOrNull()
        val provider = b.str("m3u_profile_name")
        return listOfNotNull(
            b.str("stream_name")?.let { "Source" to it },
            provider?.let { "Provider" to it },
            listOfNotNull(b.str("resolution"), d("source_fps")?.let { "%.2f fps".format(it).replace(".00", "") }).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let { "Picture" to it },
            listOfNotNull(b.str("video_codec")?.uppercase(), b.str("pixel_format")).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let { "Video" to it },
            listOfNotNull(b.str("audio_codec")?.uppercase(), b.str("audio_channels"), d("sample_rate")?.let { "${(it / 1000).toInt()} kHz" }).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let { "Audio" to it },
            (d("source_bitrate")?.let { "%.1f Mbps (source)".format(it / 1000) } ?: b.str("avg_bitrate")?.let { "$it (average)" })?.let { "Bitrate" to it },
            b.int("client_count")?.let { "Watching" to (if (it == 1) "1 viewer" else "$it viewers") },
            d("uptime")?.let { "Running for" to clockText((it * 1000).toLong()) },
            b.str("state")?.let { "State" to it },
        )
    }

    private companion object {
        const val ADMIN_ONLY = "Dispatcharr only lets admin users switch sources and see stream info."
        /** How long live TV may sit loading, with nothing new arriving, after it had been playing, before reconnecting. */
        const val REBUFFER_MS = 8_000L
    }

    private fun clockText(ms: Long): String {
        val t = ms / 1000; val h = t / 3600; val m = (t % 3600) / 60
        return if (h > 0) "${h} h ${m} min" else "${m} min"
    }

    /** Live stream address: Dispatcharr's proxy, or the IPTV provider's own stream. */
    private fun liveUrl(s: Session, channel: ChannelListItem) =
        when {
            // On a source picked by playing it directly (see switchSource): reconnects stay on it.
            direct?.first == channel.id -> direct!!.second
            s.isXc -> xtream.liveUrl(s, channel.id)
            s.isM3u -> channel.uuid // the playlist's own stream link
            else -> "${s.serverUrl.trimEnd('/')}/proxy/ts/stream/${channel.uuid}"
        }

    /** Catch-up from [from] (a moment in [program]) to the programme's end. */
    private fun catchupUrl(s: Session, channel: ChannelListItem, program: GuideProgram, from: Long = program.startTime): String {
        val minutes = maxOf(1L, (program.endTime - from + 59_999) / 60_000)
        return if (s.isXc) xtream.catchupUrl(s, channel.id, from, minutes)
        else "${s.serverUrl.trimEnd('/')}/proxy/catchup/${channel.uuid}?start=${from / 1000}&duration=$minutes"
    }

    /**
     * How far into the catch-up programme the current stream starts. Catch-up streams can't be seeked (the player
     * ignored seeks, and fast-forward from the start went back to the beginning), so every jump asks the server for
     * the stream from that moment, and positions are this plus the player's own.
     */
    private var catchupOffset = 0L
    private var seekJob: Job? = null
    private var seekTarget: Long? = null

    private fun startCatchup(channel: ChannelListItem, program: GuideProgram, s: Session, offsetMs: Long) {
        seekJob?.cancel(); seekTarget = null
        if (serverTs.covers(channel) && shiftToBuffer(channel, program, s, program.startTime + offsetMs)) return
        val length = program.endTime - program.startTime
        val wanted = program.startTime + offsetMs.coerceIn(0, maxOf(0, length - 30_000))
        // Provider (Xtream) catch-up is by the minute; Dispatcharr's by the second.
        val from = if (s.isXc) wanted - wanted % 60_000 else wanted - wanted % 1_000
        catchupOffset = from - program.startTime
        start(channel, program, s, catchupUrl(s, channel, program, from))
    }

    private var session: Session? = null
    private var retryJob: Job? = null
    private var retried = false

    private val _channel = MutableStateFlow<ChannelListItem?>(null)
    val channel = _channel.asStateFlow()

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state = _state.asStateFlow()

    /** Non-null while playing an archived programme instead of live TV. */
    private val _catchup = MutableStateFlow<GuideProgram?>(null)
    val catchup = _catchup.asStateFlow()

    /** (position, duration) in ms, updated while playing catch-up. */
    private val _position = MutableStateFlow(0L to 0L)
    val position = _position.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused = _paused.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val audioTracks = _audioTracks.asStateFlow()

    /** Includes an "Off" entry first (group == null). */
    private val _textTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val textTracks = _textTracks.asStateFlow()

    private val _aspect = MutableStateFlow(AspectMode.Fit)
    val aspect = _aspect.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                delay(500)
                val p = player ?: continue
                val c = _catchup.value
                if (c != null && seekTarget == null) {
                    val at = shiftedAt(p, c)
                    _position.value = (at - c.startTime) to (c.endTime - c.startTime)
                    if (onBuffer) followProgramme(_channel.value, at)
                }
            }
        }
    }

    var player: ExoPlayer? = null
        private set

    /** Bumped when a new player replaces the old one (another source): the screen re-attaches. */
    private val _playerVersion = MutableStateFlow(0)
    val playerVersion = _playerVersion.asStateFlow()

    private fun ensurePlayer(session: Session): ExoPlayer {
        if (player != null && this.session == session) return player!!
        player?.release()
        this.session = session
        return buildPlayer(session).also { p ->
            p.playWhenReady = true
            attachListener(p)
            player = p
            // The screen shows this one now (it kept the released player's last frame, with the new one's sound).
            _playerVersion.value++
        }
    }

    /**
     * Multi-view: another live channel in its own player, silent until its window is selected. The caller releases
     * it. Null when not signed in.
     */
    fun tilePlayer(channel: ChannelListItem): ExoPlayer? {
        val s = session ?: return null
        return buildPlayer(s).apply {
            // No audio until its window is selected (multi-view switches it on).
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            playWhenReady = true
            setMediaItem(MediaItem.fromUri(liveUrl(s, channel)))
            prepare()
        }
    }

    /** Multi-view: what to say on a window whose channel failed. */
    fun describeError(e: PlaybackException): String = describe(e)

    private val lowMemoryBox by lazy {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice == true
    }
    private fun buildPlayer(session: Session): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Viewarr/0.7")
            .setAllowCrossProtocolRedirects(true)
            // (A server that doesn't answer is given up on sooner, so the reconnect ladder can try again: 10 s to
            // connect and 20 s to read sat on a dead connection. Slow providers still get 15 s for their first bytes.)
            .setConnectTimeoutMs(6_000)
            .setReadTimeoutMs(15_000)
            // Optional for Dispatcharr's proxy, but attributes the stream to this user (limits, "who's watching").
            .setDefaultRequestProperties(if (session.isDirect) emptyMap() else mapOf("X-API-Key" to session.apiKey))
        val httpSource = reachOf(http)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        // Small start buffer so channel changes feel quick; live TV doesn't need a long one. (Half a second to start:
        // Dispatcharr sends a few seconds it already has straight away, so that's there almost at once.)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 30_000, 500, 2_500)
            // A memory cap (~48 MB; ~24 MB on a box Android calls low on memory): by default it's about 130 MB
            // per player, and multi-view or a gap-free switch runs two to four at once on 2 GB boxes.
            .setTargetBufferBytes(if (lowMemoryBox) 24 * 1024 * 1024 else 48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(context)
            .setRenderersFactory(
                // FFmpeg audio only as a fallback (MP2 on some channels); the box's own decoders and passthrough come first.
                // (The audio sink with the info bar's Audio sync on its clock.)
                object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(ctx: Context, floatOutput: Boolean, audioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink? =
                        super.buildAudioSink(ctx, floatOutput, audioTrackPlaybackParams)?.let(AvSync::wrap)

                    // (The picture's shape from the stream when the decoder says square pixels wrongly: SarFix.kt.)
                    override fun buildVideoRenderers(
                        ctx: Context, mode: Int, selector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector, fallback: Boolean,
                        handler: android.os.Handler, listener: androidx.media3.exoplayer.video.VideoRendererEventListener, joiningMs: Long,
                        out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
                    ) {
                        super.buildVideoRenderers(ctx, mode, selector, fallback, handler, listener, joiningMs, out)
                        SarFixVideoRenderer.replaceIn(out, ctx, codecAdapterFactory, selector, fallback, handler, listener, joiningMs)
                    }
                }
                    .setEnableDecoderFallback(true)
                    .apply { if (DecoderQuirks.amlogic) forceDisableMediaCodecAsynchronousQueueing() }
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpSource, extractors))
            .setLoadControl(loadControl)
            .build()
    }

    private fun attachListener(p: ExoPlayer) {
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                pictureSinceReconnect = true
                rememberWorkingSource()
                hlsConfirm = null
                // (Playing now: a stall from here on is a real one, watched as usual.)
                if (quietUntil > System.currentTimeMillis()) quietUntil = System.currentTimeMillis()
                if (startedAt > 0) Log.i("Playback", "first picture after ${SystemClock.elapsedRealtime() - startedAt} ms")
                startedAt = 0L
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> { _state.value = PlaybackState.Buffering; watchRebuffer() }
                    Player.STATE_READY -> {
                        retried = false
                        if (readySince == 0L) readySince = System.currentTimeMillis()
                        watchFrozen()
                        rebufferJob?.cancel()
                        if (_state.value != PlaybackState.Playing) { playedAt = System.currentTimeMillis(); if (sourceJob?.isActive != true) refreshStreamSource(); watchOk() }
                        _state.value = PlaybackState.Playing
                    }
                    Player.STATE_ENDED -> {
                        // The end of a catch-up programme: carry on with the next one (still behind live), or go live.
                        val ch = _channel.value
                        val ended = _catchup.value
                        if (ch != null && ended != null) playFrom(ch, ended.endTime + 1_000)
                        // Live TV "ending": the server closed the stream (Dispatcharr or the provider dropped
                        // it). The picture sat frozen while it still said playing: connected again instead,
                        // a few times a minute at most (OwnTV does the same).
                        else if (ch != null && ended == null && !switching && livePausedAt == null) reconnectAfterEnd()
                    }
                    else -> Unit
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                _audioTracks.value = options(tracks, C.TRACK_TYPE_AUDIO)
                val text = options(tracks, C.TRACK_TYPE_TEXT)
                _textTracks.value = if (text.isEmpty()) emptyList()
                else listOf(TrackOption("Off", text.none { it.selected }, null, -1)) + text
            }

            override fun onPlayerError(error: PlaybackException) {
                // During a source switch the switch handles its own errors (retrying Dispatcharr's quick 503s):
                // a restart from here cut its retries short and put the channel back underneath it.
                if (switching) return
                // On the server's buffer: fell behind its window (back to its start), else catch-up / live take over.
                if (onBuffer && player?.let { serverTs.recover(it, error) } == true) return
                // The sound couldn't open (a stream changing over, an odd first audio frame, the HDMI link
                // re-syncing): straight back in, without the wait, a few times; it's nearly always fine on the
                // next try. (It waited 2 s, then reconnected: a few seconds of black.)
                if (error.errorCode in AUDIO_ERRORS) {
                    val t = System.currentTimeMillis()
                    if (t - audioWindow > 60_000) { audioWindow = t; audioRetries = 0 }
                    if (audioRetries < 3) {
                        audioRetries++
                        _state.value = PlaybackState.Buffering
                        retryJob?.cancel()
                        // (Catch-up carries on where it was, not from the programme's start.)
                        retryJob = viewModelScope.launch { delay(150); restartHere() }
                        return
                    }
                }
                // Live TV the built-in player can't read or decode: mpv instead (it copes with more).
                if (_catchup.value == null && _channel.value != null && (error.errorCode in UNREADABLE ||
                        generateSequence(error as Throwable) { it.cause }.any { it.message?.contains("stuck buffering", true) == true })) {
                    if (session?.isDirect == false) autoFailover(error.errorCodeName)
                    // A plain Xtream / M3U link that isn't really TS (an HLS playlist behind a .ts name, or no
                    // extension): asked again as HLS first, remembered for that server; then mpv.
                    else if (error.errorCode in CONTAINER_ERRORS && tryAsHls()) Unit
                    else if (hlsConfirm != null) { hlsPrefs.edit().remove(hlsConfirm).apply(); hlsConfirm = null; useMpv("not TS, not HLS either") }
                    else useMpv(error.errorCodeName)
                    return
                }
                // Live TV: a reconnect ladder (OwnTV's, GPL-3) instead of one retry and an error.
                if (_catchup.value == null && _channel.value != null && liveLadder(error)) return
                if (!retried) {
                    retried = true
                    _state.value = PlaybackState.Buffering
                    retryJob?.cancel()
                    retryJob = viewModelScope.launch {
                        delay(2_000)
                        restart()
                    }
                } else {
                    val ch = _channel.value
                    val c = _catchup.value
                    // Catch-up of what's on now can be refused just behind live (the archive lags): go live instead.
                    if (ch != null && c != null && c.endTime > System.currentTimeMillis()) { retried = false; goLive() }
                    else _state.value = PlaybackState.Error(describe(error))
                }
            }
        })
    }

    /**
     * Stuck loading after it had been playing (Discord, 2026-11-15: a stream struggles, Dispatcharr moves the channel to
     * its next stream on its side, and the player sat on "Buffering" on the old connection until the channel was
     * turned off and on): after a few seconds with nothing new arriving, it reconnects to the channel, as turning it
     * off and on did. Live only (catch-up waits: it's a recording, it comes); a few times, then the error shows.
     */
    private var rebufferJob: Job? = null
    private var playedAt = 0L
    private var reconnects = 0
    private var reconnectWindow = 0L
    private fun watchRebuffer() {
        if (playedAt == 0L || _catchup.value != null) return // still starting: the start's own watch (stallJob) has it
        if (System.currentTimeMillis() < quietUntil) return
        rebufferJob?.cancel()
        val p = player ?: return
        val buffered = p.bufferedPosition
        val chId = _channel.value?.id
        rebufferJob = viewModelScope.launch {
            delay(REBUFFER_MS)
            val now = player ?: return@launch
            // Still this channel, still live, still loading, and nothing more has come in meanwhile.
            if (_channel.value?.id != chId || _catchup.value != null) return@launch
            if (_state.value != PlaybackState.Buffering || now.bufferedPosition > buffered + 1_000) return@launch
            // At most 4 a minute, however they go (a stream that never answers isn't asked for every 8 s for ever).
            val t = System.currentTimeMillis()
            if (t - reconnectWindow > 60_000) { reconnectWindow = t; reconnects = 0 }
            if (reconnects >= 4) {
                val keepsStopping = { _state.value = PlaybackState.Error("The stream keeps stopping. Try another channel or source.") }
                // Through Dispatcharr: the channel's next source instead (Peter, 2026-11-19); the error only with none left.
                if (dispatcharrLive) autoFailover("kept stopping", orElse = keepsStopping) else keepsStopping()
                return@launch
            }
            reconnects++
            Log.i("Playback", "stuck loading ${REBUFFER_MS / 1000} s after playing: reconnecting ($reconnects)")
            restart()
        }
    }

    /** Sound that couldn't open or be written: retried at once (see onPlayerError), [audioRetries] times per stream. */
    private val AUDIO_ERRORS = setOf(
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    )
    private var audioRetries = 0
    private var audioWindow = 0L

    // Zapping: the channel (its name, guide info) changes at once, but its stream is asked for only once you stop on
    // one. Each request starts the channel on Dispatcharr and takes a provider connection, held a few seconds after
    // you leave: zapping past several used them all up, and the one you stopped on waited, loading, until it gave up.
    private var lastPlayAt = 0L
    private var zapJob: Job? = null
    // A channel still loading with no picture after a while is asked for again, once (the connections it was waiting
    // for have been let go by then).
    private var stallJob: Job? = null

    fun play(channel: ChannelListItem, session: Session? = this.session) {
        // Someone without Live TV: no channel starts, whichever way it was asked for (zap, numbers, Sports, cards…).
        if (!Access.liveTv) return
        val s = session ?: return
        livePausedAt = null
        // A note about the last channel's sources ("Switched to …") doesn't belong to this one.
        if (_channel.value?.id != channel.id) { _sourceNote.value = null; _sources.value = null }
        val now = System.currentTimeMillis()
        // Settings → Remote buttons → Live TV → Channel change delay (0: straight away, as before).
        val wait = AppColors.palette.zapDelayMs
        val zapping = wait > 0 && now - lastPlayAt < wait + 700 && _channel.value?.id != channel.id
        // A fresh channel (not a reconnect or retry of the one playing): the best source is chosen for it.
        val fresh = _channel.value?.id != channel.id || zapFresh
        zapFresh = false
        lastPlayAt = now
        zapJob?.cancel(); bestJob?.cancel()
        if (zapping) {
            // Show it now, start it when the presses stop.
            retryJob?.cancel(); stallJob?.cancel()
            _channel.value = channel
            _catchup.value = null
            _state.value = PlaybackState.Buffering
            player?.stop()
            zapJob = viewModelScope.launch { delay(wait); lastPlayAt = 0L; zapFresh = true; play(channel, s) }
            return
        }
        val leaving = direct?.takeIf { it.first != channel.id }
        useRememberedSource(channel, s)
        if (fresh && !s.isDirect && bestSourceOn) startOnBest(channel, s, leaving) else startLive(channel, s)
        viewModelScope.launch { runCatching { remoteStore.setLastChannel(channel.id) } }
        // Also per source, so switching back to a saved source carries on with its channel.
        runCatching { guide.rememberChannel(channel.id) }
        if (!s.isDirect) checkConnections(channel, s)
    }
    /** The zap wait over: the channel it settled on is still a fresh one. */
    private var zapFresh = false

    /** The stream asked for, with the start's own watch (nothing at all in 10 s: the next source, or mpv). */
    private fun startLive(channel: ChannelListItem, s: Session) {
        start(channel, null, s, liveUrl(s, channel))
        stallJob?.cancel()
        stallJob = viewModelScope.launch {
            // Long enough for a slow provider (some take 6–7 s to start on Dispatcharr): asking again sooner threw
            // away a start that was nearly there. Only when nothing at all has arrived.
            // Checked again after asking again (the retry didn't set up a second check, so mpv never came: Sky
            // Witness sat loading, Peter 2026-11-16). Nothing at all twice running (a stream it waits on for ever:
            // empty sound tracks, say): mpv instead.
            // (Peter: "waiting 27 seconds is pointless if you have a backup source": after 10 s with nothing at all,
            // the channel's next source; mpv only when there's no other source left. IPTV logins: asked again once.)
            for (attempt in 0..1) {
                delay(if (attempt == 0) 10_000L else 12_000L)
                val stuck = _channel.value?.id == channel.id && _catchup.value == null && _mpvLive.value == null && !switching &&
                    _state.value == PlaybackState.Buffering && (player?.bufferedPosition ?: 0L) <= 0L
                if (!stuck) return@launch
                if (!s.isDirect) { autoFailover("nothing in 10 s"); return@launch }
                if (attempt == 1) { useMpv("nothing in 10 s, then nothing in 12 s more"); return@launch }
                Log.i("Playback", "channel ${channel.id} still loading after 10 s: asking again")
                start(channel, null, s, liveUrl(s, channel))
            }
        }
    }

    // ---------------------------------------------------------------- Dispatcharr: provider connections in use

    private val _connectionNote = MutableStateFlow<String?>(null)
    /** Set when this channel's providers have no free connection: what's using them (shown over the picture). */
    val connectionNote = _connectionNote.asStateFlow()
    fun clearConnectionNote() { _connectionNote.value = null }
    private var connectionJob: Job? = null
    /** Channel id → the provider accounts its streams come from (doesn't change often). */
    private val channelAccounts = HashMap<Int, Set<Int>>()

    /**
     * Providers often allow only one or two streams at once, and a recording can be holding one. If every provider
     * this channel could use is full (and the channel isn't already running, which would share its stream), say
     * what's using them, so a channel that doesn't start isn't a mystery. Admin users only; others see nothing.
     */
    private fun checkConnections(channel: ChannelListItem, s: Session) {
        connectionJob?.cancel()
        _connectionNote.value = null
        connectionJob = viewModelScope.launch {
            runCatching {
                val h = apiHeaders(s)
                val accountsRes = http.get(api(s, "api/m3u/accounts/"), h)
                if (accountsRes.code !in 200..299) return@launch
                val accounts = results(accountsRes.body)
                // Account id → (name, max, in use); max 0 means no limit.
                val usage = accounts.mapNotNull { a ->
                    val id = a.int("id") ?: return@mapNotNull null
                    val profiles = a.array("profiles").filter { (it.child("is_active") as? JsonPrimitive)?.content != "false" }
                    val max = profiles.sumOf { it.int("max_streams") ?: 0 }
                    val cur = profiles.sumOf { it.int("current_viewers") ?: 0 }
                    id to Triple(a.str("name") ?: "Your provider", max, cur)
                }.toMap()
                val mine = channelAccounts[channel.id] ?: run {
                    val ids = streamIds(http.get(api(s, "api/channels/channels/${channel.id}/"), h).body)
                    ids.mapNotNull { sid -> http.get(api(s, "api/channels/streams/$sid/"), h).body.int("m3u_account") }.toSet()
                        .also { channelAccounts[channel.id] = it }
                }
                if (mine.isEmpty()) return@launch
                val free = mine.any { id -> usage[id]?.let { (_, max, cur) -> max == 0 || cur < max } ?: true }
                if (free) return@launch
                // Full: what's running on the server right now (the same channel shares its stream, so that's fine).
                val status = http.get(api(s, "proxy/ts/status"), h).body.array("channels")
                if (status.any { it.str("channel_id") == channel.uuid || it.str("channel_uuid") == channel.uuid }) return@launch
                val running = status.mapNotNull { c -> c.str("channel_name") ?: c.str("stream_name") }.distinct().take(3)
                val names = mine.mapNotNull { usage[it] }.joinToString(" and ") { (name, max, _) -> "$name's $max" }
                val conns = if (mine.sumOf { usage[it]?.second ?: 0 } == 1) "connection is" else "connections are"
                _connectionNote.value = "$names $conns in use" + (if (running.isNotEmpty()) ": " + running.joinToString(", ") else "") +
                    ". This channel may not start until one is free."
            }
        }
    }

    /** Dispatcharr's native catch-up endpoint: authenticated by the API key header, seekable. */
    fun playCatchup(channel: ChannelListItem, program: GuideProgram, session: Session? = this.session) {
        if (!Access.liveTv) return
        val s = session ?: return
        livePausedAt = null // a programme picked from the guide: not "behind" any earlier live pause
        startCatchup(channel, program, s, 0)
    }

    private fun start(channel: ChannelListItem, program: GuideProgram?, s: Session, url: String, item: MediaItem? = null, startPos: Long = 0L) {
        // (Any new start is the built-in player's again; a different channel gets all its sources to try again.)
        if (_channel.value?.id != channel.id) failoverTried.clear()
        _mpvLive.value = null
        val p = ensurePlayer(s)
        retryJob?.cancel(); rebufferJob?.cancel()
        if (_channel.value?.id != channel.id || program != null) switchJob?.cancel()
        if (direct?.first != channel.id) { direct = null; directProvider = null }
        if (_channel.value?.id != channel.id || _catchup.value != program) { retried = false; audioRetries = 0; reconnects = 0; playedAt = 0L; _streamSource.value = null; okJob?.cancel() }
        _channel.value = channel
        _catchup.value = program
        onBuffer = item != null // (the server's buffer: ServerTimeshift)
        if (program == null) catchupOffset = 0
        _position.value = (if (program != null) catchupOffset else 0L) to (program?.let { it.endTime - it.startTime } ?: 0L)
        _paused.value = false
        _state.value = PlaybackState.Buffering
        startedAt = SystemClock.elapsedRealtime()
        p.setMediaItem(item ?: liveItem(s, url), startPos)
        p.prepare()
        p.play()
    }

    /** When the last stream was asked for: logged against its first picture, to see where channel changes spend time. */
    private var startedAt = 0L

    private fun restart(startAt: Long = 0) {
        val channel = _channel.value ?: return
        val s = session ?: return
        val program = _catchup.value
        if (program == null) play(channel, s) else startCatchup(channel, program, s, startAt)
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val channel = _channel.value ?: return
        val program = _catchup.value
        if (program == null) {
            // Live: back through catch-up (from the paused moment if paused); forward is already live.
            if (deltaMs >= 0 || !canTimeshift(channel)) return
            val now = System.currentTimeMillis()
            val from = livePausedAt ?: now
            livePausedAt = null
            // The archive runs ~45 s behind live, and anything that close just plays live again: so a first step
            // back from live goes at least a minute back (a short seek step used to do nothing).
            playFrom(channel, minOf(from + deltaMs, now - serverTs.firstStep(channel)))
            return
        }
        if (onBuffer) { seekOnBuffer(channel, program, p, deltaMs); return }
        val now = System.currentTimeMillis()
        // Repeated presses build on the last one's target; the stream is fetched once they stop.
        val atMs = (seekTarget ?: shiftedAt(p, program)) + deltaMs
        // Before this programme: the previous one; past what's been broadcast: back to live.
        if (atMs < program.startTime && deltaMs < 0) { seekJob?.cancel(); seekTarget = null; playFrom(channel, atMs); return }
        if (program.endTime > now - 60_000 && atMs >= now - 45_000) { seekJob?.cancel(); seekTarget = null; goLive(); return }
        if (atMs >= program.endTime - 5_000) { seekJob?.cancel(); seekTarget = null; playFrom(channel, program.endTime + 1_000); return }
        seekTarget = atMs
        _position.value = (atMs - program.startTime) to (program.endTime - program.startTime)
        seekJob?.cancel()
        seekJob = viewModelScope.launch {
            delay(700)
            val t = seekTarget ?: return@launch
            val s = session ?: return@launch
            startCatchup(channel, program, s, t - program.startTime)
        }
    }

    /** Live, catch-up channels: plays from [time] (the rewind marker on the player menu's bar). */
    fun rewindLiveTo(time: Long) {
        val channel = _channel.value ?: return
        if (!canTimeshift(channel)) return
        livePausedAt = null
        playFrom(channel, minOf(time, System.currentTimeMillis() - serverTs.firstStep(channel)))
    }

    fun restartProgramme() {
        val program = _catchup.value ?: return
        val channel = _channel.value ?: return
        val s = session ?: return
        startCatchup(channel, program, s, 0)
    }

    fun selectTrack(option: TrackOption, type: Int) {
        val p = player ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        val group = option.group
        if (group == null) builder.setTrackTypeDisabled(type, true)
        else builder.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
        p.trackSelectionParameters = builder.build()
    }

    fun setAspect(mode: AspectMode) {
        _aspect.value = mode
    }

    fun goLive() {
        livePausedAt = null
        _channel.value?.let { play(it) }
    }

    fun togglePause() {
        val p = player ?: return
        val channel = _channel.value ?: return
        if (_catchup.value == null) {
            // Live: pause freezes the picture and remembers the moment; Play carries on from it through catch-up.
            if (!canTimeshift(channel)) return
            val pausedAt = livePausedAt
            if (pausedAt == null) {
                livePausedAt = System.currentTimeMillis()
                p.pause()
                _paused.value = true
            } else {
                livePausedAt = null
                _paused.value = false
                playFrom(channel, pausedAt)
            }
            return
        }
        // (By what was asked for, not whether it's moving: while catch-up was loading, Pause did nothing.)
        p.playWhenReady = !p.playWhenReady
        _paused.value = !p.playWhenReady
    }

    /** Again from here: live at the live edge, catch-up where it had got to. */
    private fun restartHere() = restart(if (_catchup.value != null) _position.value.first else 0)

    fun retry() {
        retried = false
        restartHere()
    }

    /** Releases the stream (and the provider connection) when the app goes to the background. */
    fun pause() {
        // (A channel change, catch-up seek or stall check still waiting would start the stream again in the background.)
        cancelAll()
        player?.let { p -> _catchup.value?.let { c -> _position.value = (shiftedAt(p, c) - c.startTime) to (c.endTime - c.startTime) } }
        player?.stop()
    }

    /** Every waiting job and check stopped, mpv and a half-made switch let go (pause, stop). */
    private fun cancelAll() {
        retryJob?.cancel(); zapJob?.cancel(); seekJob?.cancel(); stallJob?.cancel(); rebufferJob?.cancel(); switchJob?.cancel(); sourceJob?.cancel()
        healthJob?.cancel(); sourcesJob?.cancel(); frozenJob?.cancel(); bestJob?.cancel(); okJob?.cancel(); readySince = 0; _mpvLive.value = null
        pendingPlayer?.let { runCatching { it.release() } }; pendingPlayer = null
    }

    /** Live resumes at the live edge; catch-up resumes where it was left. */
    fun resume() = restartHere()

    fun stop() {
        cancelAll()
        direct = null; directProvider = null
        player?.stop()
        _channel.value = null
        _catchup.value = null
        _state.value = PlaybackState.Idle
    }

    /** A film or episode is playing instead: live TV ends and its player is let go (a new one is made for the next channel). */
    fun close() {
        stop()
        player?.release()
        player = null
        // The screen lets go of it too.
        _playerVersion.value++
    }


    override fun onCleared() {
        pendingPlayer?.let { runCatching { it.release() } }; pendingPlayer = null
        player?.release()
        player = null
    }

    private fun options(tracks: Tracks, type: Int): List<TrackOption> {
        val result = ArrayList<TrackOption>()
        var n = 0
        for (group in tracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                n++
                result += TrackOption(trackLabel(group.getTrackFormat(i), n, type), group.isTrackSelected(i), group, i)
            }
        }
        return result
    }

    private fun trackLabel(f: Format, n: Int, type: Int): String {
        val language = f.language?.takeIf { it != "und" && it.isNotBlank() }?.let { Locale(it).displayLanguage.ifBlank { it } }
        val name = f.label ?: language ?: "Track $n"
        if (type != C.TRACK_TYPE_AUDIO) return name
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MPEG"
            else -> null
        }
        val channels = when (f.channelCount) {
            1 -> "mono"
            2 -> "stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        }
        val detail = listOfNotNull(codec, channels).joinToString(" ")
        return if (detail.isEmpty()) name else "$name ($detail)"
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused this channel (it may be at its connection limit)."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the server."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This TV box can't decode this channel's format."
        else -> "Playback failed (${e.errorCodeName})."
    }
}
