package io.github.petebutty.viewarr.ui

import kotlinx.coroutines.flow.first
import io.github.petebutty.viewarr.ui.theme.appBackground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
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
    accessLoader: io.github.petebutty.viewarr.data.profiles.AccessLoader,
) : ViewModel() {
    /** Setup was shown in this run of the app (so the tour follows it). */
    var sawSetup by androidx.compose.runtime.mutableStateOf(false)

    init {
        settingsSync.start()
        // What the signed-in person may do (an admin's locks on their Dispatcharr user).
        accessLoader.start(viewModelScope)
        // Away addresses from the setup (empty: everything stays on its home address).
        viewModelScope.launch { accounts.accounts.collect { io.github.petebutty.viewarr.data.net.Reach.setPairs(it.away) } }
    }
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
    // The splash stays up for at least 3.8 s (the logo's animation, then a moment's hold) while the start page loads
    // underneath it, and then until that page is ready (StartupGate: the guide's channels are in), so the first press
    // after it fades lands on a ready page. Slow boxes get longer; a guide that never comes (no live TV yet) doesn't
    // hold it past the limit.
    var splashDone by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (splashDone) return@LaunchedEffect
        kotlinx.coroutines.delay(3_800)
        val am = appCtx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val slow = am?.isLowRamDevice == true || io.github.petebutty.viewarr.ui.theme.AppColors.palette.lowMemory
        kotlinx.coroutines.withTimeoutOrNull(if (slow) 12_000L else 7_000L) {
            androidx.compose.runtime.snapshotFlow { io.github.petebutty.viewarr.ui.common.StartupGate.ready }.first { it }
        }
        splashDone = true
    }
    val splashShown = !splashDone || state == StartState.Loading
    val splashAlpha by androidx.compose.animation.core.animateFloatAsState(
        if (splashShown) 1f else 0f, androidx.compose.animation.core.tween(300), label = "splash",
    )
    // Settings → Appearance → Text size: everything scaled together (layout and text).
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val scale = io.github.petebutty.viewarr.ui.theme.AppColors.palette.textScale
    // Buttons taught in Settings → Remote: read wherever a press is turned into an action.
    androidx.compose.runtime.SideEffect { io.github.petebutty.viewarr.ui.common.RemoteKeys.setTaught(remote.buttons) }
    // Weak boxes get smaller pictures (Low-memory mode counts, as well as what Android says about the box).
    val lowMem = io.github.petebutty.viewarr.ui.theme.AppColors.palette.lowMemory
    val lowRam = androidx.compose.runtime.remember { (appCtx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice == true }
    androidx.compose.runtime.SideEffect { io.github.petebutty.viewarr.data.net.SmallerImages.weak = lowMem || lowRam }
    androidx.compose.runtime.CompositionLocalProvider(
        io.github.petebutty.viewarr.data.settings.LocalRemote provides remote,
        io.github.petebutty.viewarr.data.settings.LocalPlayback provides playbackSettings,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density * scale, baseDensity.fontScale),
        // Settings → Appearance → Font.
        androidx.tv.material3.LocalTextStyle provides androidx.tv.material3.LocalTextStyle.current.copy(fontFamily = appFont()),
        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = appFont()),
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .appBackground()
            // Presses during the splash are swallowed rather than lost on a half-loaded page.
            .onPreviewKeyEvent { splashShown },
    ) {
        // (After the splash: decoding pictures competed with the start page loading.)
        if (!splashShown) io.github.petebutty.viewarr.ui.theme.WallpaperLoader()
        when (val s = state) {
            StartState.Loading -> Unit
            StartState.NeedsSetup -> if (!splashShown) {
                // A new install: running your own server, or invited by someone who does.
                var role by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.sawSetup = true }
                when (role) {
                    null -> io.github.petebutty.viewarr.ui.onboarding.RoleScreen(onAdmin = { role = "admin" }, onInvited = { role = "invited" })
                    "invited" -> io.github.petebutty.viewarr.ui.onboarding.InvitedSetup(onBack = { role = null })
                    else -> SetupWizard()
                }
                // Signed in to a Dispatcharr user another TV saved its setup to: offer it.
                io.github.petebutty.viewarr.ui.sync.SyncOfferHost()
            }
            is StartState.Ready -> {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var tourNow by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(vm.sawSetup) { if (vm.sawSetup && !io.github.petebutty.viewarr.ui.onboarding.Tour.done(ctx)) tourNow = true }
                val touring = !splashShown && (tourNow || io.github.petebutty.viewarr.ui.onboarding.Tour.requested)
                // Composed straight away, under the splash, so rows and the guide load while it shows. Not while the
                // tour is up: a channel playing underneath kept the remote (Left / Right there mean skip).
                // (No offscreen layer for the page while a film glides: switching it on cost one very slow frame at the
                // start of every glide, more than it saved; measured on a Formuler.)
                // (Drawn above the film while the menu is open over it: Left from the film controls' first button.)
                if (!touring) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().zIndex(if (io.github.petebutty.viewarr.ui.media.VodHost.behind) 2f else 0f)) {
                    HomeScreen(session = s.session, onSignOut = vm::signOut)
                }
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
                // Who's watching? (on start and from Settings), and the admin Users page.
                if (!splashShown) io.github.petebutty.viewarr.ui.profiles.ProfileHost()
                // The tour: once, straight after setting up on this TV (and from Settings → About).
                if (touring) io.github.petebutty.viewarr.ui.onboarding.TourScreen(onDone = {
                    io.github.petebutty.viewarr.ui.onboarding.Tour.markDone(ctx); tourNow = false; io.github.petebutty.viewarr.ui.onboarding.Tour.requested = false
                })
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
    var now by remember { androidx.compose.runtime.mutableStateOf(io.github.petebutty.viewarr.ui.theme.Dates.time(System.currentTimeMillis())) }
    androidx.compose.runtime.LaunchedEffect(io.github.petebutty.viewarr.ui.theme.AppColors.palette) {
        while (true) { now = io.github.petebutty.viewarr.ui.theme.Dates.time(System.currentTimeMillis()); kotlinx.coroutines.delay(5_000) }
    }
    androidx.tv.material3.Text(
        now, fontSize = 15.sp, color = Color.White,
        modifier = modifier.padding(top = 10.dp, end = 18.dp)
            .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Settings → Appearance → Font: one of the fonts every Android TV box has (null: the standard one). */
@Composable
private fun appFont(): androidx.compose.ui.text.font.FontFamily? {
    val i = io.github.petebutty.viewarr.ui.theme.AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.FONT)
    return androidx.compose.runtime.remember(i) {
        when (i) {
            1 -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
            2 -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL))
            3 -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
            4 -> androidx.compose.ui.text.font.FontFamily.Serif
            else -> null
        }
    }
}
