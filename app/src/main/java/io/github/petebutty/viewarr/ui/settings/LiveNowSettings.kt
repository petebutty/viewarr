package io.github.petebutty.viewarr.ui.settings

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.guide.LiveSports
import io.github.petebutty.viewarr.data.sports.LiveNowPick
import io.github.petebutty.viewarr.data.sports.LiveNowPicks
import io.github.petebutty.viewarr.data.sports.SportFollow
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Tokens
import io.github.petebutty.viewarr.ui.theme.appBackground
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings → Live TV → Live now: the list of teams, leagues and sports Live now is limited to (data/sports/LiveNowPicks). */
@HiltViewModel
class LiveNowViewModel @Inject constructor(private val store: LiveNowPicks) : ViewModel() {
    val picks = store.picks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    // (The old switches carried over once, before the list is first shown.)
    init { viewModelScope.launch { runCatching { store.migrate() } } }
    fun add(p: LiveNowPick) = viewModelScope.launch { store.add(p) }
    fun remove(p: LiveNowPick) = viewModelScope.launch { store.remove(p) }
    fun move(p: LiveNowPick, by: Int) = viewModelScope.launch { store.move(p, by) }
    fun fromFollow(f: SportFollow) = store.fromFollow(f)
}

/** The Live now search screen and a pick's Remove pop-up, opened from the settings page (shown over everything). */
object LiveNowUi {
    var adding by mutableStateOf(false)
    var removing by mutableStateOf<LiveNowPick?>(null)
}

/** The Live now list, for the settings page. */
@Composable
fun liveNowPicksState(): List<LiveNowPick> {
    val vm: LiveNowViewModel = hiltViewModel()
    return vm.picks.collectAsStateWithLifecycle().value
}

/**
 * Settings → Live TV → Live now (Peter, 2026-11-19: "search for any team or sport … once added then should be possible
 * to be removed again"): Add, then one row per team, league or sport (OK: remove). Took over from the Live now sports
 * and Live now leagues switches.
 */
fun liveNowCategory(picks: List<LiveNowPick>) = SettingCategory(
    "Live now", "What 📡 Live now in the TV guide shows, in this order. Empty: Live now shows everything live. Add teams, leagues or sports to show only those.",
    listOf(
        SettingItem("Add a team, league or sport", "Search for any team, league or sport (darts, snooker, racing too) and add it: Live now then shows only what's in this list.",
            emptyList(), PlainChoice("OK: search"), action = { LiveNowUi.adding = true }, onSelect = {}),
    ) + picks.map { p ->
        SettingItem((if (p.kind == LiveNowPick.Kind.Sport) LiveSports.emoji(p.id) + "  " else "") + p.name,
            p.kindLine + ". Live now shows games in this list's order. OK: move it up or down, or remove it.", emptyList(), PlainChoice(p.kindLine),
            action = { LiveNowUi.removing = p }, onSelect = {})
    },
)

/** Shown over everything (AppRoot): the search screen and the Remove pop-up. */
@Composable
fun LiveNowHost() {
    if (!LiveNowUi.adding && LiveNowUi.removing == null) return
    val vm: LiveNowViewModel = hiltViewModel()
    val context = LocalContext.current
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    if (LiveNowUi.adding) LiveNowAddScreen(
        live = vm,
        onAdd = { p -> vm.add(p); toast("Added to Live now: ${p.name}"); LiveNowUi.adding = false },
        onBack = { LiveNowUi.adding = false },
    )
    LiveNowUi.removing?.let { p ->
        OptionsPopup(
            title = p.name, subtitle = p.kindLine,
            options = listOf(
                PopupOption("Move up") { vm.move(p, -1) },
                PopupOption("Move down") { vm.move(p, 1) },
                PopupOption("Move to the top") { vm.move(p, -1000) },
                PopupOption("Remove from Live now") { vm.remove(p); toast("Removed from Live now: ${p.name}") },
            ),
            onClose = { LiveNowUi.removing = null },
        )
    }
}

/**
 * Add to Live now: Settings → Sports' search (type a name, or Down for the sport buttons, then a sport's leagues and a
 * league's teams), plus the sports themselves (darts, snooker, horse racing… which ESPN doesn't cover). OK adds it.
 */
@Composable
private fun LiveNowAddScreen(live: LiveNowViewModel, onAdd: (LiveNowPick) -> Unit, onBack: () -> Unit, vm: SportsViewModel = hiltViewModel()) {
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    val picks by live.picks.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    // The sport button pressed (one of LiveSports.names), if any.
    var browsed by remember { mutableStateOf<String?>(null) }
    var leagueFor by remember { mutableStateOf<SportFollow?>(null) }
    var focusKey by remember { mutableStateOf<String?>(null) }
    var rowFocused by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val firstPill = remember { FocusRequester() }
    val pillFocus = remember { HashMap<String, FocusRequester>() }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    fun rowReq(id: String) = rowFocus.getOrPut(id) { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val teamsView = heading.endsWith(": teams")
    // The whole-sport rows: the button's sport, or the sports the typed words name.
    val sportRows = if (teamsView) emptyList() else browsed?.let { listOf(it) } ?: LiveSports.named(query)
    val keys = sportRows.map { "s-$it" } + results.map { "r-" + it.shortcutId }
    val inList = picks.map { it.key }.toSet()
    fun pickOf(f: SportFollow) = live.fromFollow(f)
    DisposableEffect(Unit) { vm.clear(); onDispose { vm.clear() } }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { firstPill.requestFocus() } }
    LaunchedEffect(keys, focusKey, searching, leagueFor) {
        val key = focusKey ?: return@LaunchedEffect
        if (leagueFor != null || searching) return@LaunchedEffect
        focusKey = null
        if (keys.isEmpty()) { awaitFrame(); runCatching { firstPill.requestFocus() }; return@LaunchedEffect }
        val id = keys.firstOrNull { it == "r-$key" } ?: keys.first()
        listState.scrollToItem(keys.indexOf(id))
        if (!retryFocus(24, { rowReq(id).requestFocus() }) { rowFocused == id }) runCatching { firstPill.requestFocus() }
    }
    BackHandler {
        when {
            leagueFor != null -> leagueFor = null
            else -> vm.back()?.let { focusKey = it } ?: if (results.isNotEmpty() || query.isNotBlank() || browsed != null) {
                val was = browsed
                query = ""; browsed = null; vm.clear()
                scope.launch { awaitFrame(); runCatching { (was?.let { pillFocus[it] } ?: firstPill).requestFocus() } }
            } else onBack()
        }
    }
    Box(Modifier.fillMaxSize().appBackground(readable = true).trapFocus()) {
        Row(Modifier.fillMaxSize().padding(horizontal = Tokens.Gutter, vertical = 40.dp)) {
            Column(Modifier.width(300.dp).fillMaxHeight().padding(top = if (SettingsLayout.belowPreview) 170.dp else 0.dp)) {
                Text("Settings → Live now", fontSize = 14.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Add to Live now", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Type a team, league or sport, or press Down for the sport buttons: a sport's button shows the whole sport and its leagues, and a league can show its teams. OK adds it. Live now then shows only what's in your list.",
                    fontSize = 13.sp, color = AppColors.TextSecondary)
            }
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // The keyboard opens on OK, not just by landing on the box (as Settings → Sports).
                var editing by remember { mutableStateOf(false) }
                val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it; browsed = null; vm.search(it) },
                    label = { androidx.compose.material3.Text("Search a team, league or sport (e.g. Celtic, NBA, darts)") },
                    singleLine = true, readOnly = !editing,
                    modifier = Modifier.fillMaxWidth().downTo { firstPill }
                        .onFocusChanged { if (!it.isFocused) { editing = false; keyboard?.hide() } }
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                                    if (editing) false else { editing = true; scope.launch { awaitFrame(); keyboard?.show() }; true }
                                Key.Back -> { editing = false; false }
                                else -> false
                            }
                        },
                )
                androidx.compose.foundation.lazy.LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(LiveSports.names, key = { it }) { name ->
                        val label = LiveSports.label(name)
                        MenuItem(text = "${LiveSports.emoji(name)}  $label", selected = browsed == name, compact = true,
                            modifier = Modifier.width(if (label.length > 12) 230.dp else 140.dp)
                                .then(if (name == LiveSports.names.first()) Modifier.focusRequester(firstPill) else Modifier)
                                .focusRequester(pillFocus.getOrPut(name) { FocusRequester() })
                                .downTo { keys.firstOrNull()?.let { rowReq(it) } },
                            onClick = {
                                query = ""; browsed = name
                                LiveSports.toEspn(name)?.let { vm.browse(it) } ?: vm.clear()
                            })
                    }
                }
                if (searching) Text("Searching…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
                LazyColumn(Modifier.padding(top = 6.dp), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(sportRows, key = { "s-$it" }) { name ->
                        val id = "s-$name"
                        val added = LiveNowPicks.sport(name).key in inList
                        MenuItem(text = "${LiveSports.emoji(name)}  ${LiveSports.label(name)}  (the whole sport)", trailing = if (added) "In Live now" else "OK: add",
                            selected = false,
                            modifier = Modifier.focusRequester(rowReq(id)).onFocusChanged { if (it.isFocused) rowFocused = id else if (rowFocused == id) rowFocused = null }
                                .then(if (id == keys.firstOrNull()) Modifier.keyTo(Key.DirectionUp) { browsed?.let { pillFocus[it] } ?: firstPill } else Modifier),
                            onClick = { if (!added) onAdd(LiveNowPicks.sport(name)) })
                    }
                    if (teamsView) item(key = "h-teams") {
                        Text(heading, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColors.Accent, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (results.isEmpty() && !searching && teamsView) item(key = "none") {
                        Text("No teams found for this one. Back to try another.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(4.dp))
                    }
                    items(results, key = { "r-" + it.shortcutId }) { f ->
                        val id = "r-" + f.shortcutId
                        val added = pickOf(f).key in inList
                        val league = f.kind == SportFollow.Kind.League && vm.hasTeams(f.league)
                        SportRow(f, trailing = when { league -> if (added) "In Live now · OK: teams" else "OK: add or pick a team"; added -> "In Live now"; else -> "OK: add" },
                            modifier = Modifier.focusRequester(rowReq(id)).onFocusChanged { if (it.isFocused) rowFocused = id else if (rowFocused == id) rowFocused = null }
                                .then(if (id == keys.firstOrNull()) Modifier.keyTo(Key.DirectionUp) { browsed?.let { pillFocus[it] } ?: firstPill } else Modifier)) {
                            if (league) leagueFor = f else if (!added) onAdd(pickOf(f))
                        }
                    }
                }
            }
        }
        // A league with teams: add the whole league, or choose one of its teams.
        leagueFor?.let { f ->
            val added = pickOf(f).key in inList
            OptionsPopup(
                title = f.name,
                options = listOfNotNull(
                    if (!added) PopupOption("Add the whole league") { onAdd(pickOf(f)) } else null,
                    PopupOption("Choose a team") { vm.teams(f.league); focusKey = "first" },
                ),
                onClose = { leagueFor = null; if (focusKey == null) focusKey = f.shortcutId },
            )
        }
    }
}
