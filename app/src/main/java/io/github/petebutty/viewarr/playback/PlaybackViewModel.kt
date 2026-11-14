package io.github.petebutty.viewarr.playback

import android.content.Context
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.str
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val remoteStore: io.github.petebutty.viewarr.data.settings.RemoteStore,
    private val xtream: io.github.petebutty.viewarr.data.xtream.XtreamClient,
    private val http: io.github.petebutty.viewarr.data.remote.services.HttpJson,
    private val guide: io.github.petebutty.viewarr.data.repository.GuideRepository,
) : ViewModel() {

    // ---------------------------------------------------------------- Timeshift: pause / rewind live TV with catch-up

    /** Live TV paused at this moment (catch-up channels): Play carries on from here through the provider's catch-up. */
    private var livePausedAt: Long? = null

    /** The programme on [channel] at [time], from the stored guide. */
    private suspend fun programAt(channel: ChannelListItem, time: Long): GuideProgram? =
        runCatching { guide.programsFor(channel.id, channel.guideKey, time - 1, time + 1) }.getOrNull()
            ?.firstOrNull { it.startTime <= time && it.endTime > time }

    private fun canTimeshift(channel: ChannelListItem) = channel.catchupDays > 0

    /** Plays catch-up from the moment [time] (in whichever programme was on then); live if that's now. */
    private fun playFrom(channel: ChannelListItem, time: Long) {
        val s = session ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // Providers' archives run a little behind: within ~45 s of now, just play live.
            if (time >= now - 45_000) { play(channel, s); return@launch }
            val program = programAt(channel, time) ?: run { play(channel, s); return@launch }
            startCatchup(channel, program, s, time - program.startTime)
        }
    }

    /** How far behind live the picture is (catch-up of something on now, or paused live), else null. */
    fun behindLiveMs(): Long? {
        val now = System.currentTimeMillis()
        livePausedAt?.let { return now - it }
        val program = _catchup.value ?: return null
        if (program.endTime < now - 6 * 3_600_000L) return null
        val at = program.startTime + catchupOffset + (player?.currentPosition ?: 0)
        return (now - at).takeIf { it > 0 }
    }

    // ---------------------------------------------------------------- Dispatcharr: the channel's sources

    /** One of the streams behind a Dispatcharr channel (providers often have several copies of each channel). */
    data class LiveSource(val streamId: Int, val name: String, val provider: String, val current: Boolean)

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

    fun loadSources() {
        val s = session ?: return
        val ch = _channel.value ?: return
        _sources.value = null
        _sourceNote.value = null
        viewModelScope.launch {
            runCatching {
                val h = apiHeaders(s)
                val detail = http.get(api(s, "api/channels/channels/${ch.id}/"), h)
                if (detail.code == 401 || detail.code == 403) { _sourceNote.value = "Your Dispatcharr user can't see sources (needs an admin API key)."; _sources.value = emptyList(); return@launch }
                val ids = detail.body.array("streams").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                val names = providers ?: http.get(api(s, "api/m3u/accounts/"), h).body.let { b ->
                    (if (b is kotlinx.serialization.json.JsonArray) b.toList() else b.array("results"))
                        .mapNotNull { a -> a.int("id")?.let { id -> id to (a.str("name") ?: "Account $id") } }.toMap()
                }.also { providers = it }
                // Which stream is feeding the channel: the live status says (admin users only); otherwise the stream with viewers.
                val status = http.get(api(s, "proxy/ts/status/${ch.uuid}"), h)
                val statusCurrent = status.body.int("stream_id").takeIf { status.code in 200..299 }
                val streams = ids.map { id -> id to http.get(api(s, "api/channels/streams/$id/"), h).body }
                val current = statusCurrent ?: streams.firstOrNull { (_, st) -> (st.int("current_viewers") ?: 0) > 0 }?.first
                val list = streams.map { (id, st) ->
                    LiveSource(id, st.str("name") ?: "Stream $id", st.int("m3u_account")?.let { names[it] } ?: "", id == current)
                }
                if (status.code == 401 || status.code == 403) _sourceNote.value = ADMIN_ONLY
                if (list.size < 2) _sourceNote.value = "This channel has only one source."
                _sources.value = list
            }.onFailure {
                _sourceNote.value = "Couldn't load the sources."
                _sources.value = emptyList()
            }
        }
    }

    /** Tells Dispatcharr to feed this channel from another of its streams, then reconnects to pick it up cleanly. */
    fun switchSource(src: LiveSource) {
        val s = session ?: return
        val ch = _channel.value ?: return
        viewModelScope.launch {
            val r = runCatching {
                http.post(api(s, "proxy/ts/change_stream/${ch.uuid}"),
                    kotlinx.serialization.json.buildJsonObject { put("stream_id", kotlinx.serialization.json.JsonPrimitive(src.streamId)) },
                    apiHeaders(s))
            }.getOrNull()
            if (r != null && r.code in 200..299) {
                _sourceNote.value = "Switched to ${src.name}"
                _sources.value = _sources.value?.map { it.copy(current = it.streamId == src.streamId) }
                delay(800)
                restart()
            } else {
                _sourceNote.value = when (r?.code) {
                    401, 403 -> ADMIN_ONLY
                    null -> "No answer from Dispatcharr."
                    else -> r.body.str("error") ?: "Dispatcharr couldn't switch (${r.code})."
                }
            }
        }
    }

    // ---------------------------------------------------------------- Dispatcharr: live stream info

    /** What Dispatcharr knows about the stream feeding this channel right now, as label → value lines. */
    /**
     * The channel's frame rate as Dispatcharr's own probe of the stream found it (its source_fps), for the info bar.
     * Null for non-admin users (Dispatcharr only tells admins), IPTV logins and playlists, or before it's probed.
     */
    suspend fun sourceFps(): Float? {
        val s = session?.takeIf { !it.isDirect } ?: return null
        val ch = _channel.value ?: return null
        val r = runCatching { http.get(api(s, "proxy/ts/status/${ch.uuid}"), apiHeaders(s)) }.getOrNull()?.takeIf { it.code in 200..299 } ?: return null
        return (r.body.child("source_fps") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()?.takeIf { it > 0 }
    }

    /**
     * What the player on this box is getting: picture, frame rate ([fps]: the certain reading, as the badge has it),
     * sound, bitrate, how far ahead it has buffered and frames dropped. Works for every source, not just Dispatcharr.
     */
    fun playerInfo(fps: Float?): List<Pair<String, String>> {
        val p = player ?: return emptyList()
        val v = p.videoFormat
        val a = p.audioFormat
        fun codec(f: androidx.media3.common.Format?) = (f?.codecs?.substringBefore('.') ?: f?.sampleMimeType?.substringAfter('/'))?.uppercase()
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

    suspend fun streamInfo(): List<Pair<String, String>> {
        val s = session ?: return emptyList()
        val ch = _channel.value ?: return emptyList()
        val r = runCatching { http.get(api(s, "proxy/ts/status/${ch.uuid}"), apiHeaders(s)) }.getOrNull()
            ?: return listOf("Status" to "No answer from Dispatcharr")
        if (r.code == 401 || r.code == 403) return listOf("Status" to ADMIN_ONLY)
        if (r.code !in 200..299) return listOf("Status" to "Not running on the server")
        val b = r.body
        fun d(k: String) = (b.child(k) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
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
    }

    private fun clockText(ms: Long): String {
        val t = ms / 1000; val h = t / 3600; val m = (t % 3600) / 60
        return if (h > 0) "${h} h ${m} min" else "${m} min"
    }

    /** Live stream address: Dispatcharr's proxy, or the IPTV provider's own stream. */
    private fun liveUrl(s: Session, channel: ChannelListItem) =
        when {
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
                if (c != null && seekTarget == null) _position.value = (catchupOffset + p.currentPosition) to (c.endTime - c.startTime)
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

    private fun buildPlayer(session: Session): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Viewarr/0.7")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            // Optional for Dispatcharr's proxy, but attributes the stream to this user (limits, "who's watching").
            .setDefaultRequestProperties(if (session.isDirect) emptyMap() else mapOf("X-API-Key" to session.apiKey))
        val httpSource = io.github.petebutty.viewarr.data.net.reachOf(http)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        // Small start buffer so channel changes feel quick; live TV doesn't need a long one. (Half a second to start:
        // Dispatcharr sends a few seconds it already has straight away, so that's there almost at once.)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 30_000, 500, 2_500)
            .build()
        return ExoPlayer.Builder(context)
            .setRenderersFactory(
                // FFmpeg audio only as a fallback (MP2 on some channels); the box's own decoders and passthrough come first.
                // (The audio sink with the info bar's Audio sync on its clock.)
                object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(ctx: Context, floatOutput: Boolean, audioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink? =
                        super.buildAudioSink(ctx, floatOutput, audioTrackPlaybackParams)?.let(AvSync::wrap)
                }
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpSource, extractors))
            .setLoadControl(loadControl)
            .build()
    }

    private fun attachListener(p: ExoPlayer) {
                p.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        if (startedAt > 0) android.util.Log.i("Playback", "first picture after ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
                        startedAt = 0L
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> _state.value = PlaybackState.Buffering
                            Player.STATE_READY -> {
                                retried = false
                                _state.value = PlaybackState.Playing
                            }
                            Player.STATE_ENDED -> {
                                // The end of a catch-up programme: carry on with the next one (still behind live), or go live.
                                val ch = _channel.value
                                val ended = _catchup.value
                                if (ch != null && ended != null) playFrom(ch, ended.endTime + 1_000)
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

    // Zapping: the channel (its name, guide info) changes at once, but its stream is asked for only once you stop on
    // one. Each request starts the channel on Dispatcharr and takes a provider connection, held a few seconds after
    // you leave: zapping past several used them all up, and the one you stopped on waited, loading, until it gave up.
    private var lastPlayAt = 0L
    private var zapJob: Job? = null
    // A channel still loading with no picture after a while is asked for again, once (the connections it was waiting
    // for have been let go by then).
    private var stallJob: Job? = null

    fun play(channel: ChannelListItem, session: Session? = this.session) {
        val s = session ?: return
        livePausedAt = null
        // A note about the last channel's sources ("Switched to …") doesn't belong to this one.
        if (_channel.value?.id != channel.id) { _sourceNote.value = null; _sources.value = null }
        val now = System.currentTimeMillis()
        // Settings → Remote buttons → Live TV → Channel change delay (0: straight away, as before).
        val wait = io.github.petebutty.viewarr.ui.theme.AppColors.palette.zapDelayMs
        val zapping = wait > 0 && now - lastPlayAt < wait + 700 && _channel.value?.id != channel.id
        lastPlayAt = now
        zapJob?.cancel()
        if (zapping) {
            // Show it now, start it when the presses stop.
            retryJob?.cancel(); stallJob?.cancel()
            _channel.value = channel
            _catchup.value = null
            _state.value = PlaybackState.Buffering
            player?.stop()
            zapJob = viewModelScope.launch { delay(wait); lastPlayAt = 0L; play(channel, s) }
            return
        }
        start(channel, null, s, liveUrl(s, channel))
        stallJob?.cancel()
        stallJob = viewModelScope.launch {
            // Long enough for a slow provider (some take 6–7 s to start on Dispatcharr): asking again sooner threw
            // away a start that was nearly there. Only when nothing at all has arrived.
            delay(15_000)
            if (_channel.value?.id == channel.id && _catchup.value == null && _state.value == PlaybackState.Buffering &&
                (player?.bufferedPosition ?: 0L) <= 0L
            ) {
                android.util.Log.i("Playback", "channel ${channel.id} still loading after 15 s: asking again")
                start(channel, null, s, liveUrl(s, channel))
            }
        }
        viewModelScope.launch { runCatching { remoteStore.setLastChannel(channel.id) } }
        // Also per source, so switching back to a saved source carries on with its channel.
        runCatching { guide.rememberChannel(channel.id) }
        if (!s.isDirect) checkConnections(channel, s)
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
                val accountsBody = accountsRes.body
                val accounts = (if (accountsBody is kotlinx.serialization.json.JsonArray) accountsBody.toList() else accountsBody.array("results"))
                // Account id → (name, max, in use); max 0 means no limit.
                val usage = accounts.mapNotNull { a ->
                    val id = a.int("id") ?: return@mapNotNull null
                    val profiles = a.array("profiles").filter { (it.child("is_active") as? kotlinx.serialization.json.JsonPrimitive)?.content != "false" }
                    val max = profiles.sumOf { it.int("max_streams") ?: 0 }
                    val cur = profiles.sumOf { it.int("current_viewers") ?: 0 }
                    id to Triple(a.str("name") ?: "Your provider", max, cur)
                }.toMap()
                val mine = channelAccounts[channel.id] ?: run {
                    val ids = http.get(api(s, "api/channels/channels/${channel.id}/"), h).body.array("streams")
                        .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
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
        val s = session ?: return
        livePausedAt = null // a programme picked from the guide: not "behind" any earlier live pause
        startCatchup(channel, program, s, 0)
    }

    private fun start(channel: ChannelListItem, program: GuideProgram?, s: Session, url: String, startAt: Long = 0) {
        val p = ensurePlayer(s)
        retryJob?.cancel()
        if (_channel.value?.id != channel.id || _catchup.value != program) retried = false
        _channel.value = channel
        _catchup.value = program
        if (program == null) catchupOffset = 0
        _position.value = (if (program != null) catchupOffset else startAt) to (program?.let { it.endTime - it.startTime } ?: 0L)
        _paused.value = false
        _state.value = PlaybackState.Buffering
        startedAt = android.os.SystemClock.elapsedRealtime()
        p.setMediaItem(MediaItem.fromUri(url), startAt)
        p.prepare()
        p.play()
    }

    /** When the last stream was asked for: logged against its first picture, to see where channel changes spend time. */
    private var startedAt = 0L

    private fun restart(startAt: Long = 0) {
        val channel = _channel.value ?: return
        val s = session ?: return
        val program = _catchup.value
        if (program == null) play(channel, s)
        else {
            startCatchup(channel, program, s, startAt)
        }
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
            playFrom(channel, minOf(from + deltaMs, now - 60_000))
            return
        }
        val now = System.currentTimeMillis()
        // Repeated presses build on the last one's target; the stream is fetched once they stop.
        val atMs = (seekTarget ?: (program.startTime + catchupOffset + p.currentPosition)) + deltaMs
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
        playFrom(channel, minOf(time, System.currentTimeMillis() - 60_000))
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

    fun retry() {
        retried = false
        restart(if (_catchup.value != null) _position.value.first else 0)
    }

    /** Releases the stream (and the provider connection) when the app goes to the background. */
    fun pause() {
        // (A channel change, catch-up seek or stall check still waiting would start the stream again in the background.)
        retryJob?.cancel(); zapJob?.cancel(); seekJob?.cancel(); stallJob?.cancel()
        player?.let { p -> _catchup.value?.let { c -> _position.value = (catchupOffset + p.currentPosition) to (c.endTime - c.startTime) } }
        player?.stop()
    }

    /** Live resumes at the live edge; catch-up resumes where it was left. */
    fun resume() {
        restart(if (_catchup.value != null) _position.value.first else 0)
    }

    /** Live TV moved to another source: stop, and forget the last channel (its number was the old source's). */
    fun forgetSource() {
        stop()
        viewModelScope.launch { runCatching { remoteStore.clearLastChannel() } }
    }

    fun stop() {
        retryJob?.cancel(); zapJob?.cancel(); seekJob?.cancel(); stallJob?.cancel()
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
