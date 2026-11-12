package io.github.petebutty.viewarr.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.ignoreHeldOk
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.CornerPicture
import kotlinx.coroutines.android.awaitFrame

/**
 * The picture in the corner (live TV over another page, or a film), highlighted (Settings → Playback → Highlight the
 * corner picture): reached by pressing towards it at a page's edge; then Full screen or Close (Left / Right, OK), Back
 * or a move away to return to the page.
 *
 * [arm]: set by the page just before moving onto it on purpose (it can't take the highlight otherwise: a page briefly
 * without one handed it here and it stuck). [reachable]: whether it may be moved onto now.
 */
@Composable
internal fun BoxScope.CornerHighlight(
    film: Boolean,
    focusRequester: FocusRequester,
    arm: BooleanArray,
    reachable: Boolean,
    focused: Boolean,
    onFocused: (Boolean) -> Unit,
    onFullScreen: () -> Unit,
    onClose: () -> Unit,
    /** Leaving it with nothing on the page above it to land on: the page's own starting point. */
    onLeave: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val (cw, ch) = CornerPicture.size()
    val at = CornerPicture.corner()
    val left = at == 1 || at == 3
    val top = at >= 2
    // (Where each window sits: the film's 40 dp from the side, 56 / 32 from top / bottom; live TV's 32 all round.)
    val side = CornerPicture.inset.first
    val edge = CornerPicture.inset.second
    var button by remember { mutableIntStateOf(0) }
    var okDown by remember { mutableStateOf(false) }
    // Off the picture, onto the page just above it (below it, in a top corner): on screen, so the highlight can be seen.
    // (Sideways found whatever was nearest, on a title page often something out of view.) Nothing there: the page's own.
    fun back() { if (!focusManager.moveFocus(if (top) FocusDirection.Down else FocusDirection.Up)) onLeave() }
    fun act(full: Boolean) { back(); if (full) onFullScreen() else onClose() }
    // A film's player is drawn over everything (this included), and its video clears what's under it: its highlight
    // goes just outside the picture (border around it, buttons above / below), or it couldn't be seen.
    val out = if (film) 6.dp else 0.dp
    Box(
        Modifier
            .align(when (at) { 1 -> Alignment.BottomStart; 2 -> Alignment.TopEnd; 3 -> Alignment.TopStart; else -> Alignment.BottomEnd })
            .padding(start = if (left) side - out else 0.dp, end = if (left) 0.dp else side - out, top = if (top) edge - out else 0.dp, bottom = if (top) 0.dp else edge - out)
            .size(cw + out * 2, ch + out * 2)
            .focusProperties { canFocus = focused || (reachable && arm[0]) }
            .focusRequester(focusRequester)
            .onFocusChanged {
                onFocused(it.isFocused); arm[0] = false
                if (it.isFocused) button = 0 else okDown = false
            }
            .focusable()
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                when (e.key) {
                    // Left / Right between the two only (they never leave the picture: past the ends, nothing).
                    Key.DirectionLeft -> { if (down && button == 1) button = 0; true }
                    Key.DirectionRight -> { if (down && button == 0) button = 1; true }
                    // Towards the page (Up from a bottom corner): off the picture; the other way, nothing.
                    Key.DirectionUp -> if (down && top) true else { if (down) back(); true }
                    Key.DirectionDown -> if (down && !top) true else { if (down) back(); true }
                    // Back (a tap): off the picture, onto the page. (Holding it is the picture's menu.)
                    Key.Back -> { if (!down) back(); true }
                    // OK on its release (so the press doesn't reach what comes next).
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (down && e.nativeKeyEvent.repeatCount == 0) okDown = true
                        else if (!down && okDown) { okDown = false; act(button == 0) }
                        true
                    }
                    else -> false
                }
            }
            .then(if (focused) Modifier.border(3.dp, AppColors.Accent, RoundedCornerShape(8.dp)) else Modifier),
    ) {
        if (focused) Row(
            // (A film's: outside the picture, above it, or below it in a top corner.)
            if (!film) Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
            else if (top) Modifier.align(Alignment.BottomCenter).offset(y = 44.dp)
            else Modifier.align(Alignment.TopCenter).offset(y = (-44).dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("⤢  Full screen", "✕  Close").forEachIndexed { i, label ->
                Text(
                    label, fontSize = 14.sp,
                    color = if (button == i) AppColors.OnAccent else AppColors.TextPrimary,
                    modifier = Modifier
                        .background(if (button == i) AppColors.Accent else AppColors.Panel.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Holding Back with a picture in the corner: Full screen, Close picture, or Cancel. */
@Composable
internal fun CornerPictureMenu(film: Boolean, onFullScreen: () -> Unit, onClosePicture: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
        // The held Back that opened it: its release mustn't close it straight away.
        var backDown by remember { mutableStateOf(false) }
        Column(
            Modifier.width(400.dp).background(AppColors.Panel, RoundedCornerShape(14.dp))
                .onPreviewKeyEvent { e ->
                    if (e.key != Key.Back) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) { backDown = true; false } else !backDown
                }
                .ignoreHeldOk()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (film) "The film in the corner" else "Live TV in the corner", fontSize = 18.sp, color = AppColors.TextPrimary)
            MenuItem(text = "Full screen", selected = false, modifier = Modifier.focusRequester(first), onClick = onFullScreen)
            MenuItem(text = "Close picture", selected = false, onClick = onClosePicture)
            MenuItem(text = "Cancel", selected = false, onClick = onDismiss)
        }
    }
}

/**
 * Live TV's corner picture showing through something drawn over it (search): its window cleared where the picture sits
 * (live TV's window is 32 dp in from the edges).
 */
internal fun Modifier.cornerWindow(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val (w, h) = CornerPicture.size()
        val at = CornerPicture.corner()
        val ww = w.toPx(); val hh = h.toPx()
        // (The top-left window: the guide's preview place.)
        val x = CornerPicture.inset.first.toPx()
        val y = CornerPicture.inset.second.toPx()
        drawRoundRect(
            Color.Transparent, topLeft = androidx.compose.ui.geometry.Offset(x, y), size = androidx.compose.ui.geometry.Size(ww, hh),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), blendMode = BlendMode.Clear,
        )
    }
