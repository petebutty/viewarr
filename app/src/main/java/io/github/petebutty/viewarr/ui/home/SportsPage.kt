package io.github.petebutty.viewarr.ui.home

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import io.github.petebutty.viewarr.ui.theme.appBackground
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.sports.SportFollow
import io.github.petebutty.viewarr.ui.common.AppPill
import io.github.petebutty.viewarr.ui.common.Carry
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.ReorderAxis
import io.github.petebutty.viewarr.ui.common.leftOpens
import io.github.petebutty.viewarr.ui.common.moveOption
import io.github.petebutty.viewarr.ui.common.movingHint
import io.github.petebutty.viewarr.ui.common.rememberCarry
import io.github.petebutty.viewarr.ui.common.reorderable
import io.github.petebutty.viewarr.ui.player.CardMenu
import io.github.petebutty.viewarr.ui.player.CardMenuHost
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates
import io.github.petebutty.viewarr.ui.theme.NoScale
import io.github.petebutty.viewarr.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Sports page (Sports in the menu): what you follow (Settings → Sports) as a fixture guide: live now, then a
 * section per day, then the latest results, one slim line per game (Peter, 2026-11-16: the picture-sized panels showed
 * two rows a screen). Along the top, All or one team / league. OK on a game goes to the channel showing it (GamePlacer:
 * the one in your guide, else the one the listings name, an event channel or a team's own; or its page: Settings →
 * Sports → Game page). The top-left window stays clear for live TV, with the highlighted game's details beside it.
 */
@Composable
internal fun SportsPage(
    hub: SportsHub,
    follows: List<SportFollow>,
    channels: List<ChannelListItem>,
    besidePreview: Boolean,
    focusRequester: FocusRequester,
    focusEnabled: Boolean,
    onWatch: (ChannelListItem) -> Unit,
    onToast: (String) -> Unit,
    onOpenMenu: () -> Unit,
    onBack: () -> Unit,
    /** ＋ Follow: Settings → Sports, to find and follow a team or league. */
    onFollow: () -> Unit = {},
) {
    // A filter pill being moved (its team / league id): hold OK on one → Move. (The shared Carry / reorderable, as
    // every list: ◀ ▶ move it, OK or Back put it down. UI review, Tier 4, 2026-11-17.)
    val carry = rememberCarry()
    // (Not while a game's page is open over it: this one won, so Back left Sports for the menu, landing on Music. Nor
    // while a pill is carried: Back puts it down.)
    BackHandler(enabled = focusEnabled && hub.openGame == null) {
        if (carry.active) carry.drop() else { Log.i("Sports", "Back: to the menu"); onBack() }
    }
    // Hold OK on a game: its options.
    CardMenuHost()
    // (The game page keeps clear of the live window by itself: HomeScreen works it out where the page is drawn, so a
    // game page opened from the info bar never gets a stale value from here. UI review, 2026-11-17.)
    // The panel a game page or a game's options were opened from: focus goes back to it when they close (it was lost,
    // then landed on the menu's Music entry: Peter, 2026-11-16).
    val panelFocus = remember { HashMap<String, FocusRequester>() }
    var returnTo by remember { mutableStateOf<String?>(null) }
    // Hold OK on a pill: its options (Move, Stop following), in the shared options pop-up (it was a raw Dialog that
    // could take the held OK as a choice). Closing it puts the highlight back on the pill (returnTo), or on All after
    // Stop following.
    var pillMenu by remember { mutableStateOf<SportFollow?>(null) }
    val overlayOpen = hub.openGame != null || CardMenu.open != null || pillMenu != null
    DisposableEffect(hub) {
        hub.restoreFocus = { returnTo?.let { panelFocus[it] }?.requestFocus() ?: focusRequester.requestFocus() }
        onDispose { hub.restoreFocus = null }
    }
    LaunchedEffect(overlayOpen) {
        if (overlayOpen) return@LaunchedEffect
        val key = returnTo ?: return@LaunchedEffect
        // At once (before anything else can take it and switch the page), then a few frames more if it isn't there yet.
        repeat(6) { n -> if (n > 0) withFrameNanos { }; if (runCatching { panelFocus[key]!!.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    // Where each game is on, and why (GamePlacer: shared with Live now). Made once per channel list, not on every move.
    val placer = remember(channels) { GamePlacer(channels) }
    // (For the 🔔 on games with a reminder set.)
    val remindersNow by hub.dvr.reminders.collectAsState()
    // The team / league picked along the top (null: everything you follow).
    var filter by remember { mutableStateOf<String?>(null) }
    pillMenu?.let { f ->
        OptionsPopup(
            title = f.name,
            options = listOf(
                carry.moveOption(f.shortcutId),
                PopupOption("Stop following") {
                    if (filter == f.shortcutId) filter = null
                    hub.sportsVm.unfollow(f)
                    onToast("Stopped following ${f.name}")
                    returnTo = "f-null"
                },
            ),
            onClose = { pillMenu = null },
        )
    }
    // A fixture guide: live now, then a section per day, then the latest results; one line per game, once each.
    // Worked out again only when the games, their channels, your follows or the filter change, not as the highlight
    // moves. (Keyed on the hub's version, not copies of its maps: those were made and compared on every move.)
    val sections: List<Pair<String, List<GameCard>>> = remember(follows, hub.version, placer, filter) {
        val gamesNow = hub.games
        val chosen = follows.filter { filter == null || it.shortcutId == filter }
        val cards = chosen.flatMap { gamesNow[it.shortcutId].orEmpty() }.distinctBy { it.first.id }.map { (g, c) -> placer.place(hub, g, c) }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        buildList {
            cards.filter { it.game.state == "in" }.sortedBy { it.game.start }.takeIf { it.isNotEmpty() }?.let { add("Live now" to it) }
            cards.filter { it.game.state == "pre" }.sortedBy { it.game.start }
                .groupBy { Instant.ofEpochMilli(it.game.start).atZone(zone).toLocalDate() }
                .forEach { (day, list) -> add(dayTitle(day, today) to list) }
            cards.filter { it.game.state == "post" }.sortedByDescending { it.game.start }.take(if (filter == null) 12 else 6)
                .takeIf { it.isNotEmpty() }?.let { add("Results" to it) }
        }
    }
    var hover by remember { mutableStateOf<GameCard?>(null) }
    // The page's own highlight: put on the first game when it opens, and back there if it's ever lost while the page
    // is up (rows reload as games and channels come in, and a page behind used to take it: it came and went).
    var pageHasFocus by remember { mutableStateOf(false) }
    // (Not while a game's page or options are open over it; after, the game they were opened from.)
    LaunchedEffect(focusEnabled, sections.isNotEmpty(), pageHasFocus, overlayOpen) {
        if (!focusEnabled || sections.isEmpty() || pageHasFocus || overlayOpen) return@LaunchedEffect
        withFrameNanos { }
        delay(120)
        if (!pageHasFocus) runCatching { returnTo?.let { panelFocus[it] }?.requestFocus() ?: focusRequester.requestFocus() }
            .onFailure { runCatching { focusRequester.requestFocus() } }
    }
    // Its own wallpaper, drawn whole: Sports opens over another page (Shows…), which showed through when it had no
    // background (Peter, 2026-11-17). The same wallpaper as the app's, so it still runs on under the menu.
    // (Readable: the wallpaper dimmed well down behind the fixture list, as Settings. Peter, 2026-11-17: too see-through.)
    // (The same background as the other pages, with a light shade for the list: the "readable" Settings style fell
    // back to a flat colour when there's no picture wallpaper, so Sports looked solid. Peter, 2026-11-18.)
    // (Drawn at full-screen size and shifted back by where the page starts, so it lines up with the menu's copy of
    // the same wallpaper: drawn from the page's own edge it left a seam where the menu ends. Peter, 2026-11-18.)
    var pageX by remember { mutableStateOf(0) }
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    Box(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { pageX = it.positionInRoot().x.toInt() }) {
    Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(screenW).fillMaxHeight()
        .offset { androidx.compose.ui.unit.IntOffset(-pageX, 0) }
        .appBackground(titleArt = io.github.petebutty.viewarr.ui.theme.Wallpaper.titleBehind)
        .drawBehind { drawRect(AppColors.palette.shade.copy(alpha = 0.35f)) })
    Column(Modifier.fillMaxSize()
        .onFocusChanged { pageHasFocus = it.hasFocus }) {
        SportsHeader(hover ?: sections.firstOrNull()?.second?.firstOrNull(), besidePreview)
        if (follows.isEmpty()) Text(
            "Nothing followed yet: add teams and leagues in Settings → Sports.",
            fontSize = 16.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = Tokens.Gutter, top = 8.dp),
        )
        // All, then each team / league you follow: OK shows just its games. Fixed above the list (they were its first
        // item and scrolled away with it: UI review, 2026-11-17).
        // (Always there now: the last pill is ＋ Follow. Peter, 2026-11-18.)
        SportsPills(
            follows = follows, filter = filter, carry = carry, panelFocus = panelFocus, onOpenMenu = onOpenMenu, onFollow = onFollow,
            onMove = { f, by -> hub.sportsVm.move(f, by) },
            onOptions = { id -> returnTo = "f-$id"; pillMenu = follows.firstOrNull { it.shortcutId == id } },
            onPick = { id -> returnTo = "f-$id"; filter = id },
        )
        if (follows.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = Tokens.Gutter, end = Tokens.Gutter, top = 2.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (sections.isEmpty()) item(key = "empty") {
                Text(if (hub.games.isEmpty()) "Loading games…" else "No games coming up", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            }
            sections.forEachIndexed { r, (title, cards) ->
                item(key = "s-$title") {
                    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                        color = if (title == "Live now") Color(0xFFFF5A5F) else AppColors.Accent, modifier = Modifier.padding(top = if (r == 0) 6.dp else 12.dp, bottom = 3.dp))
                }
                itemsIndexed(cards, key = { _, c -> "g-${c.game.id}" }) { i, c ->
                    val lineKey = "g-${c.game.id}"
                    FixtureLine(
                        c,
                        reminded = c.game.state == "pre" && hub.reminded(c.game, c.channel, remindersNow),
                        modifier = (if (r == 0 && i == 0) Modifier.focusRequester(focusRequester) else Modifier)
                            .focusRequester(panelFocus.getOrPut(lineKey) { FocusRequester() })
                            .onFocusChanged { if (it.isFocused) hover = c }
                            // Left: the menu, as on every page (once per press: holding Left no longer throws it open).
                            .leftOpens(onMenu = onOpenMenu)
                            .onPreviewKeyEvent { e ->
                                // Up from the first game: the pill that's selected (not whichever is nearest on screen: it
                                // landed on Premier League with All selected).
                                if (e.key == Key.DirectionUp && r == 0 && i == 0 && follows.size > 1) {
                                    if (e.type == KeyEventType.KeyDown) runCatching { panelFocus["f-$filter"]?.requestFocus() }; panelFocus["f-$filter"] != null
                                } else false
                            },
                        // Hold OK: the game's options (game page, remind / record, even before the guide lists it).
                        onLongClick = { returnTo = lineKey; hub.openChannel = c.channel; CardMenu.open = hub.card(c.game, c.inGuide, known = c.channel) },
                    ) {
                        val ch = c.channel
                        // Settings → Sports → Game page on: OK opens the game's page (Watch is on it).
                        if (hub.gamePages) {
                            returnTo = lineKey
                            hub.openChannel = c.channel
                            hub.openWhy = c.why
                            hub.openGame = c.game to c.inGuide
                            return@FixtureLine
                        }
                        when {
                            ch != null -> onWatch(ch)
                            c.game.state == "post" -> onToast("${c.game.home} ${c.game.score ?: ""} ${c.game.away}")
                            c.named.isNotEmpty() -> onToast("${c.game.title}: on ${c.named.joinToString(", ")}, not one of your channels")
                            else -> onToast("${c.game.title}: not in your TV guide yet")
                        }
                    }
                }
            }
        }
    } } // (the background Box)
}

/**
 * The header: the top-left window's place (live TV or a film in the corner) and the highlighted game [c] beside it.
 * (Beside the window: tall enough that its picture never covers the pills below. 188 left the "All" pill under it,
 * more so with the menu open. Without it: room for every line, "OK: watch on …" too; 112 hid it under the pills.
 * Peter, 2026-11-17.)
 */
@Composable
private fun SportsHeader(c: GameCard?, besidePreview: Boolean) {
    Row(Modifier.fillMaxWidth().height(if (besidePreview) 214.dp else 150.dp)) {
        Spacer(Modifier.width(if (besidePreview) Tokens.BesideWindowStart else Tokens.Gutter))
        Column(Modifier.padding(top = 26.dp, end = Tokens.Gutter)) {
            Text("Sports", fontSize = 14.sp, color = AppColors.Accent)
            Spacer(Modifier.height(4.dp))
            Text(c?.game?.let { g -> if (g.score != null) "${g.home} ${g.score} ${g.away}" else g.title } ?: "Your teams and leagues",
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = if (besidePreview) 2 else 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(c?.let { whenLine(it) } ?: "Follow teams and leagues in Settings → Sports.", fontSize = 15.sp, color = AppColors.TextSecondary, maxLines = 2)
            c?.let { Spacer(Modifier.height(4.dp)); Text(it.game.leagueName, fontSize = 13.sp, color = AppColors.TextSecondary) }
            c?.let { Spacer(Modifier.height(6.dp)); Text(okLine(it), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (it.channel != null && it.sure) AppColors.Accent else AppColors.TextSecondary) }
        }
    }
}

/** What OK (and hold OK, before kick-off) does on the highlighted game. */
private fun okLine(c: GameCard): String {
    val ok = if (c.channel != null) "OK: watch on ${c.channel.name}" + (if (!c.sure) " (possible match)" else "") else "Not on any of your channels"
    return ok + if (c.game.state == "pre") "  ·  Hold OK: remind or record" else ""
}

/**
 * The filter pills (All, then each team / league followed), the shared [AppPill]. Hold OK on a team: its options
 * ([onOptions]: Move, Stop following); Move picks it up ([carry]), ◀ ▶ move it ([onMove], the order is saved as in
 * Settings → Sports), OK or Back put it down. Left on All: the menu.
 */
@Composable
private fun SportsPills(
    follows: List<SportFollow>,
    filter: String?,
    carry: Carry,
    panelFocus: HashMap<String, FocusRequester>,
    onOpenMenu: () -> Unit,
    onMove: (SportFollow, Int) -> Unit,
    onOptions: (String) -> Unit,
    onPick: (String?) -> Unit,
    onFollow: () -> Unit = {},
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        LazyRow(contentPadding = PaddingValues(start = Tokens.Gutter, end = Tokens.Gutter, top = 4.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val picks = listOf<Pair<String?, String>>(null to "All") + follows.map { it.shortcutId to it.name }
            itemsIndexed(picks, key = { _, p -> "f-${p.first}" }) { i, (id, name) ->
                val carried = id != null && carry.isCarried(id)
                var focused by remember { mutableStateOf(false) }
                AppPill(
                    text = if (carried) "‹ $name ›" else name, selected = filter == id, carried = carried,
                    modifier = Modifier.focusRequester(panelFocus.getOrPut("f-$id") { FocusRequester() })
                        // (Moving off it some other way puts it down: after a moment, as the row redrawing after a move
                        // blurs the pill for a frame and dropped the carry at once.)
                        .onFocusChanged {
                            focused = it.isFocused
                            if (!it.isFocused && carried && id != null) scope.launch { delay(300); if (!focused && carry.isCarried(id)) carry.drop() }
                        }
                        .reorderable(
                            carried = carried,
                            onMove = { by -> follows.firstOrNull { it.shortcutId == id }?.let { onMove(it, by) } },
                            onDrop = carry::drop,
                            axis = ReorderAxis.Horizontal,
                        )
                        .leftOpens(enabled = i == 0 && !carry.active, onMenu = onOpenMenu),
                    // Hold OK: Move, Stop following (Peter, 2026-11-17: remove from here too).
                    onLongClick = if (id != null) ({ onOptions(id) }) else null,
                    onClick = { onPick(id) },
                )
            }
            // Last: find and follow another team or league (Settings → Sports).
            item(key = "f-follow") {
                AppPill(text = "＋ Follow a team", selected = false, carried = false,
                    modifier = Modifier.focusRequester(panelFocus.getOrPut("f-follow") { FocusRequester() })
                        .leftOpens(enabled = follows.isEmpty() && !carry.active, onMenu = onOpenMenu),
                    onLongClick = null, onClick = onFollow)
            }
        }
        if (carry.active) Text(movingHint(ReorderAxis.Horizontal), fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = Tokens.Gutter, bottom = 4.dp))
    }
}

/** A day's heading: "Today", "Tomorrow", else "Saturday 21 November". */
private fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEEE d MMMM" else "EEEE d MMMM yyyy"))
}

/** When it's on and where: "Live 63'  ·  Sky Sports Main Event", "Sat 15:00  ·  TNT Sports 1", "Full time". */
private fun whenLine(c: GameCard): String {
    val where = c.channel?.name?.let { if (c.club) "$it (${ClubChannels.word(c.game)} stream)" else if (!c.sure) "possibly $it" else it } ?: c.named.firstOrNull()
    return when (c.game.state) {
        "in" -> "Live" + (c.game.detail?.let { " $it" } ?: "") + (where?.let { "  ·  $it" } ?: "")
        "post" -> "Full time  ·  " + Dates.dayTime(c.game.start)
        else -> Dates.dayTime(c.game.start) + (where?.let { "  ·  $it" } ?: if (c.notOnUkTv) "  ·  not on UK TV" else "  ·  not on your channels yet")
    }
}

/**
 * A game as one slim line of the fixture guide: kick-off (or the minute, or FT), both teams with their badges and the
 * score between, the competition, and where it's on (orange: one of your channels, OK watches it).
 */
@Composable
private fun FixtureLine(c: GameCard, modifier: Modifier, reminded: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val g = c.game
    // The standard list-row highlight: accent fill, OnAccent text (it was a navy fill of its own; UI review, Tier 3).
    var focused by remember { mutableStateOf(false) }
    val on = AppColors.OnAccent
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(40.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(Tokens.RowShape),
        scale = NoScale,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.04f), focusedContainerColor = AppColors.Accent,
            pressedContainerColor = AppColors.Accent,
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val (time, timeColour) = when (g.state) {
                "in" -> (g.detail ?: "LIVE") to Color(0xFFFF5A5F)
                "post" -> "FT" to AppColors.TextSecondary
                else -> Dates.time(g.start) to Color.White
            }
            val main = if (focused) on else Color.White
            val dim = if (focused) on else AppColors.TextSecondary
            // (🔔: a reminder is set for it. Peter, 2026-11-18: Remind me showed nothing.)
            Text(if (reminded) "🔔" else "", fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(18.dp))
            Text(time, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (focused) on else timeColour, maxLines = 1, modifier = Modifier.width(56.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(g.home, fontSize = 14.sp, color = main, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp)); Badge(g.homeLogo, g.home)
            }
            Text(g.score ?: "v", fontSize = if (g.score != null) 15.sp else 13.sp, fontWeight = FontWeight.Bold,
                color = if (g.score != null) main else dim, textAlign = TextAlign.Center, modifier = Modifier.width(60.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Badge(g.awayLogo, g.away); Spacer(Modifier.width(8.dp))
                Text(g.away, fontSize = 14.sp, color = main, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(g.leagueName, fontSize = 12.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp).padding(start = 12.dp))
            val where = c.channel?.name?.let { if (c.club) "$it (${ClubChannels.word(g)})" else if (!c.sure) "Possibly $it" else it }
                ?: c.named.firstOrNull()
                ?: when { g.state == "post" -> ""; c.notOnUkTv -> "Not on UK TV"; else -> "—" }
            Text(where, fontSize = 13.sp, fontWeight = if (c.channel != null && c.sure) FontWeight.Medium else FontWeight.Normal,
                color = if (focused) on else if (c.channel != null && c.sure) AppColors.Accent else AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, modifier = Modifier.width(200.dp).padding(start = 12.dp))
        }
    }
}

@Composable
private fun Badge(logo: String?, name: String) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (logo != null) AsyncImage(model = logo, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Text(name.take(3).uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
