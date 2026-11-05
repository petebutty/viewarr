package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.settings.PlaybackSettings
import io.github.petebutty.viewarr.data.settings.PlaybackStore
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.data.settings.RemoteSettings
import io.github.petebutty.viewarr.data.settings.RemoteStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val store: PlaybackStore,
    private val remoteStore: RemoteStore,
) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSettings())
    val remote = remoteStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, RemoteSettings())

    fun set(key: String, value: RemoteChoice) = viewModelScope.launch { store.set(key, value as Enum<*>) }
    fun setRemote(key: String, value: RemoteChoice) = viewModelScope.launch { remoteStore.set(key, value as Enum<*>) }
    fun reset() = viewModelScope.launch { store.reset() }
}

private inline fun <reified E> choices(): List<RemoteChoice> where E : Enum<E>, E : RemoteChoice = enumValues<E>().toList()

/** Settings → Playback, full screen. Defaults are the safe choice for most TVs; each setting explains itself. */
@Composable
fun PlaybackSettingsScreen(onBack: () -> Unit, vm: PlaybackSettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val r by vm.remote.collectAsStateWithLifecycle()
    fun item(title: String, description: String, key: String, all: List<RemoteChoice>, current: RemoteChoice) =
        SettingItem(title, description, all, current) { vm.set(key, it) }
    val k = PlaybackStore.Companion
    val categories = listOf(
        SettingCategory(
            "Player", "Which player plays films, shows and recordings, and how it behaves.",
            listOf(
                item("Player", "mpv plays almost any file and soundtrack. The built-in player is lighter on weak boxes.", k.ENGINE, choices<io.github.petebutty.viewarr.data.settings.PlayerEngine>(), s.engine),
                SettingItem("Skip with Left / Right", "How far each press jumps.", choices<io.github.petebutty.viewarr.data.settings.SeekStep>(), r.seek) { vm.setRemote(RemoteStore.SEEK, it) },
                item("Resume", "Carry on where you stopped last time, or always start at the beginning.", k.RESUME, choices<io.github.petebutty.viewarr.data.settings.ResumeMode>(), s.resume),
                item("mpv decoding", "Direct to screen is smoothest; picture subtitles (Blu-ray PGS) switch to the GPU way by themselves. Try software only if the picture glitches or shows colour blocks.", k.MPV_DECODING, choices<io.github.petebutty.viewarr.data.settings.MpvDecoding>(), s.mpvDecoding),
            ),
        ),
        SettingCategory(
            "Video", "Picture size and smoothness.",
            listOf(
                item("Match frame rate", "Switches the TV to the film's own rate (e.g. 24 Hz) for smoother motion. Some TVs go black for a second when it switches.", k.FRAME_RATE, choices<io.github.petebutty.viewarr.data.settings.FrameRateMatch>(), s.frameRate),
                item("Picture size", "How the picture fills the screen when its shape doesn't match the TV.", k.PICTURE_SIZE, choices<io.github.petebutty.viewarr.data.settings.PictureSize>(), s.pictureSize),
                item("Deinterlace", "Smooths combing lines on older TV recordings and broadcasts (mpv).", k.DEINTERLACE, choices<io.github.petebutty.viewarr.data.settings.Deinterlace>(), s.deinterlace),
            ),
        ),
        SettingCategory(
            "Audio", "Language, surround sound and volume.",
            listOf(
                item("Preferred language", "The soundtrack picked when a film has several.", k.AUDIO_LANG, choices<io.github.petebutty.viewarr.data.settings.AudioLanguage>(), s.audioLanguage),
                item("Surround passthrough", "Only turn on if you have an AV receiver or soundbar that decodes Dolby / DTS. Fixes stuttering sound on weak boxes.", k.PASSTHROUGH, choices<io.github.petebutty.viewarr.data.settings.Passthrough>(), s.passthrough),
                item("Channels", "Stereo mixes surround down to two speakers, so speech isn't too quiet on TV speakers.", k.DOWNMIX, choices<io.github.petebutty.viewarr.data.settings.Downmix>(), s.downmix),
                item("Night mode", "Evens out loud and quiet parts: quieter explosions, clearer speech (mpv).", k.NIGHT, choices<io.github.petebutty.viewarr.data.settings.NightMode>(), s.nightMode),
                item("Volume boost", "Louder than normal, for quiet films (mpv).", k.VOLUME_BOOST, choices<io.github.petebutty.viewarr.data.settings.VolumeBoost>(), s.volumeBoost),
                item("Lip sync", "If voices are out of step with lips, move the sound earlier or later (mpv).", k.AUDIO_DELAY, choices<io.github.petebutty.viewarr.data.settings.AudioDelay>(), s.audioDelay),
            ),
        ),
        SettingCategory(
            "Subtitles", "When subtitles show, and how they look.",
            listOf(
                item("Show subtitles", "Forced only shows the bits in another language (signs, foreign dialogue).", k.SUBS, choices<io.github.petebutty.viewarr.data.settings.SubtitleMode>(), s.subtitles),
                item("Language", "The subtitle language picked when there are several.", k.SUB_LANG, choices<io.github.petebutty.viewarr.data.settings.SubtitleLanguage>(), s.subtitleLanguage),
                item("Size", "How big subtitles are.", k.SUB_SIZE, choices<io.github.petebutty.viewarr.data.settings.SubtitleSize>(), s.subtitleSize),
                item("Colour", "Text colour.", k.SUB_COLOUR, choices<io.github.petebutty.viewarr.data.settings.SubtitleColour>(), s.subtitleColour),
                item("Background", "A dark box makes subtitles easier to read on bright scenes.", k.SUB_BACKGROUND, choices<io.github.petebutty.viewarr.data.settings.SubtitleBackground>(), s.subtitleBackground),
            ),
        ),
        SettingCategory(
            "Streams", "Quality, Plex and Stremio addon streams.",
            listOf(
                item("Addon streams", "Choose from the list yourself, or let Viewarr start the best cached stream within your quality limit.", k.STREAM_PICK, choices<io.github.petebutty.viewarr.data.settings.StreamPick>(), s.streamPick),
                item("Best quality allowed", "Streams above this are listed last and never auto-picked. Match the TV works out 4K or 1080p for you.", k.MAX_RES, choices<io.github.petebutty.viewarr.data.settings.MaxResolution>(), s.maxResolution),
                item("Plex quality", "Original plays the file itself and converts only if the TV can't. Lower settings always convert, for slow Wi-Fi.", k.PLEX_QUALITY, choices<io.github.petebutty.viewarr.data.settings.PlexQuality>(), s.plexQuality),
                item("Buffer", "How much video is loaded ahead. Large helps on patchy internet; small helps boxes with little memory.", k.BUFFER, choices<io.github.petebutty.viewarr.data.settings.BufferSize>(), s.buffer),
            ),
        ),
        SettingCategory(
            "Episodes", "Watching shows.",
            listOf(
                item("Play next episode automatically", "When an episode ends, the next one starts after a 10-second countdown.", k.AUTO_NEXT, choices<io.github.petebutty.viewarr.data.settings.AutoNext>(), s.autoNext),
            ),
        ),
        SettingCategory(
            "Trailers", "YouTube trailers for films and shows.",
            listOf(
                item("Trailer previews", "When you rest on a poster in Movies or Shows, its trailer plays in a small window at the top.", k.TRAILER_PREVIEW, choices<io.github.petebutty.viewarr.data.settings.TrailerPreview>(), s.trailerPreview),
                item("Start after", "How long to stay on a poster before its trailer starts.", k.TRAILER_DELAY, choices<io.github.petebutty.viewarr.data.settings.TrailerDelay>(), s.trailerDelay),
                item("Preview sound", "Off plays previews silently. The Trailer button on a title page always has sound.", k.TRAILER_SOUND, choices<io.github.petebutty.viewarr.data.settings.TrailerSound>(), s.trailerSound),
            ),
        ),
        SettingCategory(
            "Troubleshooting", "Tools for when something doesn't play right.",
            listOf(
                item("Show playback info", "Shows the video and audio format, decoder and dropped frames while playing.", k.STATS, choices<io.github.petebutty.viewarr.data.settings.StatsOverlay>(), s.stats),
            ),
        ),
    )
    SettingsPage(title = "Playback", categories = categories, onBack = onBack, onReset = { vm.reset() })
}
