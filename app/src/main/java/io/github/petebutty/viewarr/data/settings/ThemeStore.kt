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
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appearanceStore by preferencesDataStore(name = "appearance")

@Singleton
class ThemeStore @Inject constructor(@ApplicationContext private val context: Context) {

    val palette: Flow<Palette> = context.appearanceStore.data.map { prefs ->
        Palette(
            main = prefs[MAIN]?.let { Color(it) } ?: ThemePresets[0].main,
            highlight = prefs[HIGHLIGHT]?.let { Color(it) } ?: ThemePresets[0].highlight,
            transparency = prefs[TRANSPARENCY] ?: 0f,
            showNumbers = prefs[NUMBERS] ?: true,
            squareCorners = prefs[SQUARE] ?: true,
            hideMedia = prefs[HIDE_MEDIA] ?: false,
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

    suspend fun saveHideMedia(on: Boolean) {
        context.appearanceStore.edit { it[HIDE_MEDIA] = on }
    }

    suspend fun saveSquareCorners(on: Boolean) {
        context.appearanceStore.edit { it[SQUARE] = on }
    }

    suspend fun saveShowNumbers(on: Boolean) {
        context.appearanceStore.edit { it[NUMBERS] = on }
    }

    private companion object {
        val NUMBERS = androidx.datastore.preferences.core.booleanPreferencesKey("show_numbers")
        val SQUARE = androidx.datastore.preferences.core.booleanPreferencesKey("square_corners")
        val HIDE_MEDIA = androidx.datastore.preferences.core.booleanPreferencesKey("hide_media")
        val MAIN = intPreferencesKey("main")
        val HIGHLIGHT = intPreferencesKey("highlight")
        val TRANSPARENCY = floatPreferencesKey("menu_transparency")
    }
}
