package io.github.petebutty.viewarr.ui.launcher

import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import io.github.petebutty.viewarr.data.cast.PlayOnTv
import io.github.petebutty.viewarr.data.media.DeepLink
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.home.Page

/**
 * Previewarr on a TV: takes Play on TV requests from Previewarr Mobile on the same network (PlayOnTv), from a phone
 * signed in to the same Dispatcharr account only. Announced under the TV's own name (Android's device name). Anything
 * over the pages (Settings, the Library pages) is closed first, as the remote's own buttons would.
 */
@Composable
internal fun PlayOnTvHost(session: Session?) {
    if (!Launcher.enabled || io.github.petebutty.viewarr.BuildConfig.MOBILE) return
    val context = LocalContext.current
    // The listening itself runs in PlayOnTvService (from start-up, on screen or not): started here too, in case.
    LaunchedEffect(Unit) { io.github.petebutty.viewarr.data.cast.PlayOnTvService.start(context) }
    val handle: (android.net.Uri) -> Unit = { uri ->
        Launcher.settingsOpen = false
        LauncherLibrary.close()
        // A recording on the home screen: Viewarr's screens play it (they aren't running there).
        if (uri.host == "recording" && Launcher.home) Launcher.open(Page.Recordings)
        DeepLink.handle(uri)
    }
    DisposableEffect(Unit) {
        PlayOnTv.onScreen = handle
        onDispose { if (PlayOnTv.onScreen === handle) PlayOnTv.onScreen = null }
    }
    // Brought forward by a request: carry it out now it's on screen.
    val visible by io.github.petebutty.viewarr.AppVisible.visible.collectAsState()
    LaunchedEffect(visible) { if (visible) PlayOnTv.takePending()?.let(handle) }
}

/**
 * Live TV for Play on TV's Devices page: the playing channel and paused or not (NowPlaying), and its pause / stop /
 * channel up and down (PlaybackControl). Previewarr and Previewarr Mobile.
 */
@Composable
internal fun NowPlayingWatch() {
    if (!Launcher.enabled) return
    val pb: io.github.petebutty.viewarr.playback.PlaybackViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val ch by pb.channel.collectAsState()
    val paused by pb.paused.collectAsState()
    LaunchedEffect(ch?.uuid, paused) { io.github.petebutty.viewarr.data.cast.NowPlaying.live(ch?.uuid, ch?.name, null, ch?.logoUrl, paused) }
    DisposableEffect(pb) {
        val mobile = io.github.petebutty.viewarr.BuildConfig.MOBILE
        io.github.petebutty.viewarr.data.cast.PlaybackControl.live = io.github.petebutty.viewarr.data.cast.PlaybackControl.Live(
            togglePause = { pb.togglePause() },
            // (The TV: as the remote's Stop, which also leaves full screen. The tablet: its channel page closes too.)
            // (On the home screen, with the channel in the corner: just stopped, the home screen stays.)
            stop = { when { mobile -> { pb.stop(); io.github.petebutty.viewarr.ui.mobile.MobileNav.close() }; Launcher.home -> pb.stop(); else -> DeepLink.pendingCommand.value = DeepLink.Command("stop") } },
            step = if (mobile) null else ({ d -> DeepLink.pendingCommand.value = DeepLink.Command("step", if (d > 0) "up" else "down") }),
        )
        onDispose { io.github.petebutty.viewarr.data.cast.PlaybackControl.live = null; io.github.petebutty.viewarr.data.cast.NowPlaying.live(null, null, null, null, false) }
    }
}
