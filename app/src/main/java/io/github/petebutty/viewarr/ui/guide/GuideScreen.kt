package io.github.petebutty.viewarr.ui.guide

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import io.github.petebutty.viewarr.data.settings.ChannelKeys
import io.github.petebutty.viewarr.data.settings.GuideLeft
import io.github.petebutty.viewarr.data.settings.LocalRemote
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.repository.SyncState
import io.github.petebutty.viewarr.ui.theme.AppColors
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ChannelColumn = 230.dp
private val RowHeight = 38.dp
private const val PAGE = 7

private val CellText = TextStyle(color = Color.White, fontSize = 13.sp)
private val CellTextFocused = CellText.copy(fontWeight = FontWeight.SemiBold)

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("EEE, d MMM, HH:mm")
private fun Long.local() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

@Composable
fun GuideScreen(
    vm: GuideViewModel,
    recordings: List<io.github.petebutty.viewarr.data.dvr.DvrRecording> = emptyList(),
    /** What's playing: live on this channel, or this catch-up programme. Drawn with an accent outline. */
    playingChannelId: Int? = null,
    playingCatchupRowId: Long? = null,
    serverUrl: String,
    focusRequester: FocusRequester,
    focusEnabled: Boolean,
    onOpenMenu: () -> Unit,
    onSelect: (ChannelListItem, GuideProgram?) -> Unit,
    onLongSelect: (ChannelListItem, GuideProgram?) -> Unit = { _, _ -> },
) {
    val okPending = remember { mutableStateOf(false) }
    val remote = LocalRemote.current
    val channels by vm.channels.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    val cursor by vm.cursor.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val leftPending = remember { mutableStateOf(false) }

    val selectedChannel = channels.getOrNull(cursor.row)
    val focused = selectedChannel?.let { GuideViewModel.programAt(programs[it.id].orEmpty(), cursor.focusTime) }

    LaunchedEffect(cursor.row, channels.size) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val shown = maxOf(visible.size, 1)
        when {
            cursor.row < first + 1 -> listState.scrollToItem(maxOf(0, cursor.row - 1))
            cursor.row > first + shown - 2 -> listState.scrollToItem(maxOf(0, cursor.row - shown + 2))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, top = 20.dp)
            .focusRequester(focusRequester)
            .focusable(enabled = focusEnabled)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionLeft && remote.guideLeft == GuideLeft.NowMenu) {
                    // Tap: back one programme, or the menu when already on what's on now. Hold: back into catch-up.
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> leftPending.value = true
                        e.type == KeyEventType.KeyDown -> {
                            leftPending.value = false
                            vm.moveLeft()
                        }
                        e.type == KeyEventType.KeyUp && leftPending.value -> {
                            leftPending.value = false
                            if (vm.isAtNow()) onOpenMenu() else vm.moveLeft()
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.key == Key.DirectionLeft && remote.guideLeft == GuideLeft.AlwaysPast) {
                    if (e.type == KeyEventType.KeyDown) vm.moveLeft()
                    return@onPreviewKeyEvent true
                }
                if (e.key == Key.DirectionLeft && remote.guideLeft == GuideLeft.MenuTapPastHold) {
                    // Tap opens the menu; holding Left browses back through the guide.
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> leftPending.value = true
                        e.type == KeyEventType.KeyDown -> {
                            leftPending.value = false
                            vm.moveLeft()
                        }
                        e.type == KeyEventType.KeyUp && leftPending.value -> {
                            leftPending.value = false
                            onOpenMenu()
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.key == Key.DirectionLeft) {
                    // Short press browses back through the guide; holding Left opens the categories menu.
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> leftPending.value = true
                        e.type == KeyEventType.KeyDown && leftPending.value -> {
                            leftPending.value = false
                            onOpenMenu()
                        }
                        e.type == KeyEventType.KeyUp && leftPending.value -> {
                            leftPending.value = false
                            vm.moveLeft()
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                // OK: a tap selects (play / info); holding it opens the record options.
                if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
                    when {
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> okPending.value = true
                        e.type == KeyEventType.KeyDown && okPending.value -> {
                            okPending.value = false
                            selectedChannel?.let { onLongSelect(it, focused) }
                        }
                        e.type == KeyEventType.KeyUp && okPending.value -> {
                            okPending.value = false
                            selectedChannel?.let { onSelect(it, focused) }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> vm.moveVertical(-1)
                    Key.DirectionDown -> vm.moveVertical(1)
                    Key.ChannelUp, Key.PageUp -> vm.moveVertical(if (remote.channelKeys == ChannelKeys.Reversed) PAGE else -PAGE)
                    Key.ChannelDown, Key.PageDown -> vm.moveVertical(if (remote.channelKeys == ChannelKeys.Reversed) -PAGE else PAGE)
                    Key.DirectionRight, Key.MediaFastForward -> vm.moveRight()
                    Key.MediaRewind -> vm.moveLeft()
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        InfoPanel(selectedChannel, focused, now, serverUrl)
        Spacer(Modifier.height(10.dp))

        if (channels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when (val s = sync) {
                        is SyncState.Running -> s.message
                        is SyncState.Failed -> s.message
                        SyncState.Idle -> "No channels yet."
                    },
                    color = AppColors.TextSecondary,
                    fontSize = 18.sp,
                )
            }
            return@Column
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gridWidth = maxWidth - ChannelColumn
            // Scheduled / in-progress Dispatcharr recordings, per channel: drawn as a red dot on the programme.
            val recordedByChannel = remember(recordings) {
                recordings.filter { it.state == io.github.petebutty.viewarr.data.dvr.DvrState.Scheduled || it.state == io.github.petebutty.viewarr.data.dvr.DvrState.Recording }
                    .groupBy({ it.channelId }, { it.start..it.end })
            }

            Column(Modifier.fillMaxSize()) {
                TimeHeader(now, cursor.windowStart, sync, gridWidth)
                LazyColumn(state = listState, userScrollEnabled = false, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                        GuideRow(
                            channel = channel,
                            recorded = recordedByChannel[channel.id].orEmpty(),
                            playingLive = channel.id == playingChannelId && playingCatchupRowId == null,
                            playingRowId = if (channel.id == playingChannelId) playingCatchupRowId else null,
                            programs = programs[channel.id].orEmpty(),
                            selected = index == cursor.row,
                            focusedRowId = if (index == cursor.row) focused?.rowId else null,
                            windowStart = cursor.windowStart,
                            now = now,
                            serverUrl = serverUrl,
                            textMeasurer = textMeasurer,
                        )
                    }
                }
            }
            if (now >= cursor.windowStart && now < cursor.windowStart + GuideViewModel.VISIBLE_MS) {
                Box(
                    Modifier
                        .offset(x = ChannelColumn + xOf(now, cursor.windowStart, gridWidth))
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(AppColors.Accent)
                )
            }
        }
    }
}

@Composable
private fun InfoPanel(channel: ChannelListItem?, program: GuideProgram?, now: Long, serverUrl: String) {
    Row(Modifier.fillMaxWidth().height(180.dp)) {
        Box(
            Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(AppColors.PreviewBox, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (channel != null && (channel.logoId != null || channel.logoUrl != null)) {
                AsyncImage(
                    model = channelLogo(serverUrl, channel),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 180.dp, height = 100.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 24.dp, top = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    program?.let(::displayTitle) ?: channel?.name.orEmpty(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                channel?.groupName?.let {
                    Text(it, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 16.dp))
                }
            }
            if (program != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${program.startTime.local().format(timeFormat)} – ${program.endTime.local().format(timeFormat)}",
                        fontSize = 14.sp,
                        color = AppColors.TextPrimary,
                    )
                    if (program.startTime <= now && program.endTime > now) {
                        val fraction = (now - program.startTime).toFloat() / (program.endTime - program.startTime)
                        Spacer(Modifier.width(14.dp))
                        Box(Modifier.width(110.dp).height(4.dp).background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.TextPrimary, RoundedCornerShape(2.dp)))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("${(program.endTime - now) / 60_000} min left", fontSize = 14.sp, color = AppColors.TextSecondary)
                    }
                    Badges(program)
                }
                Spacer(Modifier.height(10.dp))
                val description = listOfNotNull(program.subTitle, program.description).joinToString(" — ")
                Text(
                    description,
                    fontSize = 14.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (channel != null) {
                Spacer(Modifier.height(6.dp))
                Text("No guide information", fontSize = 14.sp, color = AppColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun Badges(program: GuideProgram) {
    val labels = listOfNotNull(
        "LIVE".takeIf { program.isLive },
        "NEW".takeIf { program.isNew },
        "PREMIERE".takeIf { program.isPremiere },
    )
    labels.forEach {
        Spacer(Modifier.width(10.dp))
        Text(
            it,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.OnAccent,
            modifier = Modifier.background(AppColors.Accent, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun TimeHeader(now: Long, windowStart: Long, sync: SyncState, gridWidth: Dp) {
    Box(Modifier.fillMaxWidth().height(28.dp)) {
        Text(
            now.local().format(dateFormat),
            fontSize = 13.sp,
            color = AppColors.TextPrimary,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Box(Modifier.padding(start = ChannelColumn).fillMaxSize().clipToBounds()) {
            var t = windowStart
            while (t < windowStart + GuideViewModel.VISIBLE_MS) {
                Text(
                    t.local().format(timeFormat),
                    fontSize = 13.sp,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = xOf(t, windowStart, gridWidth) + 4.dp),
                )
                t += GuideViewModel.STEP_MS
            }
        }
        if (sync is SyncState.Running) {
            Text(
                sync.message,
                fontSize = 12.sp,
                color = AppColors.Accent,
                modifier = Modifier.align(Alignment.CenterEnd).background(AppColors.Background).padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun GuideRow(
    channel: ChannelListItem,
    recorded: List<LongRange>,
    playingLive: Boolean = false,
    playingRowId: Long? = null,
    programs: List<GuideProgram>,
    selected: Boolean,
    focusedRowId: Long?,
    windowStart: Long,
    now: Long,
    serverUrl: String,
    textMeasurer: TextMeasurer,
) {
    val windowEnd = windowStart + GuideViewModel.VISIBLE_MS
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 1.dp)) {
        Row(
            Modifier
                .width(ChannelColumn)
                .fillMaxHeight()
                .padding(end = 4.dp)
                .background(if (selected) AppColors.RowSelected else AppColors.CellFuture.copy(alpha = 0.55f), RoundedCornerShape(if (AppColors.palette.squareCorners) 0.dp else 6.dp))
                .padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            if (io.github.petebutty.viewarr.ui.theme.AppColors.palette.showNumbers) Text(
                channel.number?.let(::formatNumber).orEmpty(),
                fontSize = 13.sp,
                color = if (selected) AppColors.TextPrimary else AppColors.TextSecondary,
                modifier = Modifier.width(44.dp).padding(start = 6.dp),
                maxLines = 1,
            )
            Box(Modifier.width(52.dp).fillMaxHeight().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                if (channel.logoId != null || channel.logoUrl != null) {
                    AsyncImage(
                        model = channelLogo(serverUrl, channel),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(
                channel.name,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            if (channel.catchupDays > 0) CatchupIcon(Modifier.padding(start = 4.dp).size(14.dp))
        }
        val catchupFrom = if (channel.catchupDays > 0) now - channel.catchupDays * 86_400_000L else null
        val onAccent = AppColors.OnAccent
        val accent = AppColors.Accent
        val iconColor = AppColors.TextSecondary
        val square = AppColors.palette.squareCorners
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val pxPerMs = size.width / GuideViewModel.VISIBLE_MS
            val gap = 2.dp.toPx()
            val pad = 8.dp.toPx()
            val radius = if (square) CornerRadius.Zero else CornerRadius(6.dp.toPx())
            val outline = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
            val ordered = if (focusedRowId == null) programs else programs.sortedBy { it.rowId == focusedRowId }
            for (p in ordered) {
                if (p.endTime <= windowStart || p.startTime >= windowEnd) continue
                val x0 = (maxOf(p.startTime, windowStart) - windowStart) * pxPerMs
                val x1 = (minOf(p.endTime, windowEnd) - windowStart) * pxPerMs - gap
                if (x1 - x0 < 1f) continue
                val isFocused = p.rowId == focusedRowId
                val color = when {
                    isFocused -> AppColors.Accent
                    p.endTime <= now -> AppColors.CellPast
                    p.startTime <= now && p.endTime > now -> AppColors.CellCurrent
                    else -> AppColors.CellFuture
                }
                drawRoundRect(color, topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height), cornerRadius = radius)
                if (!isFocused) drawRoundRect(Color.White.copy(alpha = 0.08f), topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height), cornerRadius = radius, style = outline)
                // On now: a bar along the bottom showing how far through it is.
                if (p.startTime <= now && p.endTime > now && x1 - x0 > 16.dp.toPx()) {
                    val inset = 6.dp.toPx()
                    val h = 3.dp.toPx()
                    val y = size.height - h - 4.dp.toPx()
                    val full = p.endTime - p.startTime
                    val done = (now - p.startTime).toFloat() / full
                    // The bar spans the whole programme, even where the cell runs off the left edge.
                    val px0 = (p.startTime - windowStart) * pxPerMs + inset
                    val px1 = (p.endTime - windowStart) * pxPerMs - gap - inset
                    val l = maxOf(px0, x0 + inset)
                    val r = minOf(px1, x1 - inset)
                    val fillEnd = (px0 + (px1 - px0) * done).coerceIn(l, r)
                    val bar = CornerRadius(h / 2)
                    if (r > l) {
                        drawRoundRect((if (isFocused) onAccent else Color.White).copy(alpha = 0.18f), topLeft = Offset(l, y), size = Size(r - l, h), cornerRadius = bar)
                        if (fillEnd > l) drawRoundRect(if (isFocused) onAccent else accent, topLeft = Offset(l, y), size = Size(fillEnd - l, h), cornerRadius = bar)
                    }
                }
                // The programme on screen right now keeps an accent outline, so you always know what's playing.
                val isPlaying = p.rowId == playingRowId || (playingLive && p.startTime <= now && p.endTime > now)
                if (isPlaying && !isFocused) {
                    val w = 2.5.dp.toPx()
                    drawRoundRect(accent, topLeft = Offset(x0 + w / 2, w / 2), size = Size(x1 - x0 - w, size.height - w), cornerRadius = radius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                }
                val replayable = catchupFrom != null && p.startTime < now && p.startTime >= catchupFrom
                val iconSide = 12.dp.toPx()
                var textX = x0 + pad
                if (replayable && x1 - x0 > iconSide + 2 * pad) {
                    drawCatchupIcon(Offset(textX, (size.height - iconSide) / 2f), iconSide, if (isFocused) onAccent else iconColor)
                    textX += iconSide + 5.dp.toPx()
                }
                val textWidth = (x1 - textX - pad).toInt()
                if (textWidth > 12) {
                    val layout = textMeasurer.measure(
                        text = displayTitle(p),
                        style = if (isFocused) CellTextFocused.copy(color = AppColors.OnAccent) else CellText,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = textWidth),
                    )
                    drawText(layout, topLeft = Offset(textX, (size.height - layout.size.height) / 2f))
                }
                val isRecorded = recorded.any { r -> minOf(r.last, p.endTime) - maxOf(r.first, p.startTime) > (p.endTime - p.startTime) / 2 }
                if (isRecorded && x1 - x0 > 20.dp.toPx()) {
                    val dot = 4.dp.toPx()
                    drawCircle(RecordRed, dot, Offset(x1 - pad - dot, size.height / 2f))
                }
            }
        }
    }
}

private val RecordRed = Color(0xFFE53935)

/** Rewind-style arrow marking channels and programmes with catch-up. */
@Composable
private fun CatchupIcon(modifier: Modifier) {
    val color = AppColors.TextSecondary
    Canvas(modifier) { drawCatchupIcon(Offset.Zero, size.minDimension, color) }
}

private fun DrawScope.drawCatchupIcon(topLeft: Offset, side: Float, color: Color) {
    val stroke = side * 0.12f
    val x = topLeft.x
    val y = topLeft.y
    drawArc(
        color = color,
        startAngle = -60f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = Offset(x + stroke, y + stroke),
        size = Size(side - 2 * stroke, side - 2 * stroke),
        style = Stroke(width = stroke),
    )
    drawPath(
        Path().apply {
            moveTo(x + side * 0.46f, y + side * 0.1f)
            lineTo(x + side * 0.83f, y + side * 0.02f)
            lineTo(x + side * 0.73f, y + side * 0.4f)
            close()
        },
        color,
    )
    drawLine(color, Offset(x + side / 2, y + side * 0.3f), Offset(x + side / 2, y + side / 2), stroke)
    drawLine(color, Offset(x + side / 2, y + side / 2), Offset(x + side * 0.66f, y + side * 0.62f), stroke)
}

private fun xOf(t: Long, windowStart: Long, gridWidth: Dp): Dp =
    gridWidth * ((t - windowStart).toFloat() / GuideViewModel.VISIBLE_MS)

fun displayTitle(p: GuideProgram): String {
    val se = when {
        p.season != null && p.episode != null -> ". S${p.season}E${p.episode}"
        p.episode != null -> ". E${p.episode}"
        else -> ""
    }
    val sep = if (se.isNotEmpty() && p.title.lastOrNull()?.let { it in "?!." } == true) " " else ""
    return p.title + if (sep.isEmpty()) se else sep + se.removePrefix(". ")
}

fun logoUrl(serverUrl: String, logoId: Int) = "${serverUrl.trimEnd('/')}/api/channels/logos/$logoId/cache/"

/** A channel's logo: the provider's own address (IPTV provider) or Dispatcharr's cached logo. */
fun channelLogo(serverUrl: String, channel: io.github.petebutty.viewarr.data.local.ChannelListItem): String? =
    channel.logoUrl ?: channel.logoId?.let { logoUrl(serverUrl, it) }

fun formatNumber(n: Double): String = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
