package io.github.petebutty.viewarr.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
fun TransparencyPanel(onClose: () -> Unit, onRow: (Int) -> Unit = {},
    /** On the glass colour / info bar rows: true, so the info bar shows behind the panel while you change it. */
    onInfoBar: (Boolean) -> Unit = {},
    vm: AppearanceViewModel = hiltViewModel(),
    opts: io.github.petebutty.viewarr.ui.settings.UiOptsViewModel = hiltViewModel()) {
    val p = AppColors.palette
    var row by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { focus.requestFocus() } }
    // The screen behind follows the slider (full screen for the channel list and the glass, the guide for the others).
    BackHandler(onBack = onClose)
    // (name, value 0..1 as shown, lowest, highest)
    val wallpaperOn = p.wallpaperMode != 0
    val rows = buildList {
        add(Triple("Channel list", p.transparency, 0f..1f))
        add(Triple("Guide", p.guideTransparency, 0f..1f))
        if (wallpaperOn) add(Triple("Wallpaper dimming", p.wallpaperDim, 0.3f..0.9f))
    }
    // After the sliders: the glass's colour (◀ ▶ through them) and how solid the info bar and pop-ups are.
    val tints = io.github.petebutty.viewarr.ui.theme.GlassTints
    val tint = p.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.GLASS_TINT).coerceIn(0, tints.lastIndex)
    val popup = p.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.POPUP_GLASS).coerceIn(0, 10)
    val colourRow = rows.size
    val popupRow = rows.size + 1
    val last = popupRow
    LaunchedEffect(row) { onRow(if (row >= colourRow) 0 else row); onInfoBar(row >= colourRow) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onInfoBar(false) } }
    fun change(i: Int, dir: Int) {
        if (i == colourRow) { opts.set(io.github.petebutty.viewarr.ui.theme.UiOpts.GLASS_TINT, (tint + dir + tints.size) % tints.size); return }
        if (i == popupRow) { opts.set(io.github.petebutty.viewarr.ui.theme.UiOpts.POPUP_GLASS, (popup + dir).coerceIn(0, 10)); return }
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
                // (Drawn in the glass's own colour, so a new one shows straight away.)
                .background(AppColors.MenuPanel)
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key != Key.Back
                    when (e.key) {
                        Key.DirectionUp -> row = (row - 1).coerceAtLeast(0)
                        Key.DirectionDown -> row = (row + 1).coerceAtMost(last)
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
            Text("Transparency & glass", fontSize = 22.sp, color = AppColors.TextPrimary)
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
            // Glass colour: its name and a swatch of each colour, the chosen one ringed.
            Column(
                Modifier.fillMaxWidth()
                    .background(if (row == colourRow) AppColors.Accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Glass colour", fontSize = 16.sp, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                    Text("◀  ${tints[tint].first}  ▶", fontSize = 16.sp, color = if (row == colourRow) AppColors.Accent else AppColors.TextSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    tints.forEachIndexed { i, (_, c) ->
                        val sw = c ?: AppColors.palette.main
                        Box(
                            Modifier.width(20.dp).height(20.dp)
                                .background(sw, androidx.compose.foundation.shape.CircleShape)
                                .border(if (i == tint) 3.dp else 1.dp, if (i == tint) AppColors.Accent else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f), androidx.compose.foundation.shape.CircleShape),
                        )
                    }
                }
            }
            // Pop-up glass: how solid the info bar and every pop-up are.
            Column(
                Modifier.fillMaxWidth()
                    .background(if (row == popupRow) AppColors.Accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Info bar & pop-ups", fontSize = 16.sp, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                    Text(io.github.petebutty.viewarr.ui.theme.UiOpts.choices.getValue(io.github.petebutty.viewarr.ui.theme.UiOpts.POPUP_GLASS)[popup], fontSize = 16.sp,
                        color = if (row == popupRow) AppColors.Accent else AppColors.TextSecondary)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).background(AppColors.TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(popup / 10f).height(6.dp).background(if (row == popupRow) AppColors.Accent else AppColors.TextSecondary, RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}

