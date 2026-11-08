package io.github.petebutty.viewarr.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

/** Every shade is derived from two user-chosen colours so any combination stays coherent. */
data class Palette(val main: Color, val highlight: Color, val transparency: Float = 0f, val showNumbers: Boolean = true, val squareCorners: Boolean = true, val hideMedia: Boolean = false, /** Guide progress bars: 0 every channel, 1 only the one you're watching, 2 none. */ val progressBars: Int = 0,
    /** Picture behind pages: 0 none, 1 built-in, 2 from your library, 3 what's selected. */ val wallpaper: Int = 0,
    /** Which built-in wallpaper (WallpaperStyles). */ val wallpaperStyle: Int = 0,
    /** How much the background colour covers the wallpaper (0.3 light … 0.9 dark). */ val wallpaperDim: Float = 0.6f) {
    val menuAlpha = 1f - transparency
    val panel = lerp(main, Color.White, 0.07f)
    val panelAlt = lerp(main, Color.White, 0.04f)
    val cellPast = lerp(main, Color.White, 0.025f)
    val cellFuture = lerp(main, Color.White, 0.08f)
    val cellCurrent = lerp(main, Color.White, 0.15f)
    val rowSelected = lerp(main, Color.White, 0.12f)
    val previewBox = lerp(main, Color.Black, 0.35f)
    val onHighlight = if (highlight.luminance() > 0.5f) Color(0xFF0B1220) else Color.White
}

data class ThemePreset(val name: String, val main: Color, val highlight: Color)

val ThemePresets = listOf(
    // Default. True black: OLED TVs switch those pixels off (deeper blacks, no glow around menus).
    ThemePreset("OLED black", Color(0xFF000000), Color(0xFF00E5FF)),
    // The logo's colours (near-black, aperture cyan); and its pink half.
    ThemePreset("Viewarr", Color(0xFF0E1016), Color(0xFF00E5FF)),
    ThemePreset("Viewarr pink", Color(0xFF0E1016), Color(0xFFFF3366)),
    ThemePreset("Classic blue", Color(0xFF1B2766), Color(0xFF22C7E8)),
    ThemePreset("Midnight", Color(0xFF121418), Color(0xFF3D8BFD)),
    ThemePreset("Ocean", Color(0xFF0D3640), Color(0xFF2DD4BF)),
    ThemePreset("Royal purple", Color(0xFF2A1B4D), Color(0xFFC084FC)),
    ThemePreset("Ember", Color(0xFF2B1515), Color(0xFFF97316)),
    ThemePreset("Forest", Color(0xFF10301F), Color(0xFF4ADE80)),
    ThemePreset("Sunset", Color(0xFF2D1520), Color(0xFFFB923C)),
    ThemePreset("Slate", Color(0xFF2E2E36), Color(0xFF93C5FD)),
    ThemePreset("Nord", Color(0xFF2E3440), Color(0xFF88C0D0)),
    ThemePreset("Dracula", Color(0xFF282A36), Color(0xFFFF79C6)),
    ThemePreset("Mocha", Color(0xFF2A211C), Color(0xFFE0B080)),
    ThemePreset("Rose gold", Color(0xFF2A1A1E), Color(0xFFF4A6B8)),
    ThemePreset("Gold", Color(0xFF14120C), Color(0xFFFACC15)),
    ThemePreset("High contrast", Color(0xFF000000), Color(0xFFFFFFFF)),
)

val MainColourChoices = listOf(
    Color(0xFF0E1016), Color(0xFF1B2766), Color(0xFF121418), Color(0xFF0D3640), Color(0xFF2A1B4D),
    Color(0xFF2B1515), Color(0xFF10301F), Color(0xFF2E2E36), Color(0xFF3A1D33), Color(0xFF000000),
)

val HighlightChoices = listOf(
    Color(0xFF00E5FF), Color(0xFFFF3366), Color(0xFF22C7E8), Color(0xFF3D8BFD), Color(0xFF2DD4BF), Color(0xFFC084FC),
    Color(0xFFF97316), Color(0xFF22C55E), Color(0xFFFACC15), Color(0xFFF43F5E),
)

object AppColors {
    var palette by mutableStateOf(Palette(ThemePresets[0].main, ThemePresets[0].highlight))

    val Background get() = palette.main
    /** The background colour as laid over a wallpaper: see-through when one is on, solid otherwise. */
    val Scrim get() = if (palette.wallpaper != 0) palette.main.copy(alpha = palette.wallpaperDim) else palette.main
    val Panel get() = palette.panel
    val PanelAlt get() = palette.panelAlt
    val Accent get() = palette.highlight
    val OnAccent get() = palette.onHighlight
    val CellFuture get() = palette.cellFuture
    val CellPast get() = palette.cellPast
    val CellCurrent get() = palette.cellCurrent
    val RowSelected get() = palette.rowSelected
    val PreviewBox get() = palette.previewBox
    val MenuPanel get() = palette.panel.copy(alpha = palette.menuAlpha)
    val MenuPanelAlt get() = palette.panelAlt.copy(alpha = palette.menuAlpha)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFBFC6DA)
    val Error = Color(0xFFFF8A80)
}

@Composable
fun ViewarrTheme(content: @Composable () -> Unit) {
    val p = AppColors.palette
    M3Theme(
        colorScheme = m3DarkColorScheme(
            primary = p.highlight,
            onPrimary = p.onHighlight,
            background = p.main,
            surface = p.panel,
            onSurface = AppColors.TextPrimary,
            onSurfaceVariant = AppColors.TextSecondary,
            error = AppColors.Error,
        )
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = p.highlight,
                onPrimary = p.onHighlight,
                background = p.main,
                surface = p.panel,
                onSurface = AppColors.TextPrimary,
                onSurfaceVariant = AppColors.TextSecondary,
                error = AppColors.Error,
            ),
            content = content,
        )
    }
}
