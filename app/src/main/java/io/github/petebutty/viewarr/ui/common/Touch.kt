package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Touch for the TV building blocks. Everything here does nothing on a TV ([TouchScreen.on] false): the modifiers add
 * nothing at all there, so a TV box runs exactly what it ran before.
 *
 * Touch for the TV building blocks. The TV library's buttons and surfaces listen for the remote's OK only, so on a
 * tablet or phone nothing answered a finger (not even the first-run pages). With this, a tap is OK and a long press
 * is hold OK. The tapped thing takes the highlight first, as it would have under a remote, because many presses act
 * on "the highlighted one". Dragging is left to the lists, which scroll by finger on their own.
 */
fun Modifier.tap(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = if (!TouchScreen.on) this else composed {
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    val focus = remember { FocusRequester() }
    val holds = onLongClick != null
    focusRequester(focus).pointerInput(enabled, holds) {
        if (!enabled) return@pointerInput
        detectTapGestures(
            onTap = { runCatching { focus.requestFocus() }; click() },
            onLongPress = if (holds) { { runCatching { focus.requestFocus() }; long?.invoke() } } else null,
        )
    }
}

/**
 * Touch for a text box that stays read-only until OK is pressed on it (so the keyboard doesn't pop up as the
 * highlight passes through). A finger on the box is that OK: [start] switches the box to typing as the finger lands,
 * before the tap reaches the box, and the keyboard is asked for once the box has become editable.
 */
fun Modifier.typeOnTap(start: () -> Unit): Modifier = if (!TouchScreen.on) this else composed {
    val go by rememberUpdatedState(start)
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            go()
            scope.launch { delay(200); keyboard?.show() }
        }
    }
}

/**
 * The remote, by finger, for whatever is still driven by keys alone (the players, the TV guide, sliders, the
 * app's own highlight-and-keys parts): set by the activity, which hands each press on as if a remote had sent it.
 */
object RemoteTouch {
    @Volatile var send: ((android.view.KeyEvent) -> Unit)? = null

    private fun key(action: Int, code: Int, repeat: Int = 0, down: Long = android.os.SystemClock.uptimeMillis()) =
        send?.invoke(android.view.KeyEvent(down, android.os.SystemClock.uptimeMillis(), action, code, repeat))

    /** One press of [code]. */
    fun press(code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        key(android.view.KeyEvent.ACTION_DOWN, code, 0, t); key(android.view.KeyEvent.ACTION_UP, code, 0, t)
    }

    /** [code] held: the press, its repeats (what a hold looks like from a remote), then the release. */
    fun hold(code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        key(android.view.KeyEvent.ACTION_DOWN, code, 0, t)
        key(android.view.KeyEvent.ACTION_DOWN, code, 1, t)
        key(android.view.KeyEvent.ACTION_DOWN, code, 2, t)
        key(android.view.KeyEvent.ACTION_UP, code, 0, t)
    }
}

/**
 * A touch screen (tablet, phone) rather than a TV: set once by the activity. [controls] is how much bigger the
 * players' on-screen controls are drawn there, so a button is finger-sized (about 9 mm) whatever the screen: a little
 * on a tablet, more on a phone. 1 on a TV, where a remote moves the highlight.
 */
object TouchScreen {
    /** What the device looks like: a touch screen that isn't a TV. */
    @Volatile private var found = false
    @Volatile private var size = 1f

    /** Touch is in use: what was found, unless Settings → Troubleshooting → Touch controls says On or Off. */
    val on: Boolean get() = when (io.github.petebutty.viewarr.ui.theme.AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.TOUCH)) {
        1 -> true
        2 -> false
        else -> found
    }
    val controls: Float get() = if (on) size else 1f

    fun measure(c: android.content.Context) {
        // A TV box can claim a touch screen it doesn't have (some list the feature and aren't "leanback"), so three
        // things must agree: not a TV by its features, not in Android's television mode, and a real touch panel
        // among its input devices (a TV box has remotes, keyboards and mice there, never a panel).
        val pm = c.packageManager
        val tvFeature = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("android.software.leanback_only") || pm.hasSystemFeature("amazon.hardware.fire_tv")
        val tvMode = (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val panel = runCatching {
            android.view.InputDevice.getDeviceIds().any { id ->
                val d = android.view.InputDevice.getDevice(id)
                d != null && !d.isVirtual && d.supportsSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)
            }
        }.getOrDefault(false)
        // (Previewarr Mobile is made for touch: always, whatever the device says about itself.)
        found = io.github.petebutty.viewarr.BuildConfig.MOBILE || (!tvFeature && !tvMode && panel)
        val m = c.resources.displayMetrics
        val wide = maxOf(m.widthPixels, m.heightPixels)
        val dpi = (if (m.widthPixels >= m.heightPixels) m.xdpi else m.ydpi).takeIf { it in 80f..800f } ?: m.densityDpi.toFloat()
        // The app draws on a fixed 1067-wide canvas (see App scaling): one canvas unit in millimetres on this screen.
        val mmPerUnit = wide / dpi * 25.4f / 1067f
        // A control button is about 34 units at the default size.
        size = (9f / (34f * mmPerUnit)).coerceIn(1.15f, 1.6f)
    }
}

/** How far a finger travels for one step of the highlight (one arrow press). */
private val SWIPE_STEP = 56.dp

/**
 * Finger gestures as the remote's keys, for a thing that only understands keys. A swipe is the arrows, in the
 * finger's direction, one press for every [SWIPE_STEP] travelled (so a long drag keeps stepping: seeking, a slider,
 * moving through the guide). With [tap], a tap is OK and a long press is hold OK; [before] runs first (to take the
 * highlight). Only gestures nothing above took: lists still scroll by finger and buttons still take their own taps.
 */
fun Modifier.remoteGestures(tap: Boolean, before: () -> Unit = {}): Modifier = if (!TouchScreen.on) this else composed {
    val first by rememberUpdatedState(before)
    val step = with(LocalDensity.current) { SWIPE_STEP.toPx() }
    val taps = if (!tap) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = { first(); RemoteTouch.press(android.view.KeyEvent.KEYCODE_DPAD_CENTER) },
            onLongPress = { first(); RemoteTouch.hold(android.view.KeyEvent.KEYCODE_DPAD_CENTER) },
        )
    }
    this.then(taps).pointerInput(step) {
        var x = 0f
        var y = 0f
        var across: Boolean? = null
        detectDragGestures(
            onDragStart = { x = 0f; y = 0f; across = null; first() },
            onDrag = { change, by ->
                change.consume()
                x += by.x; y += by.y
                // The first clear direction is the swipe's: a wobble the other way doesn't turn it.
                if (across == null && (kotlin.math.abs(x) >= step || kotlin.math.abs(y) >= step)) across = kotlin.math.abs(x) >= kotlin.math.abs(y)
                if (across == true) while (kotlin.math.abs(x) >= step) {
                    RemoteTouch.press(if (x > 0) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                    x -= if (x > 0) step else -step
                }
                if (across == false) while (kotlin.math.abs(y) >= step) {
                    RemoteTouch.press(if (y > 0) android.view.KeyEvent.KEYCODE_DPAD_DOWN else android.view.KeyEvent.KEYCODE_DPAD_UP)
                    y -= if (y > 0) step else -step
                }
            },
        )
    }
}

/** A full-screen picture (live TV, a film): tap for the controls (OK), hold for hold OK, swipe for the arrows. */
fun Modifier.tapIsOk(on: Boolean): Modifier = if (!on) this else remoteGestures(tap = true)

/**
 * `focusable()` for the app's own key-driven parts (the seek bar, sliders, the letter strip, the remote-button
 * tables): by finger, a tap takes the highlight and is OK, a long press is hold OK, and a swipe is the arrows.
 */
fun Modifier.touchFocusable(): Modifier = if (!TouchScreen.on) focusable() else composed {
    val focus = remember { FocusRequester() }
    focusRequester(focus).focusable().remoteGestures(tap = true) { runCatching { focus.requestFocus() } }
}

/** The TV library's pressable surface, answering a finger too (see [tap]). Same options, so only the import changes. */
@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    tonalElevation: Dp = 0.dp,
    shape: ClickableSurfaceShape = ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = ClickableSurfaceDefaults.colors(),
    scale: ClickableSurfaceScale = ClickableSurfaceDefaults.scale(),
    border: ClickableSurfaceBorder = ClickableSurfaceDefaults.border(),
    glow: ClickableSurfaceGlow = ClickableSurfaceDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) = androidx.tv.material3.Surface(
    onClick = onClick,
    modifier = modifier.tap(enabled, onLongClick, onClick),
    onLongClick = onLongClick,
    enabled = enabled,
    tonalElevation = tonalElevation,
    shape = shape,
    colors = colors,
    scale = scale,
    border = border,
    glow = glow,
    interactionSource = interactionSource,
    content = content,
)

/** The TV library's outlined button, answering a finger too (see [tap]). */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = androidx.tv.material3.OutlinedButton(
    onClick = onClick,
    modifier = modifier.tap(enabled, onLongClick, onClick),
    onLongClick = onLongClick,
    enabled = enabled,
    contentPadding = contentPadding,
    content = content,
)
