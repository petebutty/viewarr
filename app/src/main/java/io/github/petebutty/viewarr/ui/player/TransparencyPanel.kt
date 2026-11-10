package io.github.petebutty.viewarr.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.settings.AppearanceViewModel
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlin.math.roundToInt

/**
 * The live TV controls' Transparency button: the three see-through settings as sliders over the picture, so the
 * effect shows while you change them. The panel itself is drawn as see-through as the channel list.
 * Up / Down picks a slider, Left / Right changes it (saved straight away), Back or OK closes.
 */
@Composable
fun TransparencyPanel(onClose: () -> Unit, onRow: (Int) -> Unit = {}, vm: AppearanceViewModel = hiltViewModel()) {
    val p = AppColors.palette
    var row by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { focus.requestFocus() } }
    // The screen behind follows the slider (full screen for the channel list, the guide for the other two).
    LaunchedEffect(row) { onRow(row) }
    BackHandler(onBack = onClose)
    // (name, value 0..1 as shown, lowest, highest)
    val wallpaperOn = p.wallpaperMode != 0
    val rows = buildList {
        add(Triple("Channel list", p.transparency, 0f..1f))
        add(Triple("Guide", p.guideTransparency, 0f..1f))
        if (wallpaperOn) add(Triple("Wallpaper dimming", p.wallpaperDim, 0.3f..0.9f))
    }
    fun change(i: Int, dir: Int) {
        val (_, v, range) = rows[i]
        val next = ((v * 10).roundToInt() + dir).coerceIn((range.start * 10).roundToInt(), (range.endInclusive * 10).roundToInt()) / 10f
        when (i) {
            0 -> vm.setTransparency(next)
            1 -> vm.setGuideTransparency(next)
            2 -> vm.setWallpaper(p.wallpaper, p.wallpaperStyle, next)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxHeight().width(440.dp)
                .background(AppColors.Panel.copy(alpha = p.menuAlpha))
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key != Key.Back
                    when (e.key) {
                        Key.DirectionUp -> row = (row - 1).coerceAtLeast(0)
                        Key.DirectionDown -> row = (row + 1).coerceAtMost(rows.lastIndex)
                        Key.DirectionLeft -> change(row, -1)
                        Key.DirectionRight -> change(row, 1)
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> onClose()
                        Key.Back -> return@onPreviewKeyEvent false
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                .padding(start = 32.dp, end = 32.dp, top = 48.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Transparency", fontSize = 22.sp, color = AppColors.TextPrimary)
            Text("▲ ▼ choose  ·  ◀ ▶ change  ·  Back: done", fontSize = 13.sp, color = AppColors.TextSecondary)
            rows.forEachIndexed { i, (name, v, range) ->
                val on = i == row
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (on) AppColors.Accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(name, fontSize = 16.sp, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                        val pct = (v * 100).roundToInt()
                        Text(if (i < 2 && pct == 0) "Solid" else "$pct%", fontSize = 16.sp, color = if (on) AppColors.Accent else AppColors.TextSecondary)
                    }
                    val frac = ((v - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(6.dp).background(AppColors.TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth(frac).height(6.dp).background(if (on) AppColors.Accent else AppColors.TextSecondary, RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
    }
}
