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
fun PlaybackSettingsScreen(onBack: () -> Unit, vm: PlaybackSettingsViewModel = hiltViewModel(), opts: UiOptsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val r by vm.remote.collectAsStateWithLifecycle()
    fun item(title: String, description: String, key: String, all: List<RemoteChoice>, current: RemoteChoice) =
        SettingItem(title, description, all, current) { vm.set(key, it) }
    val k = PlaybackStore.Companion
    // Low-memory mode: what it switches off stays listed, locked, with how to get it back.
    val lowLock = if (io.github.petebutty.viewarr.ui.theme.AppColors.palette.lowMemory) Locks.LOW_MEMORY else null
    // Keeping things playing in a corner while you browse is part of the Live TV hub.
    val cornerLock = lowLock ?: if (r.navStyle != io.github.petebutty.viewarr.data.settings.NavStyle.LiveHub) Locks.NEEDS_HUB else null
    val categories = listOfNotNull(
        SettingCategory(
            "Player", "Which player plays films, shows and recordings, and how it behaves.",
            listOf(
                item("Player", "The built-in player is lighter and handles surround sound best. If it can't play a file, Viewarr switches to mpv by itself. Choose mpv to use it for everything.", k.ENGINE, choices<io.github.petebutty.viewarr.data.settings.PlayerEngine>(), s.engine),
                item("Video view", "Standard: the box's own video hardware shows the film (lighter, HDR and Dolby Vision as the file has them, a smooth glide into the corner). Compatible: drawn by Viewarr, for a box where Standard leaves the picture black.", k.VIDEO_VIEW, choices<io.github.petebutty.viewarr.data.settings.VideoView>(), s.videoView),
                item("Resume", "Carry on where you stopped last time, or always start at the beginning.", k.RESUME, choices<io.github.petebutty.viewarr.data.settings.ResumeMode>(), s.resume),
                optItem(opts, "Controls stay up for", "How long the seek bar and buttons stay on screen after a button press.", io.github.petebutty.viewarr.ui.theme.UiOpts.CONTROLS_TIME),
                optItem(opts, "Player controls size", "How big the seek bar, title and buttons are while a film or episode plays. Smaller sizes cover less of the picture.", io.github.petebutty.viewarr.ui.theme.UiOpts.CONTROLS_SIZE),
                optItem(opts, "Seek-bar previews", "Pictures above the seek bar. Without the server's own, the box reads the film a second time to make them: \"Server's only\" or Off saves bandwidth and memory.", io.github.petebutty.viewarr.ui.theme.UiOpts.SEEK_PREVIEWS),
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
                item("Language", "The soundtrack picked when a film has several.", k.AUDIO_LANG, choices<io.github.petebutty.viewarr.data.settings.AudioLanguage>(), s.audioLanguage),
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
            "Streams", "Quality, Plex and addon streams.",
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
                item("Play next episode automatically", "When an episode ends, the next one starts after a countdown.", k.AUTO_NEXT, choices<io.github.petebutty.viewarr.data.settings.AutoNext>(), s.autoNext),
                optItem(opts, "Next episode countdown", "How long before the next episode starts.", io.github.petebutty.viewarr.ui.theme.UiOpts.UP_NEXT),
            ),
        ),
        // (Picture-in-picture for films is off for now: Back stops a film. Its code stays, switched off.)
        SettingCategory(
            "Live TV", "Watching live channels.",
            listOf(
                optItem(opts, "Animation", "Back from full screen: the channel glides into the guide's preview, and back. Off: straight there.", io.github.petebutty.viewarr.ui.theme.UiOpts.ANIM_LIVE),
                optItem(opts, "Keep playing while you browse", "Opening Movies, Shows, Music or Recordings from the live TV shortcuts (or any page in the Live TV hub style): the channel carries on in a corner, and Back takes it full screen again. A film or music starting stops it. Off: browsing stops the channel.", io.github.petebutty.viewarr.ui.theme.UiOpts.LIVE_CORNER).locked(cornerLock),
                optItem(opts, "Corner size", "The channel's window while you browse.", io.github.petebutty.viewarr.ui.theme.UiOpts.MINI_SIZE).locked(cornerLock),
                optItem(opts, "Corner", "Where that window sits.", io.github.petebutty.viewarr.ui.theme.UiOpts.MINI_CORNER).locked(cornerLock),
                optItem(opts, "Still watching?", "After this long without a button press, live TV asks whether you're still there and stops if nobody answers within a minute (frees a provider connection when the TV's left on).", io.github.petebutty.viewarr.ui.theme.UiOpts.STILL_WATCHING),
            ),
        ),
        // Low-memory mode: no trailer previews (a second player while you browse): locked.
        SettingCategory(
            "Trailers", "YouTube trailers for films and shows.",
            listOf(
                item("Trailer previews", "When you rest on a poster in Movies or Shows, its trailer plays in a small window at the top.", k.TRAILER_PREVIEW, choices<io.github.petebutty.viewarr.data.settings.TrailerPreview>(), s.trailerPreview),
                item("Start after", "How long to stay on a poster before its trailer starts.", k.TRAILER_DELAY, choices<io.github.petebutty.viewarr.data.settings.TrailerDelay>(), s.trailerDelay),
                item("Preview sound", "Off plays previews silently. The Trailer button on a title page always has sound.", k.TRAILER_SOUND, choices<io.github.petebutty.viewarr.data.settings.TrailerSound>(), s.trailerSound),
            ).map { it.locked(lowLock) },
        ),
        SettingCategory(
            "Troubleshooting", "Tools for when something doesn't play right.",
            listOf(
                item("Show playback info", "Shows the video and audio format, decoder and dropped frames while playing.", k.STATS, choices<io.github.petebutty.viewarr.data.settings.StatsOverlay>(), s.stats),
                optItem(opts, "Low-memory mode", "For boxes with 1–2 GB. Turns off the heavy extras across the app (box-made seek previews, guide pictures, logos, wallpaper, artwork, trailers, multi-view, extra ratings); their settings show as locked until it's off. Try it if Viewarr is slow or closes.", io.github.petebutty.viewarr.ui.theme.UiOpts.LOW_MEMORY),
            ),
        ),
    )
    SettingsPage(title = "Playback", categories = categories, onBack = onBack, onReset = {
        vm.reset()
        opts.reset(listOf(io.github.petebutty.viewarr.ui.theme.UiOpts.CONTROLS_TIME, io.github.petebutty.viewarr.ui.theme.UiOpts.CONTROLS_SIZE, io.github.petebutty.viewarr.ui.theme.UiOpts.SEEK_PREVIEWS, io.github.petebutty.viewarr.ui.theme.UiOpts.UP_NEXT, io.github.petebutty.viewarr.ui.theme.UiOpts.MINI_SIZE, io.github.petebutty.viewarr.ui.theme.UiOpts.MINI_CORNER, io.github.petebutty.viewarr.ui.theme.UiOpts.ANIM_FILM, io.github.petebutty.viewarr.ui.theme.UiOpts.ANIM_LIVE, io.github.petebutty.viewarr.ui.theme.UiOpts.LIVE_CORNER, io.github.petebutty.viewarr.ui.theme.UiOpts.STILL_WATCHING, io.github.petebutty.viewarr.ui.theme.UiOpts.LOW_MEMORY))
    })
}
