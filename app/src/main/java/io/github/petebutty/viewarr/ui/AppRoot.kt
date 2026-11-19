@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.petebutty.viewarr.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.petebutty.viewarr.ui.common.followingSetting
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
import io.github.petebutty.viewarr.data.net.Reach
import io.github.petebutty.viewarr.data.net.SmallerImages
import io.github.petebutty.viewarr.data.profiles.AccessLoader
import io.github.petebutty.viewarr.data.profiles.House
import io.github.petebutty.viewarr.data.settings.LocalPlayback
import io.github.petebutty.viewarr.data.settings.LocalRemote
import io.github.petebutty.viewarr.data.settings.PlaybackSettings
import io.github.petebutty.viewarr.data.settings.PlaybackStore
import io.github.petebutty.viewarr.data.settings.RemoteSettings
import io.github.petebutty.viewarr.data.settings.RemoteStore
import io.github.petebutty.viewarr.data.sync.SettingsSync
import io.github.petebutty.viewarr.playback.AvSync
import io.github.petebutty.viewarr.ui.common.ButtonHint
import io.github.petebutty.viewarr.ui.common.RemoteKeys
import io.github.petebutty.viewarr.ui.common.ViewarrSplash
import io.github.petebutty.viewarr.ui.media.VodHost
import io.github.petebutty.viewarr.ui.media.VodHostLayer
import io.github.petebutty.viewarr.ui.music.MusicLayer
import io.github.petebutty.viewarr.ui.music.UserActivity
import io.github.petebutty.viewarr.ui.onboarding.InvitedSetup
import io.github.petebutty.viewarr.ui.onboarding.RoleScreen
import io.github.petebutty.viewarr.ui.onboarding.Tour
import io.github.petebutty.viewarr.ui.onboarding.TourScreen
import io.github.petebutty.viewarr.ui.profiles.ProfileHost
import io.github.petebutty.viewarr.ui.profiles.ProfileUi
import io.github.petebutty.viewarr.ui.settings.InfoRowsEditor
import io.github.petebutty.viewarr.ui.settings.InfoRowsScreen
import io.github.petebutty.viewarr.ui.settings.ReportScreen
import io.github.petebutty.viewarr.ui.settings.ReportUi
import io.github.petebutty.viewarr.ui.sync.SyncOfferHost
import io.github.petebutty.viewarr.ui.theme.Dates
import io.github.petebutty.viewarr.ui.theme.UiOpts
import io.github.petebutty.viewarr.ui.theme.WallpaperLoader
import io.github.petebutty.viewarr.ui.update.ChangelogHost
import io.github.petebutty.viewarr.ui.update.UpdateHost
import io.github.petebutty.viewarr.ui.update.WhatsNewHost
import io.github.petebutty.viewarr.data.sync.WatchSync
import io.github.petebutty.viewarr.ui.common.StartupGate

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
    remoteStore: RemoteStore,
    playbackStore: PlaybackStore,
    private val settingsSync: SettingsSync,
    private val watchSync: WatchSync,
    private val serverSports: io.github.petebutty.viewarr.data.sync.ServerSports,
    private val gameWatch: io.github.petebutty.viewarr.data.sports.GameWatch,
    private val sportsRepo: io.github.petebutty.viewarr.data.sports.SportsRepository,
    accessLoader: AccessLoader,
    house: House,
    houseSync: io.github.petebutty.viewarr.data.report.HouseSync,
) : ViewModel() {
    /** Setup was shown in this run of the app (so the tour follows it). */
    var sawSetup by androidx.compose.runtime.mutableStateOf(false)

    init {
        settingsSync.start()
        // Live now and the games' channels, worked out on Dispatcharr by the Viewarr plugin (when it's installed): a
        // small read, wanted for the guide's Live now, so straight away.
        serverSports.start()
        // The rest one after another once the first screen has settled (they all started together, 2026-11-18).
        viewModelScope.launch {
            io.github.petebutty.viewarr.ui.common.AppStart.after(8_000)
            // IPTV / addon watch progress shared between boxes through Dispatcharr (WatchSync).
            watchSync.start()
            io.github.petebutty.viewarr.ui.common.AppStart.after(14_000)
            // Live score alerts for what this user follows (from the plugin), as a banner on this TV.
            serverSports.startAlerts(sportsRepo.follows)
            io.github.petebutty.viewarr.ui.common.AppStart.after(20_000)
            // "Record all of a team's games" and pending game recordings shared with Dispatcharr (the plugin records them).
            gameWatch.startSharing()
        }
        // What the signed-in person may do (an admin's locks on their Dispatcharr user).
        accessLoader.start(viewModelScope)
        // The house's outside addresses from the Viewarr plugin on Dispatcharr (when it's installed).
        if (ProfileUi.ENABLED) house.start(viewModelScope)
        // The plugin's house data from everyone's reports (guide offsets, changes with Undo, guides to pick), and this
        // TV's own reports sent again if a push failed (data/report).
        houseSync.start(viewModelScope)
        // Away addresses from the setup (empty: everything stays on its home address).
        viewModelScope.launch { accounts.accounts.collect { Reach.setPairs(it.away) } }
    }
    val playback = playbackStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSettings())
    val remote = remoteStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, RemoteSettings())

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
    // Audio sync starts from Settings → Playback → Lip sync (the info bars' button changes it from there).
    androidx.compose.runtime.LaunchedEffect(playbackSettings.audioDelay) { AvSync.set(playbackSettings.audioDelay.ms) }
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
        val slow = am?.isLowRamDevice == true || AppColors.palette.lowMemory
        kotlinx.coroutines.withTimeoutOrNull(if (slow) 12_000L else 7_000L) {
            androidx.compose.runtime.snapshotFlow { StartupGate.ready }.first { it }
        }
        splashDone = true
    }
    val splashShown = !splashDone || state == StartState.Loading
    val splashAlpha by androidx.compose.animation.core.animateFloatAsState(
        if (splashShown) 1f else 0f, androidx.compose.animation.core.tween(300), label = "splash",
    )
    // Settings → Appearance → Text size: everything scaled together (layout and text).
    val systemDensity = androidx.compose.ui.platform.LocalDensity.current
    // Always laid out for the standard TV screen (960 × 540 units), whatever density the box's firmware picks: the
    // Shield is 960 wide already, but some boxes (Ugoos: 4K at 480 dpi = 1280 wide) would shrink the mini player
    // and stretch the guide. (Text scales with it; the system's font size setting still applies on top.)
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val baseDensity = androidx.compose.runtime.remember(config.screenWidthDp, config.screenHeightDp, systemDensity) {
        val wPx = config.screenWidthDp * systemDensity.density; val hPx = config.screenHeightDp * systemDensity.density
        val tv = minOf(wPx / 960f, hPx / 540f)
        if (wPx >= 1200f && tv > 0f) androidx.compose.ui.unit.Density(tv, systemDensity.fontScale) else systemDensity
    }
    val scale = AppColors.palette.textScale
    // Buttons taught in Settings → Remote: read wherever a press is turned into an action.
    androidx.compose.runtime.SideEffect { RemoteKeys.setTaught(remote.buttons) }
    // Weak boxes get smaller pictures (Low-memory mode counts, as well as what Android says about the box).
    val lowMem = AppColors.palette.lowMemory
    val lowRam = androidx.compose.runtime.remember { (appCtx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice == true }
    androidx.compose.runtime.SideEffect { SmallerImages.weak = lowMem || lowRam }
    // Every list follows the highlight per Settings → Appearance → List scrolling (instant: no trail going fast).
    val baseScroll = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
    val appScroll = androidx.compose.runtime.remember(baseScroll) { baseScroll.followingSetting() }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides appScroll,
        LocalRemote provides remote,
        LocalPlayback provides playbackSettings,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density * scale, baseDensity.fontScale),
        // Settings → Appearance → Font.
        androidx.tv.material3.LocalTextStyle provides androidx.tv.material3.LocalTextStyle.current.copy(fontFamily = appFont()),
        androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = appFont()),
    ) {
    // No screen reader running (the usual case): the whole app is one plain accessibility element. An accessibility
    // service of another kind (Projectivy's, for its remote buttons and Home) makes Android re-describe every element
    // on screen after each change, and that was the biggest single cost on the main thread while browsing (~1.3 s of
    // 15 s; Peter, 2026-11-19). With TalkBack (touch exploration) on, everything is described as normal.
    val screenReader = rememberScreenReader()
    Box(
        Modifier
            .fillMaxSize()
            .then(if (screenReader) Modifier else Modifier.clearAndSetSemantics { contentDescription = "Viewarr" })
            .appBackground(titleArt = io.github.petebutty.viewarr.ui.theme.Wallpaper.titleBehind)
            // Presses during the splash are swallowed rather than lost on a half-loaded page.
            // (Every press also counts as being there: the music screensaver waits for a quiet spell.)
            .onPreviewKeyEvent { UserActivity.touch(); splashShown },
    ) {
        // (After the splash: decoding pictures competed with the start page loading.)
        if (!splashShown) WallpaperLoader()
        when (val s = state) {
            StartState.Loading -> Unit
            StartState.NeedsSetup -> if (!splashShown) {
                // A new install: running your own server, or invited by someone who does.
                // (Profiles off for now: no "invited by someone" choice, straight to setting up your own.)
                var role by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(if (ProfileUi.ENABLED) null else "admin") }
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.sawSetup = true }
                when (role) {
                    null -> RoleScreen(onAdmin = { role = "admin" }, onInvited = { role = "invited" })
                    "invited" -> InvitedSetup(onBack = { role = null })
                    // Running your own: first how Viewarr works (the tour), then what setting up will ask for (1-2-3),
                    // then the setup itself. (The tour's then done: it isn't shown again after setting up.)
                    else -> {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        var stage by androidx.compose.runtime.saveable.rememberSaveable {
                            androidx.compose.runtime.mutableStateOf(if (Tour.done(ctx)) "setup" else "tour")
                        }
                        when (stage) {
                            "tour" -> TourScreen(
                                onDone = { Tour.markDone(ctx); stage = "prep" },
                                finishLabel = "Next: setting up  ›",
                                onBack = { if (ProfileUi.ENABLED) role = null },
                            )
                            "prep" -> TourScreen(onDone = { stage = "setup" }, prep = true, finishLabel = "Start setup", onBack = { stage = "tour" })
                            else -> SetupWizard()
                        }
                    }
                }
                // Signed in to a Dispatcharr user another TV saved its setup to: offer it.
                SyncOfferHost()
            }
            is StartState.Ready -> {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var tourNow by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(vm.sawSetup) { if (vm.sawSetup && !Tour.done(ctx)) tourNow = true }
                val touring = !splashShown && (tourNow || Tour.requested)
                // Composed straight away, under the splash, so rows and the guide load while it shows. Not while the
                // tour is up: a channel playing underneath kept the remote (Left / Right there mean skip).
                // (No offscreen layer for the page while a film glides: switching it on cost one very slow frame at the
                // start of every glide, more than it saved; measured on a Formuler.)
                // (Drawn above the film while the menu is open over it: Left from the film controls' first button.)
                if (!touring) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().zIndex(if (VodHost.behind) 2f else 0f)) {
                    HomeScreen(session = s.session, onSignOut = vm::signOut)
                }
                // Films, episodes and recordings: above every page, full screen or shrunk to a corner.
                VodHostLayer()
                // A film or episode on full screen: Back makes it the mini player, holding Back closes it.
                if (VodHost.covering) ButtonHint(
                    "film-full", "Back: mini player  ·  hold Back: close the player")
                // Music: the Now Playing screen, or the small now-playing bar while you browse.
                MusicLayer()
                // Settings → Appearance → Clock.
                if (!splashShown && AppColors.palette.opt(UiOpts.CLOCK) == 1) Clock(Modifier.align(Alignment.TopEnd))
                // Over everything: the "update available" pop-up (and the automatic check on start).
                // A poster's trailer full screen (OK while it plays in the card).
                io.github.petebutty.viewarr.ui.media.PosterTrailerHost()
                if (!splashShown) UpdateHost()
                // Once after an update: a few lines on what's new.
                if (!splashShown) WhatsNewHost()
                // Settings → About → Changelog, or Full changelog on the What's new card.
                ChangelogHost()
                if (!splashShown) SyncOfferHost()
                // Who's watching? (on start and from Settings), and the admin Users page.
                if (!splashShown) ProfileHost()
                // The tour: once, straight after setting up on this TV (and from Settings → About).
                if (touring) TourScreen(onDone = {
                    Tour.markDone(ctx); tourNow = false; Tour.requested = false
                })
            }
        }
        // Settings → Appearance → Rows under the info bar, over everything.
        InfoRowsEditor.open?.let { pl ->
            InfoRowsScreen(start = pl, onClose = { InfoRowsEditor.open = null })
        }
        // Settings → Troubleshooting → Send a report, over everything.
        if (ReportUi.open) ReportScreen(onClose = { ReportUi.open = false })
        // Live score alerts (the plugin), over everything but the splash.
        io.github.petebutty.viewarr.ui.common.AlertBanner.Host()
        // Report a problem (hold OK on a channel), the guide picker, Settings → Live TV → Reports and the house card.
        io.github.petebutty.viewarr.ui.report.ReportHost()
        // Settings → Live TV → Live now: its search screen and a pick's Remove pop-up.
        io.github.petebutty.viewarr.ui.settings.LiveNowHost()
        if (splashAlpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = splashAlpha }) {
            ViewarrSplash()
        }
    }
}
}

/** The time, small, in the top corner (Settings → Appearance → Clock). */
@Composable
private fun Clock(modifier: Modifier) {
    var now by remember { androidx.compose.runtime.mutableStateOf(Dates.time(System.currentTimeMillis())) }
    androidx.compose.runtime.LaunchedEffect(AppColors.palette) {
        while (true) { now = Dates.time(System.currentTimeMillis()); kotlinx.coroutines.delay(5_000) }
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
    val i = AppColors.palette.opt(UiOpts.FONT)
    return androidx.compose.runtime.remember(i) {
        when (i) {
            1 -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
            2 -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL))
            3 -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
            4 -> androidx.compose.ui.text.font.FontFamily.Serif
            5 -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL))
            6 -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL))
            7 -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-condensed-light", android.graphics.Typeface.NORMAL))
            8 -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL))
            9 -> androidx.compose.ui.text.font.FontFamily.Monospace
            10 -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-smallcaps", android.graphics.Typeface.NORMAL))
            11 -> androidx.compose.ui.text.font.FontFamily.Cursive
            else -> null
        }
    }
}


/** Whether a screen reader (touch exploration, e.g. TalkBack) is on now; follows it being switched on or off. */
@androidx.compose.runtime.Composable
private fun rememberScreenReader(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val am = androidx.compose.runtime.remember { context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager }
    var on by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(am?.isTouchExplorationEnabled == true) }
    androidx.compose.runtime.DisposableEffect(am) {
        val l = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        am?.addTouchExplorationStateChangeListener(l)
        onDispose { am?.removeTouchExplorationStateChangeListener(l) }
    }
    return on
}
