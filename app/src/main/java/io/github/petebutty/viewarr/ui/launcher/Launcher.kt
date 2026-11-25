package io.github.petebutty.viewarr.ui.launcher

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import io.github.petebutty.viewarr.BuildConfig
import io.github.petebutty.viewarr.ui.home.HomeState
import io.github.petebutty.viewarr.ui.home.Page
import io.github.petebutty.viewarr.ui.media.VodHost
import io.github.petebutty.viewarr.ui.theme.appBackground
import kotlinx.coroutines.android.awaitFrame

/**
 * Viewarr launcher (the "launcher" build, 2026-11-24): a launcher-style home screen instead of the main menu, on
 * Viewarr's own live TV, guide, pages, title pages and players. The home screen ([LauncherHome]) hands over to
 * Viewarr's screens (HomeScreen) with what to open; wherever Viewarr would open its main menu, or ask to exit, it
 * comes back here instead. Settings is the launcher's own page (LauncherSettings); the guide's channel groups still
 * open as in Viewarr.
 */
internal object Launcher {
    val enabled: Boolean get() = BuildConfig.LAUNCHER

    /** The launcher home screen is showing (else Viewarr's screens, opened from it). */
    var home by mutableStateOf(true)
        private set

    /** What to open once Viewarr's screens are up (a page, a service, a title, Settings). */
    private var pending: ((HomeState) -> Unit)? = null
    private var enteredAt = 0L

    /** Set while Viewarr's screens are up: stops what's playing as the home screen comes back. */
    var onLeave: (() -> Unit)? = null

    /** The page Viewarr's screens start on (so nothing else is drawn first); taken once. */
    private var startPage: Page? = null
    fun takeStartPage(): Page? = startPage.also { startPage = null }

    /** A title opened straight from the home screen: closing it comes back home (not to Movies / Shows). */
    var titleOnly by mutableStateOf(false)
        private set

    /**
     * Just opened and what was asked for not up yet: Viewarr's screens stay covered (the home screen's background
     * instead), so nothing else is seen first: the Movies page under search or Settings, the guide before the last
     * channel, a page's first empty frames.
     */
    var arriving by mutableStateOf(false)
        private set
    private var arrivedWhen: (HomeState) -> Boolean = { true }

    fun open(page: Page? = null, action: ((HomeState) -> Unit)? = null, readyWhen: (HomeState) -> Boolean = { true }) {
        pending = action
        arrivedWhen = readyWhen
        arriving = true
        startPage = page
        titleOnly = false
        overlay = null
        enteredAt = SystemClock.elapsedRealtime()
        home = false
    }

    /** Search opened from the home screen ("search"): closing it comes back home. */
    var overlay by mutableStateOf<String?>(null)
        private set

    fun openSearch() {
        open(Page.Movies, { hs -> hs.openPage(Page.Search) }, readyWhen = { hs -> hs.quickSearch })
        overlay = "search"
        searchBacked = false
    }

    /** Search closed with Back (nothing picked): straight home, without waiting for a pick to start. */
    var searchBacked = false

    /** Viewarr's screens covered meanwhile (the home background instead): search closed, a pick still starting. */
    fun cover(on: Boolean) { arriving = on }

    /** The launcher's own Settings page (LauncherSettings), over the home screen. */
    var settingsOpen by mutableStateOf(false)

    /** Settings asked for from Viewarr's screens (their menu's Settings): home, on the Settings page. */
    fun openSettings() { goHome(); settingsOpen = true }

    /** A title opened from search (its page now up): closing it comes home, as one opened from the home screen. */
    fun overlayBecameTitle() { overlay = null; titleOnly = true }

    /** Something picked in search / Settings now playing: it's what's on screen now, not a page under them. */
    fun overlayDone() { overlay = null }

    /** A title's page, already opened on [page]'s view model, straight away: no other page drawn first. */
    fun openTitle(page: Page) {
        open(page, readyWhen = { hs -> hs.moviesVm.detail.value != null || hs.showsVm.detail.value != null })
        titleOnly = true
    }

    fun goHome() {
        if (!enabled || home) return
        onLeave?.invoke()
        onLeave = null
        titleOnly = false
        overlay = null
        arriving = false
        home = true
    }

    /** Just opened: Viewarr's own start (its start page, a menu it opens itself) isn't a request to go home. */
    val settling: Boolean get() = SystemClock.elapsedRealtime() - enteredAt < 2_000

    fun takePending(): ((HomeState) -> Unit)? = pending.also { pending = null }

    /** Uncovers Viewarr's screens once what was asked for is up (at most a few seconds: never stuck covered). */
    suspend fun arrive(hs: HomeState) {
        val test = arrivedWhen
        // (Checked every few frames: not everything it looks at is Compose state.)
        kotlinx.coroutines.withTimeoutOrNull(3_500) {
            while (!runCatching { test(hs) }.getOrDefault(true)) kotlinx.coroutines.delay(40)
        }
        awaitFrame(); awaitFrame(); awaitFrame()
        arriving = false
    }

    // ---- Where you were on the home screen (kept while the app runs, so coming back home lands there) ----
    val columnState = androidx.compose.foundation.lazy.LazyListState()
    val rowStates = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    /** The card last highlighted: "<row key>|<item key>". */
    var lastFocus: String? = null
}

/** HomeScreen's part in the launcher build: opens what was asked for, and sends main-menu requests home. */
@Composable
internal fun LauncherEffects(hs: HomeState) {
    if (!Launcher.enabled) return
    LaunchedEffect(Unit) {
        awaitFrame(); awaitFrame()
        Launcher.takePending()?.invoke(hs)
        Launcher.arrive(hs)
    }
    // A live channel carrying on from the home screen's corner, on a page other than the guide (Movies, a title…): in that
    // page's top-left window (Viewarr's live TV over a page), so it's seen, hold Back stops it, and Back is home. Without
    // it the sound went on with no picture (2026-11-25).
    val liveNow by hs.playback.channel.collectAsState()
    LaunchedEffect(hs.page, liveNow != null) {
        if (liveNow != null && hs.page != Page.Tv && !hs.liveBehind) hs.livePreview = true
    }
    // Search opened from home, closed: home, unless what was picked there is now up (a title's page, a channel playing).
    var wasOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // (The channel already playing when search opened, from the home screen's corner: only another one is "picked".
    // 2026-11-25: with live TV on, any pick counted as a channel, and Back from a title went to Movies, not home.)
    val channelBefore = androidx.compose.runtime.remember { arrayOfNulls<Any>(1) }
    val overlayUp = Launcher.overlay == "search" && hs.quickSearch
    LaunchedEffect(overlayUp, Launcher.overlay) {
        if (Launcher.overlay == null) { wasOpen = false; return@LaunchedEffect }
        if (overlayUp) { if (!wasOpen) channelBefore[0] = hs.playing?.id; wasOpen = true; return@LaunchedEffect }
        if (!wasOpen) return@LaunchedEffect
        wasOpen = false
        // What was picked can take a moment to start (a channel waits for its group up to 3 s; music opens its page):
        // looked at for a few seconds before deciding nothing was (a channel then played over the home screen).
        if (Launcher.searchBacked) { Launcher.goHome(); return@LaunchedEffect }
        Launcher.cover(true)
        repeat(36) { i ->
            when {
                hs.moviesVm.detail.value != null || hs.showsVm.detail.value != null -> { Launcher.cover(false); Launcher.overlayBecameTitle(); return@LaunchedEffect }
                (hs.playing != null && hs.playing?.id != channelBefore[0]) || VodHost.active || hs.page != Page.Movies || hs.showAccounts -> { Launcher.cover(false); Launcher.overlayDone(); return@LaunchedEffect }
            }
            if (i < 35) kotlinx.coroutines.delay(100)
        }
        Launcher.goHome()
    }
    // A title opened from the home screen, closed (Back from it): home.
    val movieTitle by hs.moviesVm.detail.collectAsState()
    val showTitle by hs.showsVm.detail.collectAsState()
    LaunchedEffect(movieTitle == null && showTitle == null) {
        if (!Launcher.titleOnly || movieTitle != null || showTitle != null) return@LaunchedEffect
        // (Closed in the first moments, before its page had even come up: still home, once it's settled.)
        while (Launcher.settling) kotlinx.coroutines.delay(100)
        if (hs.moviesVm.detail.value == null && hs.showsVm.detail.value == null) Launcher.goHome()
    }
    SideEffect {
        Launcher.onLeave = {
            hs.menuOpen = false
            runCatching { hs.moviesVm.closeTitlePages(); hs.showsVm.closeTitlePages() }
            // (A film carries on in the mini player over the home screen, never stopped by going home (Settings from
            // a film's controls stopped it); hold Back there closes it. Not left "behind" a page that's gone.)
            if (VodHost.active && !VodHost.mini) VodHost.minimise()
            VodHost.behind = false
            // (A live channel carries on in the corner over the home screen, as a film does; only with a film playing
            // too is it stopped. 2026-11-25: "back from mini screen should go to home with the mini player still playing".)
            if (VodHost.active) runCatching { hs.playback.stop() }
        }
    }
    // There's no side menu here. Left at a page's edge (which opened it on its main list) does nothing: only Back
    // goes home (2026-11-25: Left from a row's first card went home). On Settings: the launcher's Settings page.
    // The guide's channel groups open as usual.
    LaunchedEffect(hs.menuOpen, hs.menuOnSettings, hs.menuOnMain) {
        if (!hs.menuOpen) return@LaunchedEffect
        if (hs.menuOnSettings) { hs.menuOpen = false; hs.menuOnSettings = false; Launcher.openSettings(); return@LaunchedEffect }
        if (hs.menuOnMain || hs.page != Page.Tv) hs.menuOpen = false
    }
}

/**
 * Viewarr's screens in the launcher build, hidden in the moment a title opened from the home screen closes: Back
 * from it showed the Movies / Shows page underneath for a frame or two before home came back. (In Viewarr itself:
 * just the screens.)
 */
@Composable
internal fun LauncherVeil(content: @Composable () -> Unit) {
    if (!Launcher.enabled) { content(); return }
    val movies: io.github.petebutty.viewarr.ui.media.MediaViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = "media-Movie")
    val shows: io.github.petebutty.viewarr.ui.media.MediaViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = "media-Show")
    val m by movies.detail.collectAsState()
    val s by shows.detail.collectAsState()
    val hide = Launcher.titleOnly && m == null && s == null || Launcher.arriving
    // (The home screen's background behind them while they're covered.)
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize()
            .then(if (hide) androidx.compose.ui.Modifier.appBackground() else androidx.compose.ui.Modifier),
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer { alpha = if (hide) 0f else 1f },
        ) { content() }
    }
}

/**
 * Previewarr's home screen and Settings: live TV let go when the app goes to the background (standby, another app), and
 * picked up again on coming back, as Viewarr's own screens do (ResumeSpot). Those screens aren't there while the home
 * screen or Settings is, and a channel in the home screen's corner kept streaming, with sound (2026-11-25).
 */
@androidx.compose.runtime.Composable
internal fun LauncherLifecycle() {
    if (!Launcher.enabled) return
    val playback: io.github.petebutty.viewarr.playback.PlaybackViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        if (!Launcher.home) return@LifecycleEventEffect
        if (playback.channel.value != null) playback.releaseForBackground()
        io.github.petebutty.viewarr.ui.home.ResumeSpot.wentAway = true
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) {
        // (Only after really being away from here: this also runs when it's first shown.)
        if (!Launcher.home || !io.github.petebutty.viewarr.ui.home.ResumeSpot.wentAway) return@LifecycleEventEffect
        io.github.petebutty.viewarr.ui.home.ResumeSpot.wentAway = false
        if (playback.channel.value != null) playback.resume()
    }
}
