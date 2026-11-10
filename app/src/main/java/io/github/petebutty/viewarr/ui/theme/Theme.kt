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
data class Palette(val main: Color, val highlight: Color, val transparency: Float = 0f, /** Guide: how see-through the channel names and programmes are (0 solid … 1 clear). */ val guideTransparency: Float = 0f, val showNumbers: Boolean = true, val squareCorners: Boolean = true, val hideMedia: Boolean = false, /** Guide progress bars: 0 every channel, 1 only the one you're watching, 2 none. */ val progressBars: Int = 0,
    /** Picture behind pages: 0 none, 1 built-in, 2 from your library, 3 what's selected. */ val wallpaper: Int = 0,
    /** Which built-in wallpaper (WallpaperStyles). */ val wallpaperStyle: Int = 0,
    /** How much the background colour covers the wallpaper (0.3 light … 0.9 dark). */ val wallpaperDim: Float = 0.6f,
    /** Title pages: the parts switched off in Settings → Appearance (TitleParts ids). */ val titleHidden: Set<String> = emptySet(),
    /** Small display / behaviour options (UiOpts names → chosen index; 0 = the default). */ val opts: Map<String, Int> = emptyMap()) {
    fun shows(part: String) = part !in titleHidden
    fun opt(name: String) = opts[name] ?: UiOpts.defaults[name] ?: 0
    /** Low-memory mode: the heavy extras are off whatever their own settings say. */
    val lowMemory get() = opt(UiOpts.LOW_MEMORY) == 1
    val guideArt get() = opt(UiOpts.GUIDE_ART) == 0 && !lowMemory
    /** The wallpaper in use: none in low-memory mode (plain Viewarr colours), whatever's chosen. */
    val wallpaperMode get() = if (lowMemory) 0 else wallpaper
    /** Trailer previews, multi-view, title-page artwork: not in low-memory mode. */
    val heavyExtras get() = !lowMemory
    val channelLogos get() = opt(UiOpts.CHANNEL_LOGOS) == 0
    val sourceBadges get() = opt(UiOpts.BADGE_SOURCE) == 0
    val progressBadges get() = opt(UiOpts.BADGE_PROGRESS) == 0
    val serverPreviews get() = opt(UiOpts.SEEK_PREVIEWS) != 2
    val boxPreviews get() = opt(UiOpts.SEEK_PREVIEWS) == 0 && !lowMemory
    /** Player controls (seek bar area and buttons) scale: Medium, Small, Large (the original size). */
    val controlsScale get() = floatArrayOf(0.8f, 0.65f, 1f)[opt(UiOpts.CONTROLS_SIZE).coerceIn(0, 2)]
    val controlsMs get() = longArrayOf(4_000, 3_000, 6_000, 10_000)[opt(UiOpts.CONTROLS_TIME).coerceIn(0, 3)]
    val upNextSeconds get() = intArrayOf(10, 5, 20)[opt(UiOpts.UP_NEXT).coerceIn(0, 2)]
    val textScale get() = floatArrayOf(1f, 0.9f, 1.12f, 1.25f)[opt(UiOpts.TEXT_SIZE).coerceIn(0, 3)]
    /** Hours of no button presses on Live TV before "Still watching?" (0 = never). */
    val stillWatchingHours get() = intArrayOf(0, 2, 3, 4, 6)[opt(UiOpts.STILL_WATCHING).coerceIn(0, 4)]
    // The sliders' percentages as they look over a TV picture: a straight 1 - t left a dark panel looking all but
    // clear from about 50% (and invisible at 100%, the text on the bare picture). A curve keeps the middle of the
    // slider useful and leaves a trace of the panel at 100% so text stays readable.
    val menuAlpha = 1f - 0.9f * transparency * transparency
    val guideAlpha = 1f - 0.9f * guideTransparency * guideTransparency
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
    val Scrim get() = if (palette.wallpaperMode != 0) palette.main.copy(alpha = palette.wallpaperDim) else palette.main
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

/** What a title page can show, each switchable in Settings → Appearance. Off: not shown, and not fetched either. */
object TitleParts {
    /** Ratings: the badge names (RatingBadge.source; TMDB's own is "TMDB"). */
    val ratings = listOf("TMDB" to "TMDB", "IMDb" to "IMDb", "RT" to "Rotten Tomatoes (critics)", "Audience" to "Rotten Tomatoes (audience)",
        "Metacritic" to "Metacritic", "Letterboxd" to "Letterboxd", "Trakt" to "Trakt")
    fun rating(source: String) = "rating:$source"
    const val LOGO = "logo"
    const val UP_NEXT = "upnext"
    const val TRAILER = "trailer"
    const val EPISODES = "episodes"
    const val CAST = "cast"
    const val REVIEWS = "reviews"
    const val COLLECTION = "collection"
    const val SIMILAR = "similar"
    const val DETAILS = "details"
    const val BADGES = "badges"
    val sections = listOf(
        BADGES to ("Badges" to "Age rating, picture (4K, HDR, Dolby Vision), sound (Dolby Atmos, 5.1…), subtitles, and the time a film would end."),
        LOGO to ("Title logo" to "The title's logo instead of its name in plain text."),
        UP_NEXT to ("Up next line" to "Shows: the episode you're up to, above the synopsis."),
        TRAILER to ("Trailer button" to "Off: the trailer isn't looked up either."),
        EPISODES to ("Seasons & episodes row" to "Shows: the seasons and episodes on the page (Play and holding OK still work)."),
        CAST to ("Cast & crew" to "The cast & crew tab."),
        REVIEWS to ("Reviews" to "The reviews tab. Off: Trakt's reviews aren't fetched."),
        COLLECTION to ("Collection" to "Films in a series: the collection tab."),
        SIMILAR to ("More like this" to "The more like this tab."),
        DETAILS to ("Details" to "The details tab (released, runtime, studio, language…)."),
    )
}

/** Small display / behaviour settings (Appearance and Playback), each a list of choices; the first is the default. */
object UiOpts {
    const val TEXT_SIZE = "text_size"
    const val CLOCK = "clock"
    const val BADGE_SOURCE = "badge_source"
    const val BADGE_PROGRESS = "badge_progress"
    const val GUIDE_ART = "guide_art"
    const val CHANNEL_LOGOS = "channel_logos"
    const val MINI_SIZE = "mini_size"
    const val MINI_CORNER = "mini_corner"
    const val PLAYER_BACK = "player_back"
    const val AGE_RATING = "age_rating"
    const val BUTTON_HINTS = "button_hints"
    const val ANIM_FILM = "anim_film"
    const val ANIM_LIVE = "anim_live"
    const val SEEK_PREVIEWS = "seek_previews"
    const val CONTROLS_TIME = "controls_time"
    const val CONTROLS_SIZE = "controls_size"
    const val UP_NEXT = "up_next"
    const val STILL_WATCHING = "still_watching"
    const val LOW_MEMORY = "low_memory"
    const val DATE_ORDER = "date_order"
    const val TIME_FORMAT = "time_format"
    /** Defaults other than the first choice (anyone who never chose). Stored choices are kept as they are. */
    val defaults = mapOf(
        // Back closes the film, holding Back puts it in the corner (since 0.23; was: Back = corner).
        PLAYER_BACK to 2,
    )
    val choices = mapOf(
        TEXT_SIZE to listOf("Normal", "Small", "Large", "Extra large"),
        CLOCK to listOf("Off", "On"),
        BADGE_SOURCE to listOf("On", "Off"),
        BADGE_PROGRESS to listOf("On", "Off"),
        GUIDE_ART to listOf("On", "Off"),
        CHANNEL_LOGOS to listOf("On", "Off"),
        MINI_SIZE to listOf("Medium", "Small", "Large"),
        MINI_CORNER to listOf("Bottom right", "Bottom left", "Top right", "Top left"),
        ANIM_FILM to listOf("On", "Off"),
        ANIM_LIVE to listOf("On", "Off"),
        BUTTON_HINTS to listOf("First few times", "Always", "Off"),
        AGE_RATING to listOf("UK (BBFC: U, PG, 12A, 15, 18)", "US (MPA: G, PG, PG-13, R · TV-14, TV-MA)"),
        PLAYER_BACK to listOf("Press Back", "Off (Back stops the film)", "Hold Back (recommended; Back stops the film)"),
        SEEK_PREVIEWS to listOf("Server's, else the box makes them", "Server's only", "Off"),
        CONTROLS_TIME to listOf("4 seconds", "3 seconds", "6 seconds", "10 seconds"),
        CONTROLS_SIZE to listOf("Medium", "Small", "Large"),
        UP_NEXT to listOf("10 seconds", "5 seconds", "20 seconds"),
        STILL_WATCHING to listOf("Never", "After 2 hours", "After 3 hours", "After 4 hours", "After 6 hours"),
        LOW_MEMORY to listOf("Off", "On"),
        DATE_ORDER to listOf("Day first (Tue 10 Nov)", "Month first (Tue, Nov 10)"),
        TIME_FORMAT to listOf("24-hour (19:30)", "12-hour (7:30 pm)"),
    )
}
