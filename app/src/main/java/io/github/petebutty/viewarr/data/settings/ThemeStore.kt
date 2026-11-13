package io.github.petebutty.viewarr.data.settings

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.petebutty.viewarr.ui.theme.Palette
import io.github.petebutty.viewarr.ui.theme.ThemePresets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.appearanceStore by preferencesDataStore(name = "appearance")

@Singleton
class ThemeStore @Inject constructor(@ApplicationContext private val context: Context) {

    init {
        // Once, on updating to the glass design (0.26): everyone moves to the Viewarr theme, whatever colours they
        // had picked before (the highlight can be changed again afterwards).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            // (Written to the log as it stands before any change: how the Viewarr theme's values were taken.)
            context.appearanceStore.edit { p ->
                if ((p[THEME_VERSION] ?: 0) >= 26) return@edit
                p[MAIN] = ThemePresets[0].main.toArgb()
                p[HIGHLIGHT] = ThemePresets[0].highlight.toArgb()
                // The rest of the Viewarr look: glass, wallpaper, the info bar's glass, lettering.
                p[TRANSPARENCY] = 0.5f
                p[GUIDE_TRANSPARENCY] = 0.7f
                p[WALLPAPER] = 3
                p[WALLPAPER_STYLE] = 0
                p[WALLPAPER_DIM] = 0.7f
                p[PROGRESS] = 1
                p[NUMBERS] = false
                VIEWARR_LOOK.forEach { (name, v) -> p[intPreferencesKey("opt_$name")] = v }
                p[THEME_VERSION] = 26
            }
        }
    }

    val palette: Flow<Palette> = context.appearanceStore.data.map { prefs ->
        Palette(
            main = prefs[MAIN]?.let { Color(it) } ?: ThemePresets[0].main,
            highlight = prefs[HIGHLIGHT]?.let { io.github.petebutty.viewarr.ui.theme.OldHighlights[it] ?: Color(it) } ?: ThemePresets[0].highlight,
            transparency = prefs[TRANSPARENCY] ?: 0f,
            guideTransparency = prefs[GUIDE_TRANSPARENCY] ?: 0f,
            showNumbers = prefs[NUMBERS] ?: true,
            // Settings → Appearance → Live TV → Programme corners (rounded unless chosen).
            squareCorners = prefs[SQUARE] ?: false,
            hideMedia = prefs[HIDE_MEDIA] ?: false,
            progressBars = prefs[PROGRESS] ?: 0,
            // 2 was "From your library" (removed): None.
            wallpaper = (prefs[WALLPAPER] ?: 0).takeIf { it != 2 } ?: 0,
            wallpaperStyle = prefs[WALLPAPER_STYLE] ?: 0,
            wallpaperDim = prefs[WALLPAPER_DIM] ?: 0.6f,
            titleHidden = prefs[TITLE_HIDDEN] ?: emptySet(),
            menuOrder = prefs[MENU_ORDER]?.split('\u001F')?.filter { it.isNotEmpty() }.orEmpty(),
            ratingsOrder = prefs[RATINGS_ORDER]?.split('\u001F')?.filter { it.isNotEmpty() }.orEmpty(),
            barOrder = prefs.asMap().entries.mapNotNull { (k, v) -> if (k.name.startsWith("bar_") && v is String) k.name.removePrefix("bar_") to v.split('\u001F').filter { it.isNotEmpty() } else null }.toMap(),
            opts = prefs.asMap().entries.mapNotNull { (k, v) -> if (k.name.startsWith("opt_") && v is Int) k.name.removePrefix("opt_") to v else null }.toMap(),
        )
    }

    suspend fun save(main: Color, highlight: Color) {
        context.appearanceStore.edit {
            it[MAIN] = main.toArgb()
            it[HIGHLIGHT] = highlight.toArgb()
        }
    }

    suspend fun saveTransparency(value: Float) {
        context.appearanceStore.edit { it[TRANSPARENCY] = value }
    }

    suspend fun saveGuideTransparency(value: Float) {
        context.appearanceStore.edit { it[GUIDE_TRANSPARENCY] = value }
    }

    /** The ratings' order on title pages. */
    suspend fun saveRatingsOrder(ids: List<String>) {
        context.appearanceStore.edit { it[RATINGS_ORDER] = ids.joinToString("\u001F") }
    }

    /** An info bar's button order ("live", "film"; hold OK on a button → move). */
    suspend fun saveBarOrder(bar: String, ids: List<String>) {
        context.appearanceStore.edit { it[androidx.datastore.preferences.core.stringPreferencesKey("bar_$bar")] = ids.joinToString("\u001F") }
    }

    /** The main menu's order (hold OK in the menu → move). */
    suspend fun saveMenuOrder(ids: List<String>) {
        context.appearanceStore.edit { it[MENU_ORDER] = ids.joinToString("\u001F") }
    }

    suspend fun saveHideMedia(on: Boolean) {
        context.appearanceStore.edit { it[HIDE_MEDIA] = on }
    }

    suspend fun saveSquareCorners(on: Boolean) {
        context.appearanceStore.edit { it[SQUARE] = on }
    }

    suspend fun saveProgressBars(mode: Int) {
        context.appearanceStore.edit { it[PROGRESS] = mode }
    }

    suspend fun saveWallpaper(mode: Int, style: Int, dim: Float) {
        context.appearanceStore.edit {
            it[WALLPAPER] = mode
            it[WALLPAPER_STYLE] = style
            it[WALLPAPER_DIM] = dim
        }
    }

    /** Title pages: switch one part (TitleParts) on or off. */
    suspend fun saveTitlePart(part: String, on: Boolean) {
        context.appearanceStore.edit { val now = it[TITLE_HIDDEN] ?: emptySet(); it[TITLE_HIDDEN] = if (on) now - part else now + part }
    }

    /** A UiOpts setting (its chosen index). */
    suspend fun saveOpt(name: String, value: Int) {
        context.appearanceStore.edit { it[intPreferencesKey("opt_$name")] = value }
    }

    suspend fun resetOpts(names: Collection<String>) {
        context.appearanceStore.edit { p -> names.forEach { p.remove(intPreferencesKey("opt_$it")) } }
    }

    suspend fun resetTitleParts() {
        // (The main menu's, the info bars' and the hold-OK pop-up's choices, kept in the same list, stay.)
        context.appearanceStore.edit { p -> p[TITLE_HIDDEN] = (p[TITLE_HIDDEN] ?: emptySet()).filter { it.startsWith("menu:") || it.startsWith("bar:") || it.startsWith("hold:") }.toSet() }
    }

    suspend fun saveShowNumbers(on: Boolean) {
        context.appearanceStore.edit { it[NUMBERS] = on }
    }

    private companion object {
        val NUMBERS = androidx.datastore.preferences.core.booleanPreferencesKey("show_numbers")
        val SQUARE = androidx.datastore.preferences.core.booleanPreferencesKey("square_corners")
        val HIDE_MEDIA = androidx.datastore.preferences.core.booleanPreferencesKey("hide_media")
        val MAIN = intPreferencesKey("main")
        /** The Viewarr look's display options (UiOpts): its info bar, lettering and guide details. */
        private val VIEWARR_LOOK = mapOf(
            "font" to 4, "text_size" to 1, "banner_glass" to 1, "banner_border" to 0, "banner_badges" to 0,
            "banner_episode" to 1, "banner_number" to 1, "catchup_icons" to 1, "clock" to 0,
        )
        /** The theme migration last applied (26: the glass design's Viewarr theme). */
        val THEME_VERSION = intPreferencesKey("theme_version")
        val HIGHLIGHT = intPreferencesKey("highlight")
        val TRANSPARENCY = floatPreferencesKey("menu_transparency")
        val GUIDE_TRANSPARENCY = floatPreferencesKey("guide_transparency")
        val PROGRESS = intPreferencesKey("guide_progress_bars")
        val WALLPAPER = intPreferencesKey("wallpaper")
        val WALLPAPER_STYLE = intPreferencesKey("wallpaper_style")
        val WALLPAPER_DIM = floatPreferencesKey("wallpaper_dim")
        val TITLE_HIDDEN = androidx.datastore.preferences.core.stringSetPreferencesKey("title_hidden")
        val MENU_ORDER = androidx.datastore.preferences.core.stringPreferencesKey("menu_order")
        val RATINGS_ORDER = androidx.datastore.preferences.core.stringPreferencesKey("ratings_order")
    }
}
