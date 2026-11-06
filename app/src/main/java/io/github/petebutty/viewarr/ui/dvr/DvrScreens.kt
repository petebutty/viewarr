package io.github.petebutty.viewarr.ui.dvr

import io.github.petebutty.viewarr.ui.common.ignoreHeldOk
import io.github.petebutty.viewarr.ui.common.trapFocus
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.dvr.DvrRecording
import io.github.petebutty.viewarr.data.dvr.DvrRepository
import io.github.petebutty.viewarr.data.dvr.DvrState
import io.github.petebutty.viewarr.data.dvr.SeriesRule
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DvrViewModel @Inject constructor(
    private val repo: DvrRepository,
    guide: GuideRepository,
    private val reminderStore: io.github.petebutty.viewarr.data.dvr.ReminderStore,
) : ViewModel() {
    /** "Remind me" programmes (pop up when they start). */
    val reminders = reminderStore.reminders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun reminderFor(channelId: Int, p: GuideProgram) = reminders.value.firstOrNull { it.channelId == channelId && it.start == p.startTime }
    fun remind(channel: ChannelListItem, p: GuideProgram) = viewModelScope.launch {
        reminderStore.add(io.github.petebutty.viewarr.data.dvr.Reminder(channel.id, p.startTime, p.endTime, p.title, channel.name))
        _message.value = "We'll remind you when it starts (while Viewarr is open)."
    }
    fun forget(id: String) = viewModelScope.launch { reminderStore.remove(id) }
    fun pruneReminders() = viewModelScope.launch { reminderStore.prune(System.currentTimeMillis()) }

    val recordings = repo.recordings
    val rules = repo.rules
    val allowed = repo.allowed
    val xc = repo.xc
    val channels = guide.observeChannels(null).map { list -> list.associateBy { it.id } }
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

    private fun act(block: suspend () -> io.github.petebutty.viewarr.data.dvr.DvrResult) = viewModelScope.launch {
        _message.value = "Working…"
        _message.value = runCatching { block().message }.getOrElse { "Something went wrong." }
    }

    fun record(channel: ChannelListItem, p: GuideProgram) = act {
        // A programme already on air records from now.
        val now = System.currentTimeMillis()
        repo.record(channel, if (p.startTime < now) p.copy(startTime = now) else p)
    }
    fun recordSeries(channel: ChannelListItem, p: GuideProgram, newOnly: Boolean) = act { repo.recordSeries(channel, p, newOnly) }
    fun cancel(rec: DvrRecording) = act { repo.cancel(rec) }
    fun stop(rec: DvrRecording) = act { repo.stop(rec) }
    fun removeRule(rule: SeriesRule) = act { repo.removeRule(rule) }

    suspend fun playback(rec: DvrRecording): VodStream? =
        repo.playback(rec)?.let { (url, headers) -> VodStream(url, rec.title, headers) }
}

private val dayTime = SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK)
private val time = SimpleDateFormat("HH:mm", Locale.UK)
private fun span(start: Long, end: Long) = "${dayTime.format(Date(start))}–${time.format(Date(end))}"
private fun episodeTag(season: Int?, episode: Int?) = if (season != null && episode != null) " · S$season E$episode" else ""

/** Small centred panel of choices, used for the record options and the recording options. */
@Composable
private fun ChoicePanel(
    title: String,
    subtitle: String,
    choices: List<Pair<String, () -> Unit>>,
    message: String?,
    onClose: () -> Unit,
    description: String? = null,
) {
    val first = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(24.dp).trapFocus()
                .then(Modifier.ignoreHeldOk()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 12.dp))
            description?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = AppColors.TextPrimary, maxLines = 7, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 14.dp))
            }
            choices.forEachIndexed { i, (label, action) ->
                MenuItem(text = label, selected = false, onClick = action, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
            message?.let { Text(it, fontSize = 14.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 10.dp)) }
        }
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
        subtitle = "${channel.name}  ·  ${span(program.startTime, program.endTime)}$status",
        choices = listOf("Record or remind me" to onRecord, "Close" to onClose),
        message = null,
        onClose = onClose,
        description = listOf(sub, program.description.orEmpty()).filter { it.isNotBlank() }.joinToString("\n"),
    )
}

/** Record options for one programme in the guide (or what's on now, from the player). */
@Composable
fun RecordDialog(channel: ChannelListItem, program: GuideProgram, vm: DvrViewModel, onDetails: (() -> Unit)? = null, onClose: () -> Unit) {
    val message by vm.message.collectAsStateWithLifecycle()
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
    val canRecord = vm.allowed.collectAsStateWithLifecycle().value != false && !vm.xc.collectAsStateWithLifecycle().value
    val future = program.startTime > System.currentTimeMillis()
    val choices = buildList<Pair<String, () -> Unit>> {
        if (!canRecord) {
            // No recordings here (IPTV provider login, or a Dispatcharr user without DVR access): reminders still work.
        } else when {
            rec?.state == DvrState.Recording -> add("Stop recording" to { run { vm.stop(rec) } })
            rec != null -> add("Cancel this recording" to { run { vm.cancel(rec) } })
            else -> add((if (program.startTime <= System.currentTimeMillis()) "Record from now" else "Record this programme") to { run { vm.record(channel, program) } })
        }
        if (canRecord) {
            if (rule != null) add("Stop series recording" to { run { vm.removeRule(rule) } })
            else {
                add("Record series: every episode" to { run { vm.recordSeries(channel, program, newOnly = false) } })
                add("Record series: new episodes only" to { run { vm.recordSeries(channel, program, newOnly = true) } })
            }
        }
        if (future) {
            if (reminder != null) add("Cancel reminder" to { vm.forget(reminder.id); onClose() })
            else add("Remind me when it starts" to { run { vm.remind(channel, program) } })
        }
        onDetails?.let { add("Details" to it) }
        add("Close" to onClose)
    }
    val status = when {
        rec?.state == DvrState.Recording -> "  ·  ● Recording now"
        rec != null -> "  ·  ● Scheduled"
        rule != null -> "  ·  Series recording on"
        reminder != null -> "  ·  ⏰ Reminder set"
        else -> ""
    }
    ChoicePanel(
        title = program.title + episodeTag(program.season, program.episode),
        subtitle = "${channel.name}  ·  ${span(program.startTime, program.endTime)}$status",
        choices = choices,
        message = message,
        onClose = onClose,
    )
}

private sealed interface DvrRow {
    data class Header(val text: String) : DvrRow
    data class Rec(val rec: DvrRecording) : DvrRow
    data class Rule(val rule: SeriesRule) : DvrRow
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
    var confirmDelete by remember { mutableLongStateOf(0L) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.refresh() }
    BackHandler(enabled = focusEnabled && picked == null) { onBack() }

    val rows = buildList {
        val now = recordings.filter { it.state == DvrState.Recording }
        val scheduled = recordings.filter { it.state == DvrState.Scheduled }
        val done = recordings.filter { it.state == DvrState.Recorded || it.state == DvrState.Failed }.sortedByDescending { it.start }
        if (now.isNotEmpty()) { add(DvrRow.Header("Recording now")); now.forEach { add(DvrRow.Rec(it)) } }
        add(DvrRow.Header("Scheduled (${scheduled.size})")); scheduled.forEach { add(DvrRow.Rec(it)) }
        add(DvrRow.Header("Recorded (${done.size})")); done.forEach { add(DvrRow.Rec(it)) }
        add(DvrRow.Header("Series recordings (${rules.size})")); rules.forEach { add(DvrRow.Rule(it)) }
    }
    val firstItem = rows.indexOfFirst { it !is DvrRow.Header }
    LaunchedEffect(firstItem >= 0, focusEnabled, picked == null) {
        if (!focusEnabled || picked != null || firstItem < 0) return@LaunchedEffect
        awaitFrame()
        runCatching { focusRequester.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(AppColors.Background)) {
        Column(
            Modifier
                .fillMaxSize()
                // Left (the list has nothing to its left) opens the menu, as on every page.
                .onPreviewKeyEvent { e ->
                    if (picked == null && e.key == androidx.compose.ui.input.key.Key.DirectionLeft) {
                        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) onOpenMenu()
                        true
                    } else false
                }
                .padding(horizontal = 56.dp, vertical = 32.dp),
        ) {
            Text("Recordings", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Text("Recorded and scheduled on your Dispatcharr server  ·  OK plays  ·  Hold OK for options", fontSize = 13.sp, color = AppColors.TextSecondary)
            Spacer(Modifier.height(16.dp))
            when {
                allowed == false -> Text(
                    if (xc) "Recordings need a Dispatcharr server. Your live TV comes straight from your IPTV provider, which can't record. " +
                        "You can still watch catch-up from the TV guide if your provider offers it."
                    else "Your Dispatcharr account isn't allowed to use recordings. In Dispatcharr, open Users, edit this account and set DVR access to Manage.",
                    fontSize = 15.sp, color = AppColors.TextPrimary,
                )
                allowed == null && recordings.isEmpty() -> Text("Loading…", color = AppColors.TextSecondary)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(rows.size, key = { i -> when (val r = rows[i]) {
                        is DvrRow.Header -> "h-${r.text}"
                        is DvrRow.Rec -> "r-${r.rec.id}"
                        is DvrRow.Rule -> "s-${r.rule.tvgId}-${r.rule.title}"
                    } }) { i ->
                        when (val row = rows[i]) {
                            is DvrRow.Header -> Text(row.text, fontSize = 14.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp, start = 4.dp))
                            is DvrRow.Rec -> ItemRow(
                                title = row.rec.title + episodeTag(row.rec.season, row.rec.episode) + if (row.rec.state == DvrState.Failed) "  (failed)" else "",
                                detail = "${channels[row.rec.channelId]?.name ?: "Channel"}  ·  ${span(row.rec.start, row.rec.end)}",
                                live = row.rec.state == DvrState.Recording,
                                modifier = if (i == firstItem) Modifier.focusRequester(focusRequester) else Modifier,
                                // Hold OK: the options (stop, cancel, delete…).
                                onLongClick = { picked = row; confirmDelete = 0L; vm.clearMessage() },
                            ) {
                                // OK plays a finished recording straight away; anything else shows its options.
                                if (row.rec.state == DvrState.Recorded && row.rec.hasFile) scope.launch { vm.playback(row.rec)?.let(onPlay) }
                                else { picked = row; confirmDelete = 0L; vm.clearMessage() }
                            }
                            is DvrRow.Rule -> ItemRow(
                                title = row.rule.label,
                                detail = (if (row.rule.mode == "new") "New episodes" else "Every episode") +
                                    (row.rule.tvgId.takeIf { it.isNotEmpty() }?.let { "  ·  $it" } ?: "  ·  Any channel"),
                                live = false,
                                modifier = if (i == firstItem) Modifier.focusRequester(focusRequester) else Modifier,
                            ) { picked = row; vm.clearMessage() }
                        }
                    }
                }
            }
        }

        when (val p = picked) {
            is DvrRow.Rec -> {
                val r = p.rec
                val close = { picked = null; runCatching { focusRequester.requestFocus() }; Unit }
                LaunchedEffect(message) {
                    if (message != null && message != "Working…") { delay(1_400); picked = null }
                }
                val choices = buildList<Pair<String, () -> Unit>> {
                    when (r.state) {
                        DvrState.Recorded -> {
                            if (r.hasFile) add("Play" to {
                                picked = null
                                scope.launch { vm.playback(r)?.let(onPlay) }
                            })
                            add("Delete recording…" to { confirmDelete = System.currentTimeMillis() })
                        }
                        DvrState.Failed -> add("Remove from list" to { vm.cancel(r) })
                        DvrState.Recording -> add("Stop recording" to { vm.stop(r) })
                        DvrState.Scheduled -> add("Cancel recording" to { vm.cancel(r) })
                    }
                    add("Close" to close)
                }
                if (confirmDelete != 0L) {
                    // A separate confirmation, with "Keep it" focused: a stray OK can never delete anything.
                    ChoicePanel(
                        "Delete \"${r.title}\"?",
                        "This deletes the recording from your Dispatcharr server. It can't be undone.",
                        listOf("Keep it" to { confirmDelete = 0L }, "Delete permanently" to { confirmDelete = 0L; vm.cancel(r) }),
                        message,
                        { confirmDelete = 0L },
                    )
                } else ChoicePanel(r.title + episodeTag(r.season, r.episode), "${channels[r.channelId]?.name ?: "Channel"}  ·  ${span(r.start, r.end)}", choices, message, close)
            }
            is DvrRow.Rule -> {
                val close = { picked = null; Unit }
                LaunchedEffect(message) {
                    if (message != null && message != "Working…") { delay(1_400); picked = null }
                }
                ChoicePanel(
                    p.rule.label,
                    if (p.rule.mode == "new") "Records new episodes" else "Records every episode",
                    listOf("Stop series recording" to { vm.removeRule(p.rule) }, "Close" to close),
                    message,
                    close,
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun ItemRow(title: String, detail: String, live: Boolean, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Panel,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text((if (live) "● " else "") + title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
