package io.github.petebutty.viewarr.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.sports.SportFollow
import io.github.petebutty.viewarr.data.sports.SportsRepository
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.appBackground
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.github.petebutty.viewarr.data.sports.GameInfoSource
import io.github.petebutty.viewarr.data.sports.GameWatch
import io.github.petebutty.viewarr.ui.theme.UiOpts
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.moveOption
import io.github.petebutty.viewarr.ui.common.movingHint
import io.github.petebutty.viewarr.ui.common.rememberCarry
import io.github.petebutty.viewarr.ui.common.reorderable
import io.github.petebutty.viewarr.ui.theme.Tokens
import androidx.compose.foundation.verticalScroll

@HiltViewModel
class SportsViewModel @Inject constructor(
    private val repo: SportsRepository,
    /** A game's line-ups, form and the rest (the game page). */
    val gameInfo: GameInfoSource,
    /** Reminders / recordings asked for before a game is in the guide, and "record all of a team's games". */
    val gameWatch: GameWatch,
) : ViewModel() {
    val follows = repo.follows.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _results = MutableStateFlow<List<SportFollow>>(emptyList())
    val results = _results.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()
    private val _heading = MutableStateFlow("Add")
    /** What the list shows: "Add" (search results), a sport's name, or a league's teams. */
    val heading = _heading.asStateFlow()
    private var job: Job? = null
    /** Where Back goes from a league's teams: the list you chose the league from (and which league it was). */
    private val stack = ArrayDeque<Triple<String, List<SportFollow>, String>>()

    /** Back from a league's teams to the list before. The league to land on, or null: nothing to go back to. */
    fun back(): String? {
        val (h, r, from) = stack.removeLastOrNull() ?: return null
        job?.cancel(); _searching.value = false
        _heading.value = h; _results.value = r
        return from
    }

    /** Back from a sport's (or a search's) list: the list closes. */
    fun clear() { job?.cancel(); stack.clear(); _searching.value = false; _heading.value = "Add"; _results.value = emptyList() }

    /** As you type (a short pause first): teams and leagues matching (a sport's name: all its leagues). */
    fun search(q: String) {
        job?.cancel(); stack.clear()
        job = viewModelScope.launch {
            delay(350)
            _searching.value = true
            _heading.value = io.github.petebutty.viewarr.data.sports.Sport.named(q)?.let { "Add: ${it.label} and more" } ?: "Add"
            _results.value = runCatching { repo.search(q) }.getOrDefault(emptyList())
            _searching.value = false
        }
    }

    /** A sport's button: its leagues and competitions. */
    fun browse(sport: io.github.petebutty.viewarr.data.sports.Sport) {
        job?.cancel(); stack.clear(); _searching.value = false
        _heading.value = "${sport.emoji}  ${sport.label}"
        _results.value = repo.leaguesOf(sport).map(repo::asFollow)
    }

    /** A league's Choose a team: its teams, A–Z. */
    fun teams(league: String) {
        job?.cancel()
        stack.addLast(Triple(_heading.value, _results.value, "sport:league:$league"))
        // "Searching" before the list empties: an empty list that isn't loading sends the highlight back to the buttons.
        _searching.value = true
        _results.value = emptyList()
        job = viewModelScope.launch {
            _heading.value = "${repo.league(league).name}: teams"
            _results.value = runCatching { repo.teamsIn(league) }.getOrDefault(emptyList())
            _searching.value = false
        }
    }

    /** Whether a league has teams to pick from. */
    fun hasTeams(league: String) = repo.league(league).teams

    /** A followed team's or league's games (the Sports hub's rows). */
    suspend fun games(f: SportFollow) = runCatching { repo.games(f) }.getOrDefault(emptyList())
    /** ESPN's headline games across every sport today (Live now). */
    suspend fun headline() = runCatching { repo.headline() }.getOrDefault(emptyList())
    fun league(path: String) = repo.league(path)

    fun follow(f: SportFollow) = viewModelScope.launch {
        if (follows.value.none { it.shortcutId == f.shortcutId }) repo.setFollows(follows.value + f)
    }
    fun unfollow(f: SportFollow) = viewModelScope.launch { repo.setFollows(follows.value.filter { it.shortcutId != f.shortcutId }) }
    // The order as last moved here (a held key comes faster than the saved list comes back).
    private var order: List<SportFollow>? = null
    init { viewModelScope.launch { follows.collect { order = null } } }
    /** Moves a followed team / league [by] places (the order of the Sports page's filters and its games). */
    fun move(f: SportFollow, by: Int) {
        val list = (order ?: follows.value).toMutableList()
        val i = list.indexOfFirst { it.shortcutId == f.shortcutId }.takeIf { it >= 0 } ?: return
        val j = (i + by).coerceIn(0, list.lastIndex)
        if (i == j) return
        list.add(j, list.removeAt(i))
        order = list
        viewModelScope.launch { repo.setFollows(list) }
    }
}

/**
 * Settings → Sports: type a team or league, pick it from the list, and it's yours: its games on the Sports page (on
 * now, coming up, results, and where to watch them from your guide), and score alerts if you want them. Saved per
 * profile.
 */
@Composable
fun SportsSettingsScreen(onBack: () -> Unit, vm: SportsViewModel = hiltViewModel()) {
    val appearance: AppearanceViewModel = hiltViewModel()
    val sa = io.github.petebutty.viewarr.data.sports.ScoreAlerts
    var alertPick by remember { mutableStateOf<String?>(null) }
    var alertTeam by remember { mutableStateOf<SportFollow?>(null) }
    val follows by vm.follows.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    // A league picked from the list: follow it, or choose one of its teams.
    var leagueFor by remember { mutableStateOf<SportFollow?>(null) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<SportFollow?>(null) }
    // A followed one being moved (its id; the shared ui/common/Reorder.kt, Peter, 2026-11-17).
    val carry = rememberCarry()
    val searchFocus = remember { FocusRequester() }
    // Opens on the first sport button, not the search box (that would bring the keyboard up straight away).
    val firstPill = remember { FocusRequester() }
    val pillFocus = remember { HashMap<io.github.petebutty.viewarr.data.sports.Sport, FocusRequester>() }
    var browsed by remember { mutableStateOf<io.github.petebutty.viewarr.data.sports.Sport?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    fun rowReq(id: String) = rowFocus.getOrPut(id) { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Where focus goes once the list has changed: "first" (a league's teams arrived), or a row by its id.
    var focusKey by remember { mutableStateOf<String?>(null) }
    // The row that has the highlight now (to check a move really landed).
    var rowFocused by remember { mutableStateOf<String?>(null) }
    // (Not while a pop-up is open: it has the remote, and the highlight put here meanwhile was lost when it closed.)
    LaunchedEffect(results, focusKey, searching, leagueFor, picked) {
        val key = focusKey ?: return@LaunchedEffect
        if (leagueFor != null || picked != null) return@LaunchedEffect
        // Still loading: wait. (A league's teams arrive a frame before "searching" goes off; starting then, the
        // change restarted this and dropped the move, leaving nothing highlighted and the remote doing nothing.)
        if (searching) return@LaunchedEffect
        if (results.isEmpty()) { focusKey = null; awaitFrame(); runCatching { firstPill.requestFocus() }; return@LaunchedEffect }
        val i = if (key == "first") 0 else results.indexOfFirst { it.shortcutId == key }.coerceAtLeast(0)
        listState.scrollToItem(i) // (after the heading: index i + 1 is the row; i keeps the heading in view)
        // A pop-up that's just been closed is still going for a moment (its window has the remote): a highlight put here
        // meanwhile was lost with it. So: try, and keep trying until a row really has it (a second at most).
        val id = results[i].shortcutId
        if (retryFocus(24, { rowReq(id).requestFocus() }) { rowFocused == id }) { focusKey = null; return@LaunchedEffect }
        focusKey = null
        runCatching { firstPill.requestFocus() }
    }
    BackHandler {
        when {
            // Carrying a followed one: Back puts it down (Modifier.reorderable takes that press; here too, should it
            // ever reach this), never leaves the page.
            carry.active -> carry.drop()
            picked != null -> picked = null
            leagueFor != null -> leagueFor = null
            // One step at a time: a league's teams → the list you picked it from → the sport buttons → out.
            // (A typed search with nothing found is cleared first, as one with results.)
            else -> vm.back()?.let { focusKey = it } ?: if (results.isNotEmpty() || query.isNotBlank()) {
                query = ""
                vm.clear()
                scope.launch { awaitFrame(); runCatching { (browsed?.let { pillFocus[it] } ?: firstPill).requestFocus() } }
            } else onBack()
        }
    }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { firstPill.requestFocus() } }
    // A followed one's pop-up closed: back on it, or (stopped following, so it's gone) the next one, else the buttons.
    var pickedWas by remember { mutableStateOf<SportFollow?>(null) }
    // Where it was in the list: after Stop following, the one now in its place (else the last), not the first.
    var pickedAt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Stop following: the list only changes once it's saved, so wait for that before moving the highlight.
    var removing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(picked, follows) {
        if (picked != null) { pickedWas = picked; pickedAt = follows.indexOfFirst { it.shortcutId == picked?.shortcutId }; return@LaunchedEffect }
        val was = pickedWas ?: return@LaunchedEffect
        if (removing != null && follows.any { it.shortcutId == removing }) return@LaunchedEffect
        pickedWas = null; removing = null
        val target = follows.firstOrNull { it.shortcutId == was.shortcutId } ?: follows.getOrNull(pickedAt) ?: follows.lastOrNull()
        val landed = retryFocus(12, { if (target != null) rowReq("f-" + target.shortcutId).requestFocus() }) { target != null && rowFocused == "f-" + target.shortcutId }
        if (!landed) runCatching { firstPill.requestFocus() }
    }
    // OK on a 🔔 team takes it out of the Score alerts list: the highlight goes to the next one, or the Score alerts
    // row once the list is empty (it was left on nothing). The same wait-and-retry as the results list.
    var alertFocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(alertFocus) {
        val id = alertFocus ?: return@LaunchedEffect
        retryFocus(24, { rowReq(id).requestFocus() }) { rowFocused == id }
        alertFocus = null
    }
    Box(Modifier.fillMaxSize().appBackground(readable = true).trapFocus()) {
        Row(Modifier.fillMaxSize().padding(horizontal = Tokens.Gutter, vertical = 40.dp)) {
            // (Below the live TV window when one is up, as SettingsPage.)
            Column(Modifier.width(300.dp).fillMaxHeight().padding(top = if (SettingsLayout.belowPreview) 170.dp else 0.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("Settings", fontSize = 14.sp, color = AppColors.Accent)
                Text("Sports", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 16.dp))
                Text(
                    "Follow your teams and leagues: type a name or a sport, or press Down for the sport buttons and browse a sport's leagues, then a league's teams. Sports in the menu shows them as panels: what's on now across them all, then a row for each with its next games and recent results; OK on a game goes to the channel showing it (from your TV guide, the UK Sport on TV plugin or the event channels). " +
                        "\n\nFixtures come from ESPN's public sports data (only the team or league is asked about). Saved for this profile.",
                    fontSize = 13.sp, color = AppColors.TextSecondary,
                )
                // OK on a game: its page (line-ups, form, remind / record), or just a note as before.
                val optsVm: UiOptsViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                val pageOn = AppColors.palette.opt(UiOpts.SPORTS_GAME_PAGE) == 0
                Spacer(Modifier.padding(top = 14.dp))
                MenuItem(text = "Game page", trailing = if (pageOn) "On" else "Off", selected = false,
                    modifier = Modifier.leftGoesBack(onBack),
                    onClick = { optsVm.set(UiOpts.SPORTS_GAME_PAGE, if (pageOn) 1 else 0) })
                Text("OK on a game opens its page: line-ups, each team's last five, this season's meetings, goals and stats, with Remind me / Record (also before it's in your guide, or every game of a team). Off: OK just says when and where it's on.",
                    fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
            }
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // The keyboard opens on OK, not just by landing on the box (as every text box on the remote); Back or
                // moving off it closes it.
                var editing by remember { mutableStateOf(false) }
                val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it; browsed = null; vm.search(it) },
                    label = { androidx.compose.material3.Text("Search a team, league or sport (e.g. Celtic, Premier League, golf)") },
                    singleLine = true, readOnly = !editing,
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).downTo { firstPill }
                        .onFocusChanged { if (!it.isFocused) { editing = false; keyboard?.hide() } }
                        .onPreviewKeyEvent { e ->
                            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                androidx.compose.ui.input.key.Key.DirectionCenter, androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.NumPadEnter ->
                                    if (editing) false else { editing = true; scope.launch { awaitFrame(); keyboard?.show() }; true }
                                androidx.compose.ui.input.key.Key.Back -> { editing = false; false }
                                else -> false
                            }
                        },
                )
                // Or browse: a sport's leagues, then a league's teams.
                val sports = io.github.petebutty.viewarr.data.sports.Sport.entries.filter { it != io.github.petebutty.viewarr.data.sports.Sport.Other }
                androidx.compose.foundation.lazy.LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sports, key = { it.name }) { sp ->
                        MenuItem(text = "${sp.emoji}  ${sp.label}", selected = heading.endsWith(sp.label), compact = true,
                            modifier = Modifier.width(if (sp.label.length > 12) 196.dp else 140.dp)
                                .then(if (sp == sports.first()) Modifier.focusRequester(firstPill) else Modifier)
                                .focusRequester(pillFocus.getOrPut(sp) { FocusRequester() })
                                // Down: the list's first row (not whichever is nearest underneath).
                                .downTo { results.firstOrNull()?.let { rowReq(it.shortcutId) } },
                            onClick = { browsed = sp; vm.browse(sp) })
                    }
                }
                if (searching) Text("Searching…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
                LazyColumn(Modifier.padding(top = 6.dp), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    if (results.isNotEmpty()) item(key = "h-res") { Header(heading) }
                    // A league with no teams found (or ESPN not answering): said, not an empty space.
                    if (results.isEmpty() && !searching && heading.endsWith(": teams")) item(key = "none") {
                        Text("No teams found for this one. Back to try another.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(4.dp))
                    }
                    items(results, key = { "r-" + it.shortcutId }) { f ->
                        val following = follows.any { it.shortcutId == f.shortcutId }
                        val league = f.kind == SportFollow.Kind.League && vm.hasTeams(f.league)
                        SportRow(f, trailing = when { league -> if (following) "Following · OK: teams" else "OK: follow or pick a team"; following -> "Following"; else -> "OK: follow" } +
                            (if (sa.teamOn(f)) " · 🔔" else "") + " · hold OK: alerts",
                            onLongClick = { alertTeam = f },
                            // Up from the first row: the sport button you came from (not whichever is nearest above).
                            modifier = Modifier.focusRequester(rowReq(f.shortcutId)).onFocusChanged { if (it.isFocused) rowFocused = f.shortcutId else if (rowFocused == f.shortcutId) rowFocused = null }
                                .then(if (f == results.first()) Modifier.keyTo(androidx.compose.ui.input.key.Key.DirectionUp) { browsed?.let { pillFocus[it] } ?: firstPill } else Modifier)) {
                            if (league) leagueFor = f else if (!following) vm.follow(f)
                        }
                    }
                    item(key = "h-fol") { Header(if (carry.active) movingHint() else if (follows.isEmpty()) "Following: none yet" else "Following") }
                    // The order of the Sports page's filters along the top. OK or hold OK: options (Move, Move to the
                    // top, Stop following); Move or Right (the Settings shortcut) picks one up, ▲ ▼ move it, OK / Back /
                    // Left / Right put it down (the shared Modifier.reorderable).
                    items(follows, key = { "f-" + it.shortcutId }) { f ->
                        val carried = carry.isCarried(f.shortcutId)
                        SportRow(f, trailing = if (carried) "↕" else if (sa.teamOn(f)) "Following · 🔔 · OK: options" else "Following · OK: options",
                            modifier = Modifier.focusRequester(rowReq("f-" + f.shortcutId))
                            .onFocusChanged {
                                if (it.isFocused) rowFocused = "f-" + f.shortcutId
                                else if (rowFocused == "f-" + f.shortcutId) {
                                    rowFocused = null
                                    // Moving off it some other way puts it down: after a moment, as the list redrawing
                                    // after a move blurs the row for a frame and dropped the carry at once.
                                    if (carried) scope.launch { delay(300); if (rowFocused != "f-" + f.shortcutId && carry.isCarried(f.shortcutId)) carry.drop() }
                                }
                            }
                            .reorderable(
                                carried = carried, onMove = { dir -> vm.move(f, dir) }, onDrop = carry::drop,
                                rightToggles = follows.size >= 2, onPickUp = { carry.pickUp(f.shortcutId) },
                            ),
                            onLongClick = { if (!carry.active) picked = f }) { if (carried) carry.drop() else picked = f }
                    }
                    // Score alerts (the 🔔 banner and the ticker's Live scores) for the games of what you follow.
                    item(key = "h-alerts") { Header("Score alerts") }
                    item(key = "al-on") {
                        MenuItem(text = "Score alerts", trailing = sa.mode + "  ▾", selected = false, onClick = { alertPick = "mode" },
                            modifier = Modifier.focusRequester(rowReq("al-on")).onFocusChanged { if (it.isFocused) rowFocused = "al-on" else if (rowFocused == "al-on") rowFocused = null })
                    }
                    if (sa.on) {
                        items(sa.kinds, key = { "al-k-" + it.first }) { (k, label) ->
                            MenuItem(text = "      $label", trailing = (if (sa.kindOn(k)) "On" else "Off") + "  ▾", selected = false, compact = true,
                                onClick = { alertPick = k })
                        }
                    }
                    // The teams and leagues alerts are on for (none to start).
                    val alertTeams = sa.teams()
                    if (alertTeams.isEmpty()) item(key = "al-none") {
                        Text("No teams yet: alerts are off until you choose one. Hold OK on a team or league in the search results (followed or not), or OK on one under Following → Score alerts on.",
                            fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 12.dp))
                    } else {
                        items(alertTeams, key = { "al-t-" + sa.teamKey(it) }) { t ->
                            val id = "al-t-" + sa.teamKey(t)
                            MenuItem(text = "🔔  " + t.name + (if (t.kind == SportFollow.Kind.League) "  (league)" else ""), trailing = "OK: alerts off", selected = false, compact = true,
                                modifier = Modifier.focusRequester(rowReq(id)).onFocusChanged { if (it.isFocused) rowFocused = id else if (rowFocused == id) rowFocused = null },
                                onClick = {
                                    // The next team (or the one before, at the end), else the Score alerts row.
                                    val i = alertTeams.indexOf(t)
                                    val next = alertTeams.getOrNull(i + 1) ?: alertTeams.getOrNull(i - 1)
                                    alertFocus = next?.let { "al-t-" + sa.teamKey(it) } ?: "al-on"
                                    appearance.setTitlePart(sa.teamKey(t), true)
                                })
                        }
                        item(key = "al-hint") {
                            Text("Add more: hold OK on a team or league in the search results, followed or not. Basketball has no scoring alerts, just the start, half-time and the result.",
                                fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 12.dp))
                        }
                    }
                }
            }
        }
        // A league from the list: follow the whole league, or choose one of its teams.
        // (Both through the shared options pop-up, Cancel last: they were raw Dialogs. Peter, 2026-11-17.)
        leagueFor?.let { f ->
            val following = follows.any { it.shortcutId == f.shortcutId }
            OptionsPopup(
                title = f.name,
                options = listOfNotNull(
                    PopupOption("Choose a team") { vm.teams(f.league); focusKey = "first" },
                    if (!following) PopupOption("Follow the whole league") { vm.follow(f); focusKey = f.shortcutId } else null,
                ),
                // (Choose a team set "first" already; Cancel / Back come back to the league.)
                onClose = { leagueFor = null; if (focusKey == null) focusKey = f.shortcutId },
            )
        }
        // Hold OK on a search result: follow it, and / or score alerts for it (followed or not).
        alertTeam?.let { f ->
            val following = follows.any { it.shortcutId == f.shortcutId }
            OptionsPopup(
                title = f.name,
                options = listOfNotNull(
                    if (!following) PopupOption("Follow ${f.name}") { vm.follow(f) } else null,
                    if (sa.teamOn(f)) PopupOption("Score alerts off for ${f.name}") { appearance.setTitlePart(sa.teamKey(f), true) }
                    else PopupOption("Score alerts on for ${f.name}") { appearance.setTitlePart(sa.teamKey(f), false) },
                ),
                onClose = { alertTeam = null },
            )
        }
        // Score alerts: each row's list of choices (as the other settings' drop-downs).
        alertPick?.let { what ->
            val title = if (what == "mode") "Score alerts" else sa.kinds.firstOrNull { it.first == what }?.second ?: ""
            val choices = if (what == "mode") sa.modes else listOf("On", "Off")
            OptionsPopup(
                title = title,
                options = choices.map { c ->
                    PopupOption(c) {
                        if (what == "mode") sa.partsFor(c).forEach { (k, v) -> appearance.setTitlePart(k, v) }
                        else appearance.setTitlePart(sa.kindKey(what), c == "On")
                    }
                },
                onClose = { alertPick = null },
            )
        }
        // A followed team / league: move it, or stop following it. (Its games are on the Sports page.)
        picked?.let { f ->
            OptionsPopup(
                title = f.name,
                options = listOfNotNull(
                    if (follows.size >= 2) carry.moveOption(f.shortcutId) else null,
                    if (follows.firstOrNull()?.shortcutId != f.shortcutId) PopupOption("Move to the top") { vm.move(f, -follows.size) } else null,
                    if (sa.teamOn(f)) PopupOption("Score alerts off for ${f.name}") { appearance.setTitlePart(sa.teamKey(f), true) }
                    else PopupOption("Score alerts on for ${f.name}") { appearance.setTitlePart(sa.teamKey(f), false) },
                    PopupOption("Stop following") { removing = f.shortcutId; vm.unfollow(f) },
                ),
                onClose = { picked = null },
            )
        }
    }
}

/**
 * Asks for the highlight again every 40 ms, up to [tries] times, until [landed] says a row has it (a pop-up just
 * closed still has the remote for a moment, and a request made meanwhile is lost). True: it landed.
 */
private suspend fun retryFocus(tries: Int, request: () -> Unit, landed: () -> Boolean): Boolean {
    repeat(tries) {
        delay(40)
        runCatching { request() }
        awaitFrame()
        if (landed()) return true
    }
    return false
}

@Composable
private fun Header(t: String) = Text(t, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColors.Accent, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp))

/** A team or league: its crest (or its sport), its name and league, and what OK does. */
@Composable
private fun SportRow(f: SportFollow, trailing: String, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    MenuItem(
        modifier = modifier, onLongClick = onLongClick,
        text = f.name + (if (f.kind == SportFollow.Kind.League) "  (league)" else "") + (f.detail?.let { "  ·  $it" } ?: ""),
        trailing = trailing, selected = false, onClick = onClick,
        leading = if (f.logo != null) ({ AsyncImage(f.logo, null, Modifier.size(26.dp).padding(end = 2.dp)) }) else null,
    )
}

/**
 * Down goes to [target] when it's on screen (a request to one not yet drawn would crash), else wherever Down normally
 * goes.
 */
private fun Modifier.downTo(target: () -> FocusRequester?): Modifier = keyTo(androidx.compose.ui.input.key.Key.DirectionDown, target)

/** [key] goes to [target] when it's on screen, else wherever that key normally goes. */
private fun Modifier.keyTo(key: androidx.compose.ui.input.key.Key, target: () -> FocusRequester?): Modifier = this.onPreviewKeyEvent { e ->
    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || e.key != key) return@onPreviewKeyEvent false
    val t = target() ?: return@onPreviewKeyEvent false
    runCatching { t.requestFocus() }.isSuccess
}
