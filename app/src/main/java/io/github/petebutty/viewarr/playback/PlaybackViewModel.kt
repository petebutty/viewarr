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
) : ViewModel() {

    // ---------------------------------------------------------------- Dispatcharr: the channel's sources

    /** One of the streams behind a Dispatcharr channel (providers often have several copies of each channel). */
    data class LiveSource(val streamId: Int, val name: String, val provider: String, val current: Boolean)

    /** Live TV through Dispatcharr (not a provider login, not catch-up): sources and stream info are available. */
    val dispatcharrLive get() = session?.isXc == false && _catchup.value == null

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
        if (s.isXc) xtream.liveUrl(s, channel.id) else "${s.serverUrl.trimEnd('/')}/proxy/ts/stream/${channel.uuid}"

    private fun catchupUrl(s: Session, channel: ChannelListItem, program: GuideProgram): String {
        val minutes = maxOf(1L, (program.endTime - program.startTime) / 60_000)
        return if (s.isXc) xtream.catchupUrl(s, channel.id, program.startTime, minutes)
        else "${s.serverUrl.trimEnd('/')}/proxy/catchup/${channel.uuid}?start=${program.startTime / 1000}&duration=$minutes"
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
                if (_catchup.value != null) _position.value = p.currentPosition to p.duration.coerceAtLeast(0)
            }
        }
    }

    var player: ExoPlayer? = null
        private set

    private fun ensurePlayer(session: Session): ExoPlayer {
        if (player != null && this.session == session) return player!!
        player?.release()
        this.session = session
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Viewarr/0.7")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            // Optional for Dispatcharr's proxy, but attributes the stream to this user (limits, "who's watching").
            .setDefaultRequestProperties(if (session.isXc) emptyMap() else mapOf("X-API-Key" to session.apiKey))
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        // Small start buffer so channel changes feel quick; live TV doesn't need a long one.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 30_000, 1_000, 2_500)
            .build()
        return ExoPlayer.Builder(context)
            .setRenderersFactory(
                // FFmpeg audio only as a fallback (MP2 on some channels); the box's own decoders and passthrough come first.
                DefaultRenderersFactory(context)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
            .setLoadControl(loadControl)
            .build()
            .also { p ->
                p.playWhenReady = true
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> _state.value = PlaybackState.Buffering
                            Player.STATE_READY -> {
                                retried = false
                                _state.value = PlaybackState.Playing
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
                            _state.value = PlaybackState.Error(describe(error))
                        }
                    }
                })
                player = p
            }
    }

    fun play(channel: ChannelListItem, session: Session? = this.session) {
        val s = session ?: return
        // A note about the last channel's sources ("Switched to …") doesn't belong to this one.
        if (_channel.value?.id != channel.id) { _sourceNote.value = null; _sources.value = null }
        start(channel, null, s, liveUrl(s, channel))
        viewModelScope.launch { runCatching { remoteStore.setLastChannel(channel.id) } }
    }

    /** Dispatcharr's native catch-up endpoint: authenticated by the API key header, seekable. */
    fun playCatchup(channel: ChannelListItem, program: GuideProgram, session: Session? = this.session) {
        val s = session ?: return
        start(channel, program, s, catchupUrl(s, channel, program))
    }

    private fun start(channel: ChannelListItem, program: GuideProgram?, s: Session, url: String, startAt: Long = 0) {
        val p = ensurePlayer(s)
        retryJob?.cancel()
        if (_channel.value?.id != channel.id || _catchup.value != program) retried = false
        _channel.value = channel
        _catchup.value = program
        _position.value = startAt to 0L
        _paused.value = false
        _state.value = PlaybackState.Buffering
        p.setMediaItem(MediaItem.fromUri(url), startAt)
        p.prepare()
        p.play()
    }

    private fun restart(startAt: Long = 0) {
        val channel = _channel.value ?: return
        val s = session ?: return
        val program = _catchup.value
        if (program == null) play(channel, s)
        else {
            start(channel, program, s, catchupUrl(s, channel, program), startAt)
        }
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (_catchup.value == null) return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        p.seekTo(if (p.duration > 0) target.coerceAtMost(p.duration - 1_000) else target)
        _position.value = p.currentPosition to p.duration.coerceAtLeast(0)
    }

    fun restartProgramme() {
        val p = player ?: return
        if (_catchup.value == null) return
        p.seekTo(0)
        if (!p.playWhenReady) togglePause()
        _position.value = 0L to p.duration.coerceAtLeast(0)
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
        _channel.value?.let { play(it) }
    }

    fun togglePause() {
        val p = player ?: return
        if (_catchup.value == null) return
        if (p.isPlaying) p.pause() else p.play()
        _paused.value = !p.playWhenReady
    }

    fun retry() {
        retried = false
        restart(if (_catchup.value != null) _position.value.first else 0)
    }

    /** Releases the stream (and the provider connection) when the app goes to the background. */
    fun pause() {
        retryJob?.cancel()
        player?.let { if (_catchup.value != null) _position.value = it.currentPosition to it.duration.coerceAtLeast(0) }
        player?.stop()
    }

    /** Live resumes at the live edge; catch-up resumes where it was left. */
    fun resume() {
        restart(if (_catchup.value != null) _position.value.first else 0)
    }

    fun stop() {
        retryJob?.cancel()
        player?.stop()
        _channel.value = null
        _catchup.value = null
        _state.value = PlaybackState.Idle
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
