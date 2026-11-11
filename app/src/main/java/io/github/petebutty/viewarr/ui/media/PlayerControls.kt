package io.github.petebutty.viewarr.ui.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.playback.SeekThumbs
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Seek-bar scrubbing shared by both players. Down puts you on the seek bar; Left / Right move a marker (faster the
 * longer you hold) while the film keeps playing; OK jumps there; Up or Back leaves it where it was.
 */
class Scrubber {
    /** Where the marker is, while on the seek bar; null otherwise. */
    var targetMs by mutableStateOf<Long?>(null)
        private set
    val active get() = targetMs != null

    fun begin(positionMs: Long) { targetMs = positionMs.coerceAtLeast(0) }
    fun cancel() { targetMs = null }

    /** One press or repeat of Left (-1) / Right (+1). Steps grow while the button is held. */
    fun step(direction: Int, repeatCount: Int, durationMs: Long) {
        val t = targetMs ?: return
        val step = when {
            repeatCount < 4 -> 10_000L
            repeatCount < 12 -> 30_000L
            repeatCount < 25 -> 60_000L
            else -> maxOf(120_000L, durationMs / 50)
        }
        val max = if (durationMs > 0) durationMs - 1_000 else Long.MAX_VALUE
        targetMs = (t + direction * step).coerceIn(0, max.coerceAtLeast(0))
    }

    /** OK: where to jump to; leaves the seek bar. */
    fun commit(): Long? = targetMs.also { targetMs = null }
}

/** The buttons under the seek bar. */
enum class PlayerButton { PlayPause, Stop, Details, Audio, Subtitles, Info, SwitchPlayer, Settings }

/** A control under the seek bar: an icon, with its name shown under it when focused. */
class OsdButton(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val groupEnd: Boolean = false)

fun PlayerButton.osd(paused: Boolean, infoOn: Boolean, otherPlayer: String) = OsdButton(
    when (this) {
        PlayerButton.PlayPause -> if (paused) io.github.petebutty.viewarr.ui.common.AppIcons.Play else io.github.petebutty.viewarr.ui.common.AppIcons.Pause
        PlayerButton.Stop -> io.github.petebutty.viewarr.ui.common.AppIcons.Stop
        PlayerButton.Details -> io.github.petebutty.viewarr.ui.common.AppIcons.Info
        PlayerButton.Audio -> io.github.petebutty.viewarr.ui.common.AppIcons.Audio
        PlayerButton.Subtitles -> io.github.petebutty.viewarr.ui.common.AppIcons.Subtitles
        PlayerButton.Info -> io.github.petebutty.viewarr.ui.common.AppIcons.Stats
        PlayerButton.Settings -> io.github.petebutty.viewarr.ui.common.AppIcons.Settings
        PlayerButton.SwitchPlayer -> io.github.petebutty.viewarr.ui.common.AppIcons.Swap
    },
    label(paused, infoOn, otherPlayer),
    // Play/pause and Stop are one group; the rest after a gap.
    groupEnd = this == PlayerButton.Stop,
)

/**
 * The player's controls, opened with OK: the seek bar first (Left / Right move the marker, OK jumps there), the
 * buttons below it (Down). Play/Pause on the remote pauses; OK no longer does. Closes by itself after a while
 * (not while paused), or with Back.
 */
class PlayerMenu {
    var open by mutableStateOf(false)
    var onBar by mutableStateOf(true)
    var button by androidx.compose.runtime.mutableIntStateOf(0)
    var infoOn by mutableStateOf(false)
    /** Down from the buttons: the live TV shortcuts, [shortcut] highlighted. */
    var onShortcuts by mutableStateOf(false)
    var shortcut by androidx.compose.runtime.mutableIntStateOf(0)
    /** Down from the shortcuts: the highlighted one's rows, [row] / [card] highlighted. */
    var onRows by mutableStateOf(false)
    var row by androidx.compose.runtime.mutableIntStateOf(0)
    var card by androidx.compose.runtime.mutableIntStateOf(0)
    var lastKey by androidx.compose.runtime.mutableLongStateOf(0L)
    /** Opens on the seek bar, with Play / Pause highlighted below it (not the button used last time). */
    fun show() { open = true; onBar = true; button = 0; onShortcuts = false; onRows = false; touch() }
    fun touch() { lastKey = System.currentTimeMillis() }

    /**
     * Keys while the controls are open and not scrubbing (the players handle scrubbing themselves). True when used.
     * [beginScrub]: start the marker at the current position and move it one step ([dir], [repeat] for speed).
     */
    fun handle(
        e: androidx.compose.ui.input.key.KeyEvent, buttons: List<PlayerButton>, beginScrub: (dir: Int, repeat: Int) -> Unit, press: (PlayerButton) -> Unit, upFromBar: () -> Unit = {},
        shortcutCount: Int = 0, pickShortcut: (Int) -> Unit = {},
        /** The number of cards in each of a shortcut's rows, and opening one (row, card). */
        rowSizes: (Int) -> List<Int> = { emptyList() }, pickCard: (Int, Int) -> Unit = { _, _ -> },
    ): Boolean {
        val down = e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
        val a = io.github.petebutty.viewarr.ui.common.RemoteKeys.action(e)
        val repeat = e.nativeKeyEvent.repeatCount
        if (down) touch()
        // The shortcuts (as on live TV): Left / Right move along, OK opens one (the film goes to the corner), Up back.
        // The rows under a shortcut: Left / Right along a row, Down / Up between rows (Up from the first: shortcuts).
        if (onRows) {
            val sizes = rowSizes(shortcut)
            when (a) {
                io.github.petebutty.viewarr.ui.common.RemoteAction.Left -> if (down) card = (card - 1).coerceAtLeast(0)
                io.github.petebutty.viewarr.ui.common.RemoteAction.Right -> if (down) card = (card + 1).coerceAtMost(((sizes.getOrNull(row) ?: 1) - 1).coerceAtLeast(0))
                io.github.petebutty.viewarr.ui.common.RemoteAction.Up -> if (down) { if (row == 0) onRows = false else { row--; card = 0 } }
                io.github.petebutty.viewarr.ui.common.RemoteAction.Down -> if (down && row < sizes.lastIndex) { row++; card = 0 }
                io.github.petebutty.viewarr.ui.common.RemoteAction.Ok -> if (down && repeat == 0) pickCard(row, card)
                else -> return false
            }
            return true
        }
        if (onShortcuts) {
            when (a) {
                io.github.petebutty.viewarr.ui.common.RemoteAction.Left -> if (down) shortcut = (shortcut - 1).coerceAtLeast(0)
                io.github.petebutty.viewarr.ui.common.RemoteAction.Right -> if (down) shortcut = (shortcut + 1).coerceAtMost((shortcutCount - 1).coerceAtLeast(0))
                io.github.petebutty.viewarr.ui.common.RemoteAction.Up -> if (down) onShortcuts = false
                io.github.petebutty.viewarr.ui.common.RemoteAction.Ok -> if (down && repeat == 0) pickShortcut(shortcut)
                io.github.petebutty.viewarr.ui.common.RemoteAction.Down -> if (down && rowSizes(shortcut).isNotEmpty()) { onRows = true; row = 0; card = 0 }
                else -> return false
            }
            return true
        }
        when {
            onBar && (a == io.github.petebutty.viewarr.ui.common.RemoteAction.Left || a == io.github.petebutty.viewarr.ui.common.RemoteAction.Right) -> {
                if (down) beginScrub(if (a == io.github.petebutty.viewarr.ui.common.RemoteAction.Left) -1 else 1, repeat)
            }
            onBar && a == io.github.petebutty.viewarr.ui.common.RemoteAction.Down -> if (down && buttons.isNotEmpty()) onBar = false
            // Up from the seek bar: pause, with what's playing (its synopsis) on screen.
            onBar && a == io.github.petebutty.viewarr.ui.common.RemoteAction.Up -> if (down && repeat == 0) upFromBar()
            onBar && a == io.github.petebutty.viewarr.ui.common.RemoteAction.Ok -> Unit
            !onBar && a == io.github.petebutty.viewarr.ui.common.RemoteAction.Left -> if (down) button = (button - 1).coerceAtLeast(0)
            !onBar && a == io.github.petebutty.viewarr.ui.common.RemoteAction.Right -> if (down) button = (button + 1).coerceAtMost(buttons.lastIndex)
            !onBar && a == io.github.petebutty.viewarr.ui.common.RemoteAction.Up -> if (down) onBar = true
            !onBar && a == io.github.petebutty.viewarr.ui.common.RemoteAction.Down -> if (down && shortcutCount > 0) { onShortcuts = true; shortcut = shortcut.coerceAtMost(shortcutCount - 1) }
            !onBar && a == io.github.petebutty.viewarr.ui.common.RemoteAction.Ok -> if (down && repeat == 0) buttons.getOrNull(button)?.let(press)
            else -> return false
        }
        return true
    }
}

/** A button's label (Play / Pause follow the state; Media info shows whether it's on). */
fun PlayerButton.label(paused: Boolean, infoOn: Boolean, otherPlayer: String) = when (this) {
    PlayerButton.PlayPause -> if (paused) "Play" else "Pause"
    PlayerButton.Details -> "Details"
    PlayerButton.Audio -> "Audio"
    PlayerButton.Subtitles -> "Subtitles"
    PlayerButton.Info -> if (infoOn) "Hide media info" else "Media info"
    PlayerButton.SwitchPlayer -> "Switch to $otherPlayer"
    PlayerButton.Stop -> "Stop"
    PlayerButton.Settings -> "Settings"
}

/** The controls over a playing film or episode: title, seek bar (with preview while scrubbing) and times. */
@Composable
fun PlayerOsd(
    title: String,
    positionMs: Long,
    durationMs: Long,
    paused: Boolean,
    buffering: Boolean,
    scrubMs: Long?,
    thumbs: SeekThumbs?,
    modifier: Modifier = Modifier,
    /** The title's logo: shown above the episode line instead of the title as text. */
    logo: String? = null,
    /** The controls (opened with OK): button labels, which is highlighted, and whether the seek bar has focus. */
    buttons: List<OsdButton>? = null,
    buttonFocus: Int = -1,
    barFocused: Boolean = false,
    /** The live TV shortcuts under the buttons (Down from them), [shortcutFocus] highlighted. */
    shortcuts: List<io.github.petebutty.viewarr.ui.player.LiveShortcut>? = null,
    shortcutFocus: Int = -1,
    /** Down from the shortcuts: its rows, the [rowFocus] one shown with [cardFocus] highlighted. */
    rows: List<io.github.petebutty.viewarr.ui.player.OsdRow>? = null,
    rowFocus: Int = 0,
    cardFocus: Int = 0,
    /** Whether the shortcuts are there (Live TV hub), for the hint line. */
    shortcutsHint: Boolean = false,
) {
    val scrubbing = scrubMs != null
    // Settings → Playback → Player controls size.
    val k = AppColors.palette.controlsScale
    fun d(v: Int) = (v * k).dp
    fun t(v: Int) = (v * (0.5f + k / 2)).sp
    // The preview: fetched once the marker rests for a moment, the last one stays up meanwhile.
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(scrubMs?.div(5_000), thumbs) {
        if (scrubMs == null || thumbs == null) { if (scrubMs == null) thumb = null; return@LaunchedEffect }
        delay(120)
        val b = thumbs.at(scrubMs)
        android.util.Log.i("SeekThumbs", "preview for ${scrubMs / 1000}s: ${if (b == null) "none" else "${b.width}x${b.height}"}")
        if (b != null) thumb = b
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.9f)))
            .padding(start = 56.dp, end = 56.dp, top = if (scrubbing && thumbs != null) 24.dp else d(80), bottom = d(36)),
    ) {
        // With a logo: the logo, then just the episode ("S1 E1"), if any, beside the play state.
        var logoShown by remember(logo) { mutableStateOf(false) }
        if (logo != null) coil.compose.AsyncImage(
            // Loaded at its own size: the space stays 0 high until it arrives, and the loader won't fetch into 0.
            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(logo).size(coil.size.Size.ORIGINAL).build(),
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onSuccess = { logoShown = true },
            modifier = Modifier.padding(start = d(40), bottom = d(10)).height(if (logoShown) d(48) else 0.dp).width(d(280)),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (buffering) "◌" else if (paused) "❚❚" else "▶", fontSize = t(22), color = Color.White, modifier = Modifier.width(d(40)))
            Text(if (logoShown) title.substringAfter(" · ", "") else title, fontSize = t(22), fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (buffering) "Loading…" else "", fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(d(14)))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val barWidth = maxWidth
            val played = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val marker = if (durationMs > 0 && scrubMs != null) (scrubMs.toFloat() / durationMs).coerceIn(0f, 1f) else played
            Column {
                if (scrubbing && thumbs != null) {
                    // Preview above the marker, kept on screen at the ends.
                    val w = 128.dp
                    val x = (barWidth * marker - w / 2).coerceIn(0.dp, (barWidth - w).coerceAtLeast(0.dp))
                    Column(Modifier.offset(x = x).width(w), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.width(w).height(72.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black)
                                .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val b = thumb
                            if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().fillMaxHeight())
                            else Text("…", fontSize = 22.sp, color = Color.White.copy(alpha = 0.6f))
                        }
                        Text(clockMs(scrubMs ?: 0), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                    }
                } else if (scrubbing) {
                    val w = 120.dp
                    val x = (barWidth * marker - w / 2).coerceIn(0.dp, (barWidth - w).coerceAtLeast(0.dp))
                    Text(clockMs(scrubMs ?: 0), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White,
                        modifier = Modifier.offset(x = x).width(w).padding(bottom = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                val barHeight = if (scrubbing) 10.dp else 6.dp
                Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(played).background(if (scrubbing) Color.White.copy(alpha = 0.55f) else AppColors.Accent))
                    }
                    if (scrubbing) Box(
                        Modifier.offset(x = (barWidth * marker - 9.dp).coerceAtLeast(0.dp)).size(18.dp).clip(CircleShape).background(AppColors.Accent)
                            .border(2.dp, Color.White, CircleShape),
                    )
                }
            }
        }
        Spacer(Modifier.height(d(8)))
        Row {
            Text(clockMs(positionMs), fontSize = t(14), color = Color.White)
            Spacer(Modifier.weight(1f))
            // How to get to picture-in-picture, as Settings → Remote → Picture-in-picture has it (none for "Stop").
            val pip = "" // Picture-in-picture for films is off for now.
            Text(
                when {
                    scrubbing -> "◀ ▶ move  ·  OK: jump here  ·  Back: cancel"
                    buttons != null && barFocused -> "◀ ▶ seek  ·  ▼ options  ·  ▲ pause + details$pip"
                    rows != null -> "◀ ▶ move  ·  ▲ ▼ rows  ·  OK: open (the film carries on in a corner)"
                    shortcuts != null -> "◀ ▶ shortcuts  ·  ▼ rows  ·  OK: open (the film carries on in a corner)  ·  ▲ buttons"
                    buttons != null -> "▲ seek bar  ·  " + (if (shortcutsHint) "▼ shortcuts  ·  " else "") + "⏯ play / pause$pip"
                    else -> "OK: controls  ·  ◀ ▶ skip  ·  ⏯ play / pause$pip"
                },
                fontSize = t(13), color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            Text(if (durationMs > 0) "-" + clockMs(durationMs - positionMs) else "", fontSize = t(14), color = Color.White)
        }
        if (buttons != null) Row(Modifier.padding(top = d(14)), verticalAlignment = Alignment.Top) {
            buttons.forEachIndexed { i, b ->
                val on = i == buttonFocus
                // Icons only; the focused one's name underneath (so each is still easy to learn).
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(d(72))) {
                    Box(
                        Modifier.size(d(48)).background(if (on) AppColors.Accent else Color.White.copy(alpha = 0.14f), androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(b.icon, contentDescription = b.label, tint = if (on) AppColors.OnAccent else Color.White, modifier = Modifier.size(d(26)))
                    }
                    Text(
                        if (on) b.label else "", fontSize = t(12), color = Color.White, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(top = d(4)).then(Modifier.wrapContentWidth(unbounded = true)),
                    )
                }
                if (b.groupEnd) Box(Modifier.padding(horizontal = d(10), vertical = d(10)).width(1.dp).height(d(28)).background(Color.White.copy(alpha = 0.3f)))
            }
        }
        // Down from the buttons: your live TV shortcuts (Settings → Remote & navigation → Live TV shortcuts), the
        // same row as live TV's. OK opens one; the film carries on in a corner.
        if (shortcuts != null) {
            val size = floatArrayOf(1f, 1.25f, 1.5f)[AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.SHORTCUT_SIZE).coerceIn(0, 2)]
            val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
            androidx.compose.runtime.LaunchedEffect(shortcutFocus) { if (shortcutFocus >= 0) runCatching { rowState.animateScrollToItem((shortcutFocus - 2).coerceAtLeast(0)) } }
            androidx.compose.foundation.lazy.LazyRow(state = rowState, modifier = Modifier.padding(top = d(12)), horizontalArrangement = Arrangement.spacedBy(d(10)), userScrollEnabled = false) {
                items(shortcuts.size) { i ->
                    val sc = shortcuts[i]
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(io.github.petebutty.viewarr.ui.player.shortcutBox(d((64 * size).toInt())).first + d((24 * size).toInt()))) {
                        io.github.petebutty.viewarr.ui.player.ShortcutDisc(sc, highlighted = i == shortcutFocus, size = d((64 * size).toInt()))
                        Text(
                            if (i == shortcutFocus) sc.label else "", fontSize = t(12), color = Color.White, maxLines = 1, softWrap = false,
                            modifier = Modifier.padding(top = d(4)).then(Modifier.wrapContentWidth(unbounded = true)),
                        )
                    }
                }
            }
        }
        // The rows under the highlighted shortcut, one at a time (as on live TV).
        val shownRow = rows?.getOrNull(rowFocus)
        if (shownRow != null) {
            Row(Modifier.padding(top = d(10)), verticalAlignment = Alignment.CenterVertically) {
                Text(shownRow.title, fontSize = t(15), color = Color.White)
                rows.getOrNull(rowFocus + 1)?.let { Text("   ·   Down: ${it.title}", fontSize = t(12), color = Color.White.copy(alpha = 0.6f)) }
            }
            val cardState = androidx.compose.foundation.lazy.rememberLazyListState()
            androidx.compose.runtime.LaunchedEffect(rowFocus, cardFocus) { runCatching { cardState.animateScrollToItem((cardFocus - 1).coerceAtLeast(0)) } }
            androidx.compose.foundation.lazy.LazyRow(state = cardState, modifier = Modifier.padding(top = d(6)), horizontalArrangement = Arrangement.spacedBy(d(12)), userScrollEnabled = false) {
                items(shownRow.items.size) { i ->
                    val p = shownRow.items[i]
                    val on = i == cardFocus
                    androidx.compose.foundation.layout.Column(Modifier.width(d(176))) {
                        Box(
                            Modifier.size(d(176), d(99)).clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                                .then(if (on) Modifier.border(2.dp, AppColors.Accent, RoundedCornerShape(8.dp)) else Modifier),
                        ) {
                            if (p.image != null) coil.compose.AsyncImage(
                                p.image, null,
                                contentScale = if (p.logo) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = if (p.logo) Modifier.fillMaxSize().padding(horizontal = d(28), vertical = d(18)) else Modifier.fillMaxSize(),
                            )
                        }
                        Text(p.title, fontSize = t(12), color = if (on) Color.White else Color.White.copy(alpha = 0.7f), maxLines = 1, modifier = Modifier.padding(top = d(5)))
                        p.subtitle?.let { Text(it, fontSize = t(11), color = Color.White.copy(alpha = 0.6f), maxLines = 1) }
                    }
                }
            }
        }
    }
}

fun clockMs(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}


/**
 * While a film or episode opens: its backdrop with a dark fade towards the bottom, and its logo fading in and then
 * slowly "breathing" (a gentle grow and shrink) in the middle; the title as text when there's no logo. Fades away
 * when the picture starts.
 */
@Composable
fun PlayerLoading(title: String, art: io.github.petebutty.viewarr.data.media.PlayerArt?, visible: Boolean = true, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250)),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)),
        modifier = modifier,
    ) {
        val breath = androidx.compose.animation.core.rememberInfiniteTransition(label = "breath")
        val scale by breath.animateFloat(
            initialValue = 1f, targetValue = 1.05f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(2_000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "scale",
        )
        // Fades in once, shortly after the screen appears.
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val fadeIn by androidx.compose.animation.core.animateFloatAsState(
            if (shown) 1f else 0f, androidx.compose.animation.core.tween(700, delayMillis = 300), label = "fadeIn",
        )
        val context = androidx.compose.ui.platform.LocalContext.current
        var logoFailed by remember(art?.logo) { mutableStateOf(false) }
        val showLogo = art?.logo != null && !logoFailed
        Box(Modifier.fillMaxWidth().fillMaxHeight().background(Color.Black)) {
            art?.backdrop?.let { bg ->
                coil.compose.AsyncImage(
                    model = bg, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopEnd,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                )
            }
            Box(
                Modifier.fillMaxWidth().fillMaxHeight().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.4f to Color.Black.copy(alpha = 0.6f), 0.75f to Color.Black.copy(alpha = 0.82f), 1f to Color.Black.copy(alpha = 0.92f)),
                ),
            )
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showLogo) coil.compose.AsyncImage(
                    model = remember(art?.logo) { coil.request.ImageRequest.Builder(context).data(art?.logo).build() },
                    contentDescription = title, contentScale = ContentScale.Fit,
                    onError = { logoFailed = true },
                    modifier = Modifier.width(320.dp).height(140.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = fadeIn },
                )
                else Text(
                    title.substringBefore(" · "), fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 120.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = fadeIn },
                )
                title.substringAfter(" · ", "").takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 17.sp, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(top = 20.dp).graphicsLayer { alpha = fadeIn })
                }
            }
        }
    }
}


/**
 * What's playing, over the picture: poster, title (or logo), the episode, facts and the synopsis. Opened with the
 * remote's Info button (or Details), and shown by itself while paused, as other TV players do.
 */
@Composable
fun PlayerDetails(title: String, art: io.github.petebutty.viewarr.data.media.PlayerArt?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.88f), 0.55f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent))) {
        Row(Modifier.padding(start = 64.dp, top = 56.dp, end = 64.dp).fillMaxWidth(0.7f), verticalAlignment = Alignment.Top) {
            art?.poster?.let {
                io.github.petebutty.viewarr.ui.common.PosterImage(it, null, Modifier.width(180.dp).height(270.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(28.dp))
            }
            Column(Modifier.weight(1f)) {
                var logoShown by remember(art?.logo) { mutableStateOf(false) }
                if (art?.logo != null) coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current).data(art.logo).size(coil.size.Size.ORIGINAL).build(),
                    contentDescription = title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                    onSuccess = { logoShown = true },
                    modifier = Modifier.height(if (logoShown) 72.dp else 0.dp).width(360.dp),
                )
                if (!logoShown) Text(title.substringBefore(" · "), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                art?.episodeLine?.let { Text(it, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = AppColors.Accent, modifier = Modifier.padding(top = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                art?.facts?.let { Text(it, fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp)) }
                art?.overview?.let { Text(it, fontSize = 17.sp, lineHeight = 25.sp, color = Color.White, maxLines = 7, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp)) }
                Text("Info or Back to close", fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}
