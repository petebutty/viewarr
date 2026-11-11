package io.github.petebutty.viewarr.ui.player

import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import io.github.petebutty.viewarr.ui.common.ignoreHeldOk
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.playback.AspectMode
import io.github.petebutty.viewarr.playback.PlaybackViewModel
import io.github.petebutty.viewarr.playback.TrackOption
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.guide.displayTitle
import io.github.petebutty.viewarr.ui.guide.formatNumber
import io.github.petebutty.viewarr.ui.guide.logoUrl
import io.github.petebutty.viewarr.ui.theme.AppColors
import coil.compose.AsyncImage
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class OsdPopup { Audio, Subtitles, Aspect, Source, Info }

private val hhmm get() = io.github.petebutty.viewarr.ui.theme.Dates.time
private fun Long.clockTime() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(hhmm)

/** TiviMate-style player menu: compact info, catch-up seek bar, and a row of small option pills. */
@Composable
fun PlayerOsd(
    vm: PlaybackViewModel,
    channel: ChannelListItem,
    current: GuideProgram?,
    now: Long,
    serverUrl: String,
    onGuide: () -> Unit,
    onClose: () -> Unit,
    onActivity: () -> Unit,
    onRecord: (() -> Unit)? = null,
    /** Several channels at once. */
    onMultiView: (() -> Unit)? = null,
    /** The settings, over live TV (Back returns here). */
    onSettings: (() -> Unit)? = null,
    /** Channel list, guide and wallpaper transparency, adjusted over the picture. */
    onTransparency: (() -> Unit)? = null,
    /** Down from the buttons: your shortcuts (libraries, rows, Music…; Settings → Navigation → Live TV shortcuts). */
    shortcuts: List<Pair<LiveShortcut, () -> Unit>> = emptyList(),
    /** Down from the shortcuts: rows of posters (Continue watching, Recent channels…), one row on screen at a time. */
    posterRows: List<OsdRow> = emptyList(),
    /** The rows a shortcut previews underneath while it's highlighted (its network's, its row, its page's); null: [posterRows]. */
    previewFor: (String) -> List<OsdRow>? = { null },
    /** Hold OK on a shortcut: take it off the row. */
    onHideShortcut: (LiveShortcut) -> Unit = {},
    /** Move a shortcut one place along the row (-1 left, 1 right), from the hold-OK menu's Move. */
    onMoveShortcut: (LiveShortcut, Int) -> Unit = { _, _ -> },
    /** Refresh just this shortcut's rows (from the hold-OK menu). */
    onRefreshShortcut: ((LiveShortcut) -> Unit)? = null,
    /** Opened with the Menu button: start on the audio choices. */
    openAudio: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val catchup by vm.catchup.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val audio by vm.audioTracks.collectAsStateWithLifecycle()
    val subtitles by vm.textTracks.collectAsStateWithLifecycle()
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val sourceNote by vm.sourceNote.collectAsStateWithLifecycle()
    var popup by remember { mutableStateOf<OsdPopup?>(if (openAudio) OsdPopup.Audio else null) }
    var note by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    // The progress bar: Left on it while live rewinds (catch-up channels); it stays focused as it turns into the
    // catch-up seek bar, so you can keep scrubbing.
    val barFocus = remember { FocusRequester() }
    var keepBar by remember { mutableStateOf(false) }
    LaunchedEffect(catchup != null) {
        if (!keepBar) return@LaunchedEffect
        awaitFrame(); awaitFrame()
        runCatching { barFocus.requestFocus() }
        keepBar = false
    }
    val seekStep = io.github.petebutty.viewarr.data.settings.LocalRemote.current.seek.ms

    BackHandler { if (popup != null) popup = null else onClose() }
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, AppColors.Background.copy(alpha = 0.9f), AppColors.Background)))
            .onPreviewKeyEvent { onActivity(); false }
            // Opened by OK: the release of that same press (Fire TV remotes send it a beat later) mustn't press
            // "TV guide", the first button, and throw you out to the guide.
            .ignoreHeldOk()
            .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (popup == OsdPopup.Info) StreamInfoPopup(vm)
        popup?.takeIf { it != OsdPopup.Info }?.let { p ->
            OptionPopup(
                wide = p == OsdPopup.Source,
                options = when (p) {
                    OsdPopup.Audio -> audio.map { it.label to it.selected }
                    OsdPopup.Subtitles -> subtitles.map { it.label to it.selected }
                    OsdPopup.Aspect -> AspectMode.entries.map { it.label to (it == aspect) }
                    OsdPopup.Source -> sources.orEmpty().map { (if (it.current) "✓  " else "     ") + (if (it.provider.isNotEmpty()) "${it.name}  ·  ${it.provider}" else it.name) to it.current }
                    OsdPopup.Info -> emptyList()
                },
                title = if (p == OsdPopup.Source) "Source" + (sourceNote?.let { "  ·  $it" } ?: "") else p.name,
                emptyText = when {
                    p == OsdPopup.Subtitles -> "No subtitles on this channel"
                    p == OsdPopup.Source -> sourceNote ?: "Loading sources…"
                    else -> "Nothing to choose"
                },
                onPick = { i ->
                    when (p) {
                        OsdPopup.Audio -> audio.getOrNull(i)?.let { vm.selectTrack(it, C.TRACK_TYPE_AUDIO) }
                        OsdPopup.Subtitles -> subtitles.getOrNull(i)?.let { vm.selectTrack(it, C.TRACK_TYPE_TEXT) }
                        OsdPopup.Aspect -> vm.setAspect(AspectMode.entries[i])
                        OsdPopup.Source -> sources?.getOrNull(i)?.let { if (!it.current) vm.switchSource(it) }
                        OsdPopup.Info -> Unit
                    }
                    popup = null
                },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                channel.number?.let(::formatNumber).orEmpty(),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.width(64.dp),
            )
            Box(Modifier.size(width = 72.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                io.github.petebutty.viewarr.ui.guide.channelLogo(serverUrl, channel)?.let { AsyncImage(it, null, contentScale = ContentScale.Fit) }
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.name, fontSize = 13.sp, color = AppColors.TextSecondary)
                    val behind = vm.behindLiveMs()?.takeIf { it > 60_000 }?.let { "${it / 60_000} MIN BEHIND LIVE" }
                    if (catchup != null) Badge(listOfNotNull(if (behind != null) null else "CATCH-UP", behind, if (paused) "PAUSED" else null).joinToString(" · "))
                    else if (paused) Badge("PAUSED · PLAY CARRIES ON FROM HERE")
                }
                Text(
                    current?.let(::displayTitle) ?: "No guide information",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (current != null) {
                Text(
                    "${current.startTime.clockTime()} – ${current.endTime.clockTime()}",
                    fontSize = 14.sp,
                    color = AppColors.TextSecondary,
                )
            }
        }

        if (catchup != null) {
            SeekBar(
                position = position.first,
                // The programme's own length: a programme still on air is only as long as what's been broadcast in
                // the provider's archive, which made the bar look nearly finished.
                duration = catchup!!.endTime - catchup!!.startTime,
                // How far through it live is (a programme still on air).
                live = (now - catchup!!.startTime).takeIf { now < catchup!!.endTime },
                paused = paused,
                onSeek = vm::seekBy,
                onToggle = vm::togglePause,
                modifier = Modifier.focusRequester(firstFocus).focusRequester(barFocus),
            )
        } else if (current != null) {
            if (channel.catchupDays > 0) {
                var barOn by remember { mutableStateOf(false) }
                // Left / Right move a marker to where the rewind will jump to; it jumps on OK, or once you stop.
                var rewindTo by remember { mutableStateOf<Long?>(null) }
                val earliest = maxOf(current.startTime, now - channel.catchupDays * 86_400_000L)
                fun jump() { rewindTo?.let { keepBar = true; vm.rewindLiveTo(it) }; rewindTo = null }
                LaunchedEffect(rewindTo) { if (rewindTo != null) { kotlinx.coroutines.delay(2_500); jump() } }
                Column(
                    Modifier.focusRequester(barFocus).onFocusChanged { barOn = it.isFocused; if (!it.isFocused) rewindTo = null }.focusable().onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val step = if (e.nativeKeyEvent.repeatCount >= 4) seekStep * 4 else seekStep
                        when (e.key) {
                            // The first step back is at least a minute (the archive runs just behind live).
                            Key.DirectionLeft -> rewindTo = (minOf(rewindTo ?: now, now - 60_000 + step) - step).coerceAtLeast(earliest)
                            Key.DirectionRight -> rewindTo = rewindTo?.let { (it + step).takeIf { t -> t < now - 60_000 } }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (rewindTo != null) jump() else { keepBar = true; vm.togglePause() }
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
                ) {
                    val marker = rewindTo?.let { ((it - current.startTime).toFloat() / (current.endTime - current.startTime)).coerceIn(0f, 1f) }
                    LiveProgress(current, now, highlighted = barOn, marker = marker)
                    val hint = rewindTo?.let { t -> "Back to ${t.clockTime()}  ·  ${((now - t) / 60_000).coerceAtLeast(1)} min ago  ·  OK to jump now" }
                        ?: "◀ Left to rewind  ·  OK to pause"
                    if (barOn) Text(hint, fontSize = 11.sp, color = if (rewindTo != null) Color.White else AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
            } else LiveProgress(current, now)
        }

        // The same icon buttons as the film / show player's controls (the focused one's name underneath).
        var showShortcuts by remember { mutableStateOf(false) }
        val shortcutFocus = remember { FocusRequester() }
        Row(
            Modifier.padding(top = 6.dp).onPreviewKeyEvent { e ->
                // Down from the buttons: the shortcuts row.
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown && shortcuts.isNotEmpty() && popup == null) { showShortcuts = true; true } else false
            },
            verticalAlignment = Alignment.Top,
        ) {
            if (catchup != null || paused) {
                if (catchup != null) Pill(io.github.petebutty.viewarr.ui.common.AppIcons.Replay, "Start over") { vm.restartProgramme() }
                Pill(io.github.petebutty.viewarr.ui.common.AppIcons.SkipNext, "Go live", groupEnd = true) { vm.goLive() }
            }
            Pill(io.github.petebutty.viewarr.ui.common.AppIcons.Guide, "TV guide", if (catchup == null) Modifier.focusRequester(firstFocus) else Modifier, onClick = onGuide)
            Pill(io.github.petebutty.viewarr.ui.common.AppIcons.Audio, "Audio") { popup = OsdPopup.Audio }
            Pill(io.github.petebutty.viewarr.ui.common.AppIcons.Subtitles, "Subtitles") { popup = OsdPopup.Subtitles }
            Pill(io.github.petebutty.viewarr.ui.common.AppIcons.Aspect, "Aspect: ${aspect.label}") { popup = OsdPopup.Aspect }
            if (vm.dispatcharrLive) {
                Pill(io.github.petebutty.viewarr.ui.common.AppIcons.Layers, "Source") { vm.loadSources(); popup = OsdPopup.Source }
                Pill(io.github.petebutty.viewarr.ui.common.AppIcons.Stats, if (popup == OsdPopup.Info) "Hide stream info" else "Stream info") { popup = if (popup == OsdPopup.Info) null else OsdPopup.Info }
            }
            if (onRecord != null) Pill(io.github.petebutty.viewarr.ui.common.AppIcons.Record, "Record") { onRecord() }
            if (onMultiView != null) Pill(io.github.petebutty.viewarr.ui.common.AppIcons.Grid, "Multi-view") { onMultiView() }
            if (onTransparency != null) Pill(io.github.petebutty.viewarr.ui.common.AppIcons.Opacity, "Transparency") { onTransparency() }
            if (onSettings != null) Pill(io.github.petebutty.viewarr.ui.common.AppIcons.Settings, "Settings") { onSettings() }
            (note ?: sourceNote?.takeIf { popup == null })?.let { Text(it, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 8.dp, top = 14.dp)) }
        }
        // -1: on the shortcuts (the first row peeks underneath); otherwise the poster row being browsed.
        var posterRow by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
        // The highlighted shortcut previews its own content underneath; going down keeps what was showing.
        // (Taken a moment after the highlight rests: each preview loads a new set of pictures, and doing that for
        // every shortcut passed on the way along made moving sluggish.)
        var focusedRaw by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        var focusedSc by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        LaunchedEffect(focusedRaw) { kotlinx.coroutines.delay(250); focusedSc = focusedRaw }
        fun rowsFor(i: Int) = shortcuts.getOrNull(i)?.first?.id?.let(previewFor)?.takeIf { it.isNotEmpty() } ?: posterRows
        val allRows = rowsFor(focusedSc)
        // Settings → Remote & navigation → Rows under the shortcuts: Down reaches this many; the rest are on the
        // shortcut's own page.
        val maxRows = when (AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.OSD_ROWS)) { 1 -> 2; 2 -> 5; 3 -> Int.MAX_VALUE; else -> 3 }
        val shownRows = allRows.take(maxRows)
        val moreRows = allRows.size > shownRows.size
        var browsing by remember { mutableStateOf<List<OsdRow>?>(null) }
        // Hold OK on a shortcut takes it off; focus then goes to the one now in its place.
        val scFocus = remember { HashMap<String, FocusRequester>() }
        var hiddenAt by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
        // Hold OK asks first: "Hide Netflix from your shortcuts?" (Cancel highlighted, so a stray press does nothing).
        var askHide by remember { mutableStateOf<Pair<Int, LiveShortcut>?>(null) }
        // Move (from that menu): the shortcut picked up; Left / Right slide it along, OK or Back put it down.
        var movingSc by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(shortcuts.map { it.first.id }) {
            val id = movingSc ?: return@LaunchedEffect
            awaitFrame()
            runCatching { scFocus[id]?.requestFocus() }
        }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        askHide?.let { (at, sc) ->
            ShortcutOptions(
                sc, onActivity = onActivity,
                onMove = {
                    askHide = null; movingSc = sc.id
                    scope.launch { awaitFrame(); runCatching { scFocus[sc.id]?.requestFocus() } }
                },
                onRefresh = onRefreshShortcut?.let { f -> {
                    askHide = null; f(sc)
                    scope.launch { awaitFrame(); runCatching { scFocus[sc.id]?.requestFocus() } }
                } },
                onHide = { askHide = null; hiddenAt = at; onHideShortcut(sc) },
                onCancel = {
                    askHide = null
                    scope.launch { awaitFrame(); runCatching { scFocus[sc.id]?.requestFocus() } }
                },
            )
        }
        LaunchedEffect(shortcuts.size) {
            val at = hiddenAt.takeIf { it >= 0 } ?: return@LaunchedEffect
            hiddenAt = -1
            awaitFrame()
            shortcuts.getOrNull(at.coerceAtMost(shortcuts.lastIndex))?.first?.id?.let { id -> runCatching { scFocus[id]?.requestFocus() } }
        }
        if (showShortcuts && shortcuts.isNotEmpty()) {
            LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { shortcutFocus.requestFocus() } }
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.padding(top = 10.dp).onPreviewKeyEvent { e ->
                    // Moving one: Left / Right slide it; OK or Back put it down (on letting go, so the release doesn't
                    // also open it or close the controls); nothing else moves.
                    movingSc?.let { id ->
                        onActivity()
                        val sc = shortcuts.firstOrNull { it.first.id == id }?.first ?: run { movingSc = null; return@onPreviewKeyEvent false }
                        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        if (e.type == KeyEventType.KeyDown) {
                            if (e.key == Key.DirectionLeft) onMoveShortcut(sc, -1)
                            if (e.key == Key.DirectionRight) onMoveShortcut(sc, 1)
                        } else if (ok || e.key == Key.Back) movingSc = null
                        return@onPreviewKeyEvent true
                    }
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        // Up: back to the buttons, the shortcuts folded away again.
                        Key.DirectionUp -> { showShortcuts = false; posterRow = -1; runCatching { firstFocus.requestFocus() }; true }
                        // Down: into the posters.
                        // (The highlighted one's rows now, not the preview's, which follows the highlight a moment late.)
                        Key.DirectionDown -> {
                            focusedSc = focusedRaw
                            val rows = rowsFor(focusedRaw).take(maxRows)
                            if (rows.isNotEmpty()) { browsing = rows; posterRow = 0 }
                            true
                        }
                        else -> false
                    }
                },
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                // Round icons like the buttons above, the highlighted one's name underneath.
                items(shortcuts.size, key = { shortcuts[it].first.id }) { i ->
                    val (sc, go) = shortcuts[i]
                    val fr = scFocus.getOrPut(sc.id) { FocusRequester() }
                    Pill(sc.icon, if (movingSc == sc.id) "◀  ${sc.label}  ▶" else sc.label, (if (i == 0) Modifier.focusRequester(shortcutFocus) else Modifier).focusRequester(fr), emoji = sc.emoji,
                        logo = sc.logoName?.let { ShortcutLogos.map[it] }, big = true, onFocus = { focusedRaw = i }, colors = sc.colors, shortcut = sc,
                        onLongClick = if (sc.id.startsWith("action:")) null else ({ askHide = i to sc })) { go() }
                }
            }
            val rowsNow = browsing ?: shownRows
            // Settings → Live TV shortcuts → Rows under the shortcuts appear: only once you go down into them
            // (the default, cleaner), or a dimmed preview under the shortcuts all along.
            val peek = AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.OSD_PEEK) == 1
            if (rowsNow.isNotEmpty() && (peek || posterRow >= 0)) PosterRows(rowsNow, posterRow, more = moreRows, onRow = { r ->
                posterRow = r
                // Up from the first row: back to the shortcut it came from.
                if (r < 0) { browsing = null; runCatching { (shortcuts.getOrNull(focusedRaw)?.first?.id?.let { scFocus[it] } ?: shortcutFocus).requestFocus() } }
            })
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = AppColors.OnAccent,
        modifier = Modifier.padding(start = 8.dp).background(AppColors.Accent, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * One poster row at a time, floating under the shortcuts (no panel: the controls' own fade is behind it). Up / Down
 * change row (Up from the first goes back to the shortcuts); the next row's name shows beside this one's.
 */
@Composable
private fun PosterRows(rows: List<OsdRow>, current: Int, more: Boolean = false, onRow: (Int) -> Unit) {
    val r = current.coerceIn(0, rows.lastIndex)
    val row = rows[r]
    val inside = current >= 0
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(current) { if (current >= 0) { awaitFrame(); runCatching { firstCard.requestFocus() } } }
    Column(
        Modifier
            .padding(top = 6.dp)
            // Dimmed while it's only a preview: a dark tint drawn over it (fading the whole row with alpha made the
            // TV draw it twice, every frame).
            .drawWithContent { drawContent(); if (!inside) drawRect(Color.Black.copy(alpha = 0.5f)) }
            .onPreviewKeyEvent { e ->
                if (!inside || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> { onRow(r - 1); true }
                    Key.DirectionDown -> { if (r < rows.lastIndex) onRow(r + 1); true }
                    else -> false
                }
            },
    ) {
        OsdRowHeading(row.title, rows.getOrNull(r + 1)?.title, more)
        androidx.compose.runtime.key(r) {
            // Room round the cards for the highlighted one's slight growth (it was cut off at the row's left edge),
            // pulled back by the same amount so the first card still lines up with the row's name.
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.padding(top = 2.dp).offset(x = (-12).dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(row.items.size, key = { row.items[it].key }) { i ->
                    PosterCard(row.items[i], enabled = inside, modifier = if (i == 0) Modifier.focusRequester(firstCard) else Modifier)
                }
            }
        }
    }
}

/**
 * Holding OK on a shortcut (live TV and the film player show this same one): Move along the row, Refresh just its
 * rows (shortcuts that have rows), Hide, or Cancel (highlighted, so a stray press does nothing).
 */
@Composable
fun ShortcutOptions(sc: LiveShortcut, onMove: () -> Unit, onRefresh: (() -> Unit)?, onHide: () -> Unit, onCancel: () -> Unit, onActivity: () -> Unit = {}) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        val cancel = remember { FocusRequester() }
        LaunchedEffect(Unit) { awaitFrame(); runCatching { cancel.requestFocus() } }
        Column(
            // (Pressing here counts as using the controls, so they don't close behind it. The held OK that opened it,
            // still down and then let go, mustn't press Cancel: it closed at once.)
            Modifier.width(420.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).onPreviewKeyEvent { onActivity(); false }.ignoreHeldOk().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(sc.label, fontSize = 18.sp, color = AppColors.TextPrimary)
            Text("Move it along your row, fetch its rows again, or hide it (add it back in Settings → Navigation → Live TV shortcuts).", fontSize = 13.sp, color = AppColors.TextSecondary)
            io.github.petebutty.viewarr.ui.common.MenuItem(text = "Move", selected = false, onClick = onMove)
            // Shortcuts with rows of their own (networks, rows, Movies / Shows, libraries): fetch just those again.
            if (onRefresh != null && listOf("set:", "row:", "home:", "lib:").any { sc.id.startsWith(it) })
                io.github.petebutty.viewarr.ui.common.MenuItem(text = "Refresh", selected = false, onClick = onRefresh)
            io.github.petebutty.viewarr.ui.common.MenuItem(text = "Hide", selected = false, onClick = onHide)
            io.github.petebutty.viewarr.ui.common.MenuItem(text = "Cancel", selected = false, modifier = Modifier.focusRequester(cancel), onClick = onCancel)
        }
    }
}

/** The picture inside a row card (live TV and the film player draw the same): artwork or logo, else the title; progress. */
@Composable
fun OsdCardContent(p: OsdPoster) {
    Box(Modifier.fillMaxSize()) {
        // No picture, with a look of its own (Live now's sport): its colours and icon (the name is under the card).
        if (p.image == null && p.emoji != null) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient((p.tint ?: listOf(0xFF455A64, 0xFF263238)).map { Color(it) })))
            Text(p.emoji, fontSize = 44.sp, modifier = Modifier.align(Alignment.Center))
        } else if (p.image != null) AsyncImage(
            p.image, null,
            contentScale = if (p.logo) ContentScale.Fit else ContentScale.Crop,
            modifier = if (p.logo) Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp) else Modifier.fillMaxSize(),
        ) else Text(p.title, fontSize = 13.sp, color = Color.White, maxLines = 2, modifier = Modifier.align(Alignment.Center).padding(8.dp))
        p.progress?.takeIf { it > 0f }?.let { f ->
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f.coerceIn(0f, 1f)).height(3.dp).background(AppColors.Accent))
        }
    }
}

/** A row card's name and subtitle under it (the same on live TV and in the film player). */
@Composable
fun OsdCardCaption(p: OsdPoster, highlighted: Boolean) {
    Text(p.title, fontSize = 12.sp, color = if (highlighted) Color.White else AppColors.TextSecondary, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
    p.subtitle?.let { Text(it, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A row's heading: its name, then the next row's (or that there are more on the shortcut's own page). */
@Composable
fun OsdRowHeading(title: String, next: String?, more: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        if (next != null) Text("   ·   Down: $next", fontSize = 12.sp, color = AppColors.TextSecondary)
        else if (more) Text("   ·   More rows: open the shortcut", fontSize = 12.sp, color = AppColors.TextSecondary)
    }
}

/** Card size and look, shared by live TV's cards and the film player's. */
const val OSD_CARD_W = 176
const val OSD_CARD_H = 99

/** The film player's card: drawn exactly as live TV's, highlighted by the player (it has no focus of its own). */
@Composable
fun OsdCardStatic(p: OsdPoster, highlighted: Boolean) {
    // The same gentle grow as live TV's focused card (the TV Surface's own animation), not a jump.
    val grow by androidx.compose.animation.core.animateFloatAsState(if (highlighted) 1.05f else 1f, label = "card")
    Column(Modifier.width(OSD_CARD_W.dp)) {
        Box(
            Modifier.size(OSD_CARD_W.dp, OSD_CARD_H.dp)
                .graphicsLayer { scaleX = grow; scaleY = grow }
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = if (highlighted) 0.18f else 0.10f))
                .then(if (highlighted) Modifier.border(2.dp, AppColors.Accent, RoundedCornerShape(8.dp)) else Modifier),
        ) { OsdCardContent(p) }
        OsdCardCaption(p, highlighted)
    }
}

@Composable
private fun PosterCard(p: OsdPoster, enabled: Boolean, modifier: Modifier = Modifier) {
    var on by remember { mutableStateOf(false) }
    Column(Modifier.width(OSD_CARD_W.dp)) {
        Surface(
            onClick = p.onClick,
            enabled = enabled,
            modifier = modifier.size(OSD_CARD_W.dp, OSD_CARD_H.dp).onFocusChanged { on = it.isFocused },
            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.05f, pressedScale = 1f),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, AppColors.Accent)),
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.10f),
                focusedContainerColor = Color.White.copy(alpha = 0.18f),
            ),
        ) { OsdCardContent(p) }
        OsdCardCaption(p, on)
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, groupEnd: Boolean = false, emoji: String? = null,
    logo: io.github.petebutty.viewarr.data.media.ServiceLogo? = null,
    /** The shortcuts row: a larger circle, so logos read from the sofa. */
    big: Boolean = false, onFocus: () -> Unit = {}, onLongClick: (() -> Unit)? = null,
    /** A coloured disc behind the icon (Viewarr's own shortcuts, so they sit with the services' logos). */
    colors: List<Long>? = null,
    /** The shortcut itself (the shortcuts row): drawn in Settings → Shortcut style. */
    shortcut: LiveShortcut? = null, onClick: () -> Unit) {
    // Sized like the film / show player's buttons (Settings → Playback → Player controls size).
    val k = AppColors.palette.controlsScale
    fun d(v: Int) = (v * k).dp
    var on by remember { mutableStateOf(false) }
    // Shortcuts: Settings → Live TV shortcuts → Shortcut size (Small is the first size).
    val sc = if (big) floatArrayOf(1f, 1.25f, 1.5f)[AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.SHORTCUT_SIZE).coerceIn(0, 2)] else 1f
    fun b(v: Int) = d((v * sc).toInt())
    val style = if (shortcut != null) shortcutStyle() else ShortcutStyle.Round
    val (bw, bh) = shortcutBox(if (big) b(64) else d(48), style)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (big) bw + b(24) else d(72))) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.size(bw, bh).onFocusChanged { on = it.isFocused; if (it.isFocused) onFocus() },
            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
            shape = ClickableSurfaceDefaults.shape(shortcutShape(style)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.14f),
                contentColor = Color.White,
                focusedContainerColor = AppColors.Accent,
                focusedContentColor = AppColors.OnAccent,
                pressedContainerColor = AppColors.Accent,
                pressedContentColor = AppColors.OnAccent,
            ),
        ) {
            if (shortcut != null) Box(Modifier.fillMaxSize().padding(if (on) d(3) else 0.dp), contentAlignment = Alignment.Center) {
                ShortcutFace(shortcut, style, bh)
            } else Box(Modifier.fillMaxHeight().fillMaxWidth(), contentAlignment = Alignment.Center) {
                // A service's app icon fills the circle; a network's logo sits on a white disc (they're drawn for
                // light backgrounds). The ring shows focus around either.
                if (logo != null && logo.square) AsyncImage(logo.url, label, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(if (on) d(3) else 0.dp).clip(CircleShape))
                else if (logo != null) Box(Modifier.fillMaxSize().padding(if (on) d(3) else 0.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    AsyncImage(logo.url, label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(d(6)))
                }
                else if (colors != null) Box(
                    Modifier.fillMaxSize().padding(if (on) d(3) else 0.dp)
                        .background(Brush.linearGradient(colors.map { Color(it) }), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(if (big) b(32) else d(26))) }
                else if (emoji != null) Text(emoji, fontSize = (22 * k).sp)
                else androidx.compose.material3.Icon(icon, contentDescription = label, tint = if (on) AppColors.OnAccent else Color.White, modifier = Modifier.size(if (big) b(32) else d(26)))
            }
        }
        Text(
            if (on) label else "", fontSize = (12 * (0.5f + k / 2)).sp, color = Color.White, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(top = d(4)).wrapContentWidth(unbounded = true),
        )
    }
    if (groupEnd) Box(Modifier.padding(horizontal = d(10), vertical = d(10)).width(1.dp).height(d(28)).background(Color.White.copy(alpha = 0.3f)))
}

@Composable
private fun LiveProgress(program: GuideProgram, now: Long, highlighted: Boolean = false, marker: Float? = null) {
    val fraction = ((now - program.startTime).toFloat() / (program.endTime - program.startTime)).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The rewind marker: the bar up to it in the highlight colour, from it to live dimmed, and a white knob on it.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).height(14.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(if (highlighted) 6.dp else 4.dp).background(Color(0x44FFFFFF), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.Accent.copy(alpha = if (marker != null) 0.35f else 1f), RoundedCornerShape(2.dp)))
                if (marker != null) Box(Modifier.fillMaxWidth(marker).fillMaxHeight().background(AppColors.Accent, RoundedCornerShape(2.dp)))
            }
            if (marker != null) Box(Modifier.padding(start = (maxWidth * marker - 7.dp).coerceAtLeast(0.dp)).size(14.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Text("${((program.endTime - now) / 60_000).coerceAtLeast(0)} min left", fontSize = 13.sp, color = AppColors.TextSecondary)
    }
}

/** Focusable seek bar: Left/Right skip (faster when held), OK plays/pauses. */
@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    live: Long? = null,
    paused: Boolean,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val seekMs = io.github.petebutty.viewarr.data.settings.LocalRemote.current.seek.ms
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = if (e.nativeKeyEvent.repeatCount >= 4) seekMs * 4 else seekMs
                when (e.key) {
                    Key.DirectionLeft -> onSeek(-step)
                    Key.DirectionRight -> onSeek(step)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> onToggle()
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
            val liveFraction = live?.let { (it.toFloat() / duration).coerceIn(0f, 1f) }
            Box(Modifier.fillMaxWidth().height(if (focused) 6.dp else 4.dp).background(Color(0x44FFFFFF), RoundedCornerShape(3.dp))) {
                // Broadcast so far (up to live), then where you're playing.
                liveFraction?.let { Box(Modifier.fillMaxWidth(it).fillMaxHeight().background(AppColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(3.dp))) }
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.Accent, RoundedCornerShape(3.dp)))
            }
            // Where you are: a knob. Live: a thin white mark.
            Box(Modifier.padding(start = (maxWidth * fraction - 6.dp).coerceAtLeast(0.dp)).size(12.dp).background(Color.White, CircleShape))
            liveFraction?.let { Box(Modifier.padding(start = (maxWidth * it - 1.dp).coerceAtLeast(0.dp)).size(width = 2.dp, height = 14.dp).background(Color(0xCCFFFFFF))) }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (focused) {
                Box(Modifier.size(8.dp).background(AppColors.Accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (paused) "Paused · OK to play · ◀ ▶ to skip" else "OK to pause · ◀ ▶ to skip, hold to skip faster",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("${clock(position)} / ${clock(duration)}" + (live?.let { l -> if (l > position + 60_000) "  ·  ${(l - position) / 60_000} min behind live" else "" } ?: ""), fontSize = 13.sp, color = Color.White)
        }
    }
}

@Composable
private fun OptionPopup(
    wide: Boolean = false,
    title: String,
    options: List<Pair<String, Boolean>>,
    emptyText: String,
    onPick: (Int) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(options.size) {
        awaitFrame()
        runCatching { focus.requestFocus() }
    }
    Column(
        Modifier
            .width(if (wide) 560.dp else 300.dp)
            .background(AppColors.Panel, RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
        if (options.isEmpty()) {
            Text(emptyText, fontSize = 13.sp, color = Color.White, modifier = Modifier.padding(12.dp).focusRequester(focus).focusable())
        }
        val selectedIndex = options.indexOfFirst { it.second }.coerceAtLeast(0)
        options.forEachIndexed { i, (label, selected) ->
            MenuItem(
                text = label,
                selected = selected,
                onClick = { onPick(i) },
                trailing = if (selected) "✓" else null,
                modifier = if (i == selectedIndex) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
}

/** Dispatcharr's live view of this channel's stream (refreshes every 2 s while open). */
@Composable
private fun StreamInfoPopup(vm: PlaybackViewModel) {
    var lines by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            lines = vm.streamInfo()
            delay(2_000)
        }
    }
    Column(
        Modifier.width(460.dp).background(AppColors.Panel, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("Stream info (from Dispatcharr)", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
        val l = lines
        if (l == null) Text("Loading…", fontSize = 13.sp, color = Color.White)
        else l.forEach { (k, v) ->
            Row {
                Text(k, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.width(110.dp))
                Text(v, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
