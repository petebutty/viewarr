package io.github.petebutty.viewarr.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.playback.AvSync
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors

/** The film player's Audio sync choices (live TV shows the same list in its own pop-up): OK picks one. */
@Composable
fun AvSyncPanel(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val current = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { current.requestFocus() } }
    Column(
        modifier.padding(24.dp).width(300.dp).background(AppColors.MenuPanel, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Audio sync", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        AvSync.steps.forEach { v ->
            MenuItem(
                text = AvSync.label(v), selected = v == AvSync.ms, compact = true,
                onClick = { AvSync.set(v); onClose() },
                modifier = if (v == AvSync.ms) Modifier.focusRequester(current) else Modifier,
            )
        }
    }
}
