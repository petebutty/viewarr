package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Where every setting lives. The settings themselves are built where they always were (appearanceCategories,
 * playbackCategories, navigationCategories: each "source" with its own sections); this map says which page and
 * section of Settings each one is listed in, by "Source/Section/Title". The pages and Search settings both read it,
 * so they always agree.
 *
 * - "Source/Section/" + "*" (all): everything in that section not placed anywhere else by name.
 * - Anything the map doesn't place at all still shows: in a "More" section on its source's own page (so a new setting
 *   is never lost, it just isn't tidied away yet).
 *
 * Appearance is how things look, Live TV and Playback how they behave, Menu & navigation where things are.
 */
object SettingsMap {
    class Section(val name: String, val blurb: String, val refs: List<String>)
    class Page(val id: String, val title: String, val sections: List<Section>)

    private const val A = "Appearance"
    private const val P = "Playback"
    private const val N = "Navigation"
    private fun r(source: String, section: String, vararg titles: String) = titles.map { "$source/$section/$it" }
    private fun all(source: String, section: String) = listOf("$source/$section/*")

    const val APPEARANCE = "appearance"
    const val LIVE = "live"
    const val PLAYBACK = "playback"
    const val NAVIGATION = "navigation"
    const val SYSTEM = "system"

    val pages = listOf(
        Page(APPEARANCE, "Appearance", listOf(
            Section("Theme", "Colours, text and how dates and times show. Changes show straight away.",
                r(A, "Colours & text", "Background colour", "Highlight colour", "Text size", "Font", "Clock", "Date format", "Time format")),
            Section("Wallpaper", "A picture behind the guide, menus and settings, darkened so text stays easy to read.",
                all(A, "Wallpaper")),
            Section("Glass & transparency", "Left / Right on the sliders: how much shows through. Solid hides what's behind completely.",
                r(A, "Transparency", "Glass", "Glass colour", "Guide", "Wallpaper dimming") + r(A, "Live TV", "Pop-up glass", "Info bar: border")),
            Section("TV guide", "How the TV guide and the channel list look.",
                r(A, "Live TV", "Channel numbers", "Channel logos", "Programme corners", "Progress bars", "Episode numbers", "Catch-up icons", "Programme pictures")),
            Section("Info bar & controls", "The bar over live TV and films (when you change channel or press OK / Info), and the film controls.",
                r(A, "Live TV", "Info bar style", "Info bar corners", "Info bar size", "Info bar width", "Preview cards", "Info bar: channel number", "Info bar: episode numbers", "Info bar: picture quality", "Info bar: frame rate", "Rows under the info bar") +
                    r(P, "Player", "Player controls size")),
            Section("Menu", "How the menu on the left looks: headings, colours, hints and marks.",
                r(A, "Main menu", "List headings", "Source in the Live TV heading", "Colour edge", "Colour tint", "Icon column", "Left / Right hints", "TV mark on channel groups", "LIVE mark") +
                    r(N, "Menu", "Button hints")),
            Section("Posters", "How big library posters are, and what's drawn on posters in Movies, Shows and search.",
                all(A, "Posters")),
            Section("Title pages", "What a film's or show's page shows. Anything off isn't fetched either, so pages open with less to do.",
                all(A, "Title pages")),
            Section("Ratings", "Which ratings show on title pages and the Movies / Shows header, and in what order.",
                all(A, "Ratings")),
            Section("Subtitles", "How subtitles look.",
                r(P, "Subtitles", "Size", "Colour", "Background")),
            Section("Music", "Now Playing: the artwork, lyrics, the visualiser and the screensaver.",
                r(P, "Music", "Artwork", "Visualiser", "Screensaver", "Lyrics")),
            Section("Motion", "How things move on screen.",
                r(A, "Colours & text", "List scrolling")),
        )),
        Page(LIVE, "Live TV", listOf(
            Section("Watching", "Full-screen live TV: changing channel and the picture in the corner.",
                r(P, "Live TV", "Up / Down while watching", "Browsing channels", "Highlight the corner picture", "Still watching?")),
            Section("TV guide", "How far the guide reaches, how often it's fetched, and moving through it.",
                r(P, "Live TV", "TV guide days ahead", "TV guide refresh") + r(A, "Live TV", "End of a list")),
            Section("Channel groups", "The channel groups in the menu.",
                r(P, "Live TV", "\"All channels\" group")),
        )),
        Page(PLAYBACK, "Playback", listOf(
            Section("Player", "Which player plays films, shows and recordings, and how it behaves.",
                r(P, "Player", "Player", "Video view", "Resume", "Time on the right", "Controls stay up for", "Seek-bar previews", "mpv decoding")),
            Section("Video", "Matching the TV's frame rate, picture size and smoothness.", all(P, "Video")),
            Section("Audio", "Language, surround sound and volume.", all(P, "Audio")),
            Section("Subtitles", "When subtitles show, and in which language.", r(P, "Subtitles", "Show subtitles", "Language")),
            Section("Streams", "Quality, Plex and addon streams.", all(P, "Streams")),
            Section("Episodes", "Watching shows: skipping intros and credits, and the next episode.", all(P, "Episodes")),
            Section("Trailers", "YouTube trailers for films and shows.", all(P, "Trailers")),
            Section("Music", "Where lyrics come from.", r(P, "Music", "Lyrics from LRCLIB")),
        )),
        Page(NAVIGATION, "Menu & navigation", listOf(
            Section("Start", "What you see when Viewarr opens.", all(N, "Start")),
            Section("Menu", "Opening the side menu and going into its lists.", r(N, "Menu", "Menu opens on")),
            Section("What the menu lists", "What's listed in the menu on the left. Live TV and Settings are always there; streaming services appear once your addons' rows have loaded.",
                all(A, "Main menu")),
            Section("Poster rows", "Moving around the rows of posters on Movies, Shows and title pages.", all(N, "Poster rows")),
        )),
        Page(SYSTEM, "Troubleshooting", listOf(
            Section("Troubleshooting", "Tools for when something doesn't play right, or the box struggles.", all(P, "Troubleshooting")),
        )),
    )

    /** The page each source's unplaced settings go to ("More"). */
    private val home = mapOf(A to APPEARANCE, P to PLAYBACK, N to NAVIGATION)

    fun page(id: String) = pages.first { it.id == id }

    /** One setting from a source, where it was built. */
    class Entry(val source: String, val section: String, val item: SettingItem) {
        val key get() = "$source/$section/${item.title}"
    }

    /** [id]'s sections, filled from [pool] (empty sections left out). */
    fun categories(id: String, pool: List<Entry>): List<SettingCategory> {
        val named = pages.flatMap { p -> p.sections.flatMap { it.refs } }.filterNot { it.endsWith("/*") }.toSet()
        val wild = pages.flatMap { p -> p.sections.flatMap { it.refs } }.filter { it.endsWith("/*") }.map { it.removeSuffix("*") }.toSet()
        val page = page(id)
        val out = page.sections.mapNotNull { sec ->
            val items = sec.refs.flatMap { ref ->
                if (ref.endsWith("/*")) {
                    val prefix = ref.removeSuffix("*")
                    pool.filter { it.key.startsWith(prefix) && it.key !in named }.map { it.item }
                } else pool.filter { it.key == ref }.map { it.item }
            }
            if (items.isEmpty()) null else SettingCategory(sec.name, sec.blurb, items)
        }
        // Not placed anywhere: listed on its source's page so it's never lost.
        val more = pool.filter { e -> home[e.source] == id && e.key !in named && wild.none { e.key.startsWith(it) } }.map { it.item }
        return if (more.isEmpty()) out else out + SettingCategory("More", "Other settings.", more)
    }

    /** Every setting with its page and section, for Search settings. */
    fun index(pool: List<Entry>): List<Hit> = pages.flatMap { p ->
        categories(p.id, pool).flatMap { c -> c.items.map { Hit(p.id, p.title, c.name, it.title, it.description) } }
    }

    class Hit(val page: String, val pageTitle: String, val section: String, val title: String, val description: String)
}

/** Every setting, from where each is built (the catalogue the map places). */
@Composable
fun settingsPool(
    appearance: AppearanceViewModel = hiltViewModel(),
    playback: PlaybackSettingsViewModel = hiltViewModel(),
    remote: RemoteViewModel = hiltViewModel(),
    opts: UiOptsViewModel = hiltViewModel(),
): List<SettingsMap.Entry> {
    fun from(source: String, cats: List<SettingCategory>) = cats.flatMap { c -> c.items.map { SettingsMap.Entry(source, c.name, it) } }
    return from("Appearance", appearanceCategories(appearance, opts)) +
        from("Playback", playbackCategories(playback, opts)) +
        from("Navigation", navigationCategories(remote, opts))
}

/** A page of Settings as the map lays it out ([startSection] / [startItem]: open there, that setting highlighted). */
@Composable
fun MappedSettingsScreen(pageId: String, onBack: () -> Unit, startSection: String? = null, startItem: String? = null) {
    val appearance: AppearanceViewModel = hiltViewModel()
    val playback: PlaybackSettingsViewModel = hiltViewModel()
    val opts: UiOptsViewModel = hiltViewModel()
    val pool = settingsPool(appearance = appearance, playback = playback, opts = opts)
    val page = SettingsMap.page(pageId)
    val reset: (() -> Unit)? = when (pageId) {
        SettingsMap.APPEARANCE -> ({ appearanceReset(appearance, opts) })
        SettingsMap.PLAYBACK -> ({ playbackReset(playback, opts) })
        else -> null
    }
    SettingsPage(title = page.title, categories = SettingsMap.categories(pageId, pool), onBack = onBack, onReset = reset,
        startSection = startSection, startItem = startItem)
}
