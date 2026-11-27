package io.github.petebutty.viewarr.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.settings.SettingsMap
import io.github.petebutty.viewarr.ui.settings.settingsPool
import io.github.petebutty.viewarr.ui.theme.AppColors
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.petebutty.viewarr.data.homeassistant.HaStore
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.data.profiles.Lock
import io.github.petebutty.viewarr.data.sync.SyncMode
import io.github.petebutty.viewarr.ui.common.NewsViewModel
import io.github.petebutty.viewarr.ui.settings.AppearanceViewModel
import io.github.petebutty.viewarr.ui.settings.PlaybackSettingsViewModel
import io.github.petebutty.viewarr.ui.settings.UiOptsViewModel
import io.github.petebutty.viewarr.ui.settings.advancedReset
import io.github.petebutty.viewarr.ui.settings.appearanceReset
import io.github.petebutty.viewarr.ui.settings.playbackReset
import io.github.petebutty.viewarr.ui.sync.SyncViewModel

/** Settings on a phone or tablet: open or not, and the page shown (null: the menu of pages). */
internal object MobileSettingsNav {
    var open by mutableStateOf(false)
    var page by mutableStateOf<String?>(null)
    fun back() { if (page != null) page = null else open = false }
}

/** The phone pages that aren't one of SettingsMap's (those are named by their SettingsMap id). */
private object Pages {
    const val ACCOUNTS = "m:accounts"
    const val BACKUP = "m:backup"
    const val ABOUT = "m:about"
    const val HA = "m:ha"
    const val PLUGINS = "m:plugins"
    const val SPORTS = "m:sports"
    const val SEARCH = "m:search"
}

/**
 * Settings made for a phone: a menu of rounded cards (your account at the top; managing your services; how the app
 * looks and plays; help), each page then a list of its settings in cards, a tap to change one (a switch flips, a choice
 * opens its list). The settings themselves are the TV app's (SettingsMap, the same view models), so both always agree.
 * Every page is a phone page: none of the TV app's Settings screens open from here.
 */
@Composable
internal fun MobileSettings(session: Session?, onSignOut: () -> Unit) {
    BackHandler { MobileSettingsNav.back() }
    val page = MobileSettingsNav.page
    val back = { MobileSettingsNav.page = null }
    Box(Modifier.fillMaxSize().background(MobileColors.Background)) {
        when (page) {
            null -> SettingsMenu(session, onSignOut)
            Pages.ACCOUNTS -> MobileAccounts(onClose = back)
            Pages.BACKUP -> MobileBackup(back)
            Pages.ABOUT -> io.github.petebutty.viewarr.ui.settings.AboutSettingsScreen(onBack = back)
            Pages.HA -> MobileHomeAssistant(back)
            Pages.PLUGINS -> MobilePlugins(back)
            Pages.SPORTS -> MobileSports(back)
            Pages.SEARCH -> MobileSearchSettings(back)
            else -> MappedPageMobile(page, back)
        }
    }
}

private class MenuRow(val icon: ImageVector, val title: String, val note: String? = null, val trailing: String? = null, val onTap: () -> Unit)

@Composable
private fun SettingsMenu(session: Session?, onSignOut: () -> Unit) {
    val guide: GuideViewModel = hiltViewModel()
    var confirmSignOut by remember { mutableStateOf(false) }
    var refreshed by remember { mutableStateOf(false) }
    val canAccounts = Access.can(Lock.ACCOUNTS)
    val canSettings = Access.can(Lock.SETTINGS)
    val canSignOut = Access.can(Lock.SIGNOUT)
    val dispatcharr = session != null && !session.isDirect
    val admin = dispatcharr && io.github.petebutty.viewarr.ui.profiles.isAdmin()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { HaStore.start(context) }
    val ha by HaStore.config.collectAsState()
    val sync: SyncViewModel = hiltViewModel()
    val syncState by sync.status.collectAsStateWithLifecycle()
    fun go(p: String) { MobileSettingsNav.page = p }
    val groups = listOf(
        "Manage" to listOfNotNull(
            if (canAccounts) MenuRow(AppIcons.Layers, "Accounts & sources", "Live TV, Plex, Jellyfin, Trakt, addons, music") { go(Pages.ACCOUNTS) } else null,
            MenuRow(AppIcons.Refresh, "Refresh channels & guide", if (refreshed) "Refreshing…" else null) { guide.refresh(); refreshed = true },
            if (canSignOut && dispatcharr) MenuRow(AppIcons.Swap, "Backup & sync", "Your setup saved on your Dispatcharr server", if (syncState.mode == SyncMode.On) "On" else "Off") { go(Pages.BACKUP) } else null,
            if (admin) MenuRow(AppIcons.Grid, "Dispatcharr plugins", "Plugins on your Dispatcharr server") { go(Pages.PLUGINS) } else null,
        ),
        "Preferences" to if (!canSettings) emptyList() else listOf(
            MenuRow(AppIcons.Search, "Search settings", "Find any setting by name") { go(Pages.SEARCH) },
            MenuRow(AppIcons.Style, "Appearance", "Text, dates, posters, title pages, ratings, subtitles") { go(SettingsMap.APPEARANCE) },
            MenuRow(AppIcons.LiveTv, "Live TV", "Channels, groups, the guide, Still watching?") { go(SettingsMap.LIVE) },
            MenuRow(AppIcons.Play, "Playback", "Players, audio, subtitles, streams, play order, episodes") { go(SettingsMap.PLAYBACK) },
            MenuRow(AppIcons.Bell, "Notifications", "What pops up by itself: new episodes, live games…") { go(SettingsMap.NAVIGATION) },
            MenuRow(AppIcons.Star, "Sports", "Your teams and leagues") { go(Pages.SPORTS) },
        ),
        "This device" to listOfNotNull(
            if (canSettings) MenuRow(AppIcons.Home, "Home Assistant", "What's playing, and pop-ups like a doorbell camera", when { !ha.ready -> "Not set up"; ha.sendPlaying || ha.popups -> "On"; else -> "Off" }) { go(Pages.HA) } else null,
            if (canSettings) MenuRow(AppIcons.Tune, "Troubleshooting", "Playback info on screen, Low-memory mode") { go(SettingsMap.SYSTEM) } else null,
            if (canSettings) MenuRow(AppIcons.Settings, "Advanced", "Best left alone unless asked to change something") { go(SettingsMap.ADVANCED) } else null,
            MenuRow(AppIcons.Info, "About", "Version, this device, storage, your Live TV sources") { go(Pages.ABOUT) },
            if (canSignOut) MenuRow(AppIcons.Back, "Sign out of everything") { confirmSignOut = true } else null,
        ),
    ).filter { it.second.isNotEmpty() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            BrandBar("Settings", onBack = { MobileSettingsNav.open = false })
            // Who you're signed in as, and where.
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(AppColors.Accent), contentAlignment = Alignment.Center) {
                    Text(session?.username?.take(1)?.uppercase() ?: "?", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(session?.username ?: "Not signed in", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    session?.serverUrl?.let { Text(it, color = AppColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        items(groups, key = { it.first }) { (name, rows) ->
            GroupTitle(name)
            PhoneCard {
                rows.forEachIndexed { i, r ->
                    if (i > 0) RowLine(indent = true)
                    PhoneRow(r.title, r.note, trailing = r.trailing, icon = r.icon, opens = r.title != "Sign out of everything" && !r.title.startsWith("Refresh"), onTap = r.onTap)
                }
                if (name == "Preferences") WatchlistRow(divider = true)
            }
        }
    }
    if (confirmSignOut) Chooser(
        title = "Sign out of everything?",
        note = "Previewarr Mobile forgets your server and every service on this device. Your setup stays saved on the server.",
        options = listOf("Sign out", "Cancel"), current = null,
        onPick = { i -> confirmSignOut = false; if (i == 0) { MobileSettingsNav.open = false; onSignOut() } },
        onDismiss = { confirmSignOut = false },
    )
}

/** One of SettingsMap's pages (Appearance, Live TV, Playback…), with its Reset to defaults where it has one. */
@Composable
private fun MappedPageMobile(pageId: String, onBack: () -> Unit) {
    val appearance: AppearanceViewModel = hiltViewModel()
    val playback: PlaybackSettingsViewModel = hiltViewModel()
    val opts: UiOptsViewModel = hiltViewModel()
    val news: NewsViewModel = hiltViewModel()
    val pool = settingsPool(appearance = appearance, playback = playback, opts = opts, sources = remember(pageId) { SettingsMap.sources(pageId) })
    val reset: (() -> Unit)? = when (pageId) {
        SettingsMap.APPEARANCE -> ({ appearanceReset(appearance, opts, playback, news.feeds.all().map { it.id }) })
        SettingsMap.PLAYBACK -> ({ playbackReset(playback, opts, appearance) })
        SettingsMap.ADVANCED -> ({ advancedReset(playback, opts) })
        else -> null
    }
    CategoriesPage(SettingsMap.page(pageId).title, SettingsMap.categories(pageId, pool), onBack, onReset = reset)
}

/**
 * A sheet of choices from the bottom of the screen (a setting's options, or a question): [current] ticked; a tap
 * picks; tapping outside closes it.
 */
@Composable
internal fun Chooser(title: String, note: String?, options: List<String>, current: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    // In a window of its own over the whole screen, wherever it's called from (a part of a page too). Its own dimming.
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color(0xFF1E1E1E))
                .clickable(enabled = false) {}.navigationBarsPadding().padding(top = 12.dp, bottom = 24.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(MobileColors.Line))
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp))
            note?.takeIf { it.isNotBlank() }?.let { Text(it, color = AppColors.TextSecondary, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp)) }
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(options.size) { i ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(i) }.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(options[i], color = if (i == current) AppColors.Accent else Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        if (i == current) Icon(AppIcons.Check, null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
    }
}
