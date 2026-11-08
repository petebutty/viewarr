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

    fun setHideMedia(on: Boolean) {
        viewModelScope.launch { store.saveHideMedia(on) }
    }

    fun setSquareCorners(on: Boolean) {
        viewModelScope.launch { store.saveSquareCorners(on) }
    }

    fun setProgressBars(mode: Int) {
        viewModelScope.launch { store.saveProgressBars(mode) }
    }

    fun setShowNumbers(on: Boolean) {
        viewModelScope.launch { store.saveShowNumbers(on) }
    }

    fun setWallpaper(mode: Int, style: Int, dim: Float) {
        viewModelScope.launch { store.saveWallpaper(mode, style, dim) }
    }

    fun setTransparency(value: Float) {
        viewModelScope.launch { store.saveTransparency(value) }
    }
}

private class Choice(override val label: String, val value: Any) : io.github.petebutty.viewarr.data.settings.RemoteChoice {
    override fun equals(other: Any?) = other is Choice && other.value == value
    override fun hashCode() = value.hashCode()
}

private val MainColourNames = listOf("Charcoal", "Navy", "Graphite", "Deep teal", "Plum", "Oxblood", "Forest", "Slate", "Aubergine", "OLED black")
private val HighlightNames = listOf("Cyan", "Pink", "Sky blue", "Blue", "Teal", "Lilac", "Orange", "Green", "Yellow", "Rose")

/** Settings → Appearance, full screen like the other settings pages. */
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit, vm: AppearanceViewModel = hiltViewModel()) {
    val palette = AppColors.palette
    val presets = ThemePresets.map { Choice(it.name, it.name) }
    val currentPreset = ThemePresets.firstOrNull { it.main == palette.main && it.highlight == palette.highlight }
    val mains = MainColourChoices.mapIndexed { i, c -> Choice(MainColourNames.getOrElse(i) { "Colour ${i + 1}" }, c) }
    val highs = HighlightChoices.mapIndexed { i, c -> Choice(HighlightNames.getOrElse(i) { "Colour ${i + 1}" }, c) }
    val onOff = listOf(Choice("On", true), Choice("Off", false))
    val corners = listOf(Choice("Rounded", false), Choice("Square", true))
    val transparencies = (0..70 step 10).map { Choice(if (it == 0) "Solid" else "$it%", it) }
    val currentT = ((palette.transparency * 100).roundToInt() / 10 * 10).coerceIn(0, 70)
    val wallModes = io.github.petebutty.viewarr.ui.theme.WallpaperModes.map { (v, n) -> Choice(n, v) }
    val wallStyles = io.github.petebutty.viewarr.ui.theme.WallpaperStyles.mapIndexed { i, w -> Choice(w.name, i) }
    val dims = (30..90 step 10).map { Choice(if (it <= 40) "$it% (light)" else if (it >= 80) "$it% (dark)" else "$it%", it) }
    val categories = listOf(
        SettingCategory(
            "Colours", "How Viewarr looks. Changes show straight away.",
            listOf(
                SettingItem("Theme", "A ready-made pair of background and highlight colours.", presets,
                    presets.firstOrNull { it.value == currentPreset?.name } ?: Choice("Custom", "custom")) { c ->
                    ThemePresets.firstOrNull { it.name == (c as Choice).value }?.let { vm.apply(it.main, it.highlight) }
                },
                // With a wallpaper on, the background colour is only its tint (Dimming sets that): hidden to avoid
                // two settings that seem to do the same thing.
                *(if (palette.wallpaper != 0) emptyArray() else arrayOf(
                SettingItem("Background colour", "The main colour behind menus and pages.", mains,
                    mains.firstOrNull { it.value == palette.main } ?: Choice("Custom", palette.main)) { c ->
                    vm.apply((c as Choice).value as androidx.compose.ui.graphics.Color, palette.highlight)
                })),
                SettingItem("Highlight colour", "The colour of whatever is selected.", highs,
                    highs.firstOrNull { it.value == palette.highlight } ?: Choice("Custom", palette.highlight)) { c ->
                    vm.apply(palette.main, (c as Choice).value as androidx.compose.ui.graphics.Color)
                },
                SettingItem("Menu transparency", "How much of the picture shows through the side menu while you watch full screen. Over the guide and other pages the menu is always solid.", transparencies,
                    transparencies.first { it.value == currentT }) { c -> vm.setTransparency(((c as Choice).value as Int) / 100f) }
            ),
        ),
        SettingCategory(
            "Wallpaper", "A picture behind the guide, menus and settings, darkened so text stays easy to read.",
            buildList {
                add(SettingItem("Wallpaper", "None, a built-in design, or the artwork of the programme you've highlighted. (Movies and Shows always show the highlighted title's artwork.)", wallModes,
                    wallModes.firstOrNull { it.value == palette.wallpaper } ?: wallModes[0]) { c -> vm.setWallpaper(((c as Choice).value as Int), palette.wallpaperStyle, palette.wallpaperDim) })
                if (palette.wallpaper == 1) add(SettingItem("Design", "Which built-in wallpaper.", wallStyles,
                    wallStyles[palette.wallpaperStyle.coerceIn(0, wallStyles.lastIndex)]) { c -> vm.setWallpaper(1, (c as Choice).value as Int, palette.wallpaperDim) })
                if (palette.wallpaper != 0) add(SettingItem("Dimming", "How dark the wallpaper is under the menus and text.", dims,
                    dims.firstOrNull { it.value == (palette.wallpaperDim * 100).roundToInt() / 10 * 10 } ?: dims[3]) { c ->
                    vm.setWallpaper(palette.wallpaper, palette.wallpaperStyle, ((c as Choice).value as Int) / 100f)
                })
            },
        ),
        SettingCategory(
            "TV guide", "How channels are shown in the guide and the channel list.",
            listOf(
                SettingItem("Channel numbers", "Show each channel's number next to its name.", onOff,
                    if (palette.showNumbers) onOff[0] else onOff[1]) { c -> vm.setShowNumbers((c as Choice).value == true) },
                SettingItem("Progress bars", "The bar showing how far through each programme on now is: on every channel, only the channel you're watching, or none.", progressChoices,
                    progressChoices[palette.progressBars.coerceIn(0, 2)]) { c -> vm.setProgressBars((c as Choice).value as Int) },
                SettingItem("Corners", "Rounded or square corners on the guide's programmes and channel names.", corners,
                    if (palette.squareCorners) corners[1] else corners[0]) { c -> vm.setSquareCorners((c as Choice).value == true) },
            ),
        ),
        SettingCategory(
            "Menu", "What the side menu offers.",
            listOf(
                SettingItem("Movies & Shows", "Turn off to use Viewarr for live TV only: Movies and Shows leave the menu.", onOff,
                    if (palette.hideMedia) onOff[1] else onOff[0]) { c -> vm.setHideMedia((c as Choice).value != true) },
            ),
        ),
    )
    SettingsPage(title = "Appearance", categories = categories, onBack = onBack, onReset = {
        vm.apply(ThemePresets[0].main, ThemePresets[0].highlight); vm.setTransparency(0f)
        vm.setShowNumbers(true); vm.setSquareCorners(true); vm.setHideMedia(false); vm.setWallpaper(0, 0, 0.6f)
    })
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

private val progressChoices = listOf(Choice("All channels", 0), Choice("Only what you're watching", 1), Choice("Off", 2))
