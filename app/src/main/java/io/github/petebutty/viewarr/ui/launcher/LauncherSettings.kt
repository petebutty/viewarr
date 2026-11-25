package io.github.petebutty.viewarr.ui.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.data.profiles.Lock
import io.github.petebutty.viewarr.data.repository.SyncState
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.home.MenuGroupLabel
import io.github.petebutty.viewarr.ui.settings.AboutSettingsScreen
import io.github.petebutty.viewarr.ui.settings.AdvancedPage
import io.github.petebutty.viewarr.ui.settings.HomeAssistantItem
import io.github.petebutty.viewarr.ui.settings.HomeRowsSettingsScreen
import io.github.petebutty.viewarr.ui.settings.MappedSettingsScreen
import io.github.petebutty.viewarr.ui.settings.OtherSettingsPage
import io.github.petebutty.viewarr.ui.settings.PluginsSettingsScreen
import io.github.petebutty.viewarr.ui.settings.RemoteSettingsScreen
import io.github.petebutty.viewarr.ui.settings.SettingsMap
import io.github.petebutty.viewarr.ui.settings.SettingsSearchScreen
import io.github.petebutty.viewarr.ui.settings.SportsSettingsScreen
import io.github.petebutty.viewarr.ui.settings.WatchlistItem
import io.github.petebutty.viewarr.ui.setup.SetupMode
import io.github.petebutty.viewarr.ui.setup.SetupWizard
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.appBackground
import kotlinx.coroutines.android.awaitFrame

/** One entry on the Settings page: [key] names the page it opens (LauncherSettings' [open]). */
private class SettingsEntry(val key: String, val title: String, val about: String)

/**
 * The launcher build's Settings (the gear on the home screen): one full page of its own, not Viewarr's side menu.
 * The list of settings pages on the left, what the highlighted one covers on the right; OK opens that page (the same
 * pages Viewarr's Settings open), Back from it comes back here on it, Back here goes home.
 */
@Composable
internal fun LauncherSettings(session: Session?, onSignOut: () -> Unit) {
    // A film in the mini player: over to the top right while Settings is open, clear of the list, and see-through
    // there (2026-11-25: it covered Settings; "I want it to be transparent so I can see the settings underneath").
    // Android's standard video picture is its own layer under the app and can't be made see-through itself, so the
    // layers swap: the film plays under Settings, and Settings is see-through over the picture's rectangle only. It
    // looks the same as a half see-through picture over Settings, with either video view.
    val vod = io.github.petebutty.viewarr.ui.media.VodHost
    val filmUp = vod.active && vod.mini
    // (Or a live channel carrying on from the home screen: shown here too, drawn under Settings. 2026-11-25: it went.)
    val playback: io.github.petebutty.viewarr.playback.PlaybackViewModel = hiltViewModel()
    val liveCh by playback.channel.collectAsStateWithLifecycle()
    val liveUp = liveCh != null && !vod.active
    val miniUp = filmUp || liveUp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    val slide = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(miniUp) { slide.animateTo(if (miniUp) 1f else 0f, androidx.compose.animation.core.tween(350)) }
    val cp = io.github.petebutty.viewarr.ui.theme.CornerPicture
    fun pushNow(): Int = with(density) { ((view.width - (cp.size().first + cp.inset.first * 2).toPx()) * slide.value).toInt() }
    androidx.compose.runtime.DisposableEffect(filmUp) {
        val before = cp.pushPx
        if (filmUp) { cp.pushPx = { pushNow() }; vod.behind = true }
        onDispose { if (filmUp) { cp.pushPx = before; vod.behind = false } }
    }
    // Hold Back: stops the film or channel in the corner, as on the home screen; Settings stays open (a tap is Back
    // as usual). (2026-11-25: holding only closed Settings on the release.)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val backHold = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    var backHeld by remember { mutableStateOf(false) }
    val holdBack = Modifier.onPreviewKeyEvent { e ->
        if (e.key != androidx.compose.ui.input.key.Key.Back) return@onPreviewKeyEvent false
        val down = e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
        when {
            // (A fresh press is a new press: a hold's lost release mustn't swallow it.)
            backHeld && down && e.nativeKeyEvent.repeatCount == 0 -> { backHeld = false; false }
            backHeld -> { if (!down) backHeld = false; true }
            !miniUp -> false
            down && e.nativeKeyEvent.repeatCount == 0 -> {
                backHold[0]?.cancel()
                backHold[0] = scope.launch {
                    kotlinx.coroutines.delay(500); backHeld = true
                    if (vod.active) vod.close() else playback.stop()
                }
                false
            }
            down -> backHeld
            else -> { backHold[0]?.cancel(); false }
        }
    }
    if (liveUp) LiveCorner(playback, Modifier.offset { androidx.compose.ui.unit.IntOffset(cp.inset.first.roundToPx() + pushNow(), cp.inset.second.roundToPx()) })
    Box(Modifier.fillMaxSize().then(holdBack).then(if (!miniUp) Modifier else Modifier
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            // How see-through Settings is over the picture: the film shows through at this much.
            val (w, h) = cp.size()
            drawRect(Color.Black.copy(alpha = 0.6f * slide.value),
                topLeft = androidx.compose.ui.geometry.Offset(cp.inset.first.toPx() + pushNow(), cp.inset.second.toPx()),
                size = androidx.compose.ui.geometry.Size(w.toPx(), h.toPx()),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstOut)
        })) {
        LauncherSettingsBody(session, onSignOut, miniUp)
    }
}

@Composable
private fun LauncherSettingsBody(session: Session?, onSignOut: () -> Unit, miniUp: Boolean) {
    // ([miniUp]: a film or a live channel in the corner, over at the top right: what's beside the list starts under it.)
    var open by remember { mutableStateOf<String?>(null) }
    var start by remember { mutableStateOf<Pair<String, String>?>(null) }
    var returnTo by remember { mutableStateOf<String?>(null) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun req(key: String) = requesters.getOrPut(key) { FocusRequester() }
    fun close() { returnTo = open; open = null; start = null; AdvancedPage.open = false }

    val page = open
    if (page != null) {
        SettingsSubPage(page, start, ::close, onOpen = { key, s -> start = s; open = key })
        return
    }
    var askSignOut by remember { mutableStateOf(false) }
    BackHandler(enabled = !askSignOut) { Launcher.settingsOpen = false }
    val vm: GuideViewModel = hiltViewModel()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val canAccounts = Access.can(Lock.ACCOUNTS)
    val canSettings = Access.can(Lock.SETTINGS)
    val canSignOut = Access.can(Lock.SIGNOUT)
    val admin = session != null && !session.isDirect && io.github.petebutty.viewarr.ui.profiles.isAdmin()
    val groups = settingsGroups(canAccounts, canSettings, admin)
    var about by remember { mutableStateOf("") }
    // On the entry you came back from; else the first.
    LaunchedEffect(Unit) {
        awaitFrame(); awaitFrame()
        val key = returnTo ?: groups.firstOrNull()?.second?.firstOrNull()?.key
        key?.let { runCatching { req(it).requestFocus() } }
    }

    Row(Modifier.fillMaxSize().appBackground(titleArt = true).padding(start = 56.dp, top = 40.dp, end = 56.dp)) {
        Column(Modifier.width(360.dp).fillMaxHeight()) {
            Text("Settings", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Text(session?.let { "Signed in as ${it.username}  ·  ${it.serverUrl}" } ?: "Live TV not set up", fontSize = 13.sp,
                color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            when (val s = sync) { is SyncState.Running -> s.message; is SyncState.Failed -> s.message; SyncState.Idle -> null }?.let {
                Text(it, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEachIndexed { gi, (group, entries) ->
                    MenuGroupLabel(group, first = gi == 0)
                    entries.forEach { e ->
                        MenuItem(text = e.title, selected = false, onClick = { returnTo = e.key; open = e.key },
                            modifier = Modifier.focusRequester(req(e.key)).onFocusChanged { if (it.isFocused) about = e.about })
                    }
                    // The settings that are a single choice, changed right here.
                    when (group) {
                        "Watching" -> if (canSettings) WatchlistItem(Modifier.onFocusChanged { if (it.isFocused) about = "Which watchlist Add / Remove and \"Your watchlist\" use: Trakt, Plex, Jellyfin or Emby favourites." })
                        "Live TV server" -> MenuItem(text = "Refresh channels & guide", selected = false, onClick = { vm.refresh() },
                            modifier = Modifier.onFocusChanged { if (it.isFocused) about = "Loads the channel list and TV guide again now." })
                        "This TV" -> {
                            if (canSignOut && session != null && !session.isDirect) io.github.petebutty.viewarr.ui.sync.BackupSyncItem()
                            if (canSettings) HomeAssistantItem(Modifier.onFocusChanged { if (it.isFocused) about = "Optional: tell Home Assistant what's playing (for lights and scenes), and show its pop-ups, like a doorbell camera." })
                            if (canSignOut) MenuItem(text = "Sign out of everything", selected = false, onClick = { askSignOut = true },
                                modifier = Modifier.onFocusChanged { if (it.isFocused) about = "Signs this TV out of all your accounts and sources." })
                        }
                    }
                }
            }
        }
        // What the highlighted entry covers, large, beside the list.
        // (Under the mini player, over at the top right, while a film plays.)
        Box(Modifier.padding(start = 56.dp, top = if (miniUp) 210.dp else 120.dp).width(420.dp)) {
            Text(about, fontSize = 18.sp, lineHeight = 26.sp, color = AppColors.TextPrimary.copy(alpha = 0.9f))
        }
    }
    if (askSignOut) OptionsPopup(
        title = "Sign out of everything?",
        description = "This TV is signed out of all your accounts and sources.",
        options = listOf(PopupOption("Sign out") { Launcher.settingsOpen = false; onSignOut() }),
        onClose = { askSignOut = false },
    )
}

/** The Settings page's entries, grouped as Viewarr's Settings list (each group's single choices are added by the page). */
private fun settingsGroups(canAccounts: Boolean, canSettings: Boolean, admin: Boolean): List<Pair<String, List<SettingsEntry>>> = listOfNotNull(
    "Find" to listOfNotNull(
        if (canSettings) SettingsEntry("search", "Search settings", "Find any setting by name: type a word (frame rate, subtitles, buttons…) and OK opens it.") else null,
    ),
    "Accounts" to listOfNotNull(
        if (canAccounts) SettingsEntry("accounts", "Accounts & sources", "Your live TV (Dispatcharr or IPTV), Plex, Jellyfin, Emby, Trakt, addons and music sign-ins.") else null,
    ),
    if (canSettings) "Look & controls" to listOf(
        SettingsEntry(SettingsMap.APPEARANCE, "Appearance", "How things look: text and dates, glass, the TV guide, the info bar, posters, title pages, ratings, subtitles and music."),
        SettingsEntry("tiles", "Home screen tiles", "Which tiles show on the home screen. (Hold OK on a tile there to move or hide it.)"),
        SettingsEntry("remote", "Remote buttons", "Every button on your remote, and what it does in the guide, on live TV, in films and shows. The search button and extra buttons."),
        SettingsEntry(SettingsMap.NAVIGATION, "Notifications", "Everything Previewarr pops up by itself: switch it all off, or choose each kind (new episodes, live games…)."),
    ) else null,
    "Watching" to listOfNotNull(
        if (canSettings) SettingsEntry(SettingsMap.LIVE, "Live TV", "Watching live TV: Up / Down and browsing channels, the TV guide (days ahead, refresh), channel groups and Still watching?") else null,
        if (canSettings) SettingsEntry(SettingsMap.PLAYBACK, "Playback", "Films, shows and players: built-in player or mpv, frame rate matching, audio, subtitles, streams, play order, episodes and trailers.") else null,
        if (canSettings) SettingsEntry("sports", "Sports", "Your teams and leagues: their games, and the channel showing each.") else null,
    ),
    "Live TV server" to listOfNotNull(
        if (admin) SettingsEntry("plugins", "Dispatcharr plugins", "Plugins on your Dispatcharr server (admins only).") else null,
    ),
    "This TV" to listOfNotNull(
        if (canSettings) SettingsEntry(SettingsMap.SYSTEM, "Troubleshooting", "Playback info on screen, and Low-memory mode for boxes that struggle.") else null,
        if (canSettings) SettingsEntry(SettingsMap.ADVANCED, "Advanced", "For when you've been asked to change something: player internals, screen and sizes, guide data. Best left alone.") else null,
        SettingsEntry("about", "About", "Version, this TV, what's stored (and clearing it), and your live TV sources."),
    ),
).filter { (name, list) -> list.isNotEmpty() || name in setOf("Watching", "Live TV server", "This TV") }

/** The page an entry opens, full screen; [close]: Back from it. Search settings opens the others ([onOpen]). */
@Composable
private fun SettingsSubPage(key: String, start: Pair<String, String>?, close: () -> Unit, onOpen: (String, Pair<String, String>?) -> Unit) {
    when (key) {
        "search" -> SettingsSearchScreen(
            onBack = close,
            onOpen = { page, sec, title -> onOpen(page, sec to title) },
            others = listOf(
                OtherSettingsPage("Remote buttons", "buttons remote presets classic skip size channel change delay zapping search button colour extra spare learn") { onOpen("remote", null) },
                OtherSettingsPage("Home screen tiles", "home screen tiles hide show live tv movies shows music sports services") { onOpen("tiles", null) },
                OtherSettingsPage("Sports", "teams leagues games football basketball nfl follow") { onOpen("sports", null) },
                OtherSettingsPage("Accounts & sources", "accounts sources sign in plex jellyfin emby trakt seerr dispatcharr iptv playlist addons music navidrome server") { onOpen("accounts", null) },
                OtherSettingsPage("Dispatcharr plugins", "plugins dispatcharr admin") { onOpen("plugins", null) },
                OtherSettingsPage("About", "about version storage clear data tour live tv sources this tv") { onOpen("about", null) },
            ),
        )
        "accounts" -> SetupWizard(mode = SetupMode.Manage, onClose = close)
        "tiles" -> HomeTilesSettings(onBack = close)
        "remote" -> RemoteSettingsScreen(onBack = close)
        "sports" -> SportsSettingsScreen(onBack = close)
        "plugins" -> PluginsSettingsScreen(onBack = close)
        "about" -> AboutSettingsScreen(onBack = close)
        SettingsMap.ADVANCED -> {
            LaunchedEffect(Unit) { AdvancedPage.open = true }
            MappedSettingsScreen(SettingsMap.ADVANCED, onBack = close, startSection = start?.first, startItem = start?.second)
        }
        else -> MappedSettingsScreen(key, onBack = close, startSection = start?.first, startItem = start?.second)
    }
}


/** Settings → Home screen tiles: each tile the home screen has had, on or off (the same as Hide / Show on the tile). */
@Composable
private fun HomeTilesSettings(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = androidx.compose.ui.platform.LocalContext.current
    val tiles = remember { HomeTiles.known(context) }
    var hidden by remember { mutableStateOf(HomeTiles.hidden(context)) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().appBackground(titleArt = true).padding(start = 56.dp, top = 40.dp, end = 56.dp)) {
        Text("Home screen tiles", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
        Text("OK switches a tile on or off.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        Column(Modifier.width(420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tiles.forEachIndexed { i, (icon, label) ->
                val on = icon !in hidden
                MenuItem(text = label, trailing = if (on) "On" else "Off", selected = false,
                    onClick = { hidden = HomeTiles.setHidden(context, icon, on) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}
