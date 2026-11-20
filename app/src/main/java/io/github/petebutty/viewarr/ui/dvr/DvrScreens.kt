package io.github.petebutty.viewarr.ui.dvr


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.dvr.DvrRecording
import io.github.petebutty.viewarr.data.dvr.DvrRepository
import io.github.petebutty.viewarr.data.dvr.DvrState
import io.github.petebutty.viewarr.data.dvr.SeriesRule
import io.github.petebutty.viewarr.data.dvr.RecordingProgress
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.data.repository.GuideRepository
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.github.petebutty.viewarr.data.dvr.DvrResult
import io.github.petebutty.viewarr.data.dvr.ReminderStore
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.ui.common.LiveBehind
import io.github.petebutty.viewarr.ui.settings.AppearanceViewModel
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.PopupPanel
import io.github.petebutty.viewarr.ui.common.PopupTitle
import io.github.petebutty.viewarr.ui.common.leftOpens
import io.github.petebutty.viewarr.ui.home.RecordingRow
import io.github.petebutty.viewarr.ui.media.RowTitle
import io.github.petebutty.viewarr.ui.theme.Tokens

@HiltViewModel
class DvrViewModel @Inject constructor(
    private val repo: DvrRepository,
    guide: GuideRepository,
    private val reminderStore: ReminderStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    /** "Remind me" programmes (pop up when they start). */
    val reminders = reminderStore.reminders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun reminderFor(channelId: Int, p: GuideProgram) = reminders.value.firstOrNull { it.channelId == channelId && it.start == p.startTime }
    fun remind(channel: ChannelListItem, p: GuideProgram) = viewModelScope.launch {
        reminderStore.add(io.github.petebutty.viewarr.data.dvr.Reminder(channel.id, p.startTime, p.endTime, p.title, channel.name))
        _message.value = "We'll remind you when it starts (while Viewarr is open)."
    }
    /** A reminder for a time on a channel with nothing in the guide yet (a game: its kick-off, on its channel). */
    fun remindAt(channel: ChannelListItem, start: Long, end: Long, title: String) = viewModelScope.launch {
        reminderStore.add(io.github.petebutty.viewarr.data.dvr.Reminder(channel.id, start, end, title, channel.name))
    }
    fun reminderAt(channelId: Int, start: Long) = reminders.value.firstOrNull { it.channelId == channelId && it.start == start }
    fun forget(id: String) = viewModelScope.launch { reminderStore.remove(id) }
    fun pruneReminders() = viewModelScope.launch { reminderStore.prune(System.currentTimeMillis()) }

    val recordings = repo.recordings
    val rules = repo.rules
    val allowed = repo.allowed
    val xc = repo.xc
    // (By id, every channel: a recording or reminder on an event channel hidden from the lists still names and plays it.)
    val channels = guide.observeAllChannels().map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        viewModelScope.launch {
            // Keep the red dots and the Recordings page current; Dispatcharr's own UI may change things too.
            while (true) {
                runCatching { repo.refresh() }
                delay(120_000)
            }
        }
    }

    fun refresh() = viewModelScope.launch { runCatching { repo.refresh() } }
    fun recordingFor(channelId: Int, p: GuideProgram) = repo.recordingFor(channelId, p)
    fun ruleFor(channel: ChannelListItem, p: GuideProgram) = repo.ruleFor(channel, p)
    fun clearMessage() { _message.value = null }

    private fun act(block: suspend () -> DvrResult) = viewModelScope.launch {
        _message.value = "Working…"
        _message.value = runCatching { block().message }.getOrElse { "Something went wrong." }
    }

    fun record(channel: ChannelListItem, p: GuideProgram) = act {
        // A programme already on air records from now.
        val now = System.currentTimeMillis()
        repo.record(channel, if (p.startTime < now) p.copy(startTime = now) else p)
    }
    fun recordSeries(channel: ChannelListItem, p: GuideProgram, newOnly: Boolean) = act { repo.recordSeries(channel, p, newOnly) }
    fun cancel(rec: DvrRecording) = act { repo.cancel(rec).also { RecordingProgress.forget(appContext, rec.id) } }
    fun stop(rec: DvrRecording) = act { repo.stop(rec) }
    fun removeRule(rule: SeriesRule) = act { repo.removeRule(rule) }

    suspend fun playback(rec: DvrRecording): VodStream? =
        repo.playback(rec)?.let { (url, headers) -> VodStream(url, rec.title, headers, resumeMs = RecordingProgress.resumeMs(appContext, rec.id)) }
}

private fun span(start: Long, end: Long) = "${io.github.petebutty.viewarr.ui.theme.Dates.dayTime(start)}–${io.github.petebutty.viewarr.ui.theme.Dates.time(end)}"
private fun episodeTag(season: Int?, episode: Int?) = if (season != null && episode != null) " · S$season · E$episode" else ""

/**
 * Small centred panel of choices, used for the record options and the programme details. On the shared pop-up
 * (PopupPanel: glass, one radius, its own window, the held OK ignored). (Peter, 2026-11-17: was its own copy of it.)
 */
@Composable
internal fun ChoicePanel(
    title: String,
    subtitle: String,
    choices: List<Pair<String, () -> Unit>>,
    message: String?,
    onClose: () -> Unit,
    description: String? = null,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { first.requestFocus() }
    }
    PopupPanel(onClose = onClose, width = Tokens.PopupStandard) {
        PopupTitle(title, subtitle)
        description?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = AppColors.TextPrimary, maxLines = 7, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 14.dp))
        }
        choices.forEachIndexed { i, (label, action) ->
            MenuItem(text = label, selected = false, onClick = action, compact = true, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
        }
        message?.let { Text(it, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** Programme details (OK on an upcoming programme in the guide), with the way to record it or set a reminder. */
@Composable
fun ProgramInfoDialog(channel: ChannelListItem, program: GuideProgram, vm: DvrViewModel, onRecord: () -> Unit, onClose: () -> Unit) {
    vm.recordings.collectAsStateWithLifecycle().value
    val rec = vm.recordingFor(channel.id, program)
    val rule = vm.ruleFor(channel, program)
    val status = when {
        rec?.state == DvrState.Recording -> "  ·  ● Recording now"
        rec != null -> "  ·  ● Scheduled"
        rule != null -> "  ·  Series recording on"
        else -> ""
    }
    val sub = listOfNotNull(program.subTitle?.takeIf { it.isNotBlank() }).joinToString()
    ChoicePanel(
        title = program.title + episodeTag(program.season, program.episode),
        subtitle = if (program.noGuide) channel.name else "${channel.name}  ·  ${span(program.startTime, program.endTime)}$status",
        choices = listOf((if (rec != null) "Cancel this recording" else "Record or remind me") to onRecord, "Cancel" to onClose),
        message = null,
        onClose = onClose,
        description = listOf(sub, program.description.orEmpty()).filter { it.isNotBlank() }.joinToString("\n"),
    )
}

/** Record options for one programme in the guide (or what's on now, from the player). */
@Composable
fun RecordDialog(
    channel: ChannelListItem, program: GuideProgram, vm: DvrViewModel, onDetails: (() -> Unit)? = null,
    /** Started already: from the beginning (catch-up, else your servers / addons). */
    onWatchFromStart: (() -> Unit)? = null,
    /** The film's / show's page (a show: its episodes, where you're up to). */
    onGoTo: (() -> Unit)? = null,
    /** Seerr set up: request the film / show from here; this opens the choose-series picker. */
    onChooseSeasons: ((GuideMatch) -> Unit)? = null,
    /** Search opened on this title, Catch-up picked: every showing that can be replayed. */
    onSearchCatchup: (() -> Unit)? = null,
    /** "Add to favourites" / "Remove from favourites" for the channel, and what it does. */
    favourite: Pair<String, () -> Unit>? = null,
    /** More choices for the channel (Guide times…). */
    extra: List<Pair<String, () -> Unit>> = emptyList(),
    /** Report a problem with the channel (its own line in Edit this menu, so hiding the guide tools doesn't hide it). */
    onReport: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val dvrMessage by vm.message.collectAsStateWithLifecycle()
    val (seerrChoices, seerrMessage) = if (onChooseSeasons != null) guideRequestChoices(program, onChooseSeasons) else (emptyList<Pair<String, () -> Unit>>() to null)
    val message = dvrMessage ?: seerrMessage
    vm.recordings.collectAsStateWithLifecycle().value // recompose when the list changes
    vm.rules.collectAsStateWithLifecycle().value
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.clearMessage() }
    LaunchedEffect(message, done) {
        if (done && message != null && message != "Working…") {
            delay(1_600)
            onClose()
        }
    }
    fun run(block: () -> Unit) {
        done = true
        block()
    }
    val rec = vm.recordingFor(channel.id, program)
    val rule = vm.ruleFor(channel, program)
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val reminder = reminders.firstOrNull { it.channelId == channel.id && it.start == program.startTime }
    // Dispatcharr's dvr_access: Standard users can only watch recordings unless an admin lets them record.
    val canRecord = vm.allowed.collectAsStateWithLifecycle().value != false && !vm.xc.collectAsStateWithLifecycle().value && Access.record
    val future = program.startTime > System.currentTimeMillis()
    // Nothing listed on the channel: only the channel's own choices (favourite, guide tools, Report a problem).
    val noGuide = program.noGuide
    // A film (TMDB says so, or the guide lists it like one: no series / episode, "Action · 2017 —"): no series recording.
    val guideVm: GuideRequestViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val matched by guideVm.match.collectAsStateWithLifecycle()
    val isFilm = matched?.item?.type == MediaType.Movie ||
        (program.season == null && program.episode == null &&
            Regex("^[A-Za-z][A-Za-z &/'-]{1,30} · (19|20)\\d{2}\\b").containsMatchIn(program.description.orEmpty()))
    // Each option has a kind, so the pop-up can be trimmed: "✎ Edit this menu" switches kinds on and off (kept on the
    // box, Settings → Appearance values "hold:<kind>"). Undoing a recording, Keep and Close are always there.
    val hidden = { kind: String -> !AppColors.palette.shows("hold:$kind") }
    var editing by remember { mutableStateOf(false) }
    val appearance: AppearanceViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val all = if (noGuide) buildList<Triple<String?, String, () -> Unit>> {
        favourite?.let { add(Triple("fav", it.first, it.second)) }
        extra.forEach { e -> add(Triple("times", e.first, e.second)) }
        onReport?.let { add(Triple("report", "⚑  Report a problem…", it)) }
    } else if (canRecord && rec != null) buildList<Triple<String?, String, () -> Unit>> {
        // Already set to record: just the way to undo it (and keep it), not the record / remind options again.
        if (rec.state == DvrState.Recording) add(Triple(null, "Stop recording", { run { vm.stop(rec) } }))
        else add(Triple(null, "Cancel this recording", { run { vm.cancel(rec) } }))
        if (rule != null) add(Triple(null, "Stop series recording", { run { vm.removeRule(rule) } }))
        add(Triple(null, if (rec.state == DvrState.Recording) "Keep recording" else "Keep this recording", onClose))
        if (!future) onWatchFromStart?.let { add(Triple("start", "Start over", it)) }
        onGoTo?.let { add(Triple("goto", if (isFilm) "Go to film" else "Go to show", it)) }
        onSearchCatchup?.let { add(Triple("catchup", "Search catch-up", it)) }
        seerrChoices.forEach { (l, f) -> add(Triple("request", l, { if (l == SEERR_MORE) f() else run(f) })) }
        favourite?.let { add(Triple("fav", it.first, it.second)) }
        extra.forEach { e -> add(Triple("times", e.first, e.second)) }
        onReport?.let { add(Triple("report", "⚑  Report a problem…", it)) }
        onDetails?.let { add(Triple("details", "Details", it)) }
    } else buildList<Triple<String?, String, () -> Unit>> {
        // The film or episode from your servers (Plex, Jellyfin, Emby) or addons: from the start if it's on or has
        // aired, or now if it's still to come.
        if (onWatchFromStart != null && !future) add(Triple("start", "Start over", onWatchFromStart))
        onGoTo?.let { add(Triple("goto", if (isFilm) "Go to film" else "Go to show", it)) }
        onSearchCatchup?.let { add(Triple("catchup", "Search catch-up", it)) }
        if (!canRecord) {
            // No recordings here (IPTV provider login, or a Dispatcharr user without DVR access): reminders still work.
        } else when {
            rec?.state == DvrState.Recording -> add(Triple(null, "Stop recording", { run { vm.stop(rec) } }))
            rec != null -> add(Triple(null, "Cancel this recording", { run { vm.cancel(rec) } }))
            // Already over: nothing to record (series recording still offered below).
            program.endTime <= System.currentTimeMillis() -> Unit
            else -> add(Triple("record", if (program.startTime <= System.currentTimeMillis()) "Record from now" else "Record this programme", { run { vm.record(channel, program) } }))
        }
        if (canRecord && (!isFilm || rule != null)) {
            if (rule != null) add(Triple(null, "Stop series recording", { run { vm.removeRule(rule) } }))
            else {
                add(Triple("series", "Record series: every episode", { run { vm.recordSeries(channel, program, newOnly = false) } }))
                add(Triple("series", "Record series: new episodes only", { run { vm.recordSeries(channel, program, newOnly = true) } }))
            }
        }
        if (future) {
            if (reminder != null) add(Triple(null, "Cancel reminder", { vm.forget(reminder.id); onClose() }))
            else add(Triple("remind", "Remind me when it starts", { run { vm.remind(channel, program) } }))
        }
        // Seerr: one line (the film; a show opens its own panel: whole show, this season, chosen seasons).
        seerrChoices.forEach { (l, f) -> add(Triple("request", l, { if (l == SEERR_MORE) f() else run(f) })) }
        favourite?.let { add(Triple("fav", it.first, it.second)) }
        extra.forEach { e -> add(Triple("times", e.first, e.second)) }
        onReport?.let { add(Triple("report", "⚑  Report a problem…", it)) }
        onDetails?.let { add(Triple("details", "Details", it)) }
    }
    val choices: List<Pair<String, () -> Unit>> = if (editing) buildList {
        // Every kind, on or off (OK switches it), whether or not this programme offers it right now.
        HoldKinds.forEach { (kind, name) ->
            add((if (hidden(kind)) "○  $name  (hidden)" else "●  $name") to { appearance.setTitlePart("hold:$kind", hidden(kind)) })
        }
        add("✓  Done" to { editing = false })
    } else buildList {
        all.filter { it.first == null || !hidden(it.first!!) }.forEach { add(it.second to it.third) }
        add("✎  Edit this menu" to { editing = true })
        if (!(canRecord && rec != null)) add("Cancel" to onClose)
    }
    val status = when {
        rec?.state == DvrState.Recording -> "  ·  ● Recording now"
        rec != null -> "  ·  ● Scheduled"
        rule != null -> "  ·  Series recording on"
        reminder != null -> "  ·  ⏰ Reminder set"
        else -> ""
    }
    androidx.compose.runtime.key(editing) {
        ChoicePanel(
            title = if (editing) "Edit this menu" else program.title + episodeTag(program.season, program.episode),
            subtitle = if (editing) "OK shows or hides an option in this pop-up" else "${channel.name}  ·  ${span(program.startTime, program.endTime)}$status",
            choices = choices,
            message = if (editing) null else message,
            onClose = if (editing) ({ editing = false }) else onClose,
        )
    }
}

/** The hold-OK pop-up's options that can be hidden ("✎ Edit this menu"). */
private val HoldKinds = listOf(
    "start" to "Start over",
    "goto" to "Go to film / show",
    "catchup" to "Search catch-up",
    "record" to "Record this programme",
    "series" to "Record series",
    "remind" to "Remind me",
    "request" to "Request (Seerr)",
    "fav" to "Add to / remove from favourites",
    "times" to "EPG offset and Timeline",
    "report" to "Report a problem",
    "details" to "Details",
)

private sealed interface DvrRow {
    data class Header(val text: String) : DvrRow
    /** A quiet line under an empty section ("Nothing recorded yet…"). */
    data class Note(val text: String) : DvrRow
    data class Rec(val rec: DvrRecording) : DvrRow
    data class Rule(val rule: SeriesRule) : DvrRow
}

/** A recording's or rule's key in the list (null: a header or note, which can't take focus). */
private fun rowKey(r: DvrRow): String? = when (r) {
    is DvrRow.Rec -> "r-${r.rec.id}"
    is DvrRow.Rule -> "s-${r.rule.tvgId}-${r.rule.title}"
    else -> null
}

/** The Recordings page: what's recording, scheduled, recorded, and the series rules. All stored on Dispatcharr. */
@Composable
fun RecordingsScreen(
    vm: DvrViewModel,
    focusRequester: FocusRequester,
    focusEnabled: Boolean,
    onBack: () -> Unit,
    onPlay: (VodStream) -> Unit,
    onOpenMenu: () -> Unit = {},
) {
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val allowed by vm.allowed.collectAsStateWithLifecycle()
    val xc by vm.xc.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf<DvrRow?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Live TV in the guide's preview place (top left): the highlighted recording's details beside it, the list below.
    val besidePreview = LiveBehind.preview
    var hover by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }
    BackHandler(enabled = focusEnabled && picked == null) { onBack() }

    // Three lists, one at a time (Peter, 2026-11-20): Scheduled (recording now at its top), Recorded and Series
    // recordings, each sorted by date either way. The list and each one's order are remembered on the box.
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("recordings_page", android.content.Context.MODE_PRIVATE) }
    var tab by remember { androidx.compose.runtime.mutableIntStateOf(prefs.getInt("tab", 0).coerceIn(0, 2)) }
    // The highlight on the tabs or the order switch: a list changing under it (moving along the tabs) mustn't pull it
    // back down into the list (it did: Recorded and Series recordings couldn't be reached. Peter, 2026-11-20).
    var onTabs by remember { mutableStateOf(false) }
    // Which of the tabs / order switch has the highlight (-1: none). Left only opens the menu from the first tab or the
    // list: from the others it moves along (Left anywhere opened the menu, so the tabs left of the order switch, where Up
    // from the list lands, couldn't be reached).
    var tabAt by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    val tabFocus = remember { List(4) { FocusRequester() } }
    // Newest / latest first: on by default for Recorded only (Scheduled starts with the soonest).
    val latestFirst = remember { androidx.compose.runtime.mutableStateMapOf(0 to prefs.getBoolean("latest_0", false), 1 to prefs.getBoolean("latest_1", true), 2 to prefs.getBoolean("latest_2", false)) }
    val progressVersion by RecordingProgress.version.collectAsStateWithLifecycle()
    val nowRecs = recordings.filter { it.state == DvrState.Recording }.sortedBy { it.start }
    val scheduled = recordings.filter { it.state == DvrState.Scheduled }
    val done = recordings.filter { it.state == DvrState.Recorded || it.state == DvrState.Failed }
    // A series' date: its next scheduled episode (one with none goes last, whichever way round).
    fun nextOf(r: SeriesRule) = scheduled.filter { it.title.equals(r.title, ignoreCase = true) || it.title.equals(r.label, ignoreCase = true) }.minOfOrNull { it.start }
    val rows = buildList {
        val desc = latestFirst[tab] == true
        when (tab) {
            0 -> {
                if (nowRecs.isNotEmpty()) { add(DvrRow.Header("Recording now")); nowRecs.forEach { add(DvrRow.Rec(it)) } }
                val list = scheduled.sortedBy { it.start }.let { if (desc) it.reversed() else it }
                if (nowRecs.isNotEmpty() && list.isNotEmpty()) add(DvrRow.Header("Coming up"))
                list.forEach { add(DvrRow.Rec(it)) }
                if (list.isEmpty() && nowRecs.isEmpty()) add(DvrRow.Note("Nothing scheduled. Hold OK on a programme in the TV guide to record it."))
            }
            1 -> {
                val list = done.sortedBy { it.start }.let { if (desc) it.reversed() else it }
                list.forEach { add(DvrRow.Rec(it)) }
                if (list.isEmpty()) add(DvrRow.Note("Nothing recorded yet."))
            }
            else -> {
                val withDate = rules.map { it to nextOf(it) }
                val dated = withDate.filter { it.second != null }.sortedBy { it.second }.let { if (desc) it.reversed() else it }
                (dated + withDate.filter { it.second == null }.sortedBy { it.first.label.lowercase() }).forEach { add(DvrRow.Rule(it.first)) }
                if (rules.isEmpty()) add(DvrRow.Note("No series recordings. Hold OK on an episode in the TV guide → Record series."))
            }
        }
    }
    // (Only recordings and rules take focus: a header or a note can't.)
    val focusable = rows.mapIndexedNotNull { i, r -> if (r is DvrRow.Rec || r is DvrRow.Rule) i else null }
    val firstItem = focusable.firstOrNull() ?: -1
    // The row you were on (its options closing, or coming back to the page): the highlight goes back there, not the top.
    // Kept by the recording / rule, not its place in the list: after deleting the last one, the place is gone and the
    // highlight went to the top instead of the row before it.
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    var lastKey by remember { mutableStateOf<String?>(null) }
    var lastIndex by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    fun focusBack() {
        val same = lastKey?.let { rowFocus[it] }
        if (same != null && runCatching { same.requestFocus() }.isSuccess) return
        // That row is gone: the focusable row before its place, else the first.
        val prev = focusable.lastOrNull { it < lastIndex } ?: firstItem
        val key = rows.getOrNull(prev)?.let(::rowKey)
        if (key == null || runCatching { rowFocus[key]!!.requestFocus() }.isFailure) runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(firstItem >= 0, focusEnabled, picked == null) {
        if (!focusEnabled || picked != null) return@LaunchedEffect
        awaitFrame()
        // (An empty list: the highlight on its tab.)
        if (firstItem < 0) runCatching { focusRequester.requestFocus() } else focusBack()
    }
    // The highlighted row left the list (deleted, cancelled) after its options closed: the row before it.
    val rowKeys = rows.mapNotNull(::rowKey)
    LaunchedEffect(rowKeys) {
        val k = lastKey ?: return@LaunchedEffect
        if (!focusEnabled || picked != null || onTabs || k in rowKeys) return@LaunchedEffect
        awaitFrame()
        focusBack()
    }

    // (The app's wallpaper shows through, as under the menu beside it: one background across both. Peter, 2026-11-17.)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                // Left (the list has nothing to its left) opens the menu, as on every page (once per press: the
                // shared leftOpens ignores a held Left's repeats).
                .leftOpens(enabled = picked == null && tabAt <= 0, onMenu = onOpenMenu)
                .padding(horizontal = Tokens.Gutter, vertical = 32.dp),
        ) {
            if (besidePreview) androidx.compose.foundation.layout.Row(Modifier.heightIn(min = 180.dp)) {
                // (Text starts at 356 as on every page beside the window.)
                Spacer(Modifier.width(Tokens.BesideWindowStart - Tokens.Gutter))
                Column {
                    Text("Recordings", fontSize = 14.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(hover?.first ?: "Recorded and scheduled", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    Text(hover?.second ?: "OK: play  ·  Hold OK: options", fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 2)
                }
            } else {
                Text("Recordings", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Recorded and scheduled on your Dispatcharr server  ·  OK: play  ·  Hold OK: options", fontSize = 13.sp, color = AppColors.TextSecondary)
            }
            Spacer(Modifier.height(12.dp))
            // The three lists, and the order (Up from the list).
            if (allowed != false) androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.onFocusChanged { onTabs = it.hasFocus }) {
                val names = listOf("Scheduled (${scheduled.size + nowRecs.size})", "Recorded (${done.size})", "Series recordings (${rules.size})")
                names.forEachIndexed { i, n ->
                    io.github.petebutty.viewarr.ui.common.AppPill(n, selected = tab == i,
                        onClick = { tab = i; lastKey = null; prefs.edit().putInt("tab", i).apply() },
                        modifier = (if (firstItem < 0 && i == tab) Modifier.focusRequester(focusRequester) else Modifier)
                            .focusRequester(tabFocus[i]).onFocusChanged { if (it.isFocused) tabAt = i else if (tabAt == i) tabAt = -1 }
                            // (Another list: the row you were on belongs to the old one, so Down starts at the top.)
                            .onFocusChanged { if (it.isFocused && tab != i) { tab = i; lastKey = null; prefs.edit().putInt("tab", i).apply() } })
                }
                Spacer(Modifier.width(14.dp))
                val desc = latestFirst[tab] == true
                val label = when (tab) {
                    0 -> if (desc) "Latest first" else "Soonest first"
                    1 -> if (desc) "Newest first" else "Oldest first"
                    else -> if (desc) "Furthest away first" else "Next first"
                }
                io.github.petebutty.viewarr.ui.common.AppPill((if (desc) "↓  " else "↑  ") + label, selected = false, outlined = true,
                    onClick = { latestFirst[tab] = !desc; prefs.edit().putBoolean("latest_$tab", !desc).apply() },
                    modifier = Modifier.focusRequester(tabFocus[3]).onFocusChanged { if (it.isFocused) tabAt = 3 else if (tabAt == 3) tabAt = -1 })
            }
            Spacer(Modifier.height(10.dp))
            when {
                allowed == false -> Text(
                    if (xc) "Recordings need a Dispatcharr server. Your live TV comes straight from your IPTV provider, which can't record. " +
                        "You can still watch catch-up from the TV guide if your provider offers it."
                    else "Your Dispatcharr account isn't allowed to use recordings. In Dispatcharr, open Users, edit this account and set DVR access to Manage.",
                    fontSize = 15.sp, color = AppColors.TextPrimary,
                )
                allowed == null && recordings.isEmpty() -> Text("Loading…", color = AppColors.TextSecondary)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 110.dp)) {
                    // (Bottom 110 as Music and Movies: the music mini player covers the bottom of the screen.)
                    items(rows.size, key = { i -> rowKey(rows[i]) ?: when (val r = rows[i]) {
                        is DvrRow.Header -> "h-${r.text}"
                        is DvrRow.Note -> "n-${r.text}"
                        else -> "x-$i"
                    } }) { i ->
                        when (val row = rows[i]) {
                            is DvrRow.Header -> RowTitle(row.text, Modifier.padding(top = 14.dp, start = 4.dp))
                            is DvrRow.Note -> Text(row.text, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
                            // (The menu's own recording row, so a recording looks the same in the menu and on this page.)
                            is DvrRow.Rec -> RecordingRow(
                                // (Watched: a tick; started: a bar under it. RecordingProgress, kept on the box.)
                                progress = progressVersion.let { RecordingProgress.fraction(context, row.rec.id) }?.takeIf { row.rec.state == DvrState.Recorded },
                                title = (if (row.rec.state == DvrState.Recording) "● " else if (row.rec.state == DvrState.Recorded && RecordingProgress.watched(context, row.rec.id)) "✓ " else "") + row.rec.title + episodeTag(row.rec.season, row.rec.episode) + if (row.rec.state == DvrState.Failed) "  (failed)" else "",
                                details = "${channels[row.rec.channelId]?.name ?: "Channel"}  ·  ${span(row.rec.start, row.rec.end)}",
                                live = row.rec.state == DvrState.Recording,
                                modifier = (if (i == firstItem) Modifier.focusRequester(focusRequester).focusProperties { up = tabFocus[tab] } else Modifier).focusRequester(rowFocus.getOrPut(rowKey(row)!!) { FocusRequester() }).onFocusChanged {
                                    if (it.hasFocus) { lastKey = rowKey(row); lastIndex = i }
                                    if (it.hasFocus) hover = (row.rec.title + episodeTag(row.rec.season, row.rec.episode)) to "${channels[row.rec.channelId]?.name ?: "Channel"}  ·  ${span(row.rec.start, row.rec.end)}"
                                },
                                // Hold OK: the options (stop, cancel, delete…).
                                onLongClick = { picked = row; vm.clearMessage() },
                                onClick = {
                                    // OK plays a finished recording straight away; anything else shows its options.
                                    if (row.rec.state == DvrState.Recorded && row.rec.hasFile) scope.launch { vm.playback(row.rec)?.let(onPlay) }
                                    else { picked = row; vm.clearMessage() }
                                },
                            )
                            is DvrRow.Rule -> RecordingRow(
                                title = row.rule.label,
                                details = (if (row.rule.mode == "new") "New episodes" else "Every episode") +
                                    (row.rule.tvgId.takeIf { it.isNotEmpty() }?.let { "  ·  $it" } ?: "  ·  Any channel"),
                                live = false,
                                modifier = (if (i == firstItem) Modifier.focusRequester(focusRequester).focusProperties { up = tabFocus[tab] } else Modifier).focusRequester(rowFocus.getOrPut(rowKey(row)!!) { FocusRequester() }).onFocusChanged {
                                    if (it.hasFocus) { lastKey = rowKey(row); lastIndex = i }
                                    if (it.hasFocus) hover = row.rule.label to (if (row.rule.mode == "new") "Series recording · new episodes" else "Series recording · every episode")
                                },
                                onClick = { picked = row; vm.clearMessage() },
                                onLongClick = { picked = row; vm.clearMessage() },
                            )
                        }
                    }
                }
            }
        }

        RecordingOptions(picked, vm, channels, message, scope, onPlay, close = { picked = null; focusBack() })
    }
}

/**
 * Hold OK (or OK on anything not ready to play) on the Recordings page: the shared options pop-up (start on the
 * first, Cancel last; Delete asks first with "Keep it" highlighted). Stop / cancel / delete stay open to show
 * "Working…" and the answer, then close. (Peter, 2026-11-17: was ChoicePanel with "Close" and its own confirm step.)
 */
@Composable
private fun RecordingOptions(
    picked: DvrRow?, vm: DvrViewModel, channels: Map<Int, ChannelListItem>, message: String?,
    scope: kotlinx.coroutines.CoroutineScope, onPlay: (VodStream) -> Unit, close: () -> Unit,
) {
    if (picked !is DvrRow.Rec && picked !is DvrRow.Rule) return
    LaunchedEffect(message) {
        if (message != null && message != "Working…") { delay(1_400); close() }
    }
    when (picked) {
        is DvrRow.Rec -> {
            val r = picked.rec
            val options = buildList {
                when (r.state) {
                    DvrState.Recorded -> {
                        if (r.hasFile) add(PopupOption("Play") { scope.launch { vm.playback(r)?.let(onPlay) } })
                        add(PopupOption(
                            "Delete recording…", destructive = true, keepOpen = true,
                            confirmTitle = "Delete \"${r.title}\"?",
                            confirm = "This deletes the recording from your Dispatcharr server. It can't be undone.",
                        ) { vm.cancel(r) })
                    }
                    DvrState.Failed -> add(PopupOption("Remove from list", keepOpen = true) { vm.cancel(r) })
                    DvrState.Recording -> add(PopupOption("Stop recording", keepOpen = true) { vm.stop(r) })
                    DvrState.Scheduled -> add(PopupOption("Cancel recording", keepOpen = true) { vm.cancel(r) })
                }
            }
            OptionsPopup(
                title = r.title + episodeTag(r.season, r.episode),
                subtitle = "${channels[r.channelId]?.name ?: "Channel"}  ·  ${span(r.start, r.end)}",
                options = options, onClose = close, message = message,
            )
        }
        is DvrRow.Rule -> OptionsPopup(
            title = picked.rule.label,
            subtitle = if (picked.rule.mode == "new") "Records new episodes" else "Records every episode",
            options = listOf(PopupOption("Stop series recording", keepOpen = true) { vm.removeRule(picked.rule) }),
            onClose = close, message = message,
        )
        else -> Unit
    }
}
