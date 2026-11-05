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
    On("On: quieter explosions, clearer speech (mpv)"),
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
    Hardware("Hardware (fastest)"),
    Software("Software (if the picture glitches)"),
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
) {
    /** ISO 639-1 code for the chosen audio language, or null for the original track. */
    fun audioCode(): String? = audioLanguage.code?.let { if (it == "device") java.util.Locale.getDefault().language else it }
    fun subtitleCode(): String = subtitleLanguage.code.let { if (it == "device" || it == null) java.util.Locale.getDefault().language else it }
}

val LocalPlayback = staticCompositionLocalOf { PlaybackSettings() }

private val Context.playbackStore by preferencesDataStore(name = "playback")

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
    }
}
