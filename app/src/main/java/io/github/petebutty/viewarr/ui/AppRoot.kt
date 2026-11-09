package io.github.petebutty.viewarr.ui

import io.github.petebutty.viewarr.ui.theme.appBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.repository.AuthRepository
import io.github.petebutty.viewarr.data.repository.GuideRepository
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.ui.home.HomeScreen
import io.github.petebutty.viewarr.ui.setup.SetupWizard
import io.github.petebutty.viewarr.ui.theme.AppColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface StartState {
    data object Loading : StartState
    data object NeedsSetup : StartState
    /** session is null when Live TV was skipped in setup. */
    data class Ready(val session: Session?) : StartState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    sessions: SessionStore,
    private val accounts: AccountStore,
    private val auth: AuthRepository,
    private val guide: GuideRepository,
    remoteStore: io.github.petebutty.viewarr.data.settings.RemoteStore,
    playbackStore: io.github.petebutty.viewarr.data.settings.PlaybackStore,
    private val settingsSync: io.github.petebutty.viewarr.data.sync.SettingsSync,
) : ViewModel() {
    init { settingsSync.start() }
    val playback = playbackStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, io.github.petebutty.viewarr.data.settings.PlaybackSettings())
    val remote = remoteStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, io.github.petebutty.viewarr.data.settings.RemoteSettings())

    // Installs from before optional setup steps existed have no flag: a saved session means they're set up.
    val state = combine(sessions.session, accounts.setupComplete) { session, done ->
        when {
            done == false -> StartState.NeedsSetup
            done == null && session == null -> StartState.NeedsSetup
            else -> StartState.Ready(session)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StartState.Loading)

    /** Signs out of everything and returns to setup. */
    fun signOut() {
        viewModelScope.launch {
            guide.clear()
            accounts.clear()
            auth.signOutEverywhere()
            accounts.setSetupComplete(false)
            // Signing in again offers the saved copy rather than overwriting it with an empty setup.
            settingsSync.reset()
        }
    }
}

@Composable
fun AppRoot(vm: AppViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val remote by vm.remote.collectAsStateWithLifecycle()
    val playbackSettings by vm.playback.collectAsStateWithLifecycle()
    // The splash stays up for 2.5 s while the start page loads underneath it, so the first press after it
    // fades lands on a ready page (earlier, presses while rows were still loading did nothing).
    var splashDone by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2_500)
        splashDone = true
    }
    val splashShown = !splashDone || state == StartState.Loading
    val splashAlpha by androidx.compose.animation.core.animateFloatAsState(
        if (splashShown) 1f else 0f, androidx.compose.animation.core.tween(300), label = "splash",
    )
    // Settings → Appearance → Text size: everything scaled together (layout and text).
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val scale = io.github.petebutty.viewarr.ui.theme.AppColors.palette.textScale
    androidx.compose.runtime.CompositionLocalProvider(
        io.github.petebutty.viewarr.data.settings.LocalRemote provides remote,
        io.github.petebutty.viewarr.data.settings.LocalPlayback provides playbackSettings,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density * scale, baseDensity.fontScale),
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .appBackground()
            // Presses during the splash are swallowed rather than lost on a half-loaded page.
            .onPreviewKeyEvent { splashShown },
    ) {
        io.github.petebutty.viewarr.ui.theme.WallpaperLoader()
        when (val s = state) {
            StartState.Loading -> Unit
            StartState.NeedsSetup -> if (!splashShown) {
                SetupWizard()
                // Signed in to a Dispatcharr user another TV saved its setup to: offer it.
                io.github.petebutty.viewarr.ui.sync.SyncOfferHost()
            }
            is StartState.Ready -> {
                // Composed straight away, under the splash, so rows and the guide load while it shows.
                HomeScreen(session = s.session, onSignOut = vm::signOut)
                // Films, episodes and recordings: above every page, full screen or shrunk to a corner.
                io.github.petebutty.viewarr.ui.media.VodHostLayer()
                // Music: the Now Playing screen, or the small now-playing bar while you browse.
                io.github.petebutty.viewarr.ui.music.MusicLayer()
                // Settings → Appearance → Clock.
                if (!splashShown && io.github.petebutty.viewarr.ui.theme.AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.CLOCK) == 1) Clock(Modifier.align(Alignment.TopEnd))
                // Over everything: the "update available" pop-up (and the automatic check on start).
                if (!splashShown) io.github.petebutty.viewarr.ui.update.UpdateHost()
                // Once after an update: a few lines on what's new.
                if (!splashShown) io.github.petebutty.viewarr.ui.update.WhatsNewHost()
                if (!splashShown) io.github.petebutty.viewarr.ui.sync.SyncOfferHost()
            }
        }
        if (splashAlpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = splashAlpha }) {
            io.github.petebutty.viewarr.ui.common.ViewarrSplash()
        }
    }
}
}

/** The time, small, in the top corner (Settings → Appearance → Clock). */
@Composable
private fun Clock(modifier: Modifier) {
    val format = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    var now by remember { androidx.compose.runtime.mutableStateOf(format.format(java.util.Date())) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { now = format.format(java.util.Date()); kotlinx.coroutines.delay(5_000) }
    }
    androidx.tv.material3.Text(
        now, fontSize = 15.sp, color = Color.White,
        modifier = modifier.padding(top = 10.dp, end = 18.dp)
            .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
