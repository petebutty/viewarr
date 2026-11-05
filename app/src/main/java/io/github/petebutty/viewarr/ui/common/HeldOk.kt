package io.github.petebutty.viewarr.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * For pop-ups opened by holding OK: the held press (its repeats and its release) must not also choose the first
 * option, e.g. "Record from now". OK only counts once it has been let go and pressed again.
 */
@Composable
fun Modifier.ignoreHeldOk(): Modifier {
    val armed = remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        when {
            !ok || armed.value -> false
            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> { armed.value = true; false }
            e.type == KeyEventType.KeyUp -> { armed.value = true; true }
            else -> true
        }
    }
}
