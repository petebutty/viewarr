package io.github.petebutty.viewarr.ui.common

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * What a button press means while watching, whatever remote sent it. The players work in these actions, not raw
 * keys, so a button can later be given another action (Settings → Remote) in one place.
 */
enum class RemoteAction {
    Ok, Back, Up, Down, Left, Right,
    PlayPause, Rewind, FastForward, Stop,
    Info, Menu, ChannelUp, ChannelDown,
    Guide, Record,
}

/** The remote's buttons → [RemoteAction]. */
object RemoteKeys {
    /**
     * The default table: exactly what each button did before actions existed. Buttons not listed (numbers, colour
     * buttons, a remote's own extra buttons) give null and are left to whoever handles them today.
     */
    fun action(e: KeyEvent): RemoteAction? = when (e.key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> RemoteAction.Ok
        Key.Back -> RemoteAction.Back
        Key.DirectionUp -> RemoteAction.Up
        Key.DirectionDown -> RemoteAction.Down
        Key.DirectionLeft -> RemoteAction.Left
        Key.DirectionRight -> RemoteAction.Right
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> RemoteAction.PlayPause
        Key.MediaRewind -> RemoteAction.Rewind
        Key.MediaFastForward -> RemoteAction.FastForward
        Key.MediaStop -> RemoteAction.Stop
        Key.Info -> RemoteAction.Info
        Key.Menu -> RemoteAction.Menu
        Key.ChannelUp -> RemoteAction.ChannelUp
        Key.ChannelDown -> RemoteAction.ChannelDown
        Key.Guide -> RemoteAction.Guide
        Key.MediaRecord -> RemoteAction.Record
        else -> null
    }
}

enum class Press { Tap, Hold }

/**
 * Tap or hold for one button. A tap counts when the button is let go; a hold counts once, at the first repeat
 * (about half a second in), and the rest of that press is then ignored.
 */
class TapHold {
    private var down = false

    /** [Press.Tap] or [Press.Hold] when that's decided by [e], otherwise null. Feed it every event of the button. */
    fun feed(e: KeyEvent): Press? {
        val keyDown = e.type == KeyEventType.KeyDown
        return when {
            keyDown && e.nativeKeyEvent.repeatCount == 0 -> { down = true; null }
            keyDown && down -> { down = false; Press.Hold }
            !keyDown && down -> { down = false; Press.Tap }
            else -> null
        }
    }

    /** Forget a press in progress (its release then does nothing). */
    fun reset() { down = false }
}
