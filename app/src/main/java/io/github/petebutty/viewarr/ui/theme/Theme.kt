package io.github.petebutty.viewarr.ui.theme

import kotlin.math.roundToInt
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
import io.github.petebutty.viewarr.ui.media.PlayOrder

/** Every shade is derived from two user-chosen colours so any combination stays coherent. */
data class Palette(val main: Color, val highlight: Color, val transparency: Float = 0f, /** Guide: how see-through the channel names and programmes are (0 solid … 1 clear). */ val guideTransparency: Float = 0f, val showNumbers: Boolean = true, val squareCorners: Boolean = true, val hideMedia: Boolean = false, /** Guide progress bars: 0 every channel, 1 only the one you're watching, 2 none. */ val progressBars: Int = 0,
    /** Picture behind pages: 0 none, 1 built-in, 3 what's selected (2, from your library, is gone: stored as none). */ val wallpaper: Int = 0,
    /** Which built-in wallpaper (WallpaperStyles). */ val wallpaperStyle: Int = 0,
    /** How much the background colour covers the wallpaper (0.3 light … 0.9 dark). */ val wallpaperDim: Float = 0.6f,
    /** Title pages: the parts switched off in Settings → Appearance (TitleParts ids). */ val titleHidden: Set<String> = emptySet(),
    /** The main menu's order (between Live TV and Settings): "Movies", "svc:Netflix", "Live now"… (hold OK in the menu to edit). */ val menuOrder: List<String> = emptyList(),
    /** The ratings' order on title pages ("IMDb", "RT", "Trakt"…; Settings → Appearance → Ratings). */ val ratingsOrder: List<String> = emptyList(),
    /** The info bars' button order, per bar ("live", "film"): button ids (hold OK on a button to edit). */ val barOrder: Map<String, List<String>> = emptyMap(),
    /** Small display / behaviour options (UiOpts names → chosen index; 0 = the default). */ val opts: Map<String, Int> = emptyMap()) {
    fun shows(part: String) = part !in titleHidden
    fun opt(name: String) = opts[name] ?: UiOpts.defaults[name] ?: 0
    /** What darkens a wallpaper / artwork: the background colour (a wash of the theme over the picture), or plain
     *  black so the picture keeps its own colours (Settings → Appearance → Wallpaper shade; 2026-11-17: the
     *  theme and the wallpaper were fighting). */
    val shade get() = if (opt(UiOpts.WALL_SHADE) == 1) androidx.compose.ui.graphics.Color(0xFF080808) else main
    /**
     * How solid the info bar, the player's controls and every pop-up and notice are (1 solid … 0 clear): Settings →
     * Appearance → Pop-up glass, 0–100% see-through in tens (since 0.28.5; the old setting only went from 70%
     * see-through to solid, and is carried over until the new one is set).
     */
    val popupAlpha: Float get() = opts[UiOpts.POPUP_SEE]?.let { 1f - it.coerceIn(0, 10) / 10f } ?: (0.3f + 0.07f * opt(UiOpts.POPUP_GLASS).coerceIn(0, 10))
    /** [popupAlpha] as the slider's step (0 solid … 10 clear). */
    val popupSee: Int get() = opts[UiOpts.POPUP_SEE] ?: ((1f - popupAlpha) * 10).roundToInt().coerceIn(0, 10)
    /** Low-memory mode: the heavy extras are off whatever their own settings say. */
    val lowMemory get() = opt(UiOpts.LOW_MEMORY) == 1
    val guideArt get() = opt(UiOpts.GUIDE_ART) == 0 && !lowMemory
    /** The guide's top: the programme's title logo (TMDB) in place of its name, when it has one. */
    /** The wallpaper in use: none in low-memory mode (plain Viewarr colours), whatever's chosen. */
    /** (A stored 4, "Your films and shows", was taken out again on 2026-11-19: shown as none.) */
    val wallpaperMode get() = if (lowMemory || wallpaper == 4) 0 else wallpaper
    /** Trailer previews, multi-view, title-page artwork: not in low-memory mode. */
    val heavyExtras get() = !lowMemory
    val channelLogos get() = opt(UiOpts.CHANNEL_LOGOS) == 0
    val sourceBadges get() = opt(UiOpts.BADGE_SOURCE) == 0
    val progressBadges get() = opt(UiOpts.BADGE_PROGRESS) == 0
    val serverPreviews get() = opt(UiOpts.SEEK_PREVIEWS) != 2
    val boxPreviews get() = opt(UiOpts.SEEK_PREVIEWS) == 0 && !lowMemory
    /** Settings → Appearance → Info bar size / Preview cards. */
    val infoBarScale get() = floatArrayOf(1f, 0.85f, 0.72f, 1.12f)[opt(UiOpts.INFOBAR_SIZE).coerceIn(0, 3)]
    /** A library poster's width (dp): Small matches the rows' posters. */
    /** Settings → Appearance → Posters → Row cards: wide pictures in the rows instead of posters. */
    val wideRows get() = opt(UiOpts.ROW_CARDS) == 1
    val libraryPosterDp get() = floatArrayOf(100f, 116f, 88f, 136f)[opt(UiOpts.LIBRARY_POSTERS).coerceIn(0, 3)]
    val infoBarWidth get() = floatArrayOf(1f, 0.9f, 0.8f, 0.7f, 0.6f)[opt(UiOpts.INFOBAR_WIDTH).coerceIn(0, 4)]
    /** Row posters' width (Settings → Appearance → Browsing style). */
    // Normal: two whole rows fit under the top panel (2026-11-19; 118 left the second row cut off), about 8½ across;
    // Large and Small in the same proportion as before. Sized from the screen's width (1280 units wide here), so a
    // box laid out narrower still shows 8.
    val rowPosterWidth get() = floatArrayOf(108f, 140f, 93f)[opt(UiOpts.ROW_POSTERS).coerceIn(0, 2)] * ScreenWidth.factor
    val heroStyle get() = opt(UiOpts.HERO_STYLE).coerceIn(0, 2)
    /** Rows: the opacity of posters that aren't highlighted (Settings → Appearance → Posters → Other posters faded). */
    val posterDimAlpha get() = 1f - 0.1f * opt(UiOpts.POSTER_DIM).coerceIn(0, 10)
    /** Rows: how much the highlighted poster grows (1 = not at all). */
    val posterZoom get() = floatArrayOf(1.06f, 1f, 1.12f)[opt(UiOpts.POSTER_ZOOM).coerceIn(0, 2)]
    val cardScale get() = floatArrayOf(1f, 0.8f, 0.65f, 1.15f)[opt(UiOpts.CARD_SIZE).coerceIn(0, 3)]
    /** Player controls (seek bar area and buttons) scale: Medium, Small, Large (the original size). */
    val controlsScale get() = floatArrayOf(0.8f, 0.65f, 1f)[opt(UiOpts.CONTROLS_SIZE).coerceIn(0, 2)]
    val controlsMs get() = longArrayOf(4_000, 3_000, 6_000, 10_000)[opt(UiOpts.CONTROLS_TIME).coerceIn(0, 3)]
    /** Settings → Remote buttons → Live TV → Channel change delay, in milliseconds. */
    val zapDelayMs get() = opt(UiOpts.ZAP_DELAY).coerceIn(0, 20) * 100L
    /** Settings → Playback → Next episode countdown: 0–180 s in 5 s steps (the older four-choice setting carries over). */
    val upNextSeconds get() = (opts[UiOpts.UP_NEXT_S] ?: opts[UiOpts.UP_NEXT]?.let { intArrayOf(20, 10, 30, 60)[it.coerceIn(0, 3)] / 5 } ?: 4).coerceIn(0, 36) * 5
    // Text size (Settings → Appearance), text only: the layout, posters and spacing stay as designed (see AppRoot).
    // "Normal" is 90% of the old size, for everyone to start with (2026-11-19: at full size a tester's guide panel
    // lost its synopsis; "make current small, normal"). Small and Large step about 10% either side (no Extra large: 2026-11-19).
    // A new key, so the old whole-app setting's choices don't carry over.
    // Picture size (Settings → Appearance): the finished picture shrunk, centred, for a TV that cuts off the edges. The
    // layout itself never changes (see ui/common/PictureScale.kt).
    val pictureSize get() = floatArrayOf(1f, 0.975f, 0.95f, 0.925f, 0.9f)[opt(UiOpts.PICTURE_SIZE).coerceIn(0, 4)]
    val textScale get() = floatArrayOf(0.9f, 0.82f, 1f)[opt(UiOpts.TEXT_SIZE_V2).coerceIn(0, 2)]
    /** How long to rest on a channel browsed in the info bar before it changes (null: only on OK). */
    val browseDelayMs: Long? get() = when (opt(UiOpts.BROWSE_DELAY)) { 1 -> 300L; 2 -> 800L; 3 -> 1_000L; 4 -> 1_500L; 5 -> 2_000L; 6 -> null; else -> 500L }
    val hideAllChannels: Boolean get() = opt(UiOpts.GROUP_ALL) == 1
    /** How far ahead the guide reaches: Settings → Live TV → TV guide days ahead. */
    val guideForwardMs: Long get() = UiOpts.guideForwardMs(opt(UiOpts.GUIDE_DAYS))
    /** Settings → Live TV → TV guide refresh: how old the guide may get (null: only Refresh channels & guide). */
    val guideRefreshMs: Long? get() = longArrayOf(4, 2, 8, 12, 24, 0)[opt(UiOpts.GUIDE_REFRESH).coerceIn(0, 5)].takeIf { it > 0 }?.times(3_600_000L)
    /** Title pages: the ratings in the order chosen (Settings → Appearance → Ratings). */
    fun ratingRank(source: String): Int = ratingsOrder.indexOf(source).let { if (it < 0) 100 + TitleParts.ratings.indexOfFirst { r -> r.first == source }.coerceAtLeast(0) else it }
    /** Settings → Appearance → Glass colour: a pastel the glass is tinted with (null: plain). */
    val glassTint: Color? get() = GlassTints.getOrNull(opt(UiOpts.GLASS_TINT))?.second
    /** Hours of no button presses on Live TV before "Still watching?" (0 = never). */
    val stillWatchingHours get() = intArrayOf(0, 2, 3, 4, 6)[opt(UiOpts.STILL_WATCHING).coerceIn(0, 4)]
    /** Settings → Live TV → Start with the best source (Dispatcharr channels start on their best-ranked stream). */
    val bestSource get() = opt(UiOpts.BEST_SOURCE) == 0
    /** Settings → Live TV → Live timeshift: the Viewarr plugin's timeshift (ServerTimeshift). */
    val serverTimeshift get() = opt(UiOpts.SERVER_TIMESHIFT) == 0
    /** Settings → Live TV → Event channels: only with a listing (the plugin's event channels wait for a programme). */
    val eventOnlyListed get() = opt(UiOpts.EVENT_ONLY_LISTED) == 0
    // The sliders' percentages as they look over a TV picture: a straight 1 - t left a dark panel looking all but
    // clear from about 50% (and invisible at 100%, the text on the bare picture). A curve keeps the middle of the
    // slider useful and leaves a trace of the panel at 100% so text stays readable.
    // As the sliders say: 0% solid … 100% clear (since 0.28.5; it was a curve that stopped at 90%, so 50% showed
    // only about a fifth through and 100% never went clear).
    val menuAlpha = 1f - transparency.coerceIn(0f, 1f)
    val guideAlpha = 1f - guideTransparency.coerceIn(0f, 1f)
    val panel = lerp(main, Color.White, 0.07f)
    val panelAlt = lerp(main, Color.White, 0.04f)
    val cellPast = lerp(main, Color.White, 0.025f)
    val cellFuture = lerp(main, Color.White, 0.08f)
    val cellCurrent = lerp(main, Color.White, 0.15f)
    val previewBox = lerp(main, Color.Black, 0.35f)
    /** The highlight as shown: toned down towards the background by Settings → Appearance → Tone down highlight
     *  (2026-11-23: the highlights were too bright on the TV). */
    val accent: Color = lerp(highlight, main, opt(UiOpts.HIGHLIGHT_STRENGTH).coerceIn(0, 8) * 0.1f)
    val onHighlight = if (accent.luminance() > 0.5f) Color(0xFF0B1220) else Color.White
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
    // Brighter blues (the pre-glass Classic blue look, asked back for): Ocean blue and Sky blue.
    Color(0xFF173F80), Color(0xFF2357B0),
)

// Softer than the first set (the brightest glared on the glass); Orange, the Viewarr highlight, stays as it was.
val HighlightChoices = listOf(
    Color(0xFF2AAFC4), Color(0xFFE0577A), Color(0xFF4A9FD4), Color(0xFF5B82D6), Color(0xFF2E9E91), Color(0xFFA47ED8),
    Color(0xFFF97316), Color(0xFF3E9E66), Color(0xFFD9A93A), Color(0xFFD9566C),
    // The bright cyan the Classic blue look had (just off the old value, which is read as the softer cyan).
    Color(0xFF20C6E8),
)

/** The first set's brighter highlights, each to its softer one (a highlight picked before still counts as chosen). */
val OldHighlights: Map<Int, Color> = listOf(
    0xFF00E5FF, 0xFFFF3366, 0xFF22C7E8, 0xFF3D8BFD, 0xFF2DD4BF, 0xFFC084FC, 0xFFF97316, 0xFF22C55E, 0xFFFACC15, 0xFFF43F5E,
).withIndex().associate { (i, v) -> v.toInt() to HighlightChoices[i] }

object AppColors {
    var palette by mutableStateOf(Palette(ThemePresets[0].main, ThemePresets[0].highlight))

    val Background get() = palette.main
    /** The background colour as laid over a wallpaper: see-through when one is on, solid otherwise. */
    val Scrim get() = if (palette.wallpaperMode != 0) palette.shade.copy(alpha = palette.wallpaperDim) else palette.main
    val Panel get() = palette.panel
    val PanelAlt get() = palette.panelAlt
    val Accent get() = palette.accent
    val OnAccent get() = palette.onHighlight
    val CellFuture get() = palette.cellFuture
    val CellPast get() = palette.cellPast
    val CellCurrent get() = palette.cellCurrent
    val PreviewBox get() = palette.previewBox
    val MenuPanel get() = tinted(palette.panel).copy(alpha = palette.menuAlpha)
    /** A glass colour with Settings → Appearance → Glass colour's pastel mixed in. */
    fun tinted(c: Color): Color = palette.glassTint?.let { androidx.compose.ui.graphics.lerp(c, it, 0.35f) } ?: c
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFBFC6DA)
    val Error = Color(0xFFFF8A80)
}

@Composable
fun ViewarrTheme(content: @Composable () -> Unit) {
    val p = AppColors.palette
    M3Theme(
        colorScheme = m3DarkColorScheme(
            primary = p.accent,
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
                primary = p.accent,
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
    const val TEXT_SIZE_V2 = "text_size_v2"
    const val PICTURE_SIZE = "picture_size"
    /** Settings → Appearance → Font: one of the box's own fonts for all text. */
    const val FONT = "font"
    /** The info bar's glass (and every pop-up's): how dark, and its thin border. */
    const val GLASS_TINT = "glass_tint"
    /** How often the TV guide is fetched again while Viewarr is open (and on starting). */
    const val GUIDE_REFRESH = "guide_refresh"
    /** How far ahead the TV guide is fetched (and can be scrolled). */
    const val GUIDE_DAYS = "guide_days"
    fun guideForwardMs(choice: Int): Long = intArrayOf(72, 24, 120, 168)[choice.coerceIn(0, 3)] * 3_600_000L
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
    /** A red "● LIVE" on the channel groups' All channels. */
    const val MENU_LIVE_MARK = "menu_live_mark"
    /** The "Hidden groups (n)" row at the end of the channel groups (off: reached from a group's hold-OK menu). */
    const val MENU_HIDDEN = "menu_hidden"
    /** Live TV's channel groups: the "All channels" group shown, or hidden (the guide then opens on the first group). */
    const val GROUP_ALL = "group_all"
    /** Live TV through Dispatcharr: a channel starts on its best-ranked stream (playback/SourceRank), or Dispatcharr's first. */
    const val BEST_SOURCE = "best_source"
    /** Live TV through Dispatcharr: pause / rewind from the Viewarr plugin's buffer on the server (playback/ServerTimeshift). */
    const val SERVER_TIMESHIFT = "server_timeshift"
    /** The Viewarr plugin's event channels (TNT events, PPV…): listed only with a programme on now or within 24 hours (GuideRepository). */
    const val EVENT_ONLY_LISTED = "event_only_listed"
    /** Lists following the highlight: instant (no trail when going fast) or a smooth slide. */
    const val LIST_SCROLL = "list_scroll"
    /** Up / Down (and Channel + / −) while watching live TV: change channel, or browse in the info bar (OK changes, or on stopping). */
    const val LIVE_UPDOWN = "live_updown"
    /** Live TV controls → Down: the Recent channels row, or nothing. */
    const val INFOBAR_RECENT = "infobar_recent"
    /** The info bar's look (live TV and films): glass panel, square, pill, a fade from the bottom, a full-width bar, minimal. */
    const val INFOBAR_STYLE = "infobar_style"
    /** Films and episodes: the right-hand time under the seek bar: time left, when it ends, or both. */
    const val FILM_TIME_RIGHT = "film_time_right"
    /** Its corners, for the rounded styles. */
    const val INFOBAR_CORNERS = "infobar_corners"
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
    /** The info bar (live TV and films, its controls and preview rows): how big all of it is drawn. */
    const val INFOBAR_SIZE = "infobar_size"
    /** The preview cards in the info bar's rows (next episodes, shortcuts): their size. */
    const val CARD_SIZE = "card_size"
    /** The info bar's width: the whole screen, or narrower and centred at the bottom. */
    const val INFOBAR_WIDTH = "infobar_width"
    /** A library's grid: how big the posters are (how many fit across). */
    const val LIBRARY_POSTERS = "library_posters"
    /** Movies / Shows / a service's rows: posters (2:3) or wide pictures (16:9, the Viewarr launcher's look). */
    const val ROW_CARDS = "row_cards"
    /** The glass's strength for the info bar and every pop-up, as a slider: 0 the clearest … 10 solid. */
    const val POPUP_GLASS = "popup_glass"
    /** Pop-up glass, 0–100% see-through (replaces [POPUP_GLASS]): see Palette.popupAlpha. */
    const val POPUP_SEE = "popup_see"
    const val BANNER_BORDER = "banner_border"
    const val CLOCK = "clock"
    /** The time on the info bar's top row (live TV, its controls, and films): Settings → Appearance → Info bar. */
    const val INFOBAR_CLOCK = "infobar_clock"
    const val BADGE_SOURCE = "badge_source"
    const val BADGE_PROGRESS = "badge_progress"
    const val GUIDE_ART = "guide_art"
    /** Wallpapers darkened with the background colour (0) or black (1): [Palette.shade]. */
    const val WALL_SHADE = "wall_shade"
    /** Settings → Playback → Sound after a frame-rate switch: 1–5 s (index + 1). */
    const val AFR_SOUND_DELAY = "afr_sound_delay"
    /** How early a reminder comes up: when it starts, or 2 / 5 / 10 / 15 minutes before. */
    const val REMIND_LEAD = "remind_lead"
    /** Where trailer previews play while browsing: the corner window, or in the poster, which widens to 16:9. */
    const val TRAILER_STYLE = "trailer_style"
    /** In the poster: how long the trailer plays in the card before it grows into the big panel with sound. */
    // (A new key: the choices became 1–5 seconds. 2026-11-18.)
    const val TRAILER_GROW = "trailer_grow_s"
    /** Movies / Shows: the details panel at the top (hero): normal, big (Cinema), or off (Lite). */
    const val HERO_STYLE = "hero_style"
    /** The posters in rows: normal, large or small. */
    const val ROW_POSTERS = "row_posters"
    /** Where the guide's programme picture goes: the background ("What's selected" wallpaper), or a banner card in
     *  the info area with the background left alone (2026-11-17). */
    const val GUIDE_ART_PLACE = "guide_art_place"
    /** The guide's top panel: the "Next" and "Then" lines on the right (a tester, 2026-11-19: a way to hide them). */
    const val GUIDE_NEXT = "guide_next"
    const val GUIDE_ROWS = "guide_rows"
    const val SNOW = "snow"
    /** Movies / Shows background: the highlighted title's artwork, or the wallpaper (a tester, 2026-11-19). */
    const val TITLE_BG = "title_bg"
    /** The wallpaper behind the TV guide too, or a plain background there (2026-11-19: a clean guide). */
    const val GUIDE_WALL = "guide_wall"
    /** The guide's top panel: the "S2026 E1119" badge under Next / Then (a tester, 2026-11-19). */
    const val GUIDE_EPISODE_BADGE = "guide_episode_badge"
    /** The guide's top panel: the "Catch-up" badge (2026-11-19). */
    const val GUIDE_CATCHUP_BADGE = "guide_catchup_badge"
    const val CHANNEL_LOGOS = "channel_logos"
    const val MINI_CORNER = "mini_corner"
    /** Pressing towards the corner picture from the page's edge highlights it (Full screen / Close). */
    const val CORNER_FOCUS = "corner_focus"
    /** The TV guide, channel list and group list at their last / first entry: loop on round, jump to the other end, or stop. */
    const val LIST_ENDS = "list_ends2"
    /** A film or episode ending or stopped: stay where you were, or back to live TV. */
    const val AFTER_FILM = "after_film"
    /** Back from a full-screen film: the mini player over the page it was started from (0, standard since 0.28.5), or over the TV guide with the menu (1). */
    const val FILM_BACK = "film_back_v2"
    /** Settings → Playback → Films and episodes → Keep playing after Home: Android's own small window over its home screen. */
    const val HOME_WINDOW = "home_window"
    const val AGE_RATING = "age_rating"
    const val BUTTON_HINTS = "button_hints"
    const val ANIM_FILM = "anim_film"
    /** TV guide: how much time the timeline shows across the screen (more time: more programmes, narrower). */
    const val GUIDE_SPAN = "guide_span"
    /** Films and episodes: where Play looks, in turn (see ui/media/PlayOrder). */
    const val PLAY_ORDER = "play_order"
    /** Movies / Shows home page: an addon's network rows ("Netflix · Popular"…) too, or only behind their shortcuts. */
    const val SET_ROWS_HOME = "set_rows_home"
    /** Guide: the catch-up (replay) icon on programmes and channel names. */
    const val CATCHUP_ICONS = "catchup_icons"
    /** Guide: the part of an on-now programme that's already gone, shaded. */
    const val GUIDE_SHADE = "guide_shade"
    /** The TV guide: the rotating glow ring round the highlighted programme (off in Viewarr to start, on in the launcher). */
    const val GUIDE_GLOW = "guide_glow"
    /** Rows: how much the posters that aren't highlighted fade (0 none … 60%). */
    const val POSTER_DIM = "poster_dim"
    /** Rows: the highlighted poster grows a little. */
    const val POSTER_ZOOM = "poster_zoom"
    /** Live TV's info bar (the banner over the picture): channel number, S·E, quality badges. */
    const val BANNER_NUMBER = "banner_number"
    const val BANNER_EPISODE = "banner_episode"
    const val BANNER_BADGES = "banner_badges"
    const val BANNER_FPS = "banner_fps"
    /** The channel list: a progress bar under each channel's programme (how far through it is). */
    const val CHLIST_PROGRESS = "chlist_progress"
    /** The channel list: the highlighted channel's progress bar colour (on the highlight). */
    const val CHLIST_PROGRESS_COLOUR = "chlist_progress_colour"
    /** How much the highlight colour is toned down towards the background, 0–80% in tens (Palette.accent). */
    const val HIGHLIGHT_STRENGTH = "highlight_strength"
    /** Movies / Shows pages: the main rows only (8; Discover.homeRows), or every row as before. */
    const val CURATED_HOME = "curated_home"
    /** Films: the highest bitrate for a copy over the internet (VersionPick.Screen.maxKbpsAway). */
    const val AWAY_MAX_MBPS = "away_max_mbps"
    /** Tell the server while a film comes in over the internet (StreamSignal), for a server script that slows
     *  downloads meanwhile. Off unless switched on: it only means something with such a script. */
    const val STREAM_SIGNAL = "stream_signal"
    /** The channel list over live TV: how wide it is. */
    const val CHLIST_WIDTH = "chlist_width"
    /** Live TV info bar: the stream's provider (Dispatcharr's account for the stream playing, e.g. a backup). */
    const val BANNER_SOURCE = "banner_source"
    const val SEEK_PREVIEWS = "seek_previews"
    const val CONTROLS_TIME = "controls_time"
    const val CONTROLS_SIZE = "controls_size"
    const val UP_NEXT = "up_next"
    const val UP_NEXT_S = "up_next_s"
    /** Live TV: how long a channel must stay chosen while zapping before its stream is asked for (tenths of a second). */
    const val ZAP_DELAY = "zap_delay"
    /** Sports: OK on a game opens its page (line-ups, form, remind / record), or just a note (as before). */
    const val SPORTS_GAME_PAGE = "sports_game_page"
    /** The news ticker (ui/common/NewsTicker): with the live info bar, in the TV guide, on the Movies / Shows / Sports pages. */
    const val NEWS_INFOBAR = "news_infobar"
    const val NEWS_GUIDE = "news_guide"
    const val NEWS_PAGES = "news_pages"
    const val NEWS_SPEED = "news_speed"
    /** Per feed: 0 as it comes, 1 on, 2 off; and its look (0 the feed's own, else TickerStyle + 1). */
    fun newsFeedKey(id: String) = "news_on_$id"
    fun newsStyleKey(id: String) = "news_style_$id"
    const val STILL_WATCHING = "still_watching"
    /** Settings → Playback → Watch progress → Send plays to Trakt: On (0, the default) or Off (data/sync/WatchSource). */
    const val SEND_TO_TRAKT = "send_to_trakt"
    const val LOW_MEMORY = "low_memory"
    const val DATE_ORDER = "date_order"
    const val TIME_FORMAT = "time_format"
    /** Defaults other than the first choice (anyone who never chose). Stored choices are kept as they are. */
    val defaults = mapOf(
        GUIDE_GLOW to if (io.github.petebutty.viewarr.BuildConfig.LAUNCHER) 0 else 1, // (Viewarr: off to start, 2026-11-25)
        EVENT_ONLY_LISTED to 1, // off until switched on (2026-11-19: app-side hiding is opt-in)
        POSTER_DIM to 2, // (20% faded to start: 2026-11-18)
        // (The Viewarr look: light glass, as "Info bar: glass" Light was.)
        POPUP_GLASS to 2,
        UP_NEXT_S to 4,
        TRAILER_GROW to 4, // (5 seconds)
        AFR_SOUND_DELAY to 2, // (3 seconds)
        ZAP_DELAY to 5,
    )
    val choices = mapOf(
        TEXT_SIZE to listOf("Normal", "Small", "Large", "Extra large"),
        TEXT_SIZE_V2 to listOf("Normal", "Small", "Large"),
        PICTURE_SIZE to listOf("100%", "97.5%", "95%", "92.5%", "90%"),
        FONT to listOf("Standard", "Condensed", "Light", "Medium", "Serif", "Thin", "Bold", "Condensed light", "Casual", "Typewriter", "Small caps", "Handwritten"),
        GLASS_TINT to GlassTints.map { it.first },
        GUIDE_EPISODE to listOf("On", "Off"),
        SPORTS_GAME_PAGE to listOf("On", "Off"),
        NEWS_INFOBAR to listOf("Off", "On"),
        NEWS_GUIDE to listOf("Off", "On"),
        NEWS_PAGES to listOf("Off", "On"),
        NEWS_SPEED to listOf("Normal", "Slow", "Fast"),
        MENU_HEADINGS to listOf("Heading", "Off", "Path (Live TV › Channel groups)"),
        MENU_EDGE to listOf("Off", "On"),
        MENU_TINT to listOf("Off", "On"),
        MENU_LIVE_MARK to listOf("Off", "On"),
        MENU_HIDDEN to listOf("Off", "On"),
        GROUP_ALL to listOf("Shown", "Hidden"),
        BEST_SOURCE to listOf("On", "Off"),
        SERVER_TIMESHIFT to listOf("On", "Off"),
        EVENT_ONLY_LISTED to listOf("On", "Off"),
        LIST_SCROLL to listOf("Instant", "Smooth"),
        INFOBAR_RECENT to listOf("On", "Off"),
        FILM_TIME_RIGHT to listOf("Time left (15:05 left)", "Ends at (ends at 21:43)", "Both (15:05 left · ends 21:43)"),
        INFOBAR_STYLE to listOf("Glass", "Square glass", "Pill", "Fade", "Full-width bar", "Minimal", "Faded glass", "Feathered glass", "Side fade", "Outline", "Corner card", "Floating card", "Slim strip", "Frosted"),
        INFOBAR_CORNERS to listOf("Medium", "Small", "Large"),
        LIVE_UPDOWN to listOf("Change channel", "Browse in the info bar, OK changes", "Browse in the info bar, changes when you stop"),
        MENU_HEAD_SOURCE to listOf("On", "Off"),
        MENU_GROUP_ICON to listOf("Off", "On"),
        MENU_HINTS to listOf("On", "Off"),
        MUSIC_LRCLIB to listOf("Off", "On"),
        SEND_TO_TRAKT to listOf("On", "Off"),
        MUSIC_VIS to listOf("Bars", "Mirrored bars", "Wave", "Dots", "Off"),
        MUSIC_SAVER to listOf("After 2 minutes", "After 5 minutes", "After 10 minutes", "Off"),
        MUSIC_LYRICS to listOf("On", "Off"),
        INFOBAR_SIZE to listOf("Normal", "Compact", "Small", "Large"),
        LIBRARY_POSTERS to listOf("Small (as the rows)", "Normal", "Smaller", "Large"),
        ROW_CARDS to listOf("Posters", "Wide (16:9)"),
        INFOBAR_WIDTH to listOf("Full width", "90%", "80%", "70%", "60%"),
        CARD_SIZE to listOf("Normal", "Small", "Smaller", "Large"),
        MUSIC_ART to listOf("Square cover", "Spinning vinyl record", "Spinning CD", "Round cover, turning slowly", "Cover with a reflection"),
        BROWSE_DELAY to listOf("0.5 seconds", "0.3 seconds", "0.8 seconds", "1 second", "1.5 seconds", "2 seconds", "Only when I press OK"),
        GUIDE_DAYS to listOf("3 days", "1 day", "5 days", "7 days"),
        GUIDE_REFRESH to listOf("Every 4 hours", "Every 2 hours", "Every 8 hours", "Every 12 hours", "Once a day", "Only when I ask"),
        POPUP_GLASS to (0..10).map { if (it == 10) "Solid" else "${100 - (30 + it * 7)}% see-through" },
        POPUP_SEE to (0..10).map { "${it * 10}%" },
        BANNER_BORDER to listOf("On", "Off"),
        CLOCK to listOf("Off", "On"),
        INFOBAR_CLOCK to listOf("Off", "On"),
        BADGE_SOURCE to listOf("On", "Off"),
        BADGE_PROGRESS to listOf("On", "Off"),
        GUIDE_ART to listOf("On", "Off"),
        WALL_SHADE to listOf("Background colour", "Black"),
        AFR_SOUND_DELAY to listOf("1 second", "2 seconds", "3 seconds", "4 seconds", "5 seconds"),
        TRAILER_STYLE to listOf("In a corner window", "In the poster"),
        TRAILER_GROW to listOf("After 1 second", "After 2 seconds", "After 3 seconds", "After 4 seconds", "After 5 seconds", "Never"),
        HERO_STYLE to listOf("Normal", "Big", "Off"),
        ROW_POSTERS to listOf("Normal", "Large", "Small"),
        REMIND_LEAD to listOf("When it starts", "2 minutes before", "5 minutes before", "10 minutes before", "15 minutes before"),
        GUIDE_ART_PLACE to listOf("Background", "Banner"),
        GUIDE_NEXT to listOf("On", "Off"),
        GUIDE_ROWS to listOf("10 rows", "12 rows", "9 rows", "8 rows"),
        SNOW to listOf("On", "Off"),
        TITLE_BG to listOf("What's selected", "Your wallpaper", "None"),
        GUIDE_WALL to listOf("On", "Off"),
        GUIDE_EPISODE_BADGE to listOf("On", "Off"),
        GUIDE_CATCHUP_BADGE to listOf("On", "Off"),
        CHANNEL_LOGOS to listOf("On", "Off"),
        MINI_CORNER to listOf("Bottom right", "Bottom left", "Top right", "Top left"),
        CORNER_FOCUS to listOf("On", "Off"),
        LIST_ENDS to listOf("Jump to the other end", "Stop"),
        AFTER_FILM to listOf("Stay where you were", "Back to live TV"),
        FILM_BACK to listOf("Its own page", "The TV guide and menu"),
        HOME_WINDOW to listOf("Off", "On"),
        ANIM_FILM to listOf("On", "Off"),
        PLAY_ORDER to PlayOrder.oldLabels,
        GUIDE_SPAN to listOf("2½ hours", "1½ hours", "2 hours", "3 hours", "4 hours", "6 hours"),
        SET_ROWS_HOME to listOf("Only through their shortcuts", "On the home page too"),
        CATCHUP_ICONS to listOf("Programmes and channels", "Channel names only", "Off", "Highlighted channel only"),
        GUIDE_SHADE to listOf("On", "Off"),
        GUIDE_GLOW to listOf("On", "Off"),
        POSTER_DIM to listOf("Off", "10%", "20%", "30%", "40%", "50%", "60%", "70%", "80%", "90%", "100%"),
        POSTER_ZOOM to listOf("Slight", "Off", "More"),
        BANNER_NUMBER to listOf("On", "Off"),
        BANNER_EPISODE to listOf("On", "Off"),
        BANNER_BADGES to listOf("HD · FHD · 4K", "720p · 1080p · 2160p", "Off"),
        BANNER_FPS to listOf("On", "Off"),
        CHLIST_PROGRESS to listOf("Every channel", "Off", "Highlighted channel only"),
        CHLIST_WIDTH to listOf("Medium", "Narrow", "Wide"),
        AWAY_MAX_MBPS to listOf("Automatic (this box's speed)", "No limit", "60 Mb/s (4K remux)", "40 Mb/s (4K)", "25 Mb/s (good 1080p / light 4K)", "15 Mb/s (1080p)", "8 Mb/s (720p / light 1080p)"),
        CURATED_HOME to listOf("Main rows only (8)", "Every row"),
        STREAM_SIGNAL to listOf("Off", "On"),
        HIGHLIGHT_STRENGTH to listOf("Off") + (10..80 step 10).map { "$it%" },
        CHLIST_PROGRESS_COLOUR to listOf("White", "Black", "Grey", "Your highlight colour", "Dark highlight"),
        BANNER_SOURCE to listOf("On", "Off"),
        BUTTON_HINTS to listOf("First few times", "Always", "Off"),
        AGE_RATING to listOf("UK (BBFC: U, PG, 12A, 15, 18)", "US (MPA: G, PG, PG-13, R · TV-14, TV-MA)"),
        SEEK_PREVIEWS to listOf("Server's, else the box makes them", "Server's only", "Off"),
        CONTROLS_TIME to listOf("4 seconds", "3 seconds", "6 seconds", "10 seconds"),
        CONTROLS_SIZE to listOf("Medium", "Small", "Large"),
        UP_NEXT to listOf("20 seconds", "10 seconds", "30 seconds", "60 seconds"),
        UP_NEXT_S to (0..36).map { if (it == 0) "No wait" else "${it * 5} seconds" },
        ZAP_DELAY to (0..20).map { if (it == 0) "No wait" else "%.1f seconds".format(it / 10f) },
        STILL_WATCHING to listOf("Never", "After 2 hours", "After 3 hours", "After 4 hours", "After 6 hours"),
        LOW_MEMORY to listOf("Off", "On"),
        DATE_ORDER to listOf("Day first (Tue 10 Nov)", "Month first (Tue, Nov 10)", "Numbers, day first (Tue 10/11)", "Numbers, month first (Tue 11/10)"),
        TIME_FORMAT to listOf("24-hour (19:30)", "12-hour (7:30 pm)"),
    )
}


/** The screen's width against the 1280-unit layout the poster sizes were set on (the AM9 at 4K). */
object ScreenWidth {
    val factor: Float by lazy {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        // A TV (1200 pixels wide or more) always has the same canvas (see ui/common/PictureScale.kt), so the same poster
        // sizes: eight across on every box. (It used to follow the box's own density: a 1080p box at 960 dp wide got
        // posters three-quarters the size of a 4K box at 1280 dp.) Smaller screens keep following their width.
        if (maxOf(dm.widthPixels, dm.heightPixels) >= 1200) 1f
        else ((maxOf(dm.widthPixels, dm.heightPixels) / dm.density) / 1280f).coerceIn(0.6f, 1.5f)
    }
}
