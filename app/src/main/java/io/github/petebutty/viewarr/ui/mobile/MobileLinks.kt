package io.github.petebutty.viewarr.ui.mobile

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.petebutty.viewarr.AppVisible
import io.github.petebutty.viewarr.data.cast.PlayOnTv
import io.github.petebutty.viewarr.data.cast.PlayOnTvService
import io.github.petebutty.viewarr.data.media.DeepLink
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import kotlinx.coroutines.launch

/** Play on TV's links carried out on the phone screens: Take over on a phone, or a request arriving on a tablet. */
internal object MobileLinks {
    @Volatile var open: (Uri) -> Unit = {}
}

/**
 * The phone screens' handling of a channel / title / recording link (MobileLinks.open): a channel opens its page and
 * plays, a film or episode opens and plays, a recording plays from the Recordings page. On a tablet it's also where
 * Play on TV's requests arrive (PlayOnTvService listens; phones only send).
 */
@Composable
internal fun MobileLinksHost() {
    val context = LocalContext.current
    val guide: GuideViewModel = hiltViewModel()
    val films: MediaViewModel = hiltViewModel(key = "media-Movie")
    val shows: MediaViewModel = hiltViewModel(key = "media-Show")
    val scope = rememberCoroutineScope()
    val musicLinks: MusicLinkViewModel = hiltViewModel()
    val handle: (Uri) -> Unit = { uri ->
        MobileSettingsNav.open = false
        MobileMusic.nowOpen = false
        when (uri.host) {
            "channel" -> uri.getQueryParameter("uuid")?.let { u ->
                scope.launch { guide.channelByUuid(u)?.let { ch -> MobileTitle.type = null; MobileNav.open(ch, watch = true) } }
            }
            "title" -> DeepLink.parse(uri)?.let { item ->
                val show = item.type == MediaType.Show
                val vm = if (show) shows else films
                MobileNav.close()
                if (uri.getQueryParameter("play") == "1") vm.openAndPlay(item, item.nextSeason, item.nextEpisode) else vm.open(item)
                MobileTitle.type = item.type
            }
            // Music (Take over from a TV or tablet): its queue carries on here, Now playing open.
            "music" -> PlayOnTv.musicOf(uri)?.let { ask ->
                scope.launch { if (PlayOnTvService.playMusic(context, musicLinks.library, ask)) { MobileNav.close(); Mobile.tab = "music"; MobileMusic.nowOpen = true } }
            }
            "recording" -> uri.getQueryParameter("id")?.toIntOrNull()?.let { id ->
                MobileNav.close(); MobileTitle.type = null
                Mobile.tab = "recordings"
                MobileRecordingsPending.id = id
            }
        }
    }
    MobileLinks.open = handle
    if (!PlayOnTvService.receives(context)) return
    LaunchedEffect(Unit) { PlayOnTvService.start(context) }
    DisposableEffect(Unit) {
        PlayOnTv.onScreen = handle
        onDispose { if (PlayOnTv.onScreen === handle) PlayOnTv.onScreen = null }
    }
    val visible by AppVisible.visible.collectAsState()
    LaunchedEffect(visible) { if (visible) PlayOnTv.takePending()?.let(handle) }
}

/** Music links' songs come from this device's own music servers. */
@dagger.hilt.android.lifecycle.HiltViewModel
class MusicLinkViewModel @javax.inject.Inject constructor(
    val library: io.github.petebutty.viewarr.data.music.MusicLibrary,
) : androidx.lifecycle.ViewModel()
