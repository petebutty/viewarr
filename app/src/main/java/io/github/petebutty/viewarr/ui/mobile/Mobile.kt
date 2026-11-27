package io.github.petebutty.viewarr.ui.mobile

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.petebutty.viewarr.BuildConfig
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.home.Page
import io.github.petebutty.viewarr.ui.launcher.Launcher
import io.github.petebutty.viewarr.ui.launcher.LauncherLibrary
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * Previewarr Mobile (the "mobile" build): screens made for fingers, not a TV's canvas scaled down, shown in place of
 * the home screen. The TV builds never show them. Everything behind them (accounts, the guide, the players, settings)
 * is the TV app's. Built a page at a time: Live TV first; the others open the TV pages for now.
 */
internal object Mobile {
    val on: Boolean get() = BuildConfig.MOBILE
    /** The page along the bottom bar that's open: "live", "films" or "shows" (Music and Recordings: the TV pages, for now). */
    var tab by androidx.compose.runtime.mutableStateOf("live")
    /** The device's own density (the TV pages and players draw on a fixed canvas; phone parts over them use this). */
    var density: Density? = null
    /** [content] drawn at the device's own density (phone-sized), inside a TV-canvas part such as the film player. */
    @Composable
    fun Phone(content: @Composable () -> Unit) {
        val d = density
        if (d == null) content() else androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides d) { content() }
    }
}

/** A round icon button for the pages' top bars. */
@Composable
internal fun TopBarIcon(icon: ImageVector, label: String, onTap: () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

/** The phone and tablet screens' colours (dark, like the TV app; the highlight colour is the app's). */
internal object MobileColors {
    val Background = Color(0xFF121212)
    val Card = Color(0xFF242424)
    val Line = Color(0xFF3A3A3A)
    val Track = Color(0xFF3A3A3A)
    val Bar = Color(0xF0202020)
}

/** The phone and tablet home: the page, with the bar of pages along the bottom. [density]: the device's own. */
@Composable
internal fun MobileRoot(session: Session?, density: Density, onSignOut: () -> Unit = {}) {
    // Upright or on its side, as the device is held: never forced sideways (2026-11-26).
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose { }
    }
    // A film or episode playing (the TV app's player, laid out sideways): the phone turns sideways for it.
    val vodFull = io.github.petebutty.viewarr.ui.media.VodHost.covering
    LaunchedEffect(vodFull) {
        activity?.requestedOrientation = if (vodFull) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }
    Mobile.density = density
    MobilePlayOnTv.key = remember(session) { io.github.petebutty.viewarr.data.cast.PlayOnTv.keyOf(session) }
    MobilePlayOnTv.session = session
    CompositionLocalProvider(LocalDensity provides density) {
        Box(Modifier.fillMaxSize().background(MobileColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            val open = MobileNav.channel
            val title = MobileTitle.type
            if (MobileSettingsNav.open) MobileSettings(session, onSignOut)
            else if (open != null) MobileChannelPage(open, session)
            else if (title != null) MobileTitlePage(title)
            else {
                when (Mobile.tab) {
                    "films" -> MobileMediaPage(io.github.petebutty.viewarr.data.media.MediaType.Movie, Modifier.fillMaxSize())
                    "shows" -> MobileMediaPage(io.github.petebutty.viewarr.data.media.MediaType.Show, Modifier.fillMaxSize())
                    "services" -> MobileServicesPage(Modifier.fillMaxSize())
                    "music" -> MobileMusicPage(Modifier.fillMaxSize())
                    "recordings" -> MobileRecordings(session, Modifier.fillMaxSize())
                    else -> MobileLiveTv(session, Modifier.fillMaxSize())
                }
                // Back on another tab (Films, Streaming, Music…): Live TV, the first one, rather than out of the app.
                androidx.activity.compose.BackHandler(enabled = Mobile.tab != "live" && Access.liveTv) { Mobile.tab = "live" }
                MobileMiniPlayer(Modifier.align(Alignment.BottomCenter))
                MobileBottomBar(Modifier.align(Alignment.BottomCenter))
                // The first time Live TV opens: how to get around (MobileTips).
                if (Mobile.tab == "live" && Access.liveTv) MobileTipsHost()
            }
            // Devices (Play on TV): over the pages while open.
            if (MobileDevices.open) MobileDevicesPage()
            // Music's Now playing: over every page while open.
            if (MobileMusic.nowOpen && io.github.petebutty.viewarr.playback.MusicPlayer.active) MobileNowPlaying()
            // Play on TV: the list of TVs, while a request is open (from any page).
            PlayOnTvSheet()
            // Play on TV's links on this phone or tablet (Take over; a tablet also receives).
            MobileLinksHost()
        }
    }
}

private class BarItem(val label: String, val icon: ImageVector, val selected: Boolean, val onTap: () -> Unit)

/** The pages along the bottom, in a floating pill. Live TV is this page; the others open the TV app's for now. */
@Composable
private fun MobileBottomBar(modifier: Modifier) {
    val items = buildList {
        if (Access.liveTv) add(BarItem("Live TV", AppIcons.LiveTv, Mobile.tab == "live") { Mobile.tab = "live" })
        add(BarItem("Films", AppIcons.Movie, Mobile.tab == "films") { Mobile.tab = "films" })
        add(BarItem("Shows", AppIcons.Episodes, Mobile.tab == "shows") { Mobile.tab = "shows" })
        add(BarItem("Streaming", AppIcons.Layers, Mobile.tab == "services") { Mobile.tab = "services" })
        add(BarItem("Music", AppIcons.MusicNote, Mobile.tab == "music") { Mobile.tab = "music" })
        if (Access.liveTv) add(BarItem("Recordings", AppIcons.Recordings, Mobile.tab == "recordings") { Mobile.tab = "recordings" })
    }
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(MobileColors.Bar)
            .border(1.dp, MobileColors.Line, RoundedCornerShape(32.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEach { item ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = item.onTap)
                    .padding(horizontal = 2.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val tint = if (item.selected) AppColors.Accent else Color.White
                Box(
                    Modifier.height(30.dp).clip(RoundedCornerShape(15.dp))
                        .background(if (item.selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) }
                Text(item.label, color = tint, fontSize = 11.sp, fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** A page of the TV app is up over the phone screens (a player, Films, Settings…): it has no way back by finger. */
internal val mobileOnTvPage: Boolean
    get() = Mobile.on && (!Launcher.home || Launcher.settingsOpen || LauncherLibrary.open) && !io.github.petebutty.viewarr.ui.media.VodHost.covering

/**
 * The way back from a TV page (a player, Films, Shows, Settings) to the phone screens: a bar at the top with Back
 * (Live TV) and the other pages. It shows when the page opens and for a few seconds after every touch, then gets out
 * of the picture's way; touches still reach the page underneath. Drawn in the device's own size, over everything.
 * (The TV pages answer only the remote's Back, and with the system's bars hidden that took a swipe from the edge.)
 */
@Composable
internal fun MobileBackHost(density: Density) {
    if (!mobileOnTvPage) return
    val shownUntil = MobileBar.shownUntil
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { MobileBar.touched() }
    LaunchedEffect(shownUntil) {
        now = System.currentTimeMillis()
        val left = shownUntil - now
        if (left > 0) { delay(left); now = System.currentTimeMillis() }
    }
    val visible = now < shownUntil
    CompositionLocalProvider(LocalDensity provides density) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = visible,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                Row(
                    Modifier
                        .padding(12.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(28.dp))
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackBarButton(AppIcons.Back, "Live TV", showLabel = true) {
                        when {
                            // A film or episode playing (the TV app's player, for now): closed, back to its page.
                            io.github.petebutty.viewarr.ui.media.VodHost.covering -> io.github.petebutty.viewarr.ui.media.VodHost.close()
                            Launcher.settingsOpen -> Launcher.settingsOpen = false
                            LauncherLibrary.open -> LauncherLibrary.hide()
                            else -> { Mobile.tab = "live"; Launcher.goHome() }
                        }
                    }
                    BackBarButton(AppIcons.Movie, "Films") { goToPage(Page.Movies, "Movies") }
                    BackBarButton(AppIcons.Episodes, "Shows") { goToPage(Page.Shows, "Shows") }
                }
            }
        }
    }
}

private const val SHOW_MS = 4_000L

/** When the bar over TV pages goes away: every touch anywhere (watched at the app's root, AppRoot) puts it back. */
internal object MobileBar {
    var shownUntil by mutableLongStateOf(0L)
    fun touched() { shownUntil = System.currentTimeMillis() + SHOW_MS }
}

/** Watches every touch on the screen without taking it (the pages still get it), for [MobileBar]. */
internal object MobileBarWatch {
    fun Modifier.watch(): Modifier = if (!Mobile.on) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.changes.any { it.pressed && !it.previousPressed }) MobileBar.touched()
        }
    }
}
}

/** Films or Shows from a TV page: within the TV app when it's up (from its player too), else opened from home. */
private fun goToPage(page: Page, name: String) {
    if (io.github.petebutty.viewarr.ui.media.VodHost.covering) io.github.petebutty.viewarr.ui.media.VodHost.close()
    MobileTitle.type = null
    if (Launcher.settingsOpen) Launcher.settingsOpen = false
    if (LauncherLibrary.open) LauncherLibrary.hide()
    // (The phone's own Films and Shows pages now: home, on that page.)
    Mobile.tab = if (page == Page.Movies) "films" else "shows"
    Launcher.goHome()
}

@Composable
private fun BackBarButton(icon: ImageVector, label: String, showLabel: Boolean = true, onTap: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        if (showLabel) Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
    }
}
