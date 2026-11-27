package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.petebutty.viewarr.ui.common.NewsViewModel

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
    class Section(val name: String, val blurb: String, val refs: List<String>, /** Under "Customise" on the page. */ val more: Boolean = false)
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
    const val ADVANCED = "advanced"

    /** The warning that starts every section of the Advanced page. */
    private const val CARE = "Careful: leave these alone unless you've been asked to change one or know why. Reset to defaults puts them all back."

    val pages = listOf(
        Page(APPEARANCE, "Appearance", listOf(
            // The few big choices first; everything finer is under "Customise" (more = true), closed until opened.
            Section("Look", "The main choices for how Viewarr looks. Everything finer is under Customise, on the left.",
                r(A, "Browsing style", "Browsing style") + r(A, "Colours & text", "Background colour", "Highlight colour", "Tone down highlight") +
                    r(A, "Wallpaper", "Wallpaper") + r(A, "Colours & text", "Text size", "Clock")),
            Section("Text & dates", "The lettering, and how dates and times show.",
                r(A, "Colours & text", "Font", "Date format", "Time format"), more = true),
            Section("Wallpaper", "A picture behind the guide, menus and settings, darkened so text stays easy to read.",
                all(A, "Wallpaper"), more = true),
            Section("Glass & transparency", "Left / Right on the sliders: how much shows through. Solid hides what's behind completely.",
                r(A, "Transparency", "Glass", "Glass colour", "Guide", "Wallpaper dimming") + r(A, "Live TV", "Pop-up glass", "Info bar: border"), more = true),
            Section("TV guide", "How the TV guide and the channel list look.",
                r(A, "Live TV", "Channel numbers", "Channel logos", "Programme corners", "Progress bars", "Episode numbers", "Channel list width", "Channel list progress", "Highlighted progress colour", "Timeline", "Catch-up icons", "Programme pictures", "Programme picture shows as", "Elapsed shading", "Glow round the highlight", "Next and Then", "Episode badge at the top", "Catch-up badge at the top", "Programme title logos"), more = true),
            Section("Info bar & controls", "The bar over live TV and films (when you change channel or press OK / Info), and the film controls.",
                r(A, "Live TV", "Info bar style", "Clock on the info bar", "Info bar: channel number", "Info bar: episode numbers", "Info bar: picture quality", "Info bar: source", "Info bar: frame rate", "Rows under the info bar"), more = true),
            Section("Menu", "How the menu on the left looks: headings, colours, hints and marks.",
                // (Button hints live in one place, Menu & navigation → Menu: it was in both. 2026-11-17.)
                r(A, "Main menu", "List headings", "Source in the Live TV heading", "Colour edge", "Colour tint", "Left / Right hints", "TV mark on channel groups", "LIVE mark"), more = true),
            Section("Info bar buttons", "Which buttons are on the info bar, and in what order. Each bar's main buttons are always there; you can move them.",
                all(A, "Info bar buttons"), more = true),
            Section("News ticker", "Scrolling headlines along the bottom: where they show, which feeds, and each one's look.",
                all(A, "News ticker"), more = true),
            Section("Browsing style", "The look of Movies, Shows and the networks' pages in one go: Viewarr, Cinema or Lite.",
                all(A, "Browsing style"), more = true),
            Section("Posters", "How big library posters are, and what's drawn on posters in Movies, Shows and search.",
                all(A, "Posters"), more = true),
            Section("Title pages", "What a film's or show's page shows. Anything off isn't fetched either, so pages open with less to do.",
                all(A, "Title pages"), more = true),
            Section("Ratings", "Which ratings show on title pages and the Movies / Shows header, and in what order.",
                all(A, "Ratings"), more = true),
            Section("Subtitles", "How subtitles look.",
                r(P, "Subtitles", "Size", "Colour", "Background"), more = true),
            Section("Music", "Now Playing: the artwork, lyrics, the visualiser and the screensaver.",
                r(P, "Music", "Artwork", "Visualiser", "Screensaver", "Lyrics"), more = true),
        )),
        Page(LIVE, "Live TV", listOf(
            // The few big choices first; everything finer is under "Customise" (more = true), closed until opened.
            Section("Live TV", "The main choices for live TV, and the reports from your TVs. Everything finer is under Customise, on the left.",
                r(P, "Live TV", "Up / Down while watching", "Start with the best source", "\"All channels\" group", "Reports", "TV guide sources")),
            Section("Watching", "Full-screen live TV: browsing channels, the picture in the corner, and pausing live TV.",
                r(P, "Live TV", "Browsing channels", "Highlight the corner picture", "Still watching?", "Live timeshift"), more = true),
            Section("TV guide", "The guide's time offset, and what happens at the end of a list.",
                r(P, "Live TV", "EPG offset") + r(A, "Live TV", "End of a list"), more = true),
            Section("Channel groups", "How channel group names show in the menu.",
                r(P, "Live TV", "Tidy group names"), more = true),
            Section("Group name clean-up", "With Tidy group names on: the marks left out of channel group names. Switch one off to keep it.",
                all(P, "Group name clean-up"), more = true),
            Section("Home screen rows", "Channel groups as rows of channels on your TV's home screen (Projectivy and other launchers): each card opens that channel.",
                all(P, "Home screen rows"), more = true),
            Section("Live now groups", "The channel groups 📡 Live now looks through for live sport. Switch on a group it missed, or off one you don't want.",
                all(P, "Live now groups"), more = true),
            Section("Live now", "What 📡 Live now shows: empty, everything live; or search for teams, leagues and sports (darts, snooker, racing too) and add them to show only those. OK on one removes it.",
                all(P, "Live now"), more = true),
        )),
        Page(PLAYBACK, "Playback", listOf(
            // The few big choices first; everything finer is under "Customise" (more = true), closed until opened.
            Section("Watching", "The main choices for films and shows. Everything finer is under Customise, on the left.",
                r(P, "Video", "Match frame rate") + r(P, "Audio", "Language") + r(P, "Subtitles", "Show subtitles") +
                    r(P, "Streams", "Addon streams", "Internet copies", "Tell the server about internet films") + r(P, "Episodes", "Play next episode automatically", "Skip intros") +
                    r(P, "Trailers", "Trailer previews")),
            Section("Play order", "Where Play looks for a film or episode, in your order: Plex and your other servers, in your house and outside it, IPTV and addons. Switch any off; hold OK to move one.",
                all(P, "Play order")),
            Section("Player", "How the player behaves.",
                r(P, "Player", "Resume", "Time on the right", "Controls stay up for", "Seek-bar previews"), more = true),
            Section("Watch progress", "What's connected for your watch history (Trakt, your servers, Dispatcharr), sending plays to Trakt and sharing IPTV progress between TVs.",
                all(P, "Watch progress"), more = true),
            Section("Video", "Smoothness.", all(P, "Video"), more = true),
            Section("Audio", "Volume and clearer speech.", all(P, "Audio"), more = true),
            Section("Subtitles", "Which language subtitles use.", r(P, "Subtitles", "Language"), more = true),
            Section("Streams", "Addon streams and copies from outside your house.", all(P, "Streams"), more = true),
            Section("Auto-play match", "Which addon stream plays when Viewarr picks: rules sorted by importance.", all(P, "Auto-play match"), more = true),
            Section("Episodes", "Skipping credits, and what happens after an episode.", all(P, "Episodes"), more = true),
            Section("Trailers", "YouTube trailers for films and shows.", all(P, "Trailers"), more = true),
            Section("Music", "Where lyrics come from.", r(P, "Music", "Lyrics from LRCLIB"), more = true),
        )),
        Page(NAVIGATION, "Menu & navigation", listOf(
            Section("Start", "What you see when Viewarr opens.", all(N, "Start")),
            Section("Menu", "Hints: what the buttons do on each screen.", r(N, "Menu", "Button hints")),
            Section("What the menu lists", "What's listed in the menu on the left. Live TV and Settings are always there; streaming services appear once your addons' rows have loaded.",
                all(A, "Main menu")),
            Section("Poster rows", "Moving around the rows of posters on Movies, Shows and title pages.", all(N, "Poster rows")),
            Section("Notifications", "Everything Viewarr pops up by itself: switch it all off, or choose each kind.", all(P, "Notifications")),
        )),
        // Settings most people never need: how the player works inside, sizes that every page was tuned around, and how
        // the guide is fetched. Kept together behind a warning so they aren't changed by accident (2026-11-20,
        // after a tester's layout broke from one of them). Each is listed here by name, so it leaves its old page.
        Page(ADVANCED, "Advanced", listOf(
            Section("Player", "$CARE Which player plays films and how it draws the picture.",
                r(P, "Player", "Player", "Video view", "mpv decoding")),
            Section("Video & sound", "$CARE How the picture is fitted and how sound is sent to your TV or receiver.",
                r(P, "Video", "Sound after a frame-rate switch", "Picture size", "Deinterlace") + r(P, "Audio", "Surround passthrough", "Sound channels", "Audio sync")),
            Section("Streams", "$CARE Quality limits and how much video is loaded ahead.",
                r(P, "Streams", "Best quality allowed", "Plex quality", "Buffer")),
            Section("Screen & sizes", "$CARE Every page is laid out for the normal sizes; other sizes fit less on screen.",
                r(A, "Colours & text", "App scaling", "List scrolling") + r(A, "Live TV", "Channel rows", "Info bar size", "Info bar width", "Info bar corners", "Preview cards") +
                    r(P, "Player", "Player controls size") + r(A, "Browsing style", "Top panel", "Row posters") + r(A, "Posters", "Library posters")),
            Section("TV guide data", "$CARE How far the guide reaches, how often it's fetched, and event channels.",
                r(P, "Live TV", "TV guide days ahead", "TV guide refresh", "Event channels: only with a listing")),
        )),
        Page(SYSTEM, "Troubleshooting", listOf(
            Section("Troubleshooting", "Tools for when something doesn't play right, or the box struggles, and settings codes to copy settings between TVs.", all(P, "Troubleshooting")),
        )),
    )

    /** The page each source's unplaced settings go to ("More"). */
    private val home = mapOf(A to APPEARANCE, P to PLAYBACK, N to NAVIGATION)

    fun page(id: String) = pages.first { it.id == id }

    /** One setting from a source, where it was built. */
    class Entry(val source: String, val section: String, val item: SettingItem) {
        val key get() = "$source/$section/${item.title}"
    }

    /** Every ref placed by name, and every "all" ref's prefix (fixed: worked out once, not per page shown). */
    private val named by lazy { pages.flatMap { p -> p.sections.flatMap { it.refs } }.filterNot { it.endsWith("/*") }.toSet() }
    private val wild by lazy { pages.flatMap { p -> p.sections.flatMap { it.refs } }.filter { it.endsWith("/*") }.map { it.removeSuffix("*") }.toSet() }

    /** The sources [id] lists settings from (its refs', and its own "More"): only these need building to show it. */
    fun sources(id: String): Set<String> =
        page(id).sections.flatMap { sec -> sec.refs.map { it.substringBefore('/') } }.toSet() + home.filterValues { it == id }.keys

    /** [id]'s sections, filled from [pool] (empty sections left out). */
    fun categories(id: String, pool: List<Entry>): List<SettingCategory> {
        val page = page(id)
        val out = page.sections.mapNotNull { sec ->
            val items = sec.refs.flatMap { ref ->
                if (ref.endsWith("/*")) {
                    val prefix = ref.removeSuffix("*")
                    pool.filter { it.key.startsWith(prefix) && it.key !in named }.map { it.item }
                } else pool.filter { it.key == ref }.map { it.item }
            }
            if (items.isEmpty()) null else SettingCategory(sec.name, sec.blurb, items, more = sec.more)
        }.map { c ->
            // One title once per section (a repeat is left out and logged). Not per page: Playback rightly has a
            // "Language" under Audio and another under Subtitles, and a page-wide rule hid the second (2026-11-20).
            val seen = HashSet<String>()
            val keep = c.items.filter { seen.add(it.title).also { ok -> if (!ok) android.util.Log.w("SettingsMap", "\"${it.title}\" twice in ${c.name} on $id: left out") } }
            if (keep.size == c.items.size) c else SettingCategory(c.name, c.blurb, keep, more = c.more)
        }
        // Not placed anywhere: listed on its source's page so it's never lost.
        val more = pool.filter { e -> home[e.source] == id && e.key !in named && wild.none { e.key.startsWith(it) } }.map { it.item }
        return if (more.isEmpty()) out else out + SettingCategory("More", "Other settings.", more, more = out.any { it.more })
    }

    /** Every setting with its page and section, for Search settings. */
    fun index(pool: List<Entry>): List<Hit> = pages.flatMap { p ->
        categories(p.id, pool).flatMap { c -> c.items.map { Hit(p.id, p.title, c.name, it.title, it.description) } }
    }

    class Hit(val page: String, val pageTitle: String, val section: String, val title: String, val description: String)
}

/**
 * Every setting, from where each is built (the catalogue the map places). [sources]: just these (a page shows one or
 * two; the rest aren't built). Fixed for a screen, so the calls below stay put.
 */
@Composable
fun settingsPool(
    appearance: AppearanceViewModel = hiltViewModel(),
    playback: PlaybackSettingsViewModel = hiltViewModel(),
    remote: RemoteViewModel = hiltViewModel(),
    opts: UiOptsViewModel = hiltViewModel(),
    sources: Set<String> = setOf("Appearance", "Playback", "Navigation"),
): List<SettingsMap.Entry> {
    fun from(source: String, cats: List<SettingCategory>) = cats.flatMap { c -> c.items.map { SettingsMap.Entry(source, c.name, it) } }
        // (Viewarr launcher: no theme or wallpaper choices; its one look, LauncherLook.)
        .filterNot { io.github.petebutty.viewarr.ui.launcher.LauncherLook.hides(it.key) }
        // (Previewarr Mobile: the TV-only ones too.)
        .filterNot { io.github.petebutty.viewarr.ui.mobile.MobileSettingsFilter.hides(it.key) }
    return (if ("Appearance" in sources) from("Appearance", appearanceCategories(appearance, opts)) else emptyList()) +
        (if ("Playback" in sources) from("Playback", playbackCategories(playback, opts)) else emptyList()) +
        (if ("Navigation" in sources) from("Navigation", navigationCategories(remote, opts)) else emptyList())
}

/** A page of Settings as the map lays it out ([startSection] / [startItem]: open there, that setting highlighted). */
@Composable
fun MappedSettingsScreen(pageId: String, onBack: () -> Unit, startSection: String? = null, startItem: String? = null) {
    val appearance: AppearanceViewModel = hiltViewModel()
    val playback: PlaybackSettingsViewModel = hiltViewModel()
    val opts: UiOptsViewModel = hiltViewModel()
    val news: NewsViewModel = hiltViewModel()
    // Only the sources this page lists from (Playback's page doesn't build Appearance's or Navigation's settings).
    val pool = settingsPool(appearance = appearance, playback = playback, opts = opts, sources = remember(pageId) { SettingsMap.sources(pageId) })
    val page = SettingsMap.page(pageId)
    val reset: (() -> Unit)? = when (pageId) {
        SettingsMap.APPEARANCE -> ({ appearanceReset(appearance, opts, playback, news.feeds.all().map { it.id }) })
        SettingsMap.PLAYBACK -> ({ playbackReset(playback, opts, appearance) })
        SettingsMap.ADVANCED -> ({ advancedReset(playback, opts) })
        else -> null
    }
    SettingsPage(title = page.title, allCategories = SettingsMap.categories(pageId, pool), onBack = onBack, onReset = reset,
        startSection = startSection, startItem = startItem)
}

/** Settings → Advanced → Reset to defaults: just the settings on that page. */
fun advancedReset(vm: PlaybackSettingsViewModel, opts: UiOptsViewModel) {
    val k = io.github.petebutty.viewarr.data.settings.PlaybackStore.Companion
    val u = io.github.petebutty.viewarr.ui.theme.UiOpts
    vm.resetKeys(listOf(k.ENGINE, k.VIDEO_VIEW, k.MPV_DECODING, k.PICTURE_SIZE, k.DEINTERLACE, k.PASSTHROUGH, k.DOWNMIX, k.AUDIO_DELAY, k.MAX_RES, k.PLEX_QUALITY, k.BUFFER))
    opts.reset(listOf(u.AFR_SOUND_DELAY, u.PICTURE_SIZE, u.LIST_SCROLL, u.GUIDE_ROWS, u.INFOBAR_SIZE, u.INFOBAR_WIDTH, u.INFOBAR_CORNERS, u.CARD_SIZE,
        u.CONTROLS_SIZE, u.HERO_STYLE, u.ROW_POSTERS, u.LIBRARY_POSTERS, u.GUIDE_DAYS, u.GUIDE_REFRESH, u.EVENT_ONLY_LISTED))
}
