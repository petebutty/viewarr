package io.github.petebutty.viewarr.ui.launcher

import androidx.compose.ui.graphics.Color
import io.github.petebutty.viewarr.BuildConfig
import io.github.petebutty.viewarr.ui.theme.Palette
import io.github.petebutty.viewarr.ui.theme.UiOpts

/**
 * The launcher build's one look: no themes or wallpapers to choose. A dark background with the highlighted title's
 * artwork in the top-right corner, faded into it, behind every page (appBackground); the orange highlight. The
 * colour, wallpaper and glass-colour settings aren't listed (SettingsMap); whatever was chosen in them is ignored.
 */
internal object LauncherLook {
    val on: Boolean get() = BuildConfig.LAUNCHER
    private val Background = Color(0xFF0B0E14)
    private val Highlight = Color(0xFFF97316)

    /** Settings that choose a look ("Source/Section/Title"; every one in Appearance → Wallpaper too). */
    private val hidden = setOf(
        "Appearance/Colours & text/Background colour",
        "Appearance/Colours & text/Highlight colour",
        "Appearance/Transparency/Glass colour",
        "Appearance/Transparency/Wallpaper dimming",
    )

    /**
     * Whole sections Previewarr leaves out (2026-11-25: Viewarr's options had become hard to follow; the basics only):
     * glass, the menu (it has none), the news ticker, browsing style, posters (its rows are always wide), title-page
     * parts, ratings order, and Menu & navigation's start / menu / poster-row choices. The TV guide's look, the info
     * bars, auto-play, subtitles, audio and video stay. Hidden settings keep their defaults.
     */
    private val hiddenSections = listOf(
        "Appearance/Wallpaper/", "Appearance/Transparency/", "Appearance/Main menu/", "Appearance/News ticker/",
        "Appearance/Browsing style/", "Appearance/Posters/", "Appearance/Title pages/", "Appearance/Ratings/",
        "Navigation/Start/", "Navigation/Menu/", "Navigation/Main menu/", "Navigation/Poster rows/",
    )

    fun hides(key: String): Boolean = on && (key in hidden || hiddenSections.any { key.startsWith(it) })

    /** The chosen look, with the launcher's in place of the theme and wallpaper parts (Viewarr: as chosen). */
    fun fix(p: Palette): Palette = if (!on) p else p.copy(
        main = Background, highlight = Highlight, wallpaper = 0,
        // (Its rows always wide, 16:9, as its home screen: no Posters / Wide choice.)
        opts = p.opts - UiOpts.TITLE_BG - UiOpts.WALL_SHADE - UiOpts.GUIDE_WALL - UiOpts.GLASS_TINT - UiOpts.SNOW + (UiOpts.ROW_CARDS to 1),
    )
}
