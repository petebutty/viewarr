package io.github.petebutty.viewarr.ui.settings

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.repository.EpgResult
import io.github.petebutty.viewarr.data.repository.EpgSource
import io.github.petebutty.viewarr.data.repository.EpgSources
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.ViewarrPopup
import io.github.petebutty.viewarr.ui.report.TextEntryDialog
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Tokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GuideSourcesViewModel @Inject constructor(private val repo: EpgSources) : ViewModel() {
    data class Ui(
        val loading: Boolean = true,
        val sources: List<EpgSource> = emptyList(),
        val problem: String? = null,
        val message: String? = null,
        val busy: Boolean = false,
        /** Counts reloads, so the screen can put focus back after one. */
        val reloads: Int = 0,
    )
    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()
    private var poll: Job? = null

    fun load(message: String? = _ui.value.message) {
        viewModelScope.launch {
            val r = runCatching { repo.list() }
            val n = _ui.value.reloads + 1
            _ui.value = r.fold(
                { list ->
                    if (list == null) Ui(loading = false, problem = "Guide sources live on a Dispatcharr server; sign in to one first.")
                    else Ui(loading = false, sources = list, message = message, reloads = n)
                },
                { e -> Ui(loading = false, problem = if (e is SecurityException) "Only Dispatcharr admins can change guide sources." else "Couldn't reach Dispatcharr.") },
            )
            if (_ui.value.sources.any { it.busy }) watch()
        }
    }

    /**
     * While a source is updating: the list is fetched again every 4 seconds, so "Updating…" turns into the result.
     * [atLeast]: polls even before the server says it's busy (a refresh just asked for). Stops after 3 minutes.
     */
    private fun watch(atLeast: Int = 0) {
        if (poll?.isActive == true) return
        poll = viewModelScope.launch {
            var n = 0
            while (n < 45 && (n < atLeast || _ui.value.sources.any { it.busy })) {
                delay(4_000); n++
                val list = runCatching { repo.list() }.getOrNull() ?: break
                _ui.value = _ui.value.copy(sources = list)
            }
        }
    }

    private fun act(work: suspend () -> EpgResult, then: (EpgResult) -> Unit = {}, poll: Boolean = false) {
        // One thing at a time: a second OK while the server is still answering would act on old values.
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, message = "Working…")
        viewModelScope.launch {
            val r = work()
            _ui.value = _ui.value.copy(busy = false, message = r.message)
            load(r.message)
            then(r)
            if (poll && r.ok) watch(atLeast = 3)
        }
    }

    fun setActive(s: EpgSource, on: Boolean) = act({ repo.setActive(s, on) }, poll = on)
    fun refresh(s: EpgSource) = act({ repo.refresh(s) }, poll = true)
    fun rename(s: EpgSource, name: String) = act({ repo.rename(s, name) })
    fun setUrl(s: EpgSource, url: String) = act({ repo.setUrl(s, url) })
    fun setRefresh(s: EpgSource, hours: Int) = act({ repo.setRefresh(s, hours) })
    fun delete(s: EpgSource) = act({ repo.delete(s) })

    /** Every source that's on and fetched by Dispatcharr itself, one after another (not all at once). */
    fun refreshAll() {
        val list = _ui.value.sources.filter { it.active && it.canUpdate }
        if (list.isEmpty()) { say("Nothing to update: no sources are on."); return }
        act({
            val bad = list.map { repo.refresh(it) }.firstOrNull { !it.ok }
            bad ?: EpgResult(true, "Updating ${list.size} guide source" + if (list.size == 1) "…" else "s…")
        }, poll = true)
    }

    /** [done]: with the new source once it's made, or null (refused: the reason shows on the page). */
    fun add(type: String, name: String, url: String?, user: String?, pass: String?, done: (EpgSource?) -> Unit) {
        if (_ui.value.busy) { done(null); return }
        act({ repo.add(type, name, url, user, pass) }, then = { done(if (it.ok) it.created else null) })
    }

    fun say(message: String) { _ui.value = _ui.value.copy(message = message) }
}

/**
 * The row in Settings → Live TV that opens TV guide sources: Dispatcharr admins only (nobody else sees it, or finds
 * it in Search settings).
 */
@Composable
fun guideSourcesItem(): SettingItem? {
    if (!io.github.petebutty.viewarr.ui.profiles.isAdmin()) return null
    return SettingItem("TV guide sources", "Admins: the TV guide (EPG) sources on your Dispatcharr server. Switch each on or off, update it now, rename it, change its address or how often it's fetched, delete it, or add a new XMLTV link or Schedules Direct account.",
        emptyList(), PlainChoice("OK: open"), action = { GuideSourcesPage.open = true }, onSelect = {})
}

/** Settings → Live TV → TV guide sources, asked for from the settings list and drawn over everything (ReportHost). */
object GuideSourcesPage {
    var open by mutableStateOf(false)

    @Composable
    fun Host() { if (open) GuideSourcesScreen { open = false } }
}

/**
 * Settings → Live TV → TV guide sources (admins): every guide source on the Dispatcharr server with its type, how it
 * last went and an On / Off switch. OK on one: its options. At the bottom, add a source and update them all.
 */
@Composable
fun GuideSourcesScreen(vm: GuideSourcesViewModel = hiltViewModel(), onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf<EpgSource?>(null) }
    var adding by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val rows = remember { HashMap<Int, FocusRequester>() }
    var lastId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { vm.load(null) }
    LaunchedEffect(ui.loading) { if (!ui.loading) { awaitFrame(); runCatching { first.requestFocus() } } }
    // After a change the list is rebuilt: focus back on the same source (or the top, if it was deleted).
    LaunchedEffect(ui.reloads) {
        if (ui.reloads <= 1) return@LaunchedEffect
        awaitFrame(); awaitFrame()
        val ok = lastId?.let { id -> rows[id]?.let { runCatching { it.requestFocus() }.isSuccess } } == true
        if (!ok) runCatching { first.requestFocus() }
    }
    BackHandler(onBack = onClose)
    ViewarrPopup(onClose = onClose) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(820.dp).heightIn(max = (io.github.petebutty.viewarr.ui.common.LocalCanvas.current.height * 0.9f))
                    .background(AppColors.MenuPanel, RoundedCornerShape(Tokens.PopupRadius)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("TV guide sources", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text("Where your Dispatcharr server gets its TV guide. OK on a source: switch it on or off, update it now, rename it, change it or delete it. Changes are for everyone on the server.",
                    fontSize = 14.sp, color = AppColors.TextSecondary)
                ui.message?.let { Text(it, fontSize = 14.sp, color = AppColors.Accent, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                when {
                    ui.loading -> Text("Loading…", fontSize = 14.sp, color = AppColors.TextSecondary)
                    ui.problem != null -> MenuItem(text = ui.problem.orEmpty(), selected = false, onClick = onClose, modifier = Modifier.focusRequester(first))
                    else -> SourceList(ui.sources, first, rows, onFocus = { lastId = it }, onOpen = { chosen = it },
                        onAdd = { adding = true }, onAll = { vm.refreshAll() })
                }
                if (!ui.loading) MenuItem(text = "Close", selected = false, onClick = onClose)
            }
        }
    }
    // (The pop-up works on the source as it is now: the list may have been fetched again since it opened.)
    chosen?.let { c -> SourceOptions(ui.sources.firstOrNull { it.id == c.id } ?: c, vm) { chosen = null } }
    if (adding) AddSourceFlow(vm, onFocusNew = { lastId = it }) { adding = false }
}

@Composable
private fun SourceList(
    sources: List<EpgSource>,
    first: FocusRequester,
    rows: HashMap<Int, FocusRequester>,
    onFocus: (Int?) -> Unit,
    onOpen: (EpgSource) -> Unit,
    onAdd: () -> Unit,
    onAll: () -> Unit,
) {
    LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        if (sources.isEmpty()) item(key = "none") {
            Text("No guide sources yet. Add one below.", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(vertical = 6.dp))
        }
        items(sources, key = { it.id }) { s ->
            val req = rows.getOrPut(s.id) { FocusRequester() }
            SourceRow(s, modifier = (if (s == sources.first()) Modifier.focusRequester(first) else Modifier).focusRequester(req)
                .onFocusChanged { if (it.isFocused) onFocus(s.id) }) { onOpen(s) }
        }
        item(key = "more") {
            Column(Modifier.padding(top = 8.dp)) {
                MenuItem(text = "Add a guide source…", selected = false, onClick = onAdd,
                    modifier = (if (sources.isEmpty()) Modifier.focusRequester(first) else Modifier).onFocusChanged { if (it.isFocused) onFocus(null) })
                MenuItem(text = "Update all now", trailing = "${sources.count { it.active && it.canUpdate }} on", selected = false, onClick = onAll,
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus(null) })
            }
        }
    }
}

/** One source: name, type and what it holds, how it last went, and its On / Off switch. */
@Composable
private fun SourceRow(s: EpgSource, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Panel,
            contentColor = if (s.active) AppColors.TextPrimary else AppColors.TextSecondary,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detailLine(s), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                Text(statusLine(s), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (s.status == "error" && s.active) Color(0xFFFF8A80) else Color.Unspecified)
            }
            Spacer(Modifier.width(20.dp))
            Switch(s.active)
        }
    }
}

@Composable
private fun Switch(on: Boolean) = Box(
    Modifier.size(width = 48.dp, height = 26.dp)
        .background(if (on) Color(0xFF2EBD5A) else Color(0xFF6B6F7A), RoundedCornerShape(13.dp))
        .padding(3.dp),
    contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
) { Box(Modifier.size(20.dp).background(Color.White, CircleShape)) }

/** "XMLTV · 2,735 channels · every 12 hours". */
private fun detailLine(s: EpgSource): String = listOfNotNull(
    s.typeLabel + if (s.fromPlugin) " (from a plugin)" else "",
    s.channels.takeIf { it > 0 }?.let { "%,d channel%s".format(it, if (it == 1) "" else "s") },
    when {
        !s.canUpdate -> null
        s.cron.isNotBlank() -> "own schedule"
        else -> EpgSources.refreshLabel(s.refreshHours).replaceFirstChar { it.lowercase() }
    },
).joinToString("  ·  ")

/** How it last went: off, updating, the error, or when it was last updated (and what it found). */
private fun statusLine(s: EpgSource): String {
    val last = if (s.updatedAt > 0) "updated " + io.github.petebutty.viewarr.ui.sync.ago(s.updatedAt) else null
    return when {
        !s.active -> "Off" + (last?.let { " · last $it" } ?: "")
        s.busy -> "Updating…"
        s.status == "error" -> "Problem: " + (s.message ?: "Dispatcharr couldn't update it.")
        last != null -> last.replaceFirstChar { it.uppercase() } + (s.message?.let { " · $it" } ?: "")
        s.type == EpgSource.DUMMY -> "Made by Dispatcharr itself (nothing to download)"
        else -> s.message ?: "Not updated yet"
    }
}

private enum class Edit { Name, Address, Interval }

/** OK on a source: everything that can be done to it. */
@Composable
private fun SourceOptions(s: EpgSource, vm: GuideSourcesViewModel, onClose: () -> Unit) {
    var edit by remember { mutableStateOf<Edit?>(null) }
    when (edit) {
        Edit.Name -> TextEntryDialog(
            title = "Rename", subtitle = "The name Dispatcharr and the guide pickers show for this source.", placeholder = s.name, initial = s.name, optional = false,
            onDone = { t -> if (t != s.name) vm.rename(s, t); onClose() }, onClose = { edit = null },
        )
        Edit.Address -> AddressDialog(s.url.orEmpty(), vm, onDone = { t -> if (t != s.url) vm.setUrl(s, t); onClose() }) { edit = null }
        Edit.Interval -> OptionsPopup(
            title = "Refresh interval", subtitle = "How often Dispatcharr fetches ${s.name} by itself." + if (s.cron.isNotBlank()) " Choosing one replaces its own schedule (${s.cron})." else "",
            options = EpgSources.REFRESH_CHOICES.map { h ->
                PopupOption(EpgSources.refreshLabel(h) + if (h == s.refreshHours && s.cron.isBlank()) "  ✓" else "") { if (h != s.refreshHours || s.cron.isNotBlank()) vm.setRefresh(s, h) }
            },
            onClose = onClose,
        )
        null -> OptionsPopup(
            title = s.name,
            subtitle = detailLine(s),
            description = sourceNote(s),
            options = sourceOptions(s, vm) { edit = it },
            onClose = onClose,
        )
    }
}

private fun sourceOptions(s: EpgSource, vm: GuideSourcesViewModel, edit: (Edit) -> Unit): List<PopupOption> = listOfNotNull(
    PopupOption(if (s.active) "Switch off" else "Switch on") { vm.setActive(s, !s.active) },
    PopupOption("Update now") { vm.refresh(s) }.takeIf { s.active && s.canUpdate },
    PopupOption("Rename…", keepOpen = true) { edit(Edit.Name) },
    PopupOption("Change address…", keepOpen = true) { edit(Edit.Address) }.takeIf { s.type == EpgSource.XMLTV && !s.fromPlugin },
    PopupOption("Refresh interval: " + (if (s.cron.isNotBlank()) "own schedule" else EpgSources.refreshLabel(s.refreshHours)) + "…", keepOpen = true) { edit(Edit.Interval) }.takeIf { s.canUpdate },
    PopupOption("Delete…", destructive = true, confirmTitle = "Delete ${s.name}?",
        confirm = "Its listings go too, and channels using it lose their guide until another is picked. It can't be undone.") { vm.delete(s) },
)

/** The pop-up's small print: how it last went, where it comes from (the server's name only: links can hold passwords). */
private fun sourceNote(s: EpgSource): String = listOfNotNull(
    statusLine(s),
    s.url?.let { u -> runCatching { java.net.URI(u).host }.getOrNull()?.let { "From $it" } },
    s.username?.takeIf { s.type == EpgSource.SD && it.isNotBlank() }?.let { "Schedules Direct account: $it" },
    "Filled in by a Dispatcharr plugin, which keeps it up to date.".takeIf { s.fromPlugin },
).joinToString("\n")

/** Typing a guide link: refused here unless it's a web address (Dispatcharr would take anything). */
@Composable
private fun AddressDialog(initial: String, vm: GuideSourcesViewModel, onDone: (String) -> Unit, onClose: () -> Unit) {
    var refused by remember { mutableStateOf<String?>(null) }
    TextEntryDialog(
        title = "Guide address", subtitle = refused ?: "The XMLTV link, starting http:// or https:// (a .xml or .xml.gz file).",
        placeholder = "https://…/guide.xml", initial = initial, optional = false,
        onDone = { t -> if (t.startsWith("http://", true) || t.startsWith("https://", true)) onDone(t) else { refused = "Not saved: it needs to start http:// or https://."; vm.say(refused!!) } },
        onClose = onClose,
    )
}

private enum class AddStep { Type, Name, Address, User, Pass, Offer }

/**
 * Add a guide source: the type (an XMLTV link or a Schedules Direct account), a name, the address or the account's
 * username and password; then it's made (switched on, fetched every 12 hours, Schedules Direct daily) and Update now
 * is offered.
 */
@Composable
private fun AddSourceFlow(vm: GuideSourcesViewModel, onFocusNew: (Int?) -> Unit, onClose: () -> Unit) {
    var step by remember { mutableStateOf(AddStep.Type) }
    var type by remember { mutableStateOf(EpgSource.XMLTV) }
    var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var made by remember { mutableStateOf<EpgSource?>(null) }
    fun create(url: String?, pass: String?) {
        // (Nothing shows while it's made: then the offer opens, or on a refusal the flow ends and the page says why.)
        step = AddStep.Offer
        vm.add(type, name, url, user.takeIf { type == EpgSource.SD }, pass) { s -> if (s == null) onClose() else { made = s; onFocusNew(s.id) } }
    }
    when (step) {
        AddStep.Type -> OptionsPopup(
            title = "Add a guide source", subtitle = "What kind of guide?",
            options = listOf(
                PopupOption("XMLTV link", keepOpen = true) { type = EpgSource.XMLTV; step = AddStep.Name },
                PopupOption("Schedules Direct account", keepOpen = true) { type = EpgSource.SD; step = AddStep.Name },
            ),
            onClose = onClose,
        )
        AddStep.Name -> TextEntryDialog(
            title = "Name", subtitle = "What to call it, e.g. Freeview or Sky.", placeholder = if (type == EpgSource.SD) "Schedules Direct" else "Freeview",
            initial = "", optional = false, onDone = { name = it; step = if (type == EpgSource.SD) AddStep.User else AddStep.Address }, onClose = onClose,
        )
        AddStep.Address -> AddressDialog("", vm, onDone = { create(it, null) }, onClose = onClose)
        AddStep.User -> TextEntryDialog(
            title = "Schedules Direct username", subtitle = "The account you signed up with at schedulesdirect.org.", placeholder = "Username",
            initial = "", optional = false, onDone = { user = it; step = AddStep.Pass }, onClose = onClose,
        )
        AddStep.Pass -> TextEntryDialog(
            title = "Schedules Direct password", subtitle = "Sent to your Dispatcharr server only. (It shows as you type.)", placeholder = "Password",
            initial = "", optional = false, onDone = { create(null, it) }, onClose = onClose,
        )
        AddStep.Offer -> made?.let { s ->
            OptionsPopup(
                title = "${s.name} added",
                subtitle = "It's on. Fetch its guide now? That can take a few minutes; the list shows how it's going.",
                description = if (s.type == EpgSource.SD) "Schedules Direct needs its lineups chosen first: add them on Dispatcharr's web page (M3U & EPG Manager → the source's lineups), then Update now." else null,
                options = listOf(PopupOption("Update now") { vm.refresh(s) }),
                onClose = onClose,
            )
        }
    }
}
