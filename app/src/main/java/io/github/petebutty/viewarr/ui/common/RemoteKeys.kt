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
    /** Buttons people taught (Settings → Remote → Buttons while watching), checked before the defaults. */
    @Volatile private var taught: Map<Int, RemoteAction> = emptyMap()

    /** From the saved settings (RemoteSettings.buttons: action name → key code). */
    fun setTaught(byAction: Map<String, Int>) {
        taught = byAction.mapNotNull { (name, code) -> RemoteAction.entries.firstOrNull { it.name == name }?.let { code to it } }.toMap()
    }

    /** What [e] means: a taught button first, then the default table. */
    fun action(e: KeyEvent): RemoteAction? = taught[e.nativeKeyEvent.keyCode] ?: defaultAction(e)

    /** Actions that can be given an extra button, with their names in Settings. */
    val teachable = listOf(
        RemoteAction.PlayPause to "Play / pause",
        RemoteAction.Rewind to "Skip back",
        RemoteAction.FastForward to "Skip forward",
        RemoteAction.Stop to "Stop",
        RemoteAction.Info to "Info",
        RemoteAction.Menu to "Audio & subtitles",
        RemoteAction.ChannelUp to "Channel up",
        RemoteAction.ChannelDown to "Channel down",
        RemoteAction.Guide to "TV guide",
        RemoteAction.Record to "Record",
    )

    /** Never taught: needed to move around and get out (or kept by the TV itself), and the number keys (channels). */
    fun isProtected(code: Int): Boolean = code in setOf(
        android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
        android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_ESCAPE, android.view.KeyEvent.KEYCODE_HOME,
        android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_MUTE,
        android.view.KeyEvent.KEYCODE_POWER,
    ) || code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ||
        code in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9

    /** A button's name for people: "Red", "F1", "Record"… */
    fun buttonName(code: Int): String = when (code) {
        android.view.KeyEvent.KEYCODE_PROG_RED -> "Red"
        android.view.KeyEvent.KEYCODE_PROG_GREEN -> "Green"
        android.view.KeyEvent.KEYCODE_PROG_YELLOW -> "Yellow"
        android.view.KeyEvent.KEYCODE_PROG_BLUE -> "Blue"
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/Pause"
        android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> "⏪"
        android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "⏩"
        android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> "⏭"
        android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "⏮"
        android.view.KeyEvent.KEYCODE_MENU -> "≡ Menu"
        android.view.KeyEvent.KEYCODE_CHANNEL_UP -> "Channel +"
        android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> "Channel −"
        else -> android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").removePrefix("MEDIA_").removePrefix("PROG_")
            .lowercase().split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .replace(Regex("^F(\\d+)$", RegexOption.IGNORE_CASE)) { "F" + it.groupValues[1] }
    }

    /**
     * The default table: exactly what each button did before actions existed. Buttons not listed (numbers, colour
     * buttons, a remote's own extra buttons) give null and are left to whoever handles them today.
     */
    fun defaultAction(e: KeyEvent): RemoteAction? = when (e.key) {
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
