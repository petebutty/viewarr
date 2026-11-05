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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class OsdPopup { Audio, Subtitles, Aspect, Source, Info }

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
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
                    OsdPopup.Source -> sources.orEmpty().map { (if (it.provider.isNotEmpty()) "${it.name}  ·  ${it.provider}" else it.name) to it.current }
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
                    if (catchup != null) Badge(if (paused) "CATCH-UP · PAUSED" else "CATCH-UP")
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
                duration = position.second.takeIf { it > 0 } ?: (catchup!!.endTime - catchup!!.startTime),
                paused = paused,
                onSeek = vm::seekBy,
                onToggle = vm::togglePause,
                modifier = Modifier.focusRequester(firstFocus),
            )
        } else if (current != null) {
            LiveProgress(current, now)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (catchup != null) {
                Pill("Restart") { vm.restartProgramme() }
                Pill("Go live") { vm.goLive() }
            }
            Pill("TV guide", if (catchup == null) Modifier.focusRequester(firstFocus) else Modifier, onClick = onGuide)
            Pill("Audio") { popup = OsdPopup.Audio }
            Pill("Subtitles") { popup = OsdPopup.Subtitles }
            Pill("Aspect: ${aspect.label}") { popup = OsdPopup.Aspect }
            if (vm.dispatcharrLive) {
                Pill("Source") { vm.loadSources(); popup = OsdPopup.Source }
                Pill("Stream info") { popup = if (popup == OsdPopup.Info) null else OsdPopup.Info }
            }
            if (onRecord != null) Pill("Record") { onRecord() }
            (note ?: sourceNote?.takeIf { popup == null && it.startsWith("Switched") })?.let { Text(it, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 8.dp)) }
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

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, dim: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(30.dp),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(15.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x55000000),
            contentColor = if (dim) AppColors.TextSecondary else Color.White,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
            pressedContainerColor = AppColors.Accent,
            pressedContentColor = AppColors.OnAccent,
        ),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun LiveProgress(program: GuideProgram, now: Long) {
    val fraction = ((now - program.startTime).toFloat() / (program.endTime - program.startTime)).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(4.dp).background(Color(0x44FFFFFF), RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.Accent, RoundedCornerShape(2.dp)))
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
        Box(Modifier.fillMaxWidth().height(if (focused) 6.dp else 4.dp).background(Color(0x44FFFFFF), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.Accent, RoundedCornerShape(3.dp)))
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
            Text("${clock(position)} / ${clock(duration)}", fontSize = 13.sp, color = Color.White)
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
