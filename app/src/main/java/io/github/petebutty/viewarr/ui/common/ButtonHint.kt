package io.github.petebutty.viewarr.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts

/**
 * A small pill at the bottom of the screen saying what the buttons do here ("OK: watch · hold Back: groups"),
 * for a few seconds when [screen] comes up (again when [text] changes). Settings → Appearance → Menu → Button hints:
 * the first few times on each screen (counted on this TV), always, or off.
 */
@Composable
fun ButtonHint(screen: String, text: String, modifier: Modifier = Modifier) {
    val mode = AppColors.palette.opt(UiOpts.BUTTON_HINTS)
    if (mode == 2 || text.isBlank()) return
    val ctx = LocalContext.current
    var shown by remember(screen) { mutableStateOf(false) }
    LaunchedEffect(screen, text) {
        if (mode == 0) {
            // "First few times": once per screen each time Viewarr is opened, for its first five openings (counting
            // every appearance used them up in an evening: each time live TV's controls closed, say).
            if (screen in HintSession.shown) return@LaunchedEffect
            val prefs = ctx.getSharedPreferences("hints", android.content.Context.MODE_PRIVATE)
            val seen = prefs.getInt(screen, 0)
            if (seen >= 5) return@LaunchedEffect
            HintSession.shown += screen
            prefs.edit().putInt(screen, seen + 1).apply()
        }
        kotlinx.coroutines.delay(600)
        shown = true
        kotlinx.coroutines.delay(8_000)
        shown = false
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(shown, enter = EnterTransition.None, exit = ExitTransition.None) {
            Text(
                text, fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 1,
                modifier = Modifier.padding(bottom = 18.dp)
                    .glass(16.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

/** The screens whose hint has shown since Viewarr was opened ("First few times"). */
private object HintSession { val shown = mutableSetOf<String>() }
