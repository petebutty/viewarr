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
    /** The main menu's order (between Live TV and Settings): "Movies", "svc:Netflix", "Live now"… (hold OK in the menu to edit). */ val menuOrder: List<String> = emptyList(),
    /** The ratings' order on title pages ("IMDb", "RT", "Trakt"…; Settings → Appearance → Ratings). */ val ratingsOrder: List<String> = emptyList(),
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
    /** Settings → Playback → Next episode countdown: 0–180 s in 5 s steps (the older four-choice setting carries over). */
    /** Settings → Remote buttons → Live TV → Channel change delay, in milliseconds. */
    val zapDelayMs get() = opt(UiOpts.ZAP_DELAY).coerceIn(0, 20) * 100L
        val upNextSeconds get() = (opts[UiOpts.UP_NEXT_S] ?: opts[UiOpts.UP_NEXT]?.let { intArrayOf(20, 10, 30, 60)[it.coerceIn(0, 3)] / 5 } ?: 4).coerceIn(0, 36) * 5
    val textScale get() = floatArrayOf(1f, 0.9f, 1.12f, 1.25f)[opt(UiOpts.TEXT_SIZE).coerceIn(0, 3)]
    /** Settings → Playback → TV guide refresh: how old the guide may get (null: only Refresh channels & guide). */
    /** How long to rest on a channel browsed in the info bar before it changes (null: only on OK). */
    val browseDelayMs: Long? get() = when (opt(UiOpts.BROWSE_DELAY)) { 1 -> 300L; 2 -> 800L; 3 -> 1_000L; 4 -> 1_500L; 5 -> 2_000L; 6 -> null; else -> 500L }
    val guideRefreshMs: Long? get() = longArrayOf(4, 2, 8, 12, 24, 0)[opt(UiOpts.GUIDE_REFRESH).coerceIn(0, 5)].takeIf { it > 0 }?.times(3_600_000L)
    /** Title pages: the ratings in the order chosen (Settings → Appearance → Ratings). */
    fun ratingRank(source: String): Int = ratingsOrder.indexOf(source).let { if (it < 0) 100 + TitleParts.ratings.indexOfFirst { r -> r.first == source }.coerceAtLeast(0) else it }
    /** Settings → Appearance → Glass colour: a pastel the glass is tinted with (null: plain). */
    val glassTint: Color? get() = GlassTints.getOrNull(opt(UiOpts.GLASS_TINT))?.second
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

/** Pastel tints for the glass (Settings → Appearance → Glass colour); the first is plain. */
// Deep, rich colours: mixed into the dark glass they read as clearly different tints (pale pastels all came out
// as much the same grey).
val GlassTints: List<Pair<String, Color?>> = listOf(
    "Plain" to null,
    "Midnight blue" to Color(0xFF1E3A8A),
    "Ocean" to Color(0xFF1E6FB8),
    "Teal" to Color(0xFF0F8B8D),
    "Emerald" to Color(0xFF16A34A),
    "Forest" to Color(0xFF3F6212),
    "Violet" to Color(0xFF6D28D9),
    "Plum" to Color(0xFF86198F),
    "Rose" to Color(0xFFBE185D),
    "Ruby" to Color(0xFFB91C1C),
    "Amber" to Color(0xFFC2700C),
    "Bronze" to Color(0xFF8A6A3B),
    "Slate" to Color(0xFF475569),
)

val ThemePresets = listOf(
    // The one theme for now: Viewarr's charcoal glass with the orange highlight (the rest of the look is set with it:
    // ThemeStore's VIEWARR_LOOK).
    ThemePreset("Viewarr", Color(0xFF0E1016), Color(0xFFF97316)),
)

val MainColourChoices = listOf(
    Color(0xFF0E1016), Color(0xFF1B2766), Color(0xFF121418), Color(0xFF0D3640), Color(0xFF2A1B4D),
    Color(0xFF2B1515), Color(0xFF10301F), Color(0xFF2E2E36), Color(0xFF3A1D33), Color(0xFF000000),
)

// Softer than the first set (the brightest glared on the glass); Orange, the Viewarr highlight, stays as it was.
val HighlightChoices = listOf(
    Color(0xFF2AAFC4), Color(0xFFE0577A), Color(0xFF4A9FD4), Color(0xFF5B82D6), Color(0xFF2E9E91), Color(0xFFA47ED8),
    Color(0xFFF97316), Color(0xFF3E9E66), Color(0xFFD9A93A), Color(0xFFD9566C),
)

/** The first set's brighter highlights, each to its softer one (a highlight picked before still counts as chosen). */
val OldHighlights: Map<Int, Color> = listOf(
    0xFF00E5FF, 0xFFFF3366, 0xFF22C7E8, 0xFF3D8BFD, 0xFF2DD4BF, 0xFFC084FC, 0xFFF97316, 0xFF22C55E, 0xFFFACC15, 0xFFF43F5E,
).withIndex().associate { (i, v) -> v.toInt() to HighlightChoices[i] }

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
    val MenuPanel get() = tinted(palette.panel).copy(alpha = palette.menuAlpha)
    /** A glass colour with Settings → Appearance → Glass colour's pastel mixed in. */
    fun tinted(c: Color): Color = palette.glassTint?.let { androidx.compose.ui.graphics.lerp(c, it, 0.35f) } ?: c
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
    /** Settings → Appearance → Font: one of the box's own fonts for all text. */
    const val FONT = "font"
    /** The info bar's glass (and every pop-up's): how dark, and its thin border. */
    const val GLASS_TINT = "glass_tint"
    /** How often the TV guide is fetched again while Viewarr is open (and on starting). */
    const val GUIDE_REFRESH = "guide_refresh"
    /** The guide and channel list: "S15E4" after programme names, or not. */
    const val GUIDE_EPISODE = "guide_episode"
    /** The menu: a heading on each list ("LIVE TV · CHANNEL GROUPS", "MOVIES"…), so groups and libraries aren't confused. */
    const val MENU_HEADINGS = "menu_headings"
    /** The Live TV heading names the source (Dispatcharr, an IPTV login…) when there are two or more. */
    const val MENU_HEAD_SOURCE = "menu_head_source"
    /** A TV mark before each channel group's name. */
    const val MENU_GROUP_ICON = "menu_group_icon"
    /** A thin bar down a menu list's edge in its section's colour (Live TV red, Movies blue…). */
    const val MENU_EDGE = "menu_edge"
    /** A menu list's glass faintly tinted in its section's colour. */
    const val MENU_TINT = "menu_tint"
    /** In a section's list, the main list kept as a narrow column of icons (the section you're in lit). */
    const val MENU_RAIL = "menu_rail"
    /** A red "● LIVE" on the channel groups' All channels. */
    const val MENU_LIVE_MARK = "menu_live_mark"
    /** The menu's "Right › open its list" / "‹ Left: main menu" hints. */
    const val MENU_HINTS = "menu_hints"
    /** Browsing channels in the info bar (Remote buttons: "Next channel in the info bar"): how long to rest on one before it changes. */
    const val BROWSE_DELAY = "browse_delay"
    /** Music: lyrics from LRCLIB when the song's own server has none (sends the artist, title, album and length). */
    const val MUSIC_LRCLIB = "music_lrclib"
    /** Music: the visualiser's look. */
    const val MUSIC_VIS = "music_vis"
    /** Music: Now Playing as a screensaver after a while without a press (big artwork and lyrics). */
    const val MUSIC_SAVER = "music_saver"
    /** Music: lyrics on Now Playing. */
    const val MUSIC_LYRICS = "music_lyrics"
    /** Music: how Now Playing shows the artwork (a cover, a spinning record or CD…). */
    const val MUSIC_ART = "music_art"
    const val BANNER_GLASS = "banner_glass"
    /** The glass's strength for the info bar and every pop-up, as a slider: 0 the clearest … 10 solid. */
    const val POPUP_GLASS = "popup_glass"
    const val BANNER_BORDER = "banner_border"
    const val CLOCK = "clock"
    const val BADGE_SOURCE = "badge_source"
    const val BADGE_PROGRESS = "badge_progress"
    const val GUIDE_ART = "guide_art"
    const val CHANNEL_LOGOS = "channel_logos"
    const val MINI_SIZE = "mini_size"
    const val MINI_CORNER = "mini_corner"
    /** Pressing towards the corner picture from the page's edge highlights it (Full screen / Close). */
    const val CORNER_FOCUS = "corner_focus"
    /** The TV guide, channel list and group list at their last / first entry: loop on round, jump to the other end, or stop. */
    const val LIST_ENDS = "list_ends2"
    /** A film or episode ending or stopped: stay where you were, or back to live TV. */
    const val AFTER_FILM = "after_film"
    const val PLAYER_BACK = "player_back"
    const val AGE_RATING = "age_rating"
    const val BUTTON_HINTS = "button_hints"
    const val ANIM_FILM = "anim_film"
    const val ANIM_LIVE = "anim_live"
    /** Live TV shortcuts: round, rounded squares (app icons), or wide tiles (wordmark logos). */
    const val SHORTCUT_STYLE = "shortcut_style"
    /** Live TV shortcuts: the poster rows only once you press Down (cleaner), or a dimmed preview always. */
    const val OSD_PEEK = "osd_peek"
    /** Live TV shortcuts: how big the round icons are. */
    const val SHORTCUT_SIZE = "shortcut_size"
    /** Live TV controls: how many poster rows Down reaches under the shortcuts (the rest: open the shortcut). */
    const val OSD_ROWS = "osd_rows"
    /** Movies / Shows home page: an addon's network rows ("Netflix · Popular"…) too, or only behind their shortcuts. */
    const val SET_ROWS_HOME = "set_rows_home"
    /** Live TV controls → Down → Down: poster rows (Continue watching, Recent channels, Recently added). */
    const val OSD_POSTERS = "osd_posters"
    /** Guide: the catch-up (replay) icon on programmes and channel names. */
    const val CATCHUP_ICONS = "catchup_icons"
    /** Live TV's info bar (the banner over the picture): channel number, S·E, its look, quality badges. */
    const val BANNER_NUMBER = "banner_number"
    const val BANNER_EPISODE = "banner_episode"
    const val BANNER_STYLE = "banner_style"
    const val BANNER_CORNERS = "banner_corners"
    const val BANNER_BADGES = "banner_badges"
    const val BANNER_FPS = "banner_fps"
    /** Live TV keeps playing in a corner while you browse from a shortcut (or any page, Live TV hub style). */
    const val LIVE_CORNER = "live_corner"
    const val SEEK_PREVIEWS = "seek_previews"
    const val CONTROLS_TIME = "controls_time"
    const val CONTROLS_SIZE = "controls_size"
    const val UP_NEXT = "up_next"
    const val UP_NEXT_S = "up_next_s"
    /** Live TV: how long a channel must stay chosen while zapping before its stream is asked for (tenths of a second). */
    const val ZAP_DELAY = "zap_delay"
    const val STILL_WATCHING = "still_watching"
    const val LOW_MEMORY = "low_memory"
    const val DATE_ORDER = "date_order"
    const val TIME_FORMAT = "time_format"
    /** Defaults other than the first choice (anyone who never chose). Stored choices are kept as they are. */
    val defaults = mapOf(
        // (The Viewarr look: light glass, as "Info bar: glass" Light was.)
        POPUP_GLASS to 2,
        UP_NEXT_S to 4,
        ZAP_DELAY to 5,
        // Back closes the film, holding Back puts it in the corner (since 0.23; was: Back = corner).
        PLAYER_BACK to 2,
    )
    val choices = mapOf(
        TEXT_SIZE to listOf("Normal", "Small", "Large", "Extra large"),
        FONT to listOf("Standard", "Condensed", "Light", "Medium", "Serif", "Thin", "Bold", "Condensed light", "Casual", "Typewriter", "Small caps", "Handwritten"),
        GLASS_TINT to GlassTints.map { it.first },
        GUIDE_EPISODE to listOf("On", "Off"),
        MENU_HEADINGS to listOf("Heading", "Off", "Path (Live TV › Channel groups)"),
        MENU_EDGE to listOf("Off", "On"),
        MENU_TINT to listOf("Off", "On"),
        MENU_RAIL to listOf("Off", "On"),
        MENU_LIVE_MARK to listOf("Off", "On"),
        MENU_HEAD_SOURCE to listOf("On", "Off"),
        MENU_GROUP_ICON to listOf("Off", "On"),
        MENU_HINTS to listOf("On", "Off"),
        MUSIC_LRCLIB to listOf("Off", "On"),
        MUSIC_VIS to listOf("Bars", "Mirrored bars", "Wave", "Dots", "Off"),
        MUSIC_SAVER to listOf("After 2 minutes", "After 5 minutes", "After 10 minutes", "Off"),
        MUSIC_LYRICS to listOf("On", "Off"),
        MUSIC_ART to listOf("Square cover", "Spinning vinyl record", "Spinning CD", "Round cover, turning slowly", "Cover with a reflection"),
        BROWSE_DELAY to listOf("0.5 seconds", "0.3 seconds", "0.8 seconds", "1 second", "1.5 seconds", "2 seconds", "Only when I press OK"),
        GUIDE_REFRESH to listOf("Every 4 hours", "Every 2 hours", "Every 8 hours", "Every 12 hours", "Once a day", "Only when I ask"),
        BANNER_GLASS to listOf("Medium", "Light", "Dark"),
        POPUP_GLASS to (0..10).map { if (it == 10) "Solid" else "${100 - (30 + it * 7)}% see-through" },
        BANNER_BORDER to listOf("On", "Off"),
        CLOCK to listOf("Off", "On"),
        BADGE_SOURCE to listOf("On", "Off"),
        BADGE_PROGRESS to listOf("On", "Off"),
        GUIDE_ART to listOf("On", "Off"),
        CHANNEL_LOGOS to listOf("On", "Off"),
        MINI_SIZE to listOf("Medium", "Small", "Large"),
        MINI_CORNER to listOf("Bottom right", "Bottom left", "Top right", "Top left"),
        CORNER_FOCUS to listOf("On", "Off"),
        LIST_ENDS to listOf("Jump to the other end", "Stop"),
        AFTER_FILM to listOf("Stay where you were", "Back to live TV"),
        ANIM_FILM to listOf("On", "Off"),
        ANIM_LIVE to listOf("On", "Off"),
        SHORTCUT_STYLE to listOf("Round", "Rounded squares", "Wide tiles"),
        OSD_PEEK to listOf("When you press Down", "Always show a preview"),
        SHORTCUT_SIZE to listOf("Small", "Medium", "Large"),
        OSD_ROWS to listOf("3", "2", "5", "All"),
        SET_ROWS_HOME to listOf("Only through their shortcuts", "On the home page too"),
        OSD_POSTERS to listOf("On", "Off"),
        CATCHUP_ICONS to listOf("Programmes and channels", "Channel names only", "Off"),
        BANNER_NUMBER to listOf("On", "Off"),
        BANNER_EPISODE to listOf("On", "Off"),
        BANNER_STYLE to listOf("Fade across the bottom", "Glass panel with a border", "Glass panel, no border", "Solid panel"),
        BANNER_CORNERS to listOf("Rounded", "Square"),
        BANNER_BADGES to listOf("HD · FHD · 4K", "720p · 1080p · 2160p", "Off"),
        BANNER_FPS to listOf("On", "Off"),
        LIVE_CORNER to listOf("On", "Off (browsing stops the channel)"),
        BUTTON_HINTS to listOf("First few times", "Always", "Off"),
        AGE_RATING to listOf("UK (BBFC: U, PG, 12A, 15, 18)", "US (MPA: G, PG, PG-13, R · TV-14, TV-MA)"),
        PLAYER_BACK to listOf("Back: mini player", "Off (Back stops it)", "Hold Back: mini player (Back stops it)"),
        SEEK_PREVIEWS to listOf("Server's, else the box makes them", "Server's only", "Off"),
        CONTROLS_TIME to listOf("4 seconds", "3 seconds", "6 seconds", "10 seconds"),
        CONTROLS_SIZE to listOf("Medium", "Small", "Large"),
        UP_NEXT to listOf("20 seconds", "10 seconds", "30 seconds", "60 seconds"),
        UP_NEXT_S to (0..36).map { if (it == 0) "No wait" else "${it * 5} seconds" },
        ZAP_DELAY to (0..20).map { if (it == 0) "No wait" else "%.1f seconds".format(it / 10f) },
        STILL_WATCHING to listOf("Never", "After 2 hours", "After 3 hours", "After 4 hours", "After 6 hours"),
        LOW_MEMORY to listOf("Off", "On"),
        DATE_ORDER to listOf("Day first (Tue 10 Nov)", "Month first (Tue, Nov 10)"),
        TIME_FORMAT to listOf("24-hour (19:30)", "12-hour (7:30 pm)"),
    )
}
