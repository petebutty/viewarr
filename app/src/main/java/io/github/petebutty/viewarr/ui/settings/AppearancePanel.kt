package io.github.petebutty.viewarr.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.settings.ThemeStore
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.HighlightChoices
import io.github.petebutty.viewarr.ui.theme.MainColourChoices
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.ThemePresets
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppearanceViewModel @Inject constructor(private val store: ThemeStore) : ViewModel() {
    fun apply(main: Color, highlight: Color) {
        viewModelScope.launch { store.save(main, highlight) }
    }

    fun setTransparency(value: Float) {
        viewModelScope.launch { store.saveTransparency(value) }
    }
}

@Composable
fun AppearancePanel(onBack: () -> Unit, vm: AppearanceViewModel = hiltViewModel()) {
    val palette = AppColors.palette
    val firstFocus = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { firstFocus.requestFocus() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel("Presets")
        ThemePresets.forEachIndexed { i, preset ->
            MenuItem(
                text = preset.name,
                selected = palette.main == preset.main && palette.highlight == preset.highlight,
                onClick = { vm.apply(preset.main, preset.highlight) },
                modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                leading = {
                    Row {
                        Dot(preset.main)
                        Dot(preset.highlight, Modifier.padding(start = 3.dp))
                    }
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        SectionLabel("Main colour")
        SwatchRow(MainColourChoices, palette.main) { vm.apply(it, palette.highlight) }
        Spacer(Modifier.height(10.dp))
        SectionLabel("Highlight colour")
        SwatchRow(HighlightChoices, palette.highlight) { vm.apply(palette.main, it) }
        Spacer(Modifier.height(10.dp))
        ValueSlider(
            label = "Menu transparency",
            value = (palette.transparency * 100).roundToInt(),
            onChange = { vm.setTransparency(it / 100f) },
        )
        Spacer(Modifier.height(12.dp))
        MenuItem(text = "Back", selected = false, onClick = onBack)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 4.dp))
}

@Composable
private fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).background(color, CircleShape))
}

@Composable
private fun SwatchRow(colors: List<Color>, current: Color, onPick: (Color) -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        colors.forEach { c ->
            val selected = c == current
            Surface(
                onClick = { onPick(c) },
                modifier = Modifier.size(22.dp),
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.15f),
                colors = ClickableSurfaceDefaults.colors(containerColor = c, focusedContainerColor = c),
                border = ClickableSurfaceDefaults.border(
                    border = if (selected) Border(BorderStroke(2.dp, Color.White), shape = CircleShape) else Border.None,
                    focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CircleShape),
                ),
            ) {}
        }
    }
}

@Composable
private fun ValueSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val text = if (focused) AppColors.OnAccent else AppColors.TextPrimary
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (focused) AppColors.Accent else Color.Transparent, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> onChange((value - STEP).coerceIn(0, 100))
                    Key.DirectionRight -> onChange((value + STEP).coerceIn(0, 100))
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, color = text, modifier = Modifier.weight(1f))
            Text("$value", fontSize = 14.sp, color = text)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).background(text.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(value / 100f).fillMaxHeight().background(text, RoundedCornerShape(2.dp)))
        }
        if (focused) {
            Text("◀ ▶ to adjust", fontSize = 11.sp, color = text.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private const val STEP = 5
