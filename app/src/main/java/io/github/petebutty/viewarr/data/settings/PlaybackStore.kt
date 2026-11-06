package io.github.petebutty.viewarr.data.settings

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Settings → Playback. The first entry of each enum is the default. */
enum class PlayerEngine(override val label: String) : RemoteChoice {
    Mpv("mpv (plays almost anything)"),
    Builtin("Built-in (lighter)"),
}

enum class AudioLanguage(override val label: String, val code: String?) : RemoteChoice {
    English("English", "en"),
    Original("Original language", null),
    Device("Same as the TV's language", "device"),
}

enum class Passthrough(override val label: String) : RemoteChoice {
    Off("Off: the TV decodes the sound"),
    On("On: send Dolby / DTS to my receiver or soundbar"),
}

enum class Downmix(override val label: String) : RemoteChoice {
    Off("Off: keep surround sound"),
    Stereo("Stereo (best for TV speakers)"),
}

enum class NightMode(override val label: String) : RemoteChoice {
    Off("Off"),
    On("On"),
}

enum class SubtitleMode(override val label: String) : RemoteChoice {
    Forced("Only forced (foreign-language bits)"),
    On("On when available"),
    Off("Off"),
}

enum class SubtitleLanguage(override val label: String, val code: String?) : RemoteChoice {
    English("English", "en"),
    Device("Same as the TV's language", "device"),
}

enum class SubtitleSize(override val label: String, val scale: Float) : RemoteChoice {
    Normal("Normal", 1f),
    Large("Large", 1.3f),
    Small("Small", 0.8f),
}

enum class ResumeMode(override val label: String) : RemoteChoice {
    Resume("Carry on where I left off"),
    Start("Always start from the beginning"),
}

enum class PlexQuality(override val label: String, val maxKbps: Int?) : RemoteChoice {
    Original("Original quality (convert only if needed)", null),
    Max20("Always convert: up to 20 Mbps", 20_000),
    Max8("Always convert: up to 8 Mbps", 8_000),
    Max4("Always convert: up to 4 Mbps (slow Wi-Fi)", 4_000),
}

enum class MpvDecoding(override val label: String) : RemoteChoice {
    /** The decoder draws straight onto the screen: smoothest, especially on weak boxes. */
    Hardware("Hardware, direct to screen (smoothest)"),
    /** Decoded frames go through the GPU, so mpv can draw picture subtitles (PGS) on them. */
    Gpu("Hardware through the GPU (heavier)"),
    Software("Software (if the picture glitches)"),
}

enum class FrameRateMatch(override val label: String) : RemoteChoice {
    Off("Off"),
    On("On"),
}

enum class PictureSize(override val label: String) : RemoteChoice {
    Fit("Fit (black bars, nothing cut off)"),
    Zoom("Zoom to fill the screen"),
    Stretch("Stretch to fill the screen"),
}

enum class Deinterlace(override val label: String) : RemoteChoice {
    Auto("Automatic"),
    Off("Off"),
}

enum class AudioDelay(override val label: String, val ms: Int) : RemoteChoice {
    D0("No delay", 0),
    P100("Sound 100 ms later", 100), P200("Sound 200 ms later", 200), P300("Sound 300 ms later", 300),
    M100("Sound 100 ms earlier", -100), M200("Sound 200 ms earlier", -200), M300("Sound 300 ms earlier", -300),
}

enum class VolumeBoost(override val label: String, val percent: Int) : RemoteChoice {
    Off("Off", 100),
    B130("+30%", 130),
    B160("+60%", 160),
}

enum class SubtitleColour(override val label: String, val argb: Long) : RemoteChoice {
    White("White", 0xFFFFFFFF),
    Yellow("Yellow", 0xFFFFE14D),
}

enum class SubtitleBackground(override val label: String) : RemoteChoice {
    Outline("Outline only"),
    Box("Dark box behind the text"),
}

enum class StreamPick(override val label: String) : RemoteChoice {
    Choose("Let me choose from the list"),
    Auto("Play the best one automatically"),
}

enum class MaxResolution(override val label: String, val height: Int?) : RemoteChoice {
    Auto("Match the TV", null),
    R2160("4K", 2160),
    R1080("1080p", 1080),
    R720("720p (slow internet)", 720),
}

enum class BufferSize(override val label: String, val mpvBytes: String, val exoMs: Int) : RemoteChoice {
    Normal("Normal", "64MiB", 50_000),
    Large("Large (slow or patchy internet)", "150MiB", 120_000),
    Small("Small (box with little memory)", "32MiB", 25_000),
}

enum class AutoNext(override val label: String) : RemoteChoice {
    On("On"),
    Off("Off"),
}

enum class StatsOverlay(override val label: String) : RemoteChoice {
    Off("Off"),
    On("On"),
}

enum class TrailerPreview(override val label: String) : RemoteChoice {
    On("On"),
    Off("Off"),
}

enum class TrailerDelay(override val label: String, val ms: Long) : RemoteChoice {
    S3("3 seconds", 3_000),
    S1("1 second", 1_000),
    S2("2 seconds", 2_000),
    S5("5 seconds", 5_000),
    S8("8 seconds", 8_000),
    S12("12 seconds", 12_000),
}

enum class TrailerSound(override val label: String) : RemoteChoice {
    On("On"),
    Off("Off"),
}

data class PlaybackSettings(
    val engine: PlayerEngine = PlayerEngine.entries.first(),
    val audioLanguage: AudioLanguage = AudioLanguage.entries.first(),
    val passthrough: Passthrough = Passthrough.entries.first(),
    val downmix: Downmix = Downmix.entries.first(),
    val nightMode: NightMode = NightMode.entries.first(),
    val subtitles: SubtitleMode = SubtitleMode.entries.first(),
    val subtitleLanguage: SubtitleLanguage = SubtitleLanguage.entries.first(),
    val subtitleSize: SubtitleSize = SubtitleSize.entries.first(),
    val resume: ResumeMode = ResumeMode.entries.first(),
    val plexQuality: PlexQuality = PlexQuality.entries.first(),
    val mpvDecoding: MpvDecoding = MpvDecoding.entries.first(),
    val frameRate: FrameRateMatch = FrameRateMatch.entries.first(),
    val pictureSize: PictureSize = PictureSize.entries.first(),
    val deinterlace: Deinterlace = Deinterlace.entries.first(),
    val audioDelay: AudioDelay = AudioDelay.entries.first(),
    val volumeBoost: VolumeBoost = VolumeBoost.entries.first(),
    val subtitleColour: SubtitleColour = SubtitleColour.entries.first(),
    val subtitleBackground: SubtitleBackground = SubtitleBackground.entries.first(),
    val streamPick: StreamPick = StreamPick.entries.first(),
    val maxResolution: MaxResolution = MaxResolution.entries.first(),
    val buffer: BufferSize = BufferSize.entries.first(),
    val autoNext: AutoNext = AutoNext.entries.first(),
    val stats: StatsOverlay = StatsOverlay.entries.first(),
    val trailerPreview: TrailerPreview = TrailerPreview.entries.first(),
    val trailerDelay: TrailerDelay = TrailerDelay.entries.first(),
    val trailerSound: TrailerSound = TrailerSound.entries.first(),
) {
    /** ISO 639-1 code for the chosen audio language, or null for the original track. */
    fun audioCode(): String? = audioLanguage.code?.let { if (it == "device") java.util.Locale.getDefault().language else it }
    fun subtitleCode(): String = subtitleLanguage.code.let { if (it == "device" || it == null) java.util.Locale.getDefault().language else it }
}

val LocalPlayback = staticCompositionLocalOf { PlaybackSettings() }

internal val Context.playbackStore by preferencesDataStore(name = "playback")

@Singleton
class PlaybackStore @Inject constructor(@ApplicationContext private val context: Context) {

    val settings: Flow<PlaybackSettings> = context.playbackStore.data.map { p ->
        PlaybackSettings(
            engine = p.read(ENGINE),
            audioLanguage = p.read(AUDIO_LANG),
            passthrough = p.read(PASSTHROUGH),
            downmix = p.read(DOWNMIX),
            nightMode = p.read(NIGHT),
            subtitles = p.read(SUBS),
            subtitleLanguage = p.read(SUB_LANG),
            subtitleSize = p.read(SUB_SIZE),
            resume = p.read(RESUME),
            plexQuality = p.read(PLEX_QUALITY),
            mpvDecoding = p.read(MPV_DECODING),
            frameRate = p.read(FRAME_RATE),
            pictureSize = p.read(PICTURE_SIZE),
            deinterlace = p.read(DEINTERLACE),
            audioDelay = p.read(AUDIO_DELAY),
            volumeBoost = p.read(VOLUME_BOOST),
            subtitleColour = p.read(SUB_COLOUR),
            subtitleBackground = p.read(SUB_BACKGROUND),
            streamPick = p.read(STREAM_PICK),
            maxResolution = p.read(MAX_RES),
            buffer = p.read(BUFFER),
            autoNext = p.read(AUTO_NEXT),
            stats = p.read(STATS),
            trailerPreview = p.read(TRAILER_PREVIEW),
            trailerDelay = p.read(TRAILER_DELAY),
            trailerSound = p.read(TRAILER_SOUND),
        )
    }

    suspend fun current(): PlaybackSettings = settings.first()

    suspend fun set(key: String, value: Enum<*>) {
        context.playbackStore.edit { it[stringPreferencesKey(key)] = value.name }
    }

    suspend fun reset() {
        context.playbackStore.edit { it.clear() }
    }

    private inline fun <reified E : Enum<E>> Preferences.read(key: String): E =
        this[stringPreferencesKey(key)]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: enumValues<E>().first()

    companion object {
        const val ENGINE = "engine"
        const val AUDIO_LANG = "audio_lang"
        const val PASSTHROUGH = "passthrough"
        const val DOWNMIX = "downmix"
        const val NIGHT = "night"
        const val SUBS = "subs"
        const val SUB_LANG = "sub_lang"
        const val SUB_SIZE = "sub_size"
        const val RESUME = "resume"
        const val PLEX_QUALITY = "plex_quality"
        const val MPV_DECODING = "mpv_decoding"
        const val FRAME_RATE = "frame_rate"
        const val PICTURE_SIZE = "picture_size"
        const val DEINTERLACE = "deinterlace"
        const val AUDIO_DELAY = "audio_delay"
        const val VOLUME_BOOST = "volume_boost"
        const val SUB_COLOUR = "sub_colour"
        const val SUB_BACKGROUND = "sub_background"
        const val STREAM_PICK = "stream_pick"
        const val MAX_RES = "max_res"
        const val BUFFER = "buffer"
        const val AUTO_NEXT = "auto_next"
        const val STATS = "stats"
        const val TRAILER_PREVIEW = "trailer_preview"
        const val TRAILER_DELAY = "trailer_delay"
        const val TRAILER_SOUND = "trailer_sound"
    }
}
