package io.github.petebutty.viewarr.ui.report

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.common.HeldOk
import io.github.petebutty.viewarr.ui.common.glass
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * A quiet card in the top corner from the Viewarr plugin's house data (data/report/HouseSync): a channel changed from
 * someone's report (admins: OK undoes it), or a wrong-guide report that needs someone to pick the right guide (OK
 * opens the picker). Takes no focus: the home screen's root key handler asks [onKey] first, as it does for a reminder.
 * Governed by Settings → Notifications → Reports from the house (off by default). (Peter, 2026-11-19.)
 */
object HouseNotice {
    class Notice(val text: String, val hint: String, val action: (() -> Unit)?)

    var current by mutableStateOf<Notice?>(null)
        private set
    private var seq by mutableIntStateOf(0)

    fun show(n: Notice) { current = n; seq++ }
    fun dismiss() { current = null }

    /** OK runs the notice's action, Back dismisses it (on the release, so a held OK from before doesn't count). */
    fun onKey(e: KeyEvent): Boolean {
        val n = current ?: return false
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (!ok && e.key != Key.Back) return false
        if (e.type == KeyEventType.KeyUp && !HeldOk.isHeldRelease(e)) {
            current = null
            if (ok) n.action?.invoke()
        }
        return true
    }

    @Composable
    fun Host() {
        val n = current ?: return
        LaunchedEffect(seq) { kotlinx.coroutines.delay(20_000); if (current === n) current = null }
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.TopEnd).padding(28.dp).width(400.dp).glass(12.dp).padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text("⚑  From a report", fontSize = 13.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(n.text, fontSize = 16.sp, color = AppColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(n.hint, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
