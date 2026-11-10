package io.github.petebutty.viewarr.ui.player

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.playback.PlaybackState
import io.github.petebutty.viewarr.playback.PlaybackViewModel
import io.github.petebutty.viewarr.ui.common.ignoreHeldOk
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.guide.displayTitle
import io.github.petebutty.viewarr.ui.guide.formatNumber
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame

/** Most windows at once: four is what TV boxes can usually decode together. */
const val MULTI_VIEW_MAX = 4

private fun ExoPlayer.setAudio(on: Boolean) {
    volume = if (on) 1f else 0f
    val params = trackSelectionParameters
    if (params.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO) == !on) return
    trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !on).build()
}

/** One window. The first is the main player (the channel you were watching); the rest have their own players. */
private class Tile(channel: ChannelListItem, val player: ExoPlayer?, val main: Boolean) {
    var channel by mutableStateOf(channel)
    /** "Loading…", an error, or null while playing. Not used for the main window (the main player says). */
    var status by mutableStateOf<String?>("Loading…")
}

/**
 * Multi-view: up to four live channels on screen at once. Arrows move between windows and the sound follows the
 * selected one. OK makes it full screen; holding OK (or Menu) changes or removes it; an empty window adds a channel.
 * Back goes full screen on the selected window.
 */
@OptIn(UnstableApi::class)
@Composable
fun MultiView(
    vm: PlaybackViewModel,
    /** Channels to open with besides the one playing (e.g. none). */
    programFor: (ChannelListItem) -> GuideProgram?,
    now: Long,
    /** Leaves multi-view, full screen on this channel. */
    onExit: (ChannelListItem) -> Unit,
    /** The channel list, to pick a channel for a window; calls onPick or onClose. */
    channelPicker: @Composable (current: ChannelListItem, onPick: (ChannelListItem) -> Unit, onClose: () -> Unit) -> Unit,
) {
    val mainChannel by vm.channel.collectAsStateWithLifecycle()
    val mainState by vm.state.collectAsStateWithLifecycle()
    val first = mainChannel ?: return
    val tiles = remember { mutableStateListOf(Tile(first, vm.player, main = true)) }
    LaunchedEffect(first) { tiles[0].channel = first }
    var selected by remember { mutableIntStateOf(0) }
    /** Where focus goes when a pop-up closes (the window it was for, or the one just added). */
    var focusTo by remember { mutableIntStateOf(0) }
    /** Window index whose channel is being picked; tiles.size = the empty "add" window. */
    var picking by remember { mutableStateOf<Int?>(null) }
    var options by remember { mutableStateOf<Int?>(null) }
    val focus = remember { List(MULTI_VIEW_MAX) { FocusRequester() } }

    fun newTile(ch: ChannelListItem): Tile? {
        val p = vm.tilePlayer(ch) ?: return null
        val t = Tile(ch, p, main = false)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> t.status = null
                    Player.STATE_BUFFERING -> if (t.status == null) t.status = "Loading…"
                    else -> Unit
                }
            }
            override fun onPlayerError(error: PlaybackException) { t.status = vm.describeError(error) }
        })
        return t
    }

    fun exit(i: Int = selected) {
        val ch = tiles.getOrNull(i)?.channel ?: first
        onExit(ch)
    }

    // The sound comes from the selected window only. The others have their audio switched off, not just muted:
    // many boxes play one audio output at a time (always so with surround passthrough), so a muted window kept
    // holding it and the selected one stayed silent. Switched off first, then the selected one on.
    LaunchedEffect(selected, tiles.size, tiles.map { it.player }) {
        tiles.forEachIndexed { i, t -> if (i != selected) t.player?.setAudio(false) }
        tiles.getOrNull(selected)?.player?.setAudio(true)
    }
    // Leaving: the extra players stop and the main one has its sound back.
    DisposableEffect(Unit) {
        onDispose {
            tiles.filter { !it.main }.forEach { it.player?.release() }
            vm.player?.setAudio(true)
        }
    }
    // Sent to the background: back to one channel (the box shouldn't keep several streams running unseen).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val exitNow by rememberUpdatedState { exit(0) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) exitNow() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // Focus the selected window on opening and after a pop-up closes.
    LaunchedEffect(picking == null, options == null, tiles.size) {
        if (picking != null || options != null) return@LaunchedEffect
        // A window just added may not be laid out yet on the first frame.
        val target = focusTo.coerceIn(0, minOf(tiles.size, MULTI_VIEW_MAX - 1))
        repeat(3) { awaitFrame(); if (runCatching { focus[target].requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    BackHandler(enabled = picking == null && options == null) { exit() }

    val slots = minOf(tiles.size + 1, MULTI_VIEW_MAX)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Opened by OK on the player menu: that press's release mustn't make a window full screen at once.
            .ignoreHeldOk()
            // The guide is underneath: the remote stays in multi-view.
            .trapFocus(),
    ) {
        val rows = if (slots <= 2) listOf(0 until slots) else listOf(0 until 2, 2 until slots)
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (r in rows) {
                // Two windows: side by side, as wide as the screen allows. More: a 2 × 2 grid, as tall as it allows.
                val rowMod = if (slots <= 2) Modifier.fillMaxWidth() else Modifier.weight(1f).fillMaxWidth()
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, modifier = rowMod) {
                    for (i in r) {
                        val size = if (slots <= 2) Modifier.weight(1f).aspectRatio(16f / 9f)
                        else Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                        val slotMod = size
                            // Nothing behind an open menu or channel list may take focus (Down in the menu went here).
                            .focusProperties { canFocus = options == null && picking == null }
                            .focusRequester(focus[i])
                            .onFocusChanged { if (it.isFocused && i < tiles.size) selected = i }
                        val t = tiles.getOrNull(i)
                        if (t == null) AddWindow(slotMod) { focusTo = i; picking = tiles.size }
                        else TileView(
                            t, selected == i,
                            status = if (t.main) when (val s = mainState) {
                                PlaybackState.Buffering -> "Loading…"
                                is PlaybackState.Error -> s.message
                                else -> null
                            } else t.status,
                            program = programFor(t.channel),
                            now = now,
                            onClick = { exit(i) },
                            onOptions = { focusTo = i; options = i },
                            modifier = slotMod,
                        )
                    }
                }
            }
        }

        options?.let { i ->
            val t = tiles.getOrNull(i)
            if (t == null) { options = null; return@let }
            BackHandler { options = null }
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
            Column(
                Modifier.align(Alignment.Center).width(360.dp).ignoreHeldOk().trapFocus()
                    .background(AppColors.Panel, RoundedCornerShape(12.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(t.channel.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                OptionButton("Full screen", Modifier.focusRequester(first)) { options = null; exit(i) }
                OptionButton("Change channel") { options = null; picking = i }
                if (!t.main) OptionButton("Remove this window") {
                    options = null
                    tiles.removeAt(i).player?.release()
                    selected = (i - 1).coerceAtLeast(0)
                    focusTo = selected
                }
                OptionButton("Back to one channel") { options = null; exit(0) }
            }
        }

        picking?.let { i ->
            val current = tiles.getOrNull(i)?.channel ?: tiles[selected].channel
            Box(Modifier.fillMaxSize()) {
                channelPicker(
                    current,
                    { ch ->
                        picking = null
                        val t = tiles.getOrNull(i)
                        when {
                            t == null -> newTile(ch)?.let { tiles.add(it); selected = tiles.size - 1; focusTo = tiles.size - 1 }
                            t.main -> if (ch.id != t.channel.id) vm.play(ch)
                            ch.id != t.channel.id -> newTile(ch)?.let { nt -> tiles[i].player?.release(); tiles[i] = nt }
                        }
                    },
                    { picking = null },
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TileView(
    t: Tile,
    selected: Boolean,
    status: String?,
    program: GuideProgram?,
    now: Long,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        onClick = onClick,
        onLongClick = onOptions,
        modifier = modifier
            .onPreviewKeyEvent { e -> if (io.github.petebutty.viewarr.ui.common.RemoteKeys.action(e) == io.github.petebutty.viewarr.ui.common.RemoteAction.Menu && e.type == KeyEventType.KeyDown) { onOptions(); true } else false }
            .border(3.dp, if (selected) AppColors.Accent else Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black, focusedContainerColor = Color.Black, pressedContainerColor = Color.Black),
    ) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    // TextureView (as the film player): several SurfaceViews inside Compose showed sound but no
                    // picture on some boxes (Fire TV Stick 4K Max).
                    (android.view.LayoutInflater.from(ctx).inflate(io.github.petebutty.viewarr.R.layout.vod_player_view, null) as PlayerView).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(AndroidColor.BLACK)
                        keepScreenOn = true
                        isFocusable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                },
                update = { view -> if (view.player !== t.player) view.player = t.player },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            status?.let {
                Text(
                    it, fontSize = 13.sp, color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp).background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            // Channel and programme along the bottom; the selected window also says what the buttons do.
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
            ) {
                Text(
                    listOfNotNull(t.channel.number?.let(::formatNumber), t.channel.name).joinToString("  ") +
                        (program?.let { "  ·  " + displayTitle(it) } ?: ""),
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (selected) Text("OK full screen  ·  Hold OK for options", fontSize = 11.sp, color = Color(0xCCFFFFFF))
                program?.takeIf { it.endTime > it.startTime }?.let { p ->
                    val f = ((now - p.startTime).toFloat() / (p.endTime - p.startTime)).coerceIn(0f, 1f)
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().background(Color(0x44FFFFFF))) {
                        Box(Modifier.fillMaxWidth(f).padding(vertical = 1.dp).background(AppColors.Accent))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddWindow(modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Panel.copy(alpha = 0.6f), contentColor = AppColors.TextSecondary,
            focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("+", fontSize = 48.sp)
            Text("Add a channel", fontSize = 16.sp)
        }
    }
}

@Composable
private fun OptionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.PanelAlt, contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Text(text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}
