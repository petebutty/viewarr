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
import io.github.petebutty.viewarr.ui.common.glass
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

@HiltViewModel
class SportsViewModel @Inject constructor(private val repo: SportsRepository) : ViewModel() {
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
    fun league(path: String) = repo.league(path)

    fun follow(f: SportFollow) = viewModelScope.launch {
        if (follows.value.none { it.shortcutId == f.shortcutId }) repo.setFollows(follows.value + f)
    }
    fun unfollow(f: SportFollow) = viewModelScope.launch { repo.setFollows(follows.value.filter { it.shortcutId != f.shortcutId }) }
    fun setPinned(f: SportFollow, pinned: Boolean) = viewModelScope.launch {
        repo.setFollows(follows.value.map { if (it.shortcutId == f.shortcutId) it.copy(pinned = pinned) else it })
    }
}

/**
 * Settings → Sports: type a team or league, pick it from the list, and it's yours: a row of its games under the
 * Live TV hub's Sports shortcut (on now, coming up, results, and where to watch them from your guide). Pin one to
 * give it its own shortcut in the row too. Saved per profile.
 */
@Composable
fun SportsSettingsScreen(onBack: () -> Unit, vm: SportsViewModel = hiltViewModel()) {
    val follows by vm.follows.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    // A league picked from the list: follow it, or choose one of its teams.
    var leagueFor by remember { mutableStateOf<SportFollow?>(null) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<SportFollow?>(null) }
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
        if (results.isEmpty()) { if (!searching) { focusKey = null; awaitFrame(); runCatching { firstPill.requestFocus() } }; return@LaunchedEffect }
        val i = if (key == "first") 0 else results.indexOfFirst { it.shortcutId == key }.coerceAtLeast(0)
        focusKey = null
        listState.scrollToItem(i) // (after the heading: index i + 1 is the row; i keeps the heading in view)
        // A pop-up that's just been closed is still going for a moment (its window has the remote): a highlight put here
        // meanwhile was lost with it. So: try, and keep trying until a row really has it (half a second at most).
        val id = results[i].shortcutId
        repeat(12) {
            delay(40)
            runCatching { rowReq(id).requestFocus() }
            awaitFrame()
            if (rowFocused == id) return@LaunchedEffect
        }
        runCatching { firstPill.requestFocus() }
    }
    BackHandler {
        when {
            picked != null -> picked = null
            leagueFor != null -> leagueFor = null
            // One step at a time: a league's teams → the list you picked it from → the sport buttons → out.
            else -> vm.back()?.let { focusKey = it } ?: if (results.isNotEmpty()) {
                vm.clear()
                scope.launch { awaitFrame(); runCatching { (browsed?.let { pillFocus[it] } ?: firstPill).requestFocus() } }
            } else onBack()
        }
    }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { firstPill.requestFocus() } }
    // A followed one's pop-up closed: back on it, or (stopped following, so it's gone) the next one, else the buttons.
    var pickedWas by remember { mutableStateOf<SportFollow?>(null) }
    // Stop following: the list only changes once it's saved, so wait for that before moving the highlight.
    var removing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(picked, follows) {
        if (picked != null) { pickedWas = picked; return@LaunchedEffect }
        val was = pickedWas ?: return@LaunchedEffect
        if (removing != null && follows.any { it.shortcutId == removing }) return@LaunchedEffect
        pickedWas = null; removing = null
        val target = follows.firstOrNull { it.shortcutId == was.shortcutId } ?: follows.firstOrNull()
        repeat(12) {
            delay(40)
            if (target != null) runCatching { rowReq("f-" + target.shortcutId).requestFocus() }
            awaitFrame()
            if (target != null && rowFocused == "f-" + target.shortcutId) return@LaunchedEffect
        }
        runCatching { firstPill.requestFocus() }
    }
    Box(Modifier.fillMaxSize().appBackground(readable = true).trapFocus()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            Column(Modifier.width(300.dp).fillMaxHeight()) {
                Text("Settings", fontSize = 14.sp, color = AppColors.Accent)
                Text("Sports", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 16.dp))
                Text(
                    "Follow your teams and leagues: type a name or a sport, or press Down for the sport buttons and browse a sport's leagues, then a league's teams. Sports in the menu shows them as panels: what's on now across them all, then a row for each with its next games and recent results; OK on a game goes to the channel showing it (from your TV guide, the UK Sport on TV plugin or the event channels). " +
                        "\n\nFixtures come from ESPN's public sports data (only the team or league is asked about). Saved for this profile.",
                    fontSize = 13.sp, color = AppColors.TextSecondary,
                )
            }
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it; browsed = null; vm.search(it) },
                    label = { androidx.compose.material3.Text("Search a team, league or sport (e.g. Celtic, Premier League, golf)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).downTo { firstPill },
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
                        SportRow(f, trailing = when { league -> if (following) "Following · OK: teams" else "OK: follow or pick a team"; following -> "Following"; else -> "OK to follow" },
                            // Up from the first row: the sport button you came from (not whichever is nearest above).
                            modifier = Modifier.focusRequester(rowReq(f.shortcutId)).onFocusChanged { if (it.isFocused) rowFocused = f.shortcutId else if (rowFocused == f.shortcutId) rowFocused = null }
                                .then(if (f == results.first()) Modifier.keyTo(androidx.compose.ui.input.key.Key.DirectionUp) { browsed?.let { pillFocus[it] } ?: firstPill } else Modifier)) {
                            if (league) leagueFor = f else if (!following) vm.follow(f)
                        }
                    }
                    item(key = "h-fol") { Header(if (follows.isEmpty()) "Following: none yet" else "Following") }
                    items(follows, key = { "f-" + it.shortcutId }) { f ->
                        SportRow(f, trailing = "Following · OK: stop", modifier = Modifier.focusRequester(rowReq("f-" + f.shortcutId))
                            .onFocusChanged { if (it.isFocused) rowFocused = "f-" + f.shortcutId else if (rowFocused == "f-" + f.shortcutId) rowFocused = null }) { picked = f }
                    }
                }
            }
        }
        // A league from the list: follow the whole league, or choose one of its teams.
        leagueFor?.let { f ->
            androidx.compose.ui.window.Dialog(onDismissRequest = { leagueFor = null; focusKey = f.shortcutId }) {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
                val following = follows.any { it.shortcutId == f.shortcutId }
                Column(
                    Modifier.width(420.dp).glass(18.dp).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(f.name, fontSize = 18.sp, color = AppColors.TextPrimary)
                    MenuItem(text = "Choose a team", selected = false, modifier = Modifier.focusRequester(first), onClick = { vm.teams(f.league); leagueFor = null; focusKey = "first" })
                    if (!following) MenuItem(text = "Follow the whole league", selected = false, onClick = { vm.follow(f); leagueFor = null; focusKey = f.shortcutId })
                    MenuItem(text = "Cancel", selected = false, onClick = { leagueFor = null; focusKey = f.shortcutId })
                }
            }
        }
        // A followed team / league: stop following it. (No pinning: the shortcut row it pinned to is gone; its games
        // are on the Sports page.)
        picked?.let { f ->
            androidx.compose.ui.window.Dialog(onDismissRequest = { picked = null }) {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
                Column(
                    Modifier.width(420.dp).glass(18.dp).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(f.name, fontSize = 18.sp, color = AppColors.TextPrimary)
                    MenuItem(text = "Stop following", selected = false, modifier = Modifier.focusRequester(first), onClick = { removing = f.shortcutId; vm.unfollow(f); picked = null })
                    MenuItem(text = "Cancel", selected = false, onClick = { picked = null })
                }
            }
        }
    }
}

@Composable
private fun Header(t: String) = Text(t, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColors.Accent, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp))

/** A team or league: its crest (or its sport), its name and league, and what OK does. */
@Composable
private fun SportRow(f: SportFollow, trailing: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    MenuItem(
        modifier = modifier,
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
