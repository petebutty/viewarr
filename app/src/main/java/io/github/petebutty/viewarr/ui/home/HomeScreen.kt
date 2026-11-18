package io.github.petebutty.viewarr.ui.home

import io.github.petebutty.viewarr.ui.theme.appBackground

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.petebutty.viewarr.ui.common.glass
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import kotlinx.coroutines.flow.first
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import io.github.petebutty.viewarr.playback.PlaybackState
import io.github.petebutty.viewarr.playback.PlaybackViewModel
import io.github.petebutty.viewarr.ui.player.PlayerLayer
import io.github.petebutty.viewarr.ui.player.PlayerOsd
import io.github.petebutty.viewarr.ui.player.MultiView
import io.github.petebutty.viewarr.ui.player.BarEditorOpen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.EdgeBlocked
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.guide.GuideScreen
import io.github.petebutty.viewarr.ui.setup.SetupMode
import io.github.petebutty.viewarr.ui.setup.SetupWizard
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.ui.media.MediaScreen
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.dvr.DvrViewModel
import io.github.petebutty.viewarr.ui.dvr.RecordDialog
import io.github.petebutty.viewarr.ui.dvr.ProgramInfoDialog
import io.github.petebutty.viewarr.ui.dvr.RecordingsScreen
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.settings.LocalRemote
import io.github.petebutty.viewarr.data.settings.MediaBack
import io.github.petebutty.viewarr.data.settings.PlayerOk
import io.github.petebutty.viewarr.data.settings.ZapDirection
import io.github.petebutty.viewarr.data.settings.StartPage
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.animateIntAsState
import io.github.petebutty.viewarr.data.dvr.DvrState
import io.github.petebutty.viewarr.data.media.DeepLink
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.data.settings.BtnAction
import io.github.petebutty.viewarr.data.settings.BtnPress
import io.github.petebutty.viewarr.data.settings.BtnScreen
import io.github.petebutty.viewarr.data.settings.ButtonMap
import io.github.petebutty.viewarr.data.settings.RemoteSettings
import io.github.petebutty.viewarr.data.settings.GuideLeft
import io.github.petebutty.viewarr.data.settings.LiveSides
import io.github.petebutty.viewarr.playback.MusicPlayer
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.common.ButtonHint
import io.github.petebutty.viewarr.ui.common.LocalQuickSearchOpen
import io.github.petebutty.viewarr.ui.common.Press
import io.github.petebutty.viewarr.ui.common.RemoteAction
import io.github.petebutty.viewarr.ui.common.RemoteKeys
import io.github.petebutty.viewarr.ui.common.TapHold
import io.github.petebutty.viewarr.ui.dvr.SeasonPicker
import io.github.petebutty.viewarr.ui.guide.channelLogo
import io.github.petebutty.viewarr.ui.guide.displayTitle
import io.github.petebutty.viewarr.ui.guide.shiftLabel
import io.github.petebutty.viewarr.ui.media.FullTrailer
import io.github.petebutty.viewarr.ui.media.VodHost
import io.github.petebutty.viewarr.ui.media.VodSource
import io.github.petebutty.viewarr.ui.music.MusicScreen
import io.github.petebutty.viewarr.ui.music.MusicViewModel
import io.github.petebutty.viewarr.ui.player.CardOption
import io.github.petebutty.viewarr.ui.player.PlayerBridge
import io.github.petebutty.viewarr.ui.player.OsdPoster
import io.github.petebutty.viewarr.ui.common.InfoRows.Kind as RowKind
import io.github.petebutty.viewarr.ui.player.OsdRow
import io.github.petebutty.viewarr.ui.player.RowSets
import io.github.petebutty.viewarr.ui.player.TransparencyPanel
import io.github.petebutty.viewarr.ui.settings.RemoteViewModel
import io.github.petebutty.viewarr.ui.settings.SportsViewModel
import io.github.petebutty.viewarr.ui.theme.CornerPicture
import io.github.petebutty.viewarr.ui.theme.Dates
import io.github.petebutty.viewarr.ui.theme.UiOpts
import io.github.petebutty.viewarr.data.settings.GuideBack
import io.github.petebutty.viewarr.data.settings.GuideBackNow
import io.github.petebutty.viewarr.playback.SleepTimer
import io.github.petebutty.viewarr.ui.common.InfoRows
import io.github.petebutty.viewarr.ui.common.LiveBehind
import io.github.petebutty.viewarr.ui.common.StartupGate
import io.github.petebutty.viewarr.ui.dvr.ChoicePanel
import io.github.petebutty.viewarr.ui.guide.GuideShiftDialog
import io.github.petebutty.viewarr.ui.player.BarExtra
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.State
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

internal enum class Section(val label: String) {
    Tv("Live TV"),
    Movies("Movies"),
    Shows("Shows"),
    /** Every library on your servers (Plex, Jellyfin, Emby, IPTV) and your music, in one list. */
    Library("My Library"),
    Music("Music"),
    Recordings("Recordings"),
    Search("Search"),
    Settings("Settings"),
}


/** What fills the screen behind the menu. */
enum class Page { Tv, Movies, Shows, Music, Recordings, Search }

@Composable
fun HomeScreen(
    session: Session?,
    onSignOut: () -> Unit,
    vm: GuideViewModel = hiltViewModel(),
    playback: PlaybackViewModel = hiltViewModel(),
    dvr: DvrViewModel = hiltViewModel(),
) {
    // Everything the screen keeps track of (HomeState.kt).
    val hs = rememberHomeState()
    hs.vm = vm; hs.playback = playback; hs.dvr = dvr; hs.session = session
    // Recordings play in the same player as films (VodHost, above every page), so they can shrink to a corner too.
    val recordingFlow = remember { MutableStateFlow<VodStream?>(null) }
    val recordingSource = remember { VodSource(recordingFlow, MutableStateFlow(null), onClose = { recordingFlow.value = null }) }
    LaunchedEffect(hs.recordingPlayback) {
        hs.recordingPlayback?.let { recordingFlow.value = it; VodHost.show(recordingSource); hs.recordingPlayback = null }
    }
    // "Remind me": the programme starting now pops up in the corner; OK watches it, Back dismisses.
    LaunchedEffect(Unit) {
        dvr.pruneReminders()
        while (true) {
            val now = System.currentTimeMillis()
            // (Settings → Notifications → Remind me: when it starts, or up to 15 minutes before.)
            val lead = io.github.petebutty.viewarr.ui.common.Notify.reminderLeadMs
            val due = dvr.reminders.value.firstOrNull { it.start <= now + 30_000 + lead && it.end > now }
            if (due != null && hs.dueReminder?.id != due.id) {
                // (Notifications → Reminders off: it's let go quietly.)
                if (io.github.petebutty.viewarr.ui.common.Notify.reminders) hs.dueReminder = due
                dvr.forget(due.id)
            }
            delay(15_000)
        }
    }
    LaunchedEffect(hs.dueReminder) { if (hs.dueReminder != null) { delay(90_000); hs.dueReminder = null } }
    // Recordings: a small card when one starts, finishes or fails (by the schedule, so it's on time).
    LaunchedEffect(Unit) {
        val told = HashSet<String>()
        var first = true
        // The recordings arrive a moment after start: what's already there then isn't news (the first look used to see
        // an empty list, so every old failed recording popped up 15 s later, one after another).
        withTimeoutOrNull(60_000) { dvr.recordings.first { it.isNotEmpty() } }
        while (true) {
            val now = System.currentTimeMillis()
            val names = dvr.channels.value
            for (r in dvr.recordings.value) {
                val ch = names[r.channelId]?.name?.let { " · $it" }.orEmpty()
                val note = when {
                    r.state == DvrState.Failed && r.end > now - 12 * 3_600_000L -> "⚠  Recording failed: ${r.title}$ch"
                    r.end in (now - 120_000)..now -> "✓  Recorded: ${r.title}$ch"
                    r.start in (now - 120_000)..now && r.end > now -> "●  Recording now: ${r.title}$ch"
                    else -> null
                } ?: continue
                val id = "${r.id}:${note.take(3)}"
                if (told.add(id) && !first) { hs.dvrNote = note; delay(6_000); hs.dvrNote = null }
            }
            first = false // what was already true when the app opened isn't news
            delay(15_000)
        }
    }
    // Live TV: the channel's providers have no free connection (see PlaybackViewModel.checkConnections).
    val connectionNote by playback.connectionNote.collectAsStateWithLifecycle()
    LaunchedEffect(connectionNote) { if (connectionNote != null) { delay(12_000); playback.clearConnectionNote() } }
    val remote = LocalRemote.current
    hs.remote = remote
    // Movies & Shows switched off (live TV only): don't leave a hidden page on screen.
    LaunchedEffect(AppColors.palette.hideMedia) {
        if (AppColors.palette.hideMedia && (hs.page == Page.Movies || hs.page == Page.Shows)) hs.page = Page.Tv
    }
    val mediaFocus = remember { FocusRequester() }
    val musicVm: MusicViewModel = hiltViewModel()
    hs.musicVm = musicVm
    val sportsFocus = remember { FocusRequester() }
    // (A film as the mini player counts too: the pages make room for it the same way.)
    SideEffect { LiveBehind.preview = hs.livePreview || (VodHost.active && VodHost.mini) }
    // Movies / Shows opened from the menu over full-screen live TV: the channel carries on full screen behind the page.
    SideEffect { LiveBehind.on = hs.liveBehind }
    // Menu on full-screen live TV: a tap (on release) opens audio & subtitles; holding it is search.
    val liveMenuKey = remember { TapHold() }
    LaunchedEffect(hs.quickSearch) { if (!hs.quickSearch) hs.searchCatchup = false }
    // A film brought back full screen (held Back, handled in MainActivity): the menu or search under it closes,
    // or it would take the next Back presses.
    LaunchedEffect(VodHost.mini) {
        if (!VodHost.mini && VodHost.active) { hs.menuOpen = false; hs.quickSearch = false }
    }
    // However the menu closed (a page or film picked in it, not only Back), the next one opens the normal way: on the
    // page's own list (Left from the guide: the channel groups), not on the main list a held Back / film menu asked for.
    // (Every "open the menu on…" is for that one opening: Settings stayed set when a page closed the menu, so Left
    // from any page then opened the Settings list.)
    LaunchedEffect(hs.menuOpen) { if (!hs.menuOpen) { hs.menuOnMain = false; hs.menuOnLibrary = false; hs.menuOnSettings = false; hs.menuOnGroups = false } }
    val searchMedia: MediaViewModel = hiltViewModel(key = "media-search")
    val fromStartScope = rememberCoroutineScope()
    hs.fromStartScope = fromStartScope
    // Leaving full screen (guide, another page) ends multi-view; it mustn't reappear next time.
    LaunchedEffect(hs.fullscreen) { if (!hs.fullscreen) hs.multiView = false }
    val guideFocus = remember { FocusRequester() }
    val playerFocus = remember { FocusRequester() }
    val playing by playback.channel.collectAsStateWithLifecycle()
    // The guide group a channel was started from (browsing another group later doesn't change it).
    LaunchedEffect(playing?.id) { if (playing != null) hs.playingGroup = vm.groupId.value }
    // Settings → Live TV → "All channels" group hidden: the guide shows the first group instead of everything
    // (also after switching source, which starts on All channels).
    val groupsForAll by vm.groups.collectAsStateWithLifecycle()
    val groupForAll by vm.groupId.collectAsStateWithLifecycle()
    LaunchedEffect(AppColors.palette.hideAllChannels, groupForAll, groupsForAll.isNotEmpty()) {
        if (AppColors.palette.hideAllChannels && groupForAll == null) groupsForAll.firstOrNull { it.id >= 0 && it.name !in vm.menuHiddenGroups.value }?.let { vm.selectGroup(it.id) }
    }
    // The last favourite removed while on ★ Favourites: back to all channels (or the first group, as above).
    val favsNow by vm.favourites.collectAsStateWithLifecycle()
    LaunchedEffect(favsNow.isEmpty(), groupForAll) {
        if (favsNow.isEmpty() && groupForAll == GuideViewModel.FAVOURITES) vm.selectGroup(null)
    }
    // Back from full screen: the guide opens on the channel playing, at now, in its group (not wherever the guide was
    // last scrolled to, nor the group browsed since).
    LaunchedEffect(hs.fullscreen) {
        if (!hs.fullscreen && hs.page == Page.Tv) playing?.let { vm.returnTo(it.id, hs.playingGroup) }
        // Into full screen with the guide on another group (one picked from the menu): back to the playing channel's,
        // so Up / Down (and the channel list) step from it, not from the first channel of the group being looked at.
        if (hs.fullscreen) playing?.let { p -> if (vm.channels.value.none { it.id == p.id }) vm.returnTo(p.id, hs.playingGroup) }
    }
    // The guide only keeps programmes for the channels near where it's scrolled to. The channel you're watching
    // (and multi-view's) are looked up on their own, or the banner said "No guide information" once you'd scrolled
    // away from it in the guide.
    val ownScope = rememberCoroutineScope()
    hs.ownScope = ownScope
    val now by vm.now.collectAsStateWithLifecycle()

    // Held up while the Transparency panel is on its glass colour / info bar rows (the bar is what's being changed).
    LaunchedEffect(hs.bannerToken, hs.bannerHeld) {
        if (hs.bannerHeld) { hs.bannerVisible = true; return@LaunchedEffect }
        if (hs.bannerVisible) {
            delay(5_000)
            hs.bannerVisible = false
        }
    }
    // The bar gone (timed out, or anything else closed it) with a channel browsed to but not changed to: let it go.
    LaunchedEffect(hs.bannerVisible, hs.fullscreen) { if (!hs.bannerVisible || !hs.fullscreen) hs.cancelBrowse() }
    // The live controls close 8 s after they were last used.
    LaunchedEffect(hs.osdVisible) {
        if (!hs.osdVisible) return@LaunchedEffect
        while (true) {
            val left = 8_000 - (System.currentTimeMillis() - hs.osdUsedAt[0])
            // (Not while its Info bar buttons editor is open: that stays until you've finished. Peter, 2026-11-17.)
            if (left <= 0 && !BarEditorOpen.value) { hs.osdVisible = false; break }
            if (left <= 0) { delay(1_000); continue }
            delay(left)
        }
    }
    // Reopening where you left off if Android cleared Viewarr in the background, and saving that spot (ResumeSpot.kt).
    ResumeEffects(hs, remote, session, playing)
    LiveTvLockEffect(hs)
    LaunchedEffect(remote.loaded) {
        if (!remote.loaded || hs.startApplied) return@LaunchedEffect
        hs.startApplied = true
        val start = hs.startFor(remote.startPage)
        when (start) {
            StartPage.Guide -> Unit
            StartPage.Movies -> hs.page = Page.Movies
            StartPage.Shows -> hs.page = Page.Shows
            StartPage.Search -> hs.quickSearch = true
            StartPage.Recordings -> hs.page = Page.Recordings
            StartPage.LastChannel -> {
                val ch = remote.lastChannelId?.let { id -> vm.channelById(id) }
                if (ch != null && session != null) hs.watchChannel(ch)
            }
        }
    }
    LaunchedEffect(hs.sportsOpen, hs.menuOpen, hs.fullscreen, hs.osdVisible, hs.transparencyOpen, hs.showAccounts, hs.page, hs.recordTarget == null, hs.seasonPick == null, hs.multiView, VodHost.covering, VodHost.gliding, hs.infoTarget == null, hs.channelListOpen, hs.quickSearch, MusicPlayer.nowPlayingOpen) {
        if (hs.channelListOpen || hs.quickSearch || hs.transparencyOpen || MusicPlayer.nowPlayingOpen) return@LaunchedEffect
        if (hs.recordTarget != null || hs.seasonPick != null || VodHost.covering || hs.infoTarget != null) return@LaunchedEffect
        // A film gliding into the corner: the page takes the remote once it's there (its highlight starting mid-glide
        // made the glide stutter).
        if (VodHost.gliding) return@LaunchedEffect
        // Just landed in the corner: a moment before the page takes the remote, so its highlight and the player
        // letting go aren't drawn in the same frame (that frame was slow).
        if (VodHost.mini) delay(150)
        awaitFrame()
        runCatching {
            when {
                hs.fullscreen && !hs.osdVisible -> playerFocus.requestFocus()
                !hs.fullscreen && !hs.menuOpen && !hs.showAccounts && !hs.quickSearch -> if (hs.sportsOpen) sportsFocus.requestFocus() else if (hs.page == Page.Tv) guideFocus.requestFocus() else mediaFocus.requestFocus()
            }
        }
    }
    // A film or episode playing over Live TV (Watch from the start, search, a recording): the channel is stopped and
    // its player let go (it carried on underneath, sound and all). Picking a channel afterwards starts it afresh.
    val vodOver = VodHost.active
    LaunchedEffect(vodOver) {
        if (vodOver) { hs.fullscreen = false; hs.livePreview = false; playback.close() }
    }
    // …and a channel started while a film is in the corner ends the film (one stream, one sound).
    LaunchedEffect(playing?.id) { if (playing != null) { VodHost.close(); MusicPlayer.stop() } }
    // Music starting ends Live TV (as a film does).
    val musicOn = MusicPlayer.active
    LaunchedEffect(musicOn) { if (musicOn) { hs.fullscreen = false; hs.livePreview = false; playback.close() } }
    // A full-screen trailer (title page → Trailer) ends live TV too; it was silent with the channel still playing.
    val trailerOn = FullTrailer.active
    LaunchedEffect(trailerOn) { if (trailerOn) { hs.fullscreen = false; hs.livePreview = false; playback.close() } }
    // Back on the guide (or the channel stopped): nothing in the corner any more.
    LaunchedEffect(hs.page, playing == null) { if (hs.page == Page.Tv || playing == null) { hs.livePreview = false } }
    // A picture in the corner: a film in the mini player. (Live TV over another page plays in the guide's preview
    // place instead, since 0.26.0.)
    val pictureInCorner = VodHost.active && VodHost.mini
    // Settings → Playback → Still watching?: after hours without a button press on live TV, ask, then stop (a TV left
    // on otherwise holds a provider connection all night).
    val stillHours = AppColors.palette.stillWatchingHours
    LaunchedEffect(stillHours, playing?.id) {
        if (stillHours == 0) return@LaunchedEffect
        while (true) {
            delay(60_000)
            if (playing != null && !hs.stillAsk && System.currentTimeMillis() - hs.lastKeyAt > stillHours * 3_600_000L) hs.stillAsk = true
        }
    }
    LaunchedEffect(hs.stillAsk) {
        if (!hs.stillAsk) return@LaunchedEffect
        delay(60_000)
        if (hs.stillAsk) { hs.stillAsk = false; hs.fullscreen = false; playback.stop() }
    }
    // Back on a page (guide, Movies, Shows, Recordings) asks before leaving: a second Back within a few
    // seconds exits. The menu handles its own Back (list → main menu → closed).
    val activity = LocalContext.current as? Activity
    hs.activity = activity
    LaunchedEffect(hs.menuOpen) { if (!hs.menuOpen) { hs.menuFromGuideBack = false; hs.menuFromFullscreen = false; hs.menuFullPage = false; VodHost.behind = false } }
    // Left from the film controls' first button: the menu over the film, which plays on full screen underneath.
    SideEffect { VodHost.openMenu = { VodHost.behind = true; hs.menuOnMain = true; hs.menuOpen = true } }
    LaunchedEffect(hs.exitArmed) {
        if (hs.exitArmed != 0L) { delay(2_500); hs.exitArmed = 0L }
    }
    // Back returns to live TV first when browsing the past/future, then asks to exit (or opens the menu).
    val guideBack = ButtonMap.guideBack(remote)
    BackHandler(enabled = hs.page == Page.Tv && !hs.menuOpen && !hs.fullscreen && !hs.showAccounts && !hs.quickSearch) {
        when {
            // "Back jumps to now first" (on as standard): away from now, Back comes back to now, the next Back does the
            // rest (full screen with something playing; Peter, 2026-11-15). Off: it acts at once.
            !vm.isAtNow() && remote.guideBackNow == GuideBackNow.On -> vm.goToNow()
            // A channel playing in the guide's window: Back brings it full screen (Peter,
            // 2026-11-15; a film in the mini player likewise, at the root). Back there shrinks it here again; hold Back
            // closes it.
            playing != null -> { if (!vm.isAtNow()) vm.goToNow(); hs.fullscreen = true; hs.showBanner() }
            // "Press Back again to exit" is up: this Back exits.
            hs.exitArmed != 0L -> hs.backOnPage()
            // On now with nothing playing: Remote buttons → Back → In the TV guide (the menu, or ask to exit).
            guideBack == GuideBack.NowThenExit -> hs.backOnPage()
            else -> { hs.menuOpen = true; hs.menuFromGuideBack = true }
        }
    }
    BackHandler(enabled = hs.fullscreen && !hs.osdVisible) {
        hs.fullscreen = false
        hs.bannerVisible = false
    }

    LaunchedEffect(hs.digitsToken) {
        if (hs.digits.isEmpty() && hs.digitsMessage == null) return@LaunchedEffect
        delay(if (hs.digitsMessage != null) 3_000 else 1_800)
        // (A note that came up while a number was being typed: cleared, and the number still tuned, not left on screen.)
        if (hs.digitsMessage != null) { hs.digitsMessage = null; if (hs.digits.isNotEmpty()) hs.tuneTyped() } else hs.tuneTyped()
    }

    val moviesVm: MediaViewModel = hiltViewModel(key = "media-Movie")
    val showsVm: MediaViewModel = hiltViewModel(key = "media-Show")
    hs.moviesVm = moviesVm; hs.showsVm = showsVm
    // Into a title (a poster on a library or catalogue page) while the channel's in the corner: it stops (choosing one
    // is watching something else).
    // (Both collected first: a composable call on the right of || is only made while the left is false, so the second
    // collector came and went as titles opened and closed.)
    val movieDetail by moviesVm.detail.collectAsStateWithLifecycle()
    val showDetail by showsVm.detail.collectAsStateWithLifecycle()
    val titleOpen = movieDetail != null || showDetail != null
    LaunchedEffect(titleOpen) {
        // (The channel in the preview place stays: the title page makes room for it.)
        if (titleOpen && hs.liveBehind) { hs.liveBehind = false; playback.stop() }
    }
    // Live TV behind a page, Watch Next cards, the splash and viewarr:// links (HomeLinkEffects).
    HomeLinkEffects(hs)
    val movieMenuRows by moviesVm.menuRows.collectAsStateWithLifecycle()
    val showMenuRows by showsVm.menuRows.collectAsStateWithLifecycle()
    // Streaming services down the main menu (AIOMetadata's "Netflix · Popular", "Netflix · New"… rows), each with its
    // rows' names: its show rows then its film rows on one page.
    val menuServices = remember(movieMenuRows.rows, showMenuRows.rows) {
        val all = showMenuRows.rows + movieMenuRows.rows
        (RowSets.sets(showMenuRows.rows) + RowSets.sets(movieMenuRows.rows)).filter { it.startsWith("name:") }.distinct().map { key ->
            key.removePrefix("name:") to all.filter { RowSets.matches(key, it) }.map { it.id to RowSets.rowTitle(it) }.distinctBy { it.first }
        }
    }
    // The other viewarr:// controls (home automation): each does what the remote's own button would.
    HomeCommandEffects(hs)
    // (Low memory mode: no poster rows, so nothing loaded for them.)
    val postersOn = !AppColors.palette.lowMemory
    // The info bar's rows, Live now and Sports: loaded after the app starts (once connected and the start page is
    // up), not the first time the controls open, so they're ready then. One at a time, a few seconds apart, so a slow
    // box isn't asked for everything at once while you start using it. (Refreshed again as the controls open.)
    val ctxForPreload = LocalContext.current
    LaunchedEffect(session?.id) {
        if (session == null) return@LaunchedEffect
        snapshotFlow { StartupGate.ready }.first { it }
        val am = ctxForPreload.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val gap = if (am?.isLowRamDevice == true || AppColors.palette.lowMemory) 5_000L else 2_500L
        delay(3_000)
        for (stage in 1..5) { hs.preloadStage = stage; delay(gap) }
        // On for long enough to load, then off, so the controls closing later doesn't load it all again.
        delay(60_000)
        hs.preloadStage = 0
    }
    // 1: libraries and the pages' rows.
    val preloadRows = hs.preloadStage >= 1
    LaunchedEffect(hs.osdVisible, preloadRows) {
        if (!hs.osdVisible && !preloadRows) return@LaunchedEffect
        moviesVm.loadLibraries(MediaType.Movie); showsVm.loadLibraries(MediaType.Show)
        moviesVm.load(MediaType.Movie); showsVm.load(MediaType.Show)
    }
    // 2: Continue watching and Recently added (rows under the info bar).
    val preloadPosters = hs.preloadStage >= 2
    LaunchedEffect(hs.osdVisible, preloadPosters) {
        if ((!hs.osdVisible && !preloadPosters) || !postersOn) return@LaunchedEffect
        moviesVm.loadContinue(MediaType.Movie); showsVm.loadContinue(MediaType.Show)
        moviesVm.loadRecent(MediaType.Movie); showsVm.loadRecent(MediaType.Show)
    }
    val preloadEvents = hs.preloadStage >= 3
    val preloadExtras = hs.preloadStage >= 4
    // Live now and Sports (below): loaded while the live TV controls are up (or the film player's).
    val rowsWanted = hs.osdVisible || PlayerBridge.filmMenuOpen

    // ---- Sports hub (Settings → Sports): your teams and leagues and their games.
    val sportsVm: SportsViewModel = hiltViewModel()
    hs.sportsVm = sportsVm
    val sportFollows by sportsVm.follows.collectAsStateWithLifecycle()
    // (SportsHub.kt: the games, where they're on, and the cards and rows made from them.)
    // Watch (game page, hold-OK menu) closes the Sports page first: it stayed open over the game.
    val sessionRef = rememberUpdatedState(session)
    val sportsHub = remember {
        SportsHub(sportsVm, vm, dvr,
            // (The same as watching any channel: Live TV's page too. It only went full screen, leaving the page under
            // Sports, Shows, on top with the game behind it. Peter, 2026-11-17.)
            watch = { ch -> hs.sportsOpen = false; hs.osdVisible = false; hs.watchChannel(ch, s = sessionRef.value) },
            toast = { hs.toast(it) })
    }
    // Live now (3): the event channels' programmes plus your Sports schedule's live games, once at the start, then
    // every minute while the controls are up (and when the schedule changes).
    // (Also while the guide shows the Live now group.)
    val liveWanted = rowsWanted || groupForAll == GuideViewModel.LIVE_NOW
    LaunchedEffect(liveWanted, preloadEvents, sportsHub.version) {
        if (!liveWanted && !preloadEvents) return@LaunchedEffect
        while (true) {
            val (sched, known) = sportsHub.liveGames(sportFollows, dvr.channels.value.values.toList())
            runCatching { vm.liveEvents(sched, known) }
            if (!liveWanted) break
            delay(60_000)
        }
    }
    // (4)
    LaunchedEffect(rowsWanted, preloadExtras, sportFollows) {
        if (!rowsWanted && !preloadExtras) return@LaunchedEffect
        sportsHub.load(sportFollows)
    }
    // The Sports page: its games and channels carry on loading after you leave it (a quick look and back used to
    // stop it part-way, and the channels never came).
    LaunchedEffect(hs.sportsOpen, sportFollows) { if (hs.sportsOpen) fromStartScope.launch { sportsHub.load(sportFollows) } }

    // What the film and show players' buttons do (Settings, the guide, Movies / Shows / Recordings, minimise).
    SideEffect {
        PlayerBridge.openSettings = { hs.settingsFromFilm = true; hs.menuOnSettings = true; hs.menuOpen = true }
        PlayerBridge.openPage = { name -> hs.buttonRequest = when (name) { "Movies" -> BtnAction.Movies; "Shows" -> BtnAction.Shows; else -> BtnAction.Recordings } }
        PlayerBridge.openGuide = { hs.menuOpen = false; hs.quickSearch = false; if (hs.page != Page.Tv) hs.openPage(Page.Tv) else hs.fullscreen = false }
        // A film minimised (Back's last step): the guide behind, the menu open on Live TV (Peter's rule: the same
        // starting point every time, ready to browse; live TV's own Back goes to the guide instead).
        PlayerBridge.openMenuOnLiveTv = {
            hs.quickSearch = false
            if (hs.page != Page.Tv) hs.openPage(Page.Tv, closeMenu = false) else hs.fullscreen = false
            hs.menuOnSettings = false; hs.menuOnMain = true; hs.menuOpen = true
        }
    }
    // Settings opened from a film: closing it brings the film back full screen.
    LaunchedEffect(hs.menuOpen) {
        if (!hs.menuOpen && hs.settingsFromFilm) {
            hs.settingsFromFilm = false
            if (VodHost.active && VodHost.mini) VodHost.expand()
        }
    }
    val remoteVm: RemoteViewModel = hiltViewModel()
    hs.remoteVm = remoteVm
    SideEffect {
        // The search button in the film player: search over the page (the film goes to the corner first).
        VodHost.openSearch = { hs.menuOpen = false; hs.channelListOpen = false; hs.searchStart = null; hs.quickSearch = true }
        // Settings → Playback → After a film or episode → Back to live TV: the channel you were watching, full screen.
        VodHost.afterStop = { if (AppColors.palette.opt(UiOpts.AFTER_FILM) == 1) hs.goLive() }
    }
    // The rows under the info bar (Down from its buttons; Settings → Appearance → Live TV → Rows under the info bar):
    // built in their own function, as HomeScreen was over Android's size limit for optimising a function.
    val liveInfoRows = rememberInfoBarRows(hs)

    // ---- The corner picture, highlighted (Settings → Live TV → Highlight the corner picture): at the page's edge,
    // pressing towards it moves onto it; then Full screen or Close (Left / Right, OK).
    val cornerFocus = remember { FocusRequester() }
    // Set just before moving onto the picture on purpose: otherwise it can't take the highlight (when a page briefly
    // has nothing to highlight, e.g. opening a title, Android handed it to the picture, and it stuck there). A plain
    // holder, read when the focus system asks, not a state (it must count in the same moment it's set).
    val cornerArm = remember { booleanArrayOf(false) }
    val rootFocus = LocalFocusManager.current
    // A film's picture is drawn over everything (search and the TV guide too); live TV's only over another page, and
    // search covers it.
    val filmInCorner = VodHost.active && VodHost.mini
    val cornerReachable = pictureInCorner && (hs.page != Page.Tv || filmInCorner) &&
        !hs.menuOpen && !hs.showAccounts &&
        AppColors.palette.opt(UiOpts.CORNER_FOCUS) == 0
    LaunchedEffect(pictureInCorner) { if (!pictureInCorner) hs.cornerFocused = false }

    Box(
        Modifier
            .fillMaxSize()
            // Towards the corner picture with nowhere further to go on the page (the end of a row, the bottom row):
            // onto the picture. Every other move is the page's own, as before.
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !cornerReachable || hs.cornerFocused) return@onKeyEvent false
                val at = AppColors.palette.opt(UiOpts.MINI_CORNER) // 0 BR, 1 BL, 2 TR, 3 TL
                val dir = when (e.key) {
                    Key.DirectionRight -> if (at == 0 || at == 2) FocusDirection.Right else null
                    // (Top left: only Up reaches it. Left at a page's edge is the menu, everywhere; it went onto the
                    // picture with no way on, so Left on a film's page did nothing.)
                    Key.DirectionLeft -> if (at == 1) FocusDirection.Left else null
                    Key.DirectionDown -> if (at < 2) FocusDirection.Down else null
                    Key.DirectionUp -> if (at >= 2) FocusDirection.Up else null
                    else -> null
                } ?: return@onKeyEvent false
                // (A page that keeps the highlight inside itself, a title page or search, blocks the move at its edge;
                // that counts as handled, so it's asked about too.)
                EdgeBlocked.hit = false
                if (rootFocus.moveFocus(dir) && !EdgeBlocked.hit) return@onKeyEvent true
                cornerArm[0] = true
                EdgeBlocked.lettingOut { runCatching { cornerFocus.requestFocus() }.isSuccess }.also { if (!it) cornerArm[0] = false }
            }
            // Every button press first (HomeState.onRootKey: holds, reminders, the corner picture, the button table…).
            .onPreviewKeyEvent { e -> hs.onRootKey(e) },
    ) {
        // The menu over a page (as classic IPTV apps): the page and the preview pushed right beside the see-through strip,
        // sliding along together as it opens and grows (Left: the main list too).
        val guidePush = animateIntAsState(
            if (hs.menuOpen && !hs.fullscreen && !hs.liveBehind && !hs.menuFullPage && !VodHost.behind) hs.menuWidth else 0,
            tween(160, easing = FastOutSlowInEasing),
            label = "guidePush",
        )
        // (The menu over a full-screen film: only the menu shows, the page stays out of sight underneath.)
        PagesLayer(hs, cornerArm, cornerFocus, cornerReachable, guideFocus, guidePush, mediaFocus, sportsFocus, sportsHub)

        val channel = playing.takeIf { hs.page == Page.Tv || hs.liveBehind || hs.livePreview }
        // The live channel list: on its own over full-screen live TV, or as the menu strip's last column.
        val channelPanel: @Composable (Boolean, () -> Unit) -> Unit = { embedded, onLeft ->
            channel?.let { ch0 ->
                ChannelListOverlay(
                    vm = vm,
                    serverUrl = session?.serverUrl.orEmpty(),
                    current = ch0,
                    now = now,
                    onPick = { ch ->
                        hs.channelListOpen = false; hs.menuOpen = false
                        // Another channel, or the same number on another source (after switching source).
                        if (ch.id != ch0.id || ch.uuid != ch0.uuid) {
                            playback.play(ch, session)
                            vm.selectChannel(ch.id)
                        }
                        hs.showBanner()
                    },
                    onClose = { hs.channelListOpen = false },
                    onCatchup = { ch, prog ->
                        hs.channelListOpen = false; hs.menuOpen = false
                        playback.playCatchup(ch, prog, session)
                        vm.selectChannel(ch.id)
                        hs.showBanner()
                    },
                    onFuture = { ch, prog -> hs.channelListOpen = false; hs.menuOpen = false; hs.recordTarget = ch to prog },
                    onLeft = onLeft,
                    embedded = embedded,
                )
            }
        }
        LiveTvLayer(hs, channel, channelPanel, guidePush, playerFocus, liveMenuKey, liveInfoRows)

        // "Rewind?" after Left on full-screen live TV: gone after 4 s, or as soon as anything else happens.
        LaunchedEffect(hs.rewindAsk) { if (hs.rewindAsk != 0L) { delay(4_000); hs.rewindAsk = 0L } }
        LaunchedEffect(hs.fullscreen, hs.osdVisible, playing?.id, hs.page) { hs.rewindAsk = 0L }
        AnimatedVisibility(
            hs.rewindAsk != 0L && hs.fullscreen && hs.page == Page.Tv,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
            enter = EnterTransition.None, exit = ExitTransition.None,
        ) {
            Row(
                Modifier.glass(20.dp).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Replay, null, tint = AppColors.Accent, modifier = Modifier.size(22.dp))
                Text("Rewind?", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(start = 10.dp))
                Text("   Press ◀ again, or hold ◀", fontSize = 13.sp, color = AppColors.TextSecondary)
            }
        }
        HomeButtonHints(hs, remote, session != null, playing != null, guideBack)
        // A film or episode in the corner: Back brings it full screen, holding Back closes it. (Full screen's hint is
        // drawn over the player, in AppRoot.)
        if (VodHost.active && VodHost.mini && !hs.menuOpen && !hs.quickSearch) ButtonHint("film-mini", "Back: full screen  ·  Hold Back: close the player")
        // Guide and wallpaper dimming only show on the guide: the panel switches the view as you move to them.
        if (hs.transparencyOpen && hs.page == Page.Tv) TransparencyPanel(
            onClose = { hs.transparencyOpen = false; if (playing != null) hs.fullscreen = true },
            onRow = { r -> if (playing != null) hs.fullscreen = r == 0 },
            onInfoBar = { show -> hs.bannerHeld = show && playing != null; if (hs.bannerHeld) { hs.fullscreen = true; hs.showBanner() } },
        )
        // Going right into a page (a library, a service, a channel group's guide): the menu's glass slides back out to the
        // left with the page and the preview moving back, rather than vanishing while they move.
        if (!hs.menuOpen && guidePush.value > 0 && !hs.fullscreen && !hs.liveBehind) Box(
            Modifier.fillMaxHeight()
                .offset { IntOffset(guidePush.value - hs.menuWidth, 0) }
                .layout { m, c -> val pl = m.measure(Constraints.fixed(hs.menuWidth, c.maxHeight)); layout(hs.menuWidth, c.maxHeight) { pl.place(0, 0) } }
                .background(AppColors.MenuPanel),
        )
        MenuLayer(hs, channel, channelPanel, guidePush, menuServices)

        SearchLayer(hs)

        PagesTicker(hs)
        DialogsLayer(hs, cornerArm, cornerFocus, cornerReachable, filmInCorner, pictureInCorner, guideFocus, mediaFocus, searchMedia, onSignOut)
        hs.guideShiftFor?.let { GuideShiftDialog(it, hs.vm, { m -> hs.toast(m) }) { hs.guideShiftFor = null } }
        if (hs.guideTimelineOpen) io.github.petebutty.viewarr.ui.guide.GuideTimelineDialog { hs.guideTimelineOpen = false }
        GamePageHost(sportsHub, besidePreview = (playing != null && !hs.fullscreen) || (VodHost.active && VodHost.mini))
    }
}

/**
 * What the buttons do here (Settings → Appearance → Menu → Button hints), following the button settings. Split out of
 * HomeScreen (its body is at Android's size limit). (UI review, 2026-11-17: "· Hold" with a capital, "‹ menu", hold
 * Back's action in its own case, and the guide's hold OK.)
 */
@Composable
private fun HomeButtonHints(hs: HomeState, remote: RemoteSettings, signedIn: Boolean, playing: Boolean, guideBack: GuideBack) {
    if (hs.menuOpen || hs.quickSearch || hs.showAccounts || hs.recordTarget != null || hs.infoTarget != null || VodHost.active) return
    when {
        hs.page == Page.Tv && hs.fullscreen && !hs.osdVisible && !hs.channelListOpen && !hs.multiView -> ButtonHint(
            "live-back", "OK: controls  ·  ▲ ▼ channels  ·  Back: mini player  ·  Hold Back: close the player")
        hs.page == Page.Tv && !hs.fullscreen && signedIn -> ButtonHint(
            if (playing) "guide-back" else "guide", "OK: watch  ·  Hold OK: more  ·  " + (if (remote.guideLeft == GuideLeft.NowMenu) "‹ menu  ·  Hold ‹ catch-up" else "‹ catch-up") + "  ·  Back: " + (if (playing) "full screen  ·  Hold Back: close the player" else if (guideBack == GuideBack.NowThenExit) "exit" else "menu"))
        hs.page == Page.Movies || hs.page == Page.Shows -> ButtonHint(
            "media", "OK: open  ·  Hold OK: options  ·  ‹ menu  ·  Hold Back: " + ButtonMap.action(remote, BtnScreen.Pages, BtnPress.BackHold).label)
        else -> Unit
    }
}

@Composable
internal fun NoLiveTv(focusRequester: FocusRequester, focusEnabled: Boolean, onSetUp: () -> Unit, onOpenMenu: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Live TV isn't set up", fontSize = 26.sp, color = AppColors.TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text("Connect your Dispatcharr server to watch channels and use the TV guide.", fontSize = 15.sp, color = AppColors.TextSecondary)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(220.dp).focusRequester(focusRequester)) {
                if (focusEnabled) MenuItem(text = "Set up Live TV", selected = false, onClick = onSetUp)
            }
            Box(Modifier.width(160.dp)) { MenuItem(text = "Menu", selected = false, onClick = onOpenMenu) }
        }
    }
}

/** Split out of HomeScreen (its body was over Android's size limit for optimising a function). */
@Composable
private fun BoxScope.LiveTvLayer(
    hs: HomeState,
    channel: ChannelListItem?,
    channelPanel: @Composable (Boolean, () -> Unit) -> Unit,
    guidePush: State<Int>,
    playerFocus: FocusRequester,
    liveMenuKey: TapHold,
    liveInfoRows: List<OsdRow>,
) {
    // (Read here as HomeScreen reads them, so this part redraws on its own when they change.)
    val vm = hs.vm; val playback = hs.playback; val session = hs.session; val remote = hs.remote
    val playing by playback.channel.collectAsStateWithLifecycle()
    val playState by playback.state.collectAsStateWithLifecycle()
    val catchup by playback.catchup.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val favsNow by vm.favourites.collectAsStateWithLifecycle()
    // Live TV into the guide's preview and back: a glide (the window's real place and size; the picture is a
    // SurfaceView, scaled by the box's display hardware). Settings → Playback → Picture-in-picture → Live TV animation.
    val liveShrink = animateFloatAsState(
        if (hs.fullscreen || hs.liveBehind) 0f else 1f,
        tween(300, easing = FastOutSlowInEasing),
        label = "live",
    )
    // (Read as a yes / no, so the glide doesn't redraw everything every frame.)
    val grown by remember { derivedStateOf { liveShrink.value < 0.01f } }
    val list = channel?.let { hs.programsOf(it) }.orEmpty()
    val current = catchup ?: GuideViewModel.programAt(list, now)
    if (channel != null && !(hs.multiView && hs.fullscreen)) {
        val next = if (catchup != null) null else current?.let { c -> list.firstOrNull { it.startTime >= c.endTime } }
        val browsed = hs.browseChannel
        val browsedList = browsed?.let { hs.programsOf(it) }.orEmpty()
        val browsedNow = browsed?.let { GuideViewModel.programAt(browsedList, now) }
        PlayerLayer(
            vm = playback,
            fullscreen = hs.fullscreen,
            browse = browsed?.let { Triple(it, browsedNow, browsedNow?.let { c -> browsedList.firstOrNull { p -> p.startTime >= c.endTime } }) },
            browseKnown = browsed?.let(hs::guideKnown) ?: true,
            // (Only once the picture has finished growing to full screen: opening a channel from the guide showed the
            // info bar during the glide, so it was seen resizing with the picture.)
            bannerVisible = hs.bannerVisible && !hs.osdVisible && !hs.liveBehind && !hs.livePreview && grown,
            current = current,
            next = next,
            now = now,
            serverUrl = session?.serverUrl.orEmpty(),
            // Read in the layout and draw steps only, so the glide doesn't rebuild the screen every frame.
            // (A library over live TV: the picture drawn first, under the page, so the see-through page is on top.)
            modifier = Modifier.zIndex(if (hs.liveBehind) -1f else 0f).fillMaxSize()
                .layout { measurable, constraints ->
                    val p = liveShrink.value
                    val fw = constraints.maxWidth
                    val fh = constraints.maxHeight
                    // The small window: the guide's preview place (320 x 180, top left), and in between on the way.
                    val sw = 320.dp.roundToPx()
                    val sh = 180.dp.roundToPx()
                    val sx = 12.dp.roundToPx()
                    val sy = 20.dp.roundToPx()
                    val cw = (fw + (sw - fw) * p).toInt().coerceAtLeast(1)
                    val ch = (fh + (sh - fh) * p).toInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(cw, ch))
                    layout(fw, fh) { placeable.place((sx * p).toInt() + (guidePush.value * p).toInt(), (sy * p).toInt()) }
                }
                .graphicsLayer { clip = liveShrink.value > 0.99f; shape = RoundedCornerShape(8.dp) },
        )
    }

    if (hs.fullscreen && hs.multiView && channel != null) {
        MultiView(
            vm = playback,
            programFor = { ch -> GuideViewModel.programAt(hs.programsOf(ch), now) },
            now = now,
            onExit = { ch ->
                hs.multiView = false
                if (ch.id != playback.channel.value?.id) {
                    playback.play(ch, session)
                    vm.selectChannel(ch.id)
                }
                hs.showBanner()
            },
            channelPicker = { cur, onPick, onClose ->
                Box(Modifier.fillMaxSize()) {
                    ChannelListOverlay(
                        vm = vm,
                        serverUrl = session?.serverUrl.orEmpty(),
                        current = cur,
                        now = now,
                        onPick = onPick,
                        onClose = onClose,
                        // (Left closes the picker: it was swallowed, nothing happening.)
                        onLeft = onClose,
                        // A programme from the list's programme panel: that channel, live.
                        onCatchup = { ch, _ -> onPick(ch) },
                    )
                }
            },
        )
    } else if (hs.fullscreen) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(playerFocus)
                .focusable(enabled = !hs.osdVisible && !hs.channelListOpen && !hs.transparencyOpen)
                .onPreviewKeyEvent { e ->
                    if (hs.osdVisible || hs.channelListOpen) return@onPreviewKeyEvent false
                    // Menu: a tap opens audio & subtitles when it's let go. Holding it is search (handled above),
                    // so acting on the press opened audio as well.
                    val action = RemoteKeys.action(e)
                    if (action == RemoteAction.Menu) {
                        if (liveMenuKey.feed(e) == Press.Tap) {
                            if (playState is PlaybackState.Error) playback.retry() else hs.showOsd(audio = true)
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (e.type != KeyEventType.KeyDown) {
                        if (action == RemoteAction.Left) hs.rewindHeld = false
                        return@onPreviewKeyEvent false
                    }
                    if (action != RemoteAction.Left) hs.rewindAsk = 0L
                    // Settings → Remote buttons → Live TV → Left / Right, as classic IPTV apps: Left the channel list, Right
                    // back to the channel watched before (catch-up still moves through the programme).
                    if (remote.liveSides == LiveSides.ListAndLast && catchup == null && !hs.multiView) {
                        if (action == RemoteAction.Left) {
                            if (e.nativeKeyEvent.repeatCount == 0) hs.channelListOpen = true
                            return@onPreviewKeyEvent true
                        }
                        if (action == RemoteAction.Right) {
                            if (e.nativeKeyEvent.repeatCount == 0) hs.lastChannel()
                            return@onPreviewKeyEvent true
                        }
                    }
                    when (action) {
                        // Live: ask first. In catch-up (already behind live) Left steps back straight away.
                        RemoteAction.Left -> if (catchup == null) {
                            val ch = playing
                            val again = hs.rewindAsk != 0L && System.currentTimeMillis() - hs.rewindAsk < 4_000
                            when {
                                hs.rewindHeld -> Unit
                                ch == null || ch.catchupDays <= 0 -> { hs.toast("This channel has no catch-up to rewind") }
                                again || e.nativeKeyEvent.repeatCount > 0 -> {
                                    hs.rewindAsk = 0L
                                    hs.rewindHeld = e.nativeKeyEvent.repeatCount > 0
                                    playback.seekBy(-remote.seek.ms)
                                    hs.showBanner()
                                }
                                else -> hs.rewindAsk = System.currentTimeMillis()
                            }
                        } else {
                            playback.seekBy(-remote.seek.ms)
                            hs.showBanner()
                        }
                        RemoteAction.Up, RemoteAction.Down -> when {
                            // Browsing in the info bar (Settings → Live TV → Up / Down while watching) wins over the list.
                            AppColors.palette.opt(UiOpts.LIVE_UPDOWN) != 0 ->
                                hs.upDown(if ((action == RemoteAction.Up) != (remote.zap == ZapDirection.UpPrevious)) 1 else -1)
                            remote.zap == ZapDirection.ChannelList -> hs.channelListOpen = true
                            remote.zap == ZapDirection.UpNext -> hs.zap(if (action == RemoteAction.Up) 1 else -1)
                            else -> hs.zap(if (action == RemoteAction.Up) -1 else 1)
                        }
                        RemoteAction.ChannelDown -> hs.upDown(-1)
                        RemoteAction.ChannelUp -> hs.upDown(1)
                        RemoteAction.Ok -> when {
                            playState is PlaybackState.Error -> playback.retry()
                            remote.playerOk == PlayerOk.Banner -> hs.showBanner()
                            remote.playerOk == PlayerOk.ChannelList && !hs.multiView -> hs.channelListOpen = true
                            else -> hs.showOsd()
                        }
                        RemoteAction.PlayPause -> {
                            playback.togglePause()
                            hs.showBanner()
                        }
                        // The remote's own rewind button means it: no question.
                        RemoteAction.Rewind -> {
                            hs.rewindAsk = 0L
                            playback.seekBy(-remote.seek.ms)
                            hs.showBanner()
                        }
                        RemoteAction.Right, RemoteAction.FastForward -> {
                            playback.seekBy(remote.seek.ms)
                            hs.showBanner()
                        }
                        RemoteAction.Info -> hs.showBanner()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        )
        // The channel list on its own; Left opens the menu strip beside it (groups, then the main list).
        if (hs.channelListOpen && channel != null && !hs.menuOpen) Box(Modifier.fillMaxSize()) {
            channelPanel(false) { hs.menuOpen = true; hs.menuOnMain = false }
        }
        if (hs.osdVisible && channel != null) {
            PlayerOsd(
                vm = playback,
                channel = channel,
                current = current,
                now = now,
                serverUrl = session?.serverUrl.orEmpty(),
                onGuide = {
                    hs.osdVisible = false
                    hs.fullscreen = false
                },
                onClose = { hs.osdVisible = false },
                onActivity = { hs.osdUsedAt[0] = System.currentTimeMillis() },
                // Settings over live TV: the menu opens on Settings; Back closes it, back to the channel.
                onSettings = { hs.osdVisible = false; hs.menuOnSettings = true; hs.menuOpen = true },
                onTransparency = { hs.osdVisible = false; hs.transparencyOpen = true },
                onSearch = { hs.osdVisible = false; hs.doButton(BtnScreen.Live, BtnAction.Search) },
                // Extra buttons (once added to the bar: hold OK on a button → OK on one marked "not added").
                extraButtons = listOf(
                    BarExtra("favourite",
                        if (channel.uuid in favsNow) AppIcons.Star else AppIcons.StarOutline,
                        if (channel.uuid in favsNow) "Remove from favourites" else "Add to favourites") {
                        hs.toast(if (vm.toggleFavourite(channel)) "${channel.name} added to favourites" else "${channel.name} removed from favourites")
                    },
                    // Refresh guide: Dispatcharr's (or the provider's) TV guide fetched again now, as viewarr://refresh?guide=1.
                    BarExtra("refreshguide", AppIcons.Refresh, "Refresh guide") {
                        hs.toast("Updating the TV guide…")
                        hs.fromStartScope.launch { vm.refreshGuide(); hs.toast("TV guide updated") }
                    },
                    BarExtra("lastchannel", AppIcons.History, "Last channel") { hs.osdVisible = false; hs.doButton(BtnScreen.Live, BtnAction.LastChannel) },
                    BarExtra("movies", AppIcons.Movie, "Movies") { hs.osdVisible = false; hs.doButton(BtnScreen.Live, BtnAction.Movies) },
                    BarExtra("shows", AppIcons.Tv, "Shows") { hs.osdVisible = false; hs.doButton(BtnScreen.Live, BtnAction.Shows) },
                    BarExtra("recordings", AppIcons.Recordings, "Recordings") { hs.osdVisible = false; hs.doButton(BtnScreen.Live, BtnAction.Recordings) },
                ),
                // (Settings → Appearance → Live TV → Rows under the info bar.)
                downRows = liveInfoRows,
                onStop = { hs.doButton(BtnScreen.Live, BtnAction.Stop) },
                // Low-memory mode: no multi-view (up to four players at once).
                onMultiView = if (!AppColors.palette.heavyExtras) null else ({
                    hs.osdVisible = false
                    if (catchup != null) playback.goLive()
                    hs.multiView = true
                }),
                openAudio = hs.osdAudio,
                // Recording: only for people allowed to record (Dispatcharr dvr_access; admins always).
                onRecord = current?.takeIf { catchup == null && Access.record }?.let { prog ->
                    {
                        hs.osdVisible = false
                        hs.recordTarget = channel to prog
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Split out of HomeScreen (its body was over Android's size limit for optimising a function). */
@Composable
private fun BoxScope.DialogsLayer(
    hs: HomeState,
    cornerArm: BooleanArray,
    cornerFocus: FocusRequester,
    cornerReachable: Boolean,
    filmInCorner: Boolean,
    pictureInCorner: Boolean,
    guideFocus: FocusRequester,
    mediaFocus: FocusRequester,
    searchMedia: MediaViewModel,
    onSignOut: () -> Unit,
) {
    // (Read here as HomeScreen reads them, so this part redraws on its own when they change.)
    val vm = hs.vm; val playback = hs.playback; val session = hs.session; val dvr = hs.dvr; val fromStartScope = hs.fromStartScope
    val connectionNote by playback.connectionNote.collectAsStateWithLifecycle()
    val dvrRecordings by dvr.recordings.collectAsStateWithLifecycle()
    // The picture in the corner: its highlight (Full screen / Close) (CornerPicture.kt).
    // A film playing at all (not only while it's in the mini player): a held Back can bring it full screen just before
    // the pop-up's answer, and live TV then started in its place.
    fun cornerFull() { if (VodHost.active) VodHost.expand() else hs.goLive() }
    fun cornerClose() { if (VodHost.active) VodHost.close() else playback.stop() }
    if (pictureInCorner && (hs.page != Page.Tv || filmInCorner)) CornerHighlight(
        film = filmInCorner, focusRequester = cornerFocus, arm = cornerArm, reachable = cornerReachable, focused = hs.cornerFocused,
        onFocused = { hs.cornerFocused = it }, onFullScreen = ::cornerFull, onClose = ::cornerClose,
        // (Nothing above it to land on: the page's own starting point, or search's.)
        onLeave = { runCatching { if (hs.page == Page.Tv) guideFocus.requestFocus() else mediaFocus.requestFocus() } },
    )

    hs.infoTarget?.let { (ch, prog) ->
        ProgramInfoDialog(ch, prog, dvr, onRecord = { hs.infoTarget = null; hs.recordTarget = ch to prog }, onClose = { hs.infoTarget = null })
    }
    // Recording started / finished / failed: bottom-right, a few seconds, no buttons.
    hs.dvrNote?.let { text ->
        Text(
            text, fontSize = 15.sp, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp).width(420.dp)
                .glass(10.dp, if (text.startsWith("⚠")) Color(0xFFE5534B) else null)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    // No free provider connection for the channel just tuned: say what's using them.
    if (hs.page == Page.Tv) connectionNote?.let { text ->
        val recordingNow = dvrRecordings.firstOrNull { it.state == DvrState.Recording }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 28.dp).width(640.dp)
                .glass(10.dp, Color(0xFFE5A23B))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text("All connections in use", fontSize = 13.sp, color = Color(0xFFE5A23B))
            Text(text, fontSize = 15.sp, color = AppColors.TextPrimary)
            recordingNow?.let { Text("Recording now: ${it.title}", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
    hs.dueReminder?.let { r ->
        Column(
            Modifier.align(Alignment.TopEnd).padding(28.dp).width(380.dp)
                .glass(12.dp)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Text("⏰  Starting now", fontSize = 13.sp, color = AppColors.Accent)
            Text(r.title, fontSize = 18.sp, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(r.channelName, fontSize = 14.sp, color = AppColors.TextSecondary)
            Text("OK: watch  ·  Back: dismiss", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        }
    }
    // Search opens on the title (so there's something to pick if it isn't matched), then the title's page opens:
    // playing it from the start, or (Go to show) on its episodes where you're up to.
    fun openTitle(prog: GuideProgram, play: Boolean) {
        hs.searchStart = prog.title.removePrefix("New: ")
        hs.fullscreen = false
        hs.quickSearch = true
        // Films in the guide usually end their description with the year, e.g. "(2014)".
        val year = prog.description?.let { Regex("\\((19|20)\\d{2}\\)").findAll(it).lastOrNull()?.value?.trim('(', ')')?.toIntOrNull() }
        fromStartScope.launch {
            if (play) searchMedia.watchFromStart(prog.title, prog.season, prog.episode, year)
            else searchMedia.goToTitle(prog.title, prog.season, prog.episode, year)
        }
    }
    hs.seasonPick?.let { m -> SeasonPicker(m, onClose = { hs.seasonPick = null }) }
    if (hs.askSignOut) ChoicePanel(
        title = "Sign out of everything?",
        subtitle = "This TV forgets all your accounts and Live TV sources (they stay on your servers). You'll need to set Viewarr up again.",
        choices = listOf(
            "Cancel" to { hs.askSignOut = false },
            "Sign out" to { hs.askSignOut = false; playback.stop(); onSignOut() },
        ),
        message = null,
        onClose = { hs.askSignOut = false },
    )
    if (hs.seasonPick == null) hs.recordTarget?.let { (ch, prog) ->
        RecordDialog(ch, prog, dvr, onDetails = { hs.recordTarget = null; hs.infoTarget = ch to prog },
            favourite = (if (ch.uuid in vm.favourites.value) "★  Remove from favourites" else "★  Add to favourites") to {
                hs.recordTarget = null
                hs.toast(if (vm.toggleFavourite(ch)) "${ch.name} added to favourites" else "${ch.name} removed from favourites")
            },
            extra = listOf(
                "🕒  EPG offset${vm.channelShiftMin(ch).takeIf { it != 0 }?.let { ": " + shiftLabel(it) } ?: "…"}" to { hs.recordTarget = null; hs.guideShiftFor = ch },
                "↔  Timeline: ${io.github.petebutty.viewarr.ui.theme.UiOpts.choices.getValue(io.github.petebutty.viewarr.ui.theme.UiOpts.GUIDE_SPAN).getOrNull(AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.GUIDE_SPAN)) ?: ""}" to { hs.recordTarget = null; hs.guideTimelineOpen = true },
            ),
            onChooseSeasons = { m -> hs.recordTarget = null; hs.seasonPick = m },
            onWatchFromStart = {
                hs.recordTarget = null
                val t = System.currentTimeMillis()
                // Catch-up first: the channel's own replay of this very showing.
                if (ch.catchupDays > 0 && prog.startTime < t && prog.startTime >= t - ch.catchupDays * 86_400_000L) {
                    playback.playCatchup(ch, prog, session)
                    vm.selectChannel(ch.id)
                } else openTitle(prog, play = true)
            },
            onGoTo = { hs.recordTarget = null; openTitle(prog, play = false) },
            // Only when some channel has catch-up (nothing to find otherwise).
            onSearchCatchup = if (vm.channels.value.none { it.catchupDays > 0 }) null else ({
                hs.recordTarget = null
                hs.searchStart = prog.title
                hs.searchVoice = false
                hs.searchCatchup = true
                hs.quickSearch = true
            }),
        ) { hs.recordTarget = null }
    }

    if (hs.stillAsk) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).glass(14.dp).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Still watching?", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Text("Press any button to carry on. Live TV stops in a minute otherwise.", fontSize = 15.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
        }
    }

    if (hs.exitArmed != 0L && !hs.menuOpen) {
        Text(
            "Press Back again to exit",
            fontSize = 18.sp,
            color = AppColors.TextPrimary,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .glass(24.dp)
                .padding(horizontal = 26.dp, vertical = 12.dp),
        )
    }

    (hs.digits.takeIf { it.isNotEmpty() } ?: hs.digitsMessage)?.let { text ->
        Text(
            text,
            fontSize = if (hs.digits.isNotEmpty()) 44.sp else 22.sp,
            color = AppColors.TextPrimary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = 40.dp)
                .glass(10.dp)
                .padding(horizontal = 22.dp, vertical = 8.dp),
        )
    }
}

/** Split out of HomeScreen (its body was over Android's size limit for optimising a function). */
@Composable
private fun BoxScope.SearchLayer(hs: HomeState) {
    // (Read here as HomeScreen reads them, so this part redraws on its own when they change.)
    val vm = hs.vm; val session = hs.session; val musicVm = hs.musicVm
    if (hs.showAccounts) {
        // Back from Accounts & sources (opened from Settings): back on Settings in the menu, like the other settings pages.
        SetupWizard(mode = SetupMode.Manage, onClose = {
            hs.showAccounts = false
            if (hs.accountsFromMenu) { hs.accountsFromMenu = false; hs.menuOnSettings = true; hs.menuOpen = true }
        })
    }

    // Search popped up from any screen: the same search page, on top; Back closes it.
    if (hs.quickSearch) {
        BackHandler { hs.quickSearch = false; hs.searchStart = null }
        // Solid: the screen underneath showing through between results (guide text, times) looked like a glitch.
        // The remote stays inside search until it closes.
        Box(Modifier.fillMaxSize().appBackground().then(if (hs.infoTarget == null && hs.recordTarget == null) Modifier.trapFocus() else Modifier)) {
            SearchClosesAfterTitle { hs.quickSearch = false; hs.searchStart = null; hs.searchCatchup = false }
            SearchPage(
                guide = vm,
                serverUrl = session?.serverUrl.orEmpty(),
                // (Not switched off while the corner picture has the highlight: search's box, results and buttons
                // would all stop being reachable, and coming back off the picture had nowhere to land. It never
                // pulls the highlight back on its own, so it needn't stand aside.)
                focusEnabled = hs.infoTarget == null && hs.recordTarget == null && hs.seasonPick == null,
                openKeyboard = hs.searchStart == null && !hs.searchVoice,
                startQuery = hs.searchStart,
                startVoice = hs.searchVoice,
                startCatchup = hs.searchCatchup,
                onProgramme = { ch, prog -> hs.recordTarget = ch to prog },
                onCatchup = { ch, prog ->
                    hs.quickSearch = false
                    hs.searchStart = null
                    hs.watchChannel(ch, catchup = prog, endMultiView = true)
                },
                // Left at the edge stays in search (it used to close it and open the menu); Back is the way out.
                onOpenMenu = {},
                onExit = { hs.quickSearch = false; hs.searchStart = null; hs.searchCatchup = false },
                onMusic = { card -> hs.quickSearch = false; hs.searchStart = null; musicVm.openFromSearch(card); hs.openPage(Page.Music) },
                onChannel = { ch ->
                    hs.quickSearch = false
                    hs.watchChannel(ch, endMultiView = true)
                },
            )
        }
    }
}

/** Split out of HomeScreen (its body was over Android's size limit for optimising a function). */
@Composable
private fun BoxScope.MenuLayer(
    hs: HomeState,
    channel: ChannelListItem?,
    channelPanel: @Composable (Boolean, () -> Unit) -> Unit,
    guidePush: State<Int>,
    menuServices: List<Pair<String, List<Pair<String, String>>>>,
) {
    // (Read here as HomeScreen reads them, so this part redraws on its own when they change.)
    val vm = hs.vm; val session = hs.session; val moviesVm = hs.moviesVm; val showsVm = hs.showsVm
    val playing by hs.playback.channel.collectAsStateWithLifecycle()
    if (hs.menuOpen) Box(Modifier.fillMaxSize().offset { IntOffset(if (!hs.fullscreen && !hs.liveBehind && !hs.menuFullPage && !VodHost.behind) guidePush.value - hs.menuWidth else 0, 0) }) {
        MenuOverlay(
            onPlayRecording = { hs.menuOpen = false; hs.recordingPlayback = it },
            // Glass only over full-screen video (live TV, a film); over a page it takes the page's own background.
            seeThrough = hs.fullscreen || hs.liveBehind || VodHost.behind || filmFullScreen(),
            vm = vm,
            session = session,
            page = hs.page,
            onOpenPage = hs::openPage,
            onAccounts = {
                hs.menuOpen = false
                hs.showAccounts = true
                hs.accountsFromMenu = true
            },
            onSettings = hs.menuOnSettings,
            onMain = hs.menuOnMain,
            onGroups = hs.menuOnGroups,
            onLibrary = hs.menuOnLibrary,
            onSearchFor = { q -> hs.searchStart = q; hs.searchVoice = false; hs.openPage(Page.Search) },
            onVoiceSearch = { hs.searchStart = null; hs.searchVoice = true; hs.openPage(Page.Search) },
            onClose = { hs.menuClosed() }, // (back to full screen when the menu came from there: HomeState)
            backOut = hs.menuFromGuideBack,
            onBackOut = {
                hs.menuOpen = false; hs.menuOnSettings = false; hs.menuOnMain = false
                // (The guide, and Music / Recordings / Search whose Back opened the menu: Back again exits.)
                if (!hs.fullscreen && hs.page in setOf(Page.Tv, Page.Music, Page.Recordings, Page.Search)) hs.exitArmed = System.currentTimeMillis()
            },
            // An "are you sure?" first (it signs out of every account and source on this TV).
            onSignOut = { hs.menuOpen = false; hs.askSignOut = true },
            channelsPanel = if (hs.fullscreen && hs.channelListOpen && channel != null) ({ onLeft -> channelPanel(true, onLeft) }) else null,
            // A group picked: back to its channels, the channel list on its own.
            onGroupPicked = { hs.menuOpen = false; hs.menuOnMain = false },
            onWidth = { hs.menuWidth = it },
            // (A film in the mini player counts too: it's drawn over everything, so a settings page must leave its place.)
            previewOn = (playing != null && !hs.fullscreen && !hs.liveBehind && (hs.page == Page.Tv || hs.livePreview)) || (VodHost.active && VodHost.mini),
            onFullPage = { hs.menuFullPage = it },
            // Live TV, Movies, Shows, Music, Recordings: the page behind changes as you move onto it (not from over
            // full-screen video).
            // (Moving through the menu changes the page behind it, except over full-screen video. A film in the mini
            // player doesn't count: it keeps playing in the corner while the page changes, as when browsing.)
            onHoverPage = { p ->
                // (Over full-screen live TV too, Peter: the channel moves to the top-left window and keeps playing while
                // the page shows; it used to stay full screen with nothing changing behind the menu.)
                if (!hs.liveBehind && !filmFullScreen()) {
                    hs.sportsOpen = false
                    // Back on Movies / Shows from a service: the page's own rows again, not the service's.
                    // (A library left open from My Library is closed by the menu's Movies / Shows entries instead:
                    // My Library's own hover opens a library and comes through here.)
                    if (p == Page.Movies && moviesVm.currentRowSet.value != null) { moviesVm.openLibrary(null, MediaType.Movie); moviesVm.jumpTo(null) }
                    if (p == Page.Shows && showsVm.currentRowSet.value != null) { showsVm.openLibrary(null, MediaType.Show); showsVm.jumpTo(null) }
                    // Coming to Movies / Shows from another page: a title page left open there (e.g. the film's, now in
                    // the mini player) closes, so the page itself shows. (Already on one: its title page stays.)
                    if (p != hs.page && p == Page.Movies) moviesVm.closeTitlePages()
                    if (p != hs.page && p == Page.Shows) showsVm.closeTitlePages()
                    if (p != hs.page) hs.openPage(p, keepLive = hs.fullscreen, closeMenu = false)
                }
            },
            services = menuServices,
            // (Live now is a group in the TV guide now: 📡 Live now heads the group list. Peter, 2026-11-17.)
            liveEntries = listOf("Sports"),
            openEntry = when {
                hs.sportsOpen -> "Sports"
                hs.page == Page.Shows -> showsVm.currentRowSet.value?.takeIf { it.startsWith("name:") }?.let { "svc:" + it.removePrefix("name:") }
                hs.page == Page.Movies && moviesVm.currentRowSet.value == io.github.petebutty.viewarr.ui.media.MyLibrary.SET -> "section:Library"
                hs.page == Page.Movies -> moviesVm.currentRowSet.value?.takeIf { it.startsWith("name:") }?.let { "svc:" + it.removePrefix("name:") }
                else -> null
            },
            onLiveEntry = { _, into -> if (into || (!hs.fullscreen && !hs.liveBehind && !filmFullScreen())) hs.openSportsPage(closeMenu = into) },
            // Moving onto a service: its page behind (not over full-screen video); Right / OK: into it.
            onService = { name, rowId, into -> if (into || (!hs.liveBehind && !filmFullScreen())) { hs.sportsOpen = false; hs.openService(name, closeMenu = into, rowId = rowId, keepLive = !into && hs.fullscreen) } },
            // My Library highlighted: its page behind (your own servers' rows); its "My Library home": into it.
            onMyLibrary = { into -> if (into || (!hs.liveBehind && !filmFullScreen())) { hs.sportsOpen = false; hs.openMyLibrary(closeMenu = into, keepLive = !into && hs.fullscreen) } },
        )
    }
}

/** Split out of HomeScreen (its body was over Android's size limit for optimising a function). */
@Composable
private fun BoxScope.PagesLayer(
    hs: HomeState,
    cornerArm: BooleanArray,
    cornerFocus: FocusRequester,
    cornerReachable: Boolean,
    guideFocus: FocusRequester,
    guidePush: State<Int>,
    mediaFocus: FocusRequester,
    sportsFocus: FocusRequester,
    sportsHub: SportsHub,
) {
    // (Read here as HomeScreen reads them, so this part redraws on its own when they change.)
    val vm = hs.vm; val playback = hs.playback; val session = hs.session; val remote = hs.remote; val dvr = hs.dvr
    val moviesVm = hs.moviesVm; val showsVm = hs.showsVm
    val playing by playback.channel.collectAsStateWithLifecycle()
    val catchup by playback.catchup.collectAsStateWithLifecycle()
    val dvrRecordings by dvr.recordings.collectAsStateWithLifecycle()
    val sportFollows by hs.sportsVm.follows.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().offset { IntOffset(guidePush.value, 0) }.graphicsLayer { alpha = if (VodHost.behind) 0f else 1f }) {
        SideEffect { CornerPicture.pushPx = { guidePush.value } }
        // (Search isn't a page any more: openPage(Search) opens quick search over the page instead.)
        if (hs.page == Page.Music) {
            MusicScreen(
                focusRequester = mediaFocus,
                focusEnabled = !hs.menuOpen && !hs.cornerFocused && !hs.showAccounts && !hs.quickSearch && !VodHost.covering && !MusicPlayer.nowPlayingOpen && !hs.sportsOpen,
                onOpenMenu = { hs.menuOpen = true },
                onBack = hs::backFromPage,
                onSetUp = { hs.showAccounts = true },
            )
        } else if (hs.page == Page.Recordings) {
            RecordingsScreen(
                vm = dvr,
                focusRequester = mediaFocus,
                focusEnabled = !hs.menuOpen && !hs.cornerFocused && !hs.showAccounts && !hs.quickSearch && !VodHost.covering && !hs.sportsOpen,
                onBack = hs::backFromPage,
                onPlay = { hs.recordingPlayback = it },
                onOpenMenu = { hs.menuOpen = true },
            )
        } else if (hs.page != Page.Tv) CompositionLocalProvider(LocalQuickSearchOpen provides hs.quickSearch) {
            MediaScreen(
                type = if (hs.page == Page.Movies) MediaType.Movie else MediaType.Show,
                cornerHasFocus = hs.cornerFocused,
                focusRequester = mediaFocus,
                // (Not under a full-screen film either, as Music and Recordings: the page and the film fought for focus.)
                focusEnabled = !hs.menuOpen && !hs.cornerFocused && !hs.showAccounts && !hs.quickSearch && !hs.sportsOpen && !VodHost.covering,
                // Left from Movies / Shows: straight to the main menu (their own list is only "home" and "Jump to a
                // row" now). A library open (from My Library): the menu on My Library. A service's page: its rows.
                onOpenMenu = {
                    val mvm = if (hs.page == Page.Movies) moviesVm else showsVm
                    when {
                        mvm.currentRowSet.value != null -> Unit
                        mvm.browse.value != null -> { hs.menuOnLibrary = true; hs.menuOnMain = true }
                        else -> hs.menuOnMain = true
                    }
                    hs.menuOpen = true
                },
                onBack = {
                    if (VodHost.active && VodHost.mini) { VodHost.expand(); return@MediaScreen }
                    // Otherwise your Back setting (not always back to live TV).
                    // Over live TV: back to the channel full screen.
                    if (hs.liveBehind && playing != null) { hs.backOnPage(); return@MediaScreen }
                    // The channel in the preview place: back to the guide.
                    if (hs.livePreview && playing != null) { hs.backOnPage(); return@MediaScreen }
                    when (remote.mediaBack) {
                        MediaBack.Exit -> hs.backOnPage()
                        MediaBack.Menu -> hs.menuOpen = true
                        MediaBack.Guide -> hs.openPage(Page.Tv)
                    }
                },
            )
        } else if (session == null) {
            NoLiveTv(
                focusRequester = guideFocus,
                focusEnabled = !hs.menuOpen && !hs.cornerFocused && !hs.showAccounts && !hs.quickSearch && !hs.sportsOpen,
                onSetUp = { hs.showAccounts = true },
                onOpenMenu = { hs.menuOpen = true },
            )
        } else {
            GuideScreen(
                // A film in the corner: past the last (or first) channel, onto it (as at a page's edge).
                onPastEnd = { up ->
                    val at = AppColors.palette.opt(UiOpts.MINI_CORNER)
                    if (!cornerReachable || up != (at >= 2)) false else {
                        cornerArm[0] = true
                        EdgeBlocked.lettingOut { runCatching { cornerFocus.requestFocus() }.isSuccess }.also { if (!it) cornerArm[0] = false }
                    }
                },
                vm = vm,
                recordings = dvrRecordings,
                playingChannelId = playing?.id,
                playingCatchupRowId = catchup?.rowId,
                serverUrl = session?.serverUrl.orEmpty(),
                focusRequester = guideFocus,
                focusEnabled = !hs.menuOpen && !hs.cornerFocused && !hs.fullscreen && !hs.showAccounts && !hs.quickSearch && !hs.sportsOpen,
                onOpenMenu = { hs.menuOpen = true },
                onSelect = { channel, program ->
                    // Settings → Remote buttons → TV guide → OK: "Watch in the preview" stays in the guide (OK again on
                    // what's playing: full screen).
                    val inGuide = ButtonMap.action(remote, BtnScreen.Guide, BtnPress.Ok) == BtnAction.WatchInGuide
                    if (!inGuide) ButtonMap.changed(remote, BtnScreen.Guide, BtnPress.Ok)?.let { hs.doButton(BtnScreen.Guide, it); return@GuideScreen }
                    hs.pickInGuide(channel, program, inGuide)
                },
                // Holding OK: the programme's options (record / remind, watch it from your library or addons), whether
                // it's on now, still to come or already aired.
                onLongSelect = { channel, program ->
                    ButtonMap.changed(remote, BtnScreen.Guide, BtnPress.OkHold)?.let { hs.doButton(BtnScreen.Guide, it); return@GuideScreen }
                    if (program != null) hs.recordTarget = channel to program
                    // No programme listed (nothing to record): hold OK just adds / removes the favourite.
                    else hs.toast(if (vm.toggleFavourite(channel)) "${channel.name} added to favourites" else "${channel.name} removed from favourites")
                },
            )
        }
        if (hs.sportsOpen) SportsPage(
            hub = sportsHub,
            onFollow = { hs.openFollowTeam() },
            follows = sportFollows,
            // (Every channel, not just the guide's group on show: the plugin may name one in another group.)
            channels = dvr.channels.collectAsStateWithLifecycle().value.values.toList(),
            // (A film minimised to the corner too: UI review, 2026-11-17.)
            besidePreview = (playing != null && !hs.fullscreen) || (VodHost.active && VodHost.mini),
            focusRequester = sportsFocus,
            focusEnabled = !hs.menuOpen && !hs.showAccounts && !hs.quickSearch && !hs.fullscreen && hs.infoTarget == null && hs.recordTarget == null,
            onWatch = { ch -> hs.sportsOpen = false; hs.watchChannel(ch) },
            onToast = { hs.toast(it) },
            onOpenMenu = { hs.menuOpen = true },
            // Back: the video it came from first (a film in the corner full screen…), else the menu on Sports (still
            // showing behind it), as Back on Music and Recordings (UI review, 2026-11-17). (It closed Sports, showing
            // whatever page was under it: Music, the last one visited. Peter, 2026-11-16.)
            onBack = hs::backFromPage,
        )
    }
}

/** Split out of HomeScreen (its body was over Android's size limit for optimising a function). */
@Composable
private fun HomeCommandEffects(hs: HomeState) {
    // (Read here as HomeScreen reads them, so these effects see the latest values.)
    val vm = hs.vm; val playback = hs.playback; val dvr = hs.dvr; val fromStartScope = hs.fromStartScope
    val playing by playback.channel.collectAsStateWithLifecycle()
    val commandLink by DeepLink.pendingCommand.collectAsStateWithLifecycle()
    LaunchedEffect(commandLink) {
        val c = commandLink ?: return@LaunchedEffect
        DeepLink.pendingCommand.value = null
        fromStartScope.launch {
            when (c.kind) {
                // Channel up / down: from the channel playing (else the first), full screen.
                "step" -> {
                    withTimeoutOrNull(60_000) { vm.channels.first { it.isNotEmpty() } }
                    if (playing == null) vm.channels.value.firstOrNull()?.let { hs.watchChannel(it) }
                    else { hs.zap(if (c.arg == "up") 1 else -1); if (hs.page == Page.Tv) hs.fullscreen = true }
                }
                // Stop whatever's playing: live TV, a film or episode, music.
                "stop" -> { playback.stop(); VodHost.close(); MusicPlayer.stop(); hs.fullscreen = false }
                "guide" -> { hs.menuOpen = false; hs.quickSearch = false; VodHost.minimise(); hs.openPage(Page.Tv); hs.fullscreen = false }
                "page" -> {
                    hs.menuOpen = false; hs.quickSearch = false
                    when (c.arg) {
                        "movies" -> hs.openPage(Page.Movies); "shows" -> hs.openPage(Page.Shows); "music" -> hs.openPage(Page.Music)
                        "recordings" -> hs.openPage(Page.Recordings); "guide" -> { hs.openPage(Page.Tv); hs.fullscreen = false }
                        "sports" -> { if (hs.fullscreen) hs.fullscreen = false; hs.sportsOpen = true }
                    }
                }
                // Record what's on now on that channel (or the one playing), as one press of Record.
                "record" -> {
                    val all = withTimeoutOrNull(60_000) { dvr.channels.first { it.isNotEmpty() } }?.values.orEmpty()
                    val ch = c.uuid?.let { u -> all.firstOrNull { it.uuid.equals(u, ignoreCase = true) } } ?: c.number?.let { n -> all.firstOrNull { it.number == n } } ?: playing
                    val prog = ch?.let { GuideViewModel.programAt(hs.programsOf(it), System.currentTimeMillis()) }
                    when {
                        ch == null || prog == null -> hs.toast("Nothing to record there")
                        !Access.record -> hs.toast("Recording isn't allowed for this profile")
                        dvr.recordingFor(ch.id, prog) != null -> hs.toast("Already recording: ${prog.title}")
                        else -> { dvr.record(ch, prog); hs.toast("⏺ Recording from now: ${prog.title}") }
                    }
                }
                "search" -> { hs.menuOpen = false; VodHost.minimise(); hs.searchStart = c.arg; hs.searchVoice = false; hs.quickSearch = true }
                // The channel list again (quick), or with guide=1 the whole guide.
                "refresh" -> { if (c.arg == "guide") vm.refreshGuide() else vm.refreshChannels() }
            }
        }
    }
}

/** Split out of HomeScreen (its body was over Android's size limit for optimising a function). */
@Composable
private fun HomeLinkEffects(hs: HomeState) {
    // (Read here as HomeScreen reads them, so these effects see the latest values.)
    val vm = hs.vm; val playback = hs.playback; val dvr = hs.dvr; val fromStartScope = hs.fromStartScope
    val moviesVm = hs.moviesVm; val showsVm = hs.showsVm; val activity = hs.activity
    val playing by playback.channel.collectAsStateWithLifecycle()
    val catchup by playback.catchup.collectAsStateWithLifecycle()
    // Over live TV only on Movies / Shows; nothing playing, nothing behind.
    LaunchedEffect(hs.page, playing == null) { if ((hs.page != Page.Movies && hs.page != Page.Shows) || playing == null) hs.liveBehind = false }
    // Watch Next cards on the TV home screen: brought up to date a little after start-up (servers answer slowly while
    // everything else loads), and a card picked there opens its title's page here.
    LaunchedEffect(Unit) { moviesVm.refreshPlexServers(); delay(20_000); moviesVm.refreshWatchNext() }
    // The splash can go once the guide has its channels (the splash's own limit covers a guide that doesn't come).
    LaunchedEffect(Unit) {
        vm.channels.first { it.isNotEmpty() }
        StartupGate.ready = true
    }
    // viewarr://channel?uuid=… / ?number=…: that live channel, full screen. On a cold start it waits for the guide's
    // channels first; the channel already playing is reconnected (a frozen stream).
    val channelLink by DeepLink.pendingChannel.collectAsStateWithLifecycle()
    LaunchedEffect(channelLink) {
        val req = channelLink ?: return@LaunchedEffect
        DeepLink.pendingChannel.value = null
        // (Run apart from this effect: clearing the request above restarts it, which cancelled a channel refresh
        // half-way through.)
        fromStartScope.launch {
            var all = withTimeoutOrNull(60_000) { dvr.channels.first { it.isNotEmpty() } }?.values.orEmpty()
            fun find() = req.uuid?.let { u -> all.firstOrNull { it.uuid.equals(u, ignoreCase = true) } }
                ?: req.number?.let { n -> all.firstOrNull { it.number == n } }
            var ch = find()
            // Not known yet (added on Dispatcharr since the last fetch): the channel list again (quick, not the guide),
            // then look once more.
            if (ch == null) {
                hs.toast("Looking for that channel…")
                vm.refreshChannels()
                all = withTimeoutOrNull(5_000) { dvr.channels.first { m -> m.values.any { c -> (req.uuid != null && c.uuid.equals(req.uuid, true)) || (req.number != null && c.number == req.number) } } }?.values ?: dvr.channels.first().values
                ch = find()
            }
            if (ch == null) { hs.toast("That channel isn't in Viewarr's guide"); return@launch }
            hs.quickSearch = false; hs.menuOpen = false; hs.sportsOpen = false; hs.channelListOpen = false; hs.osdVisible = false
            VodHost.close()
            hs.watchChannel(ch, endMultiView = true)
        }
    }
    val deepLink by DeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(deepLink) {
        val item = deepLink ?: return@LaunchedEffect
        DeepLink.pending.value = null
        hs.quickSearch = false
        hs.menuOpen = false
        val show = item.type == MediaType.Show
        if (hs.page == Page.Tv) { playback.stop(); hs.fullscreen = false }
        hs.page = if (show) Page.Shows else Page.Movies
        (if (show) showsVm else moviesVm).open(item)
    }

    // The info bars' sleep timer: when it's up, everything playing stops (music has its own timer).
    LaunchedEffect(SleepTimer.endsAt) {
        val t = SleepTimer.endsAt ?: return@LaunchedEffect
        delay((t - System.currentTimeMillis()).coerceAtLeast(0))
        SleepTimer.clear()
        if (playing != null || catchup != null) hs.doButton(BtnScreen.Live, BtnAction.Stop)
        VodHost.close()
        hs.toast("Sleep timer: stopped")
    }

    // A button's page (Movies, Recordings…), the TV guide from a page, or leaving the app.
    LaunchedEffect(hs.buttonRequest) {
        val r = hs.buttonRequest ?: return@LaunchedEffect
        hs.buttonRequest = null
        when (r) {
            BtnAction.Movies -> hs.openPage(Page.Movies)
            BtnAction.Shows -> hs.openPage(Page.Shows)
            BtnAction.Recordings -> hs.openPage(Page.Recordings)
            BtnAction.Music -> hs.openPage(Page.Music)
            BtnAction.Guide -> { hs.menuOpen = false; hs.openPage(Page.Tv) }
            BtnAction.Exit -> activity?.finish()
            else -> Unit
        }
    }
}

/**
 * The live info bar's rows (Down from its buttons), and the film players' (shared through PlayerBridge.filmRows).
 * Split out of HomeScreen (its body was over Android's size limit for optimising a function).
 */
@Composable
private fun rememberInfoBarRows(hs: HomeState): List<OsdRow> {
    // (Read here as HomeScreen reads them, so the rows rebuild when they change.)
    val vm = hs.vm; val playback = hs.playback; val session = hs.session; val remote = hs.remote; val dvr = hs.dvr
    val moviesVm = hs.moviesVm; val showsVm = hs.showsVm
    val sessionRef = rememberUpdatedState(session)
    val playing by playback.channel.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val favsNow by vm.favourites.collectAsStateWithLifecycle()
    val dvrRecordings by dvr.recordings.collectAsStateWithLifecycle()
    val movieMenuRows by moviesVm.menuRows.collectAsStateWithLifecycle()
    val showMenuRows by showsVm.menuRows.collectAsStateWithLifecycle()
    val continueMovies by moviesVm.continueItems.collectAsStateWithLifecycle()
    val continueShows by showsVm.continueItems.collectAsStateWithLifecycle()
    val recentMovies by moviesVm.recent.collectAsStateWithLifecycle()
    val recentShows by showsVm.recent.collectAsStateWithLifecycle()
    val allChannels by dvr.channels.collectAsStateWithLifecycle()
    // A film or show as a card under the live controls: OK opens its page (the channel carries on in the corner).
    // Continue watching ([resume]): OK carries on (the episode you're up to, or the film where you left it); hold OK
    // for Go to show / Go to season / Play from the start. Elsewhere OK opens its page.
    fun titleCard(item: MediaItem, resume: Boolean = false): OsdPoster {
        val show = item.type == MediaType.Show
        val mvm = if (show) showsVm else moviesVm
        val page = if (show) Page.Shows else Page.Movies
        // (A card on a row: into the title itself, so the channel stops.)
        fun open() { hs.osdVisible = false; mvm.open(item); hs.openPage(page, keepLive = false) }
        fun play(how: (MediaItem) -> Unit) { hs.osdVisible = false; how(item); hs.openPage(page, keepLive = false) }
        val carryOn = resume && (item.progress != null || item.nextSeason != null)
        val options = if (!carryOn) emptyList() else listOfNotNull(
            CardOption(if (show) "Go to show" else "Go to film") { open() },
            if (show) CardOption("Go to season" + (item.nextSeason?.let { " $it" } ?: "")) {
                hs.osdVisible = false; showsVm.quickEpisodes(item); hs.openPage(page, keepLive = false)
            } else null,
            CardOption(if (show) "Play this episode from the start" else "Play from the start", plays = true) { play(mvm::quickFromStart) },
        )
        return OsdPoster(
            key = item.key, title = item.title, subtitle = item.subtitle,
            image = item.backdrop ?: item.poster, progress = item.progress,
            options = options, plays = carryOn,
        ) { if (carryOn) play(if (show) showsVm::quickNextUp else moviesVm::quickPlay) else open() }
    }
    // (What the cards' OK does reads the session as it is when pressed.)
    val sessionNow by sessionRef
    val ownStamp = hs.ownPrograms.entries.sumOf { it.value.first }
    // ---- The rows under the info bars (Settings → Appearance → Info bar & controls → Rows under the info bar): built
    // here for live TV, and for the film players (shared through PlayerBridge.filmRows). Down from the buttons.
    val localWatchEntries by moviesVm.localWatch.rowEntries.collectAsStateWithLifecycle()
    val traktMovies by moviesVm.traktContinue.collectAsStateWithLifecycle()
    val traktShows by showsVm.traktContinue.collectAsStateWithLifecycle()
    val groupChannels by vm.channels.collectAsStateWithLifecycle()
    // The film / episode playing: left out of the Continue watching rows (it was the first card of its own row).
    val playingMovie by moviesVm.playing.collectAsStateWithLifecycle()
    val playingShow by showsVm.playing.collectAsStateWithLifecycle()
    val playingWatch = (playingShow ?: playingMovie)?.watch?.takeIf { VodHost.active }
    val liveKinds = InfoRows.active(InfoRows.LIVE)
    val filmKinds = InfoRows.active(InfoRows.FILM)
    val wantedKinds = (liveKinds + filmKinds).toSet()
    // What the chosen rows need, fetched once the controls are up (or a film is playing).
    LaunchedEffect(hs.osdVisible, VodHost.active, wantedKinds) {
        if (!hs.osdVisible && !VodHost.active) return@LaunchedEffect
        if (wantedKinds.any { it == RowKind.CONTINUE || it == RowKind.CONTINUE_SERVERS }) { moviesVm.loadContinue(MediaType.Movie); showsVm.loadContinue(MediaType.Show) }
        if (wantedKinds.any { it == RowKind.CONTINUE || it == RowKind.CONTINUE_TRAKT }) showsVm.loadTraktContinue(MediaType.Show) // (shows only: see traktContinue)
        if (RowKind.RECENTLY_ADDED in wantedKinds) { moviesVm.loadRecent(MediaType.Movie); showsVm.loadRecent(MediaType.Show) }
        if (RowKind.WATCHLIST in wantedKinds) { moviesVm.load(MediaType.Movie); showsVm.load(MediaType.Show) }
    }
    // A channel as a card (logo, what's on now); OK watches it ([plays]: from a film, which then makes way).
    // (What's on is worked out once per card: it was looked up twice, for the title and again for the progress.)
    fun channelCard(ch: ChannelListItem, prefix: String, chNow: Long, plays: Boolean = false): OsdPoster {
        val on = GuideViewModel.programAt(hs.programsOf(ch), chNow)
        return OsdPoster(
            key = "$prefix-${ch.id}", title = ch.name,
            subtitle = on?.let { displayTitle(it) },
            image = channelLogo(session?.serverUrl.orEmpty(), ch), logo = true,
            progress = on?.let { p -> ((chNow - p.startTime).toFloat() / (p.endTime - p.startTime).coerceAtLeast(1)).coerceIn(0f, 1f) },
        ) {
            hs.osdVisible = false
            playback.play(ch, sessionNow)
            vm.selectChannel(ch.id)
            if (plays) hs.fullscreen = true
            hs.showBanner()
        }
    }
    fun mixTitles(a: List<MediaItem>, b: List<MediaItem>) =
        (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }
            .distinctBy { it.tmdbId?.let { id -> "${it.type}:$id" } ?: it.key }.take(20)
    /** One row of [kind], for [player] (null: nothing in it, or the player builds it itself: next episodes). */
    fun infoRow(kind: InfoRows.Kind, player: String): OsdRow? {
        val film = player == InfoRows.FILM
        val chNow = System.currentTimeMillis()
        fun notPlaying(l: List<MediaItem>) = playingWatch?.let { w ->
            l.filterNot { it.type == w.type && ((w.tmdbId != null && it.tmdbId == w.tmdbId) || (w.imdbId != null && it.imdbId == w.imdbId)) }
        } ?: l
        // (Settings → Home page & menu → Continue watching from: each server, IPTV and Trakt on or off.)
        val cf = io.github.petebutty.viewarr.ui.media.ContinueFrom
        val iptv = if (!cf.on(cf.IPTV)) emptyList() else notPlaying(mixTitles(moviesVm.localWatch.continueItems(MediaType.Movie, localWatchEntries), moviesVm.localWatch.continueItems(MediaType.Show, localWatchEntries)))
        val servers = notPlaying(mixTitles(continueMovies.orEmpty(), continueShows.orEmpty())).filter { cf.on(cf.ofItem(it)) }
        val traktAll = if (!cf.on(cf.TRAKT)) emptyList() else notPlaying(mixTitles(traktMovies.orEmpty(), traktShows.orEmpty()))
        val row = when (kind) {
            RowKind.NEXT -> return null
            // The film / show playing's page: its recommendations.
            RowKind.SIMILAR -> OsdRow("More like this", (if (showsVm.playing.value != null) showsVm else moviesVm).similarToPlaying().take(20).map { titleCard(it) })
            RowKind.CONTINUE -> OsdRow("Continue watching", (servers + iptv + traktAll).distinctBy { it.tmdbId?.let { id -> "${it.type}:$id" } ?: it.key }.take(25).map { titleCard(it, resume = true) })
            RowKind.CONTINUE_IPTV -> OsdRow("Continue watching on IPTV", iptv.map { titleCard(it, resume = true) })
            RowKind.CONTINUE_TRAKT -> OsdRow("Continue watching on Trakt", traktAll.map { titleCard(it, resume = true) })
            RowKind.CONTINUE_SERVERS -> OsdRow("Continue watching on your servers", servers.map { titleCard(it, resume = true) })
            RowKind.RECENT_CHANNELS -> OsdRow("Recent channels", remote.recentChannels.filter { film || it != playing?.id }.mapNotNull { allChannels[it] }.take(15).map { channelCard(it, "rc", chNow, film) })
            RowKind.FAVOURITES -> OsdRow("Favourite channels", allChannels.values.filter { it.uuid in favsNow }.sortedBy { it.number ?: Double.MAX_VALUE }.take(40).map { channelCard(it, "fav", chNow, film) })
            RowKind.ON_NOW -> OsdRow("On now" + (playing?.groupName?.let { " in $it" } ?: ""), groupChannels.filter { it.id != playing?.id }.take(40).map { channelCard(it, "on", chNow) })
            RowKind.COMING_UP -> {
                val ch = playing
                OsdRow("Coming up on ${ch?.name ?: "this channel"}", ch?.let { c ->
                    hs.programsOf(c).filter { it.startTime > chNow }.sortedBy { it.startTime }.take(12).map { p ->
                        OsdPoster(key = "up-${c.id}-${p.startTime}", title = displayTitle(p), subtitle = Dates.time(p.startTime) + "–" + Dates.time(p.endTime),
                            image = channelLogo(session?.serverUrl.orEmpty(), c), logo = true) {
                            hs.osdVisible = false; PlayerBridge.openGuide()
                        }
                    }
                }.orEmpty())
            }
            RowKind.RECORDINGS -> {
                val chans = dvr.channels.value
                OsdRow("Recordings", dvrRecordings.filter { it.state == DvrState.Recorded || it.state == DvrState.Recording }.sortedByDescending { it.start }.take(20).map { rec ->
                    OsdPoster(key = "r:${rec.id}", title = rec.title,
                        subtitle = listOfNotNull(rec.season?.let { "S$it" }, rec.episode?.let { "E$it" }, Dates.dayTime(rec.start)).joinToString(" · "),
                        image = chans[rec.channelId]?.let { channelLogo(session?.serverUrl.orEmpty(), it) }, logo = true,
                    ) { hs.osdVisible = false; hs.openPage(Page.Recordings, keepLive = false) }
                })
            }
            RowKind.RECENTLY_ADDED -> OsdRow("Recently added", mixTitles(recentMovies.orEmpty(), recentShows.orEmpty()).map { titleCard(it) })
            RowKind.WATCHLIST -> OsdRow("Your watchlist", mixTitles(
                movieMenuRows.rows.firstOrNull { it.id.startsWith("trakt-watchlist") }?.items.orEmpty(),
                showMenuRows.rows.firstOrNull { it.id.startsWith("trakt-watchlist") }?.items.orEmpty(),
            ).map { titleCard(it) })
        }
        return row.takeIf { it.items.isNotEmpty() }
    }
    val infoRowsStamp = listOf(liveKinds, filmKinds, remote.recentChannels, playing?.id, allChannels, programs, ownStamp, favsNow, groupChannels,
        continueMovies, continueShows, recentMovies, recentShows, traktMovies, traktShows, localWatchEntries, dvrRecordings, movieMenuRows, showMenuRows, now, session?.serverUrl, VodHost.active, playingWatch)
    // Built only while they can be seen (the live controls up, or a film playing): every channel card looks up what's on,
    // which was being done every 30 s for rows nobody could see.
    val liveRowsWanted = hs.osdVisible
    val filmRowsWanted = VodHost.active
    val liveInfoRows: List<OsdRow> = remember(infoRowsStamp, liveRowsWanted) {
        if (!liveRowsWanted) emptyList() else liveKinds.mapNotNull { infoRow(it, InfoRows.LIVE) }
    }
    val filmInfoRows: List<Pair<String, OsdRow>> = remember(infoRowsStamp, filmRowsWanted) {
        if (!filmRowsWanted) emptyList() else filmKinds.mapNotNull { k -> infoRow(k, InfoRows.FILM)?.let { k.id to it } }
    }
    SideEffect { PlayerBridge.filmRows = filmInfoRows }
    return liveInfoRows
}

/** A film or episode on full screen (or with the menu open over it): the menu mustn't change the page underneath. */
private fun filmFullScreen() = VodHost.active && !VodHost.mini
