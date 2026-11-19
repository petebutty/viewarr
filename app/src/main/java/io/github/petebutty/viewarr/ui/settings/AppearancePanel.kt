package io.github.petebutty.viewarr.ui.settings

import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.settings.ThemeStore
import io.github.petebutty.viewarr.data.settings.PlaybackStore
import io.github.petebutty.viewarr.ui.common.BarLayout
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.HighlightChoices
import io.github.petebutty.viewarr.ui.theme.MainColourChoices
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.ThemePresets
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.github.petebutty.viewarr.data.news.NewsFeeds
import io.github.petebutty.viewarr.data.news.TickerStyle
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.ui.common.NewsViewModel
import io.github.petebutty.viewarr.ui.common.Ticker
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.media.MediaViewModel
import io.github.petebutty.viewarr.ui.player.RowSets
import io.github.petebutty.viewarr.ui.setup.WizardField
import io.github.petebutty.viewarr.ui.theme.TitleParts
import io.github.petebutty.viewarr.ui.theme.WallpaperModes
import io.github.petebutty.viewarr.ui.theme.WallpaperStyles
import io.github.petebutty.viewarr.ui.theme.UiOpts

@HiltViewModel
class AppearanceViewModel @Inject constructor(private val store: ThemeStore) : ViewModel() {
    fun apply(main: Color, highlight: Color) {
        viewModelScope.launch { store.save(main, highlight) }
    }

    fun setHideMedia(on: Boolean) {
        viewModelScope.launch { store.saveHideMedia(on) }
    }
    fun setMenuOrder(ids: List<String>) { viewModelScope.launch { store.saveMenuOrder(ids) } }
    fun setBarOrder(bar: String, ids: List<String>) { viewModelScope.launch { store.saveBarOrder(bar, ids) } }
    fun setRatingsOrder(ids: List<String>) { viewModelScope.launch { store.saveRatingsOrder(ids) } }

    fun setSquareCorners(on: Boolean) {
        viewModelScope.launch { store.saveSquareCorners(on) }
    }

    fun setProgressBars(mode: Int) {
        viewModelScope.launch { store.saveProgressBars(mode) }
    }

    fun setTitlePart(part: String, on: Boolean) { viewModelScope.launch { store.saveTitlePart(part, on) } }
    fun resetTitleParts() { viewModelScope.launch { store.resetTitleParts() } }
    fun setShowNumbers(on: Boolean) {
        viewModelScope.launch { store.saveShowNumbers(on) }
    }

    fun setWallpaper(mode: Int, style: Int, dim: Float) {
        viewModelScope.launch { store.saveWallpaper(mode, style, dim) }
    }

    fun setTransparency(value: Float) {
        viewModelScope.launch { store.saveTransparency(value) }
    }

    fun setGuideTransparency(value: Float) {
        viewModelScope.launch { store.saveGuideTransparency(value) }
    }
}

private class Choice(override val label: String, val value: Any) : RemoteChoice {
    override fun equals(other: Any?) = other is Choice && other.value == value
    override fun hashCode() = value.hashCode()
}

private val MainColourNames = listOf("Charcoal (Viewarr)", "Classic blue", "Graphite", "Deep teal", "Plum", "Oxblood", "Forest", "Slate", "Aubergine", "OLED black", "Ocean blue", "Sky blue")
private val HighlightNames = listOf("Cyan", "Pink", "Sky blue", "Blue", "Teal", "Lilac", "Orange", "Green", "Amber", "Rose", "Bright cyan")

/** Settings → Appearance, full screen like the other settings pages. */
@Composable
fun appearanceCategories(vm: AppearanceViewModel = hiltViewModel(), opts: UiOptsViewModel = hiltViewModel()): List<SettingCategory> {
    val palette = AppColors.palette
    val highs = HighlightChoices.mapIndexed { i, c -> Choice(HighlightNames.getOrElse(i) { "Colour ${i + 1}" }, c) }
    val mains = MainColourChoices.mapIndexed { i, c -> Choice(MainColourNames.getOrElse(i) { "Colour ${i + 1}" }, c) }
    val corners = listOf(Choice("Rounded", false), Choice("Square", true))
    val transparencies = (0..100 step 10).map { Choice("$it%", it) }
    val currentT = ((palette.transparency * 100).roundToInt() / 10 * 10).coerceIn(0, 100)
    val currentG = ((palette.guideTransparency * 100).roundToInt() / 10 * 10).coerceIn(0, 100)
    val wallModes = WallpaperModes.map { (v, n) -> Choice(n, v) }
    val wallStyles = WallpaperStyles.mapIndexed { i, w -> Choice(w.name, i) }
    val dims = (30..90 step 10).map { Choice("$it%", it) }
    // Low-memory mode: its settings stay listed, locked, with how to get them back.
    val lowLock = if (palette.lowMemory) Locks.LOW_MEMORY else null
    // The main menu's choices: its pages and the streaming services from your addons (AIOMetadata's rows).
    val moviesM: MediaViewModel = hiltViewModel(key = "media-Movie")
    val showsM: MediaViewModel = hiltViewModel(key = "media-Show")
    val mRows by moviesM.menuRows.collectAsStateWithLifecycle()
    val sRows by showsM.menuRows.collectAsStateWithLifecycle()
    val serviceNames = (RowSets.sets(sRows.rows) + RowSets.sets(mRows.rows))
        .filter { it.startsWith("name:") }.map { it.removePrefix("name:") }.distinct()
    fun menuItem(label: String, id: String, description: String, alsoOn: () -> Unit = {}) =
        onOffItem(label, description, palette.shows(id)) { on ->
            vm.setTitlePart(id, on)
            if (on) alsoOn()
        }
    val categories = listOfNotNull(
        SettingCategory(
            "Colours & text", "Colours, text size, the clock and how dates and times show. Changes show straight away.",
            listOf(
                // The background behind everything (the glass is tinted by it too): Viewarr's charcoal, or a colour.
                SettingItem("Background colour", "The colour behind the guide, menus and pages. Classic blue, Ocean blue and Sky blue are brighter looks.", mains,
                    mains.firstOrNull { it.value == palette.main } ?: Choice("Custom", palette.main)) { c ->
                    vm.apply((c as Choice).value as Color, palette.highlight)
                },
                SettingItem("Highlight colour", "The colour of whatever is selected.", highs,
                    highs.firstOrNull { it.value == palette.highlight } ?: Choice("Custom", palette.highlight)) { c ->
                    vm.apply(palette.main, (c as Choice).value as Color)
                },
                optItem(opts, "Text size", "How big text and everything else on screen is. Larger is easier to read from the sofa.", UiOpts.TEXT_SIZE),
                optItem(opts, "Font", "The lettering for all text, from the fonts built into your box (a box without one shows the standard font).", UiOpts.FONT),
                optItem(opts, "List scrolling", "How lists follow the highlight. Instant: the list moves with it in one go (no trail when you go through quickly). Smooth: it slides there. (Poster rows and library grids always glide.)", UiOpts.LIST_SCROLL),
                optItem(opts, "Clock", "The time in the top corner.", UiOpts.CLOCK),
                optItem(opts, "Date format", "How dates show across Viewarr: the guide, recordings, episodes and reviews.", UiOpts.DATE_ORDER),
                optItem(opts, "Time format", "24-hour or 12-hour times: the guide, the clock, programme times and recordings.", UiOpts.TIME_FORMAT),
            ),
        ),
        // Low-memory mode: no wallpaper at all (plain Viewarr colours): locked.
        SettingCategory(
            "Wallpaper", "A picture behind the guide, menus and settings, darkened so text stays easy to read.",
            buildList {
                add(SettingItem("Wallpaper", "None, a built-in design, or the artwork of the programme you've highlighted.", wallModes,
                    wallModes.firstOrNull { it.value == palette.wallpaper } ?: wallModes[0]) { c -> vm.setWallpaper(((c as Choice).value as Int), palette.wallpaperStyle, palette.wallpaperDim) })
                add(optItem(opts, "Movies and Shows background", "What's selected: the highlighted title's artwork. Your wallpaper: the wallpaper stays behind (the title's picture then shows in the top panel). None: no picture at all, just the background colour.", UiOpts.TITLE_BG))
                add(optItem(opts, "Wallpaper in the TV guide", "Off: a plain background behind the guide, the wallpaper everywhere else.", UiOpts.GUIDE_WALL))
                add(optItem(opts, "Wallpaper shade", "What darkens the wallpaper and the Movies / Shows artwork. Background colour: your theme's colour washed over the picture. Black: darker without tinting, so pictures keep their own colours.", UiOpts.WALL_SHADE))
                if (palette.wallpaper == 1) add(SettingItem("Design", "Which built-in wallpaper.", wallStyles,
                    wallStyles[palette.wallpaperStyle.coerceIn(0, wallStyles.lastIndex)]) { c -> vm.setWallpaper(1, (c as Choice).value as Int, palette.wallpaperDim) })
            }.map { it.locked(lowLock) },
        ),
        // Every transparency in one place, as sliders (Left / Right).
        SettingCategory(
            "Transparency", "Left / Right on each: how much shows through, from 0% (solid) to 100% (clear). It shows most over live TV, a film or a wallpaper; over a plain background there's little behind to see.",
            buildList {
                add(SettingItem("Glass", "The menu strip on the left and the channel list, on every page and over video.", transparencies,
                    transparencies.first { it.value == currentT }, slider = true) { c -> vm.setTransparency(((c as Choice).value as Int) / 100f) })
                add(optItem(opts, "Glass colour", "A colour through the glass: the menu, the channel list, the info bar and every pop-up. Plain is the Viewarr look.", UiOpts.GLASS_TINT))
                add(SettingItem("Guide", "The guide's channel names and programmes (the one you're on stays solid). Shows more of the wallpaper behind.", transparencies,
                    transparencies.first { it.value == currentG }, slider = true) { c -> vm.setGuideTransparency(((c as Choice).value as Int) / 100f) })
                if (palette.wallpaper != 0) add(SettingItem("Wallpaper dimming", "How dark the wallpaper is under the menus and text.", dims,
                    dims.firstOrNull { it.value == (palette.wallpaperDim * 100).roundToInt() / 10 * 10 } ?: dims[3], slider = true) { c ->
                    vm.setWallpaper(palette.wallpaper, palette.wallpaperStyle, ((c as Choice).value as Int) / 100f)
                }.locked(lowLock))
            },
        ),
        SettingCategory(
            "Live TV", "The TV guide, the channel list, and the info bar over live TV (when you change channel or press OK / Info).",
            listOf(
                onOffItem("Channel numbers", "Show each channel's number next to its name.", palette.showNumbers) { on -> vm.setShowNumbers(on) },
                SettingItem("Programme corners", "The guide's programmes and channel names: rounded, or square-cornered boxes.", corners,
                    if (palette.squareCorners) corners[1] else corners[0]) { c -> vm.setSquareCorners((c as Choice).value == true) },
                SettingItem("Progress bars", "The bar showing how far through each programme on now is: on every channel, only the channel you're watching, or none.", progressChoices,
                    progressChoices[palette.progressBars.coerceIn(0, 2)]) { c -> vm.setProgressBars((c as Choice).value as Int) },
                optItem(opts, "Channel list width", "How wide the channel list is over live TV (Left while watching): Medium, Narrow or Wide.", UiOpts.CHLIST_WIDTH),
                optItem(opts, "Channel list progress", "A bar under the programme in the channel list, showing how far through it is: on every channel, on the highlighted channel only, or off.", UiOpts.CHLIST_PROGRESS),
                optItem(opts, "Highlighted progress colour", "The progress bar's colour on the highlighted channel in the channel list (on your highlight colour, so it shows up).", UiOpts.CHLIST_PROGRESS_COLOUR),
                optItem(opts, "Channel logos", "Logos beside the channel names.", UiOpts.CHANNEL_LOGOS),
                optItem(opts, "Info bar style", "How the info bar looks over live TV and films. Each style shows as you move through them.", UiOpts.INFOBAR_STYLE),
                optItem(opts, "Info bar corners", "How rounded the info bar's corners are (Glass).", UiOpts.INFOBAR_CORNERS),
                optItem(opts, "Info bar size", "How big the info bar is drawn over live TV and films: its text, buttons and preview rows together. Smaller covers less of the picture.", UiOpts.INFOBAR_SIZE),
                optItem(opts, "Clock on the info bar", "The time at the right of the info bar: live TV, its controls, and films.", UiOpts.INFOBAR_CLOCK),
                optItem(opts, "Info bar width", "How wide the info bar is: the whole screen, or narrower and centred at the bottom (live TV and films alike).", UiOpts.INFOBAR_WIDTH),
                optItem(opts, "Preview cards", "The size of the cards in the info bar's rows (next episodes, shortcuts).", UiOpts.CARD_SIZE),
                optItem(opts, "Episode numbers", "Series and episode numbers after programme names (\"S15E4\") in the guide and the channel list.", UiOpts.GUIDE_EPISODE),
                optItem(opts, "End of a list", "The TV guide, the channel list and its groups, past the last channel (or above the first). Jump to the other end: one more press goes to the top (holding the button stops at the end, so a long scroll doesn't fly round). Stop: nothing more.", UiOpts.LIST_ENDS),
                optItem(opts, "Timeline", "How much time the TV guide shows across the screen. More time: more programmes at once, each one narrower.", UiOpts.GUIDE_SPAN),
                optItem(opts, "Catch-up icons", "The replay icon on past programmes you can watch again, and beside channels that have catch-up (or beside the highlighted channel only, so the list stays calm). Catch-up still works with it off.", UiOpts.CATCHUP_ICONS),
                optItem(opts, "Elapsed shading", "On a programme that's on now: the part that's already gone is lightly shaded up to the time line.", UiOpts.GUIDE_SHADE),
                // Low-memory mode: no programme pictures at all, so these are locked.
                optItem(opts, "Programme pictures", "The highlighted programme's picture at the top of the guide. Off: no pictures are looked up (the channel's logo shows instead).", UiOpts.GUIDE_ART).locked(lowLock),
                optItem(opts, "Programme picture shows as", "Background: it fills the screen behind the guide (with the \"What's selected\" wallpaper). Banner: a picture card at the right of the programme details, and the background stays as it is.", UiOpts.GUIDE_ART_PLACE).locked(lowLock),
                optItem(opts, "Next and Then", "At the top of the guide, what's on the highlighted channel next and after that. Off: just the programme's details.", UiOpts.GUIDE_NEXT),
                optItem(opts, "Episode badge at the top", "The series and episode badge (\"S12 E4\") at the top right of the guide, under Next and Then. Off hides it; Catch-up and Recording badges stay.", UiOpts.GUIDE_EPISODE_BADGE),
                optItem(opts, "Catch-up badge at the top", "The \"Catch-up\" badge at the top right of the guide, on programmes that can be replayed. Off hides it (the channel column's catch-up marks stay).", UiOpts.GUIDE_CATCHUP_BADGE),
                optItem(opts, "Programme title logos", "At the top of the guide, the highlighted programme's title logo (from TMDB) in place of its name, when there's a sure match (a UK, Irish or US show, or the only film with that name). Off: always the name.", UiOpts.GUIDE_TITLE_LOGO).locked(lowLock),
                // The info bar over live TV.
                // The glass: the info bar and controls, and every pop-up (next episode, skip intro, reminders, notes).
                optSlider(opts, "Pop-up glass", "Left / Right: how see-through the info bar, the player's controls and every pop-up and notice are (record options, next episode, skip intro, reminders, notes), from solid (0%) to clear (100%).", UiOpts.POPUP_SEE, AppColors.palette.popupSee),
                optItem(opts, "Info bar: border", "The thin light edge round the glass.", UiOpts.BANNER_BORDER),
                optItem(opts, "Info bar: channel number", "The channel's number at the left of the info bar.", UiOpts.BANNER_NUMBER),
                optItem(opts, "Info bar: episode numbers", "Series and episode numbers after programme names (\"S2026E180\") in the info bar.", UiOpts.BANNER_EPISODE),
                optItem(opts, "Info bar: picture quality", "Badges for the channel's picture (HD, FHD, 4K, or 720p, 1080p, 2160p) and 5.1 sound, from the stream itself.", UiOpts.BANNER_BADGES),
                optItem(opts, "Info bar: source", "The provider of the channel playing (with Dispatcharr: the one actually in use).", UiOpts.BANNER_SOURCE),
                optItem(opts, "Info bar: frame rate", "A badge with the stream's frames per second (25, 50…).", UiOpts.BANNER_FPS),
                infoRowsItem(),
            ),
        ),
        newsTickerCategory(opts),
    ) + listOfNotNull(
        // Programme title logos: off for chosen channel groups (only while title logos are on).
        if (palette.guideTitleLogos) titleLogoGroupsCategory(vm) else null,
    ) + listOf(
        // Browsing style (Peter, 2026-11-18): one choice setting the Movies / Shows look, each part still its own
        // setting below it. Viewarr: as before. Cinema (Nuvio style): a big top panel, trailers in the poster, larger
        // posters. Lite (weaker boxes): no top panel, no trailers, smaller posters, no title logos.
        run {
            val pb = io.github.petebutty.viewarr.data.settings.LocalPlayback.current
            val playbackVm: PlaybackSettingsViewModel = hiltViewModel()
            val sources = palette.sourceBadges
            val logo = palette.shows(TitleParts.LOGO)
            val trailersOn = pb.trailerPreview == io.github.petebutty.viewarr.data.settings.TrailerPreview.On
            data class Look(val name: String, val hero: Int, val posters: Int, val inPoster: Int, val sourceBadge: Boolean, val logo: Boolean, val trailers: Boolean)
            val looks = listOf(
                Look("Viewarr", 0, 0, 0, true, true, true),
                Look("Cinema", 1, 1, 1, false, true, true),
                Look("Lite", 2, 2, 0, false, false, false),
            )
            val now = looks.firstOrNull { l ->
                palette.opt(UiOpts.HERO_STYLE) == l.hero && palette.opt(UiOpts.ROW_POSTERS) == l.posters &&
                    palette.opt(UiOpts.TRAILER_STYLE) == l.inPoster && sources == l.sourceBadge && logo == l.logo && trailersOn == l.trailers
            }
            val styleChoices = looks.map { Choice(it.name, it) }
            SettingCategory(
                "Browsing style", "The look of Movies, Shows and the networks' pages in one go. Each part can still be changed on its own below (then it says Custom).",
                listOf(
                    SettingItem("Browsing style", "Viewarr: as standard, a top panel with the trailer in a corner window. Cinema: a bigger top panel, trailers playing in the poster, larger posters. Lite, for weaker boxes: no top panel, no trailer previews or title logos, smaller posters, quickest to scroll.",
                        styleChoices, styleChoices.firstOrNull { (it.value as Look).name == now?.name } ?: Choice("Custom", Unit)) { c ->
                        val l = (c as Choice).value as? Look ?: return@SettingItem
                        opts.set(UiOpts.HERO_STYLE, l.hero); opts.set(UiOpts.ROW_POSTERS, l.posters); opts.set(UiOpts.TRAILER_STYLE, l.inPoster)
                        opts.set(UiOpts.BADGE_SOURCE, if (l.sourceBadge) 0 else 1)
                        vm.setTitlePart(TitleParts.LOGO, l.logo)
                        playbackVm.set(io.github.petebutty.viewarr.data.settings.PlaybackStore.TRAILER_PREVIEW,
                            if (l.trailers) io.github.petebutty.viewarr.data.settings.TrailerPreview.On else io.github.petebutty.viewarr.data.settings.TrailerPreview.Off)
                        // Cinema: the trailer starts after 5 seconds on a poster, full screen 5 seconds later (as Nuvio KE).
                        if (l.name == "Cinema") {
                            playbackVm.set(io.github.petebutty.viewarr.data.settings.PlaybackStore.TRAILER_DELAY, io.github.petebutty.viewarr.data.settings.TrailerDelay.S5)
                            opts.set(UiOpts.TRAILER_GROW, 4)
                        } else playbackVm.set(io.github.petebutty.viewarr.data.settings.PlaybackStore.TRAILER_DELAY, io.github.petebutty.viewarr.data.settings.TrailerDelay.S3) // (back to the usual 3 s)
                    },
                    optItem(opts, "Top panel", "The highlighted title's details at the top of Movies and Shows: normal, big, or off (more rows on screen).", UiOpts.HERO_STYLE),
                    optItem(opts, "Row posters", "How big the posters in the rows are.", UiOpts.ROW_POSTERS),
                ),
            )
        },
        SettingCategory(
            "Posters", "How big library posters are, and what's drawn on posters in Movies, Shows and search.",
            listOf(
                optItem(opts, "Library posters", "How big the posters are in a library's grid (Plex, Jellyfin, Emby, IPTV). Small is the same size as the posters in the rows; as many fit across as there's room for, with the mini player or menu beside it too.", UiOpts.LIBRARY_POSTERS),
                optItem(opts, "Where it's from", "The label saying which of your servers has it (e.g. \"Plex · Living room\").", UiOpts.BADGE_SOURCE),
                optItem(opts, "Progress bars", "How far through you are, along the bottom of posters and episodes.", UiOpts.BADGE_PROGRESS),
                optSlider(opts, "Other posters faded", "Left / Right: how much the posters you're not on fade in the rows, so the highlighted one stands out. Off: all solid.", UiOpts.POSTER_DIM, palette.opt(UiOpts.POSTER_DIM)),
                optItem(opts, "Highlight zoom", "The highlighted poster grows a little (Slight), more, or stays its size.", UiOpts.POSTER_ZOOM),
            ),
        ),
        SettingCategory(
            "Title pages", "What a film's or show's page shows. Anything off isn't fetched either, so pages open with less to do.",
            TitleParts.sections.map { (id, text) ->
                onOffItem(text.first, text.second, palette.shows(id)) { on -> vm.setTitlePart(id, on) }
                    .locked(if (id == TitleParts.LOGO) lowLock else null)
            } + optItem(opts, "Age ratings", "Which country's age ratings title pages show.", UiOpts.AGE_RATING),
        ),
        // Low-memory mode fetches only TMDB's rating: the others locked.
        SettingCategory(
            "Ratings", "Which ratings show on title pages and the Movies / Shows header, and in what order. With all but TMDB off, the other ratings aren't fetched.",
            // In the order they show (hold OK → Move or Right picks one up, Up / Down move it, Right puts it down); OK switches it on / off.
            TitleParts.ratings.sortedBy { palette.ratingRank(it.first) }.let { ordered ->
                ordered.mapIndexed { i, (src, name) ->
                    val id = TitleParts.rating(src)
                    onOffItem(name, "${i + 1} of ${ordered.size}  ·  Hold OK: move", palette.shows(id),
                        reorder = { delta ->
                            val list = ordered.map { it.first }.toMutableList()
                            val j = (i + delta).coerceIn(0, list.lastIndex)
                            if (j != i) { list.add(j, list.removeAt(i)); vm.setRatingsOrder(list) }
                        },
                    ) { on -> vm.setTitlePart(id, on) }
                        .locked(if (src != "TMDB") lowLock else null)
                }
            },
        ),
        // The info bars' buttons (live TV's and the film / episode player's share one list): ticked ones are on the bar, in
        // this order. The main player buttons are always on, and can only be moved. (Peter, 2026-11-17: a setting like the
        // others, not a ⋯ More menu.)
        SettingCategory(
            "Info bar buttons", "The buttons on the info bar (OK while watching) and their order; each bar's main buttons always stay. Hold OK on a button on either bar (live TV or films) to open this list.",
            BarLayout.orderedIds().filter { it in BarLayout.labels }.let { ids ->
                ids.mapIndexed { i, id ->
                    val where = when (id) { in BarLayout.liveOnly -> " (live TV)"; in BarLayout.filmOnly -> " (films)"; else -> "" }
                    onOffItem(
                        BarLayout.labels.getValue(id) + where,
                        "${i + 1} of ${ids.size}  ·  Hold OK: move" + (BarLayout.lockNote(id)?.let { "  ·  $it" } ?: ""),
                        BarLayout.shown(id),
                        reorder = { delta -> BarLayout.moved(ids, id, delta)?.let { vm.setBarOrder("all", it) } },
                    ) { on -> BarLayout.setShown(id, on, vm::setTitlePart) }
                }
            },
        ),
        // What the main menu lists (Live TV and Settings are always there).
        SettingCategory(
            "Main menu", "What's listed in the menu on the left. Live TV and Settings are always there; streaming services appear once your addons' rows have loaded.",
            listOf(
                optItem(opts, "List headings", "A heading at the top of each list in the menu (\"LIVE TV · CHANNEL GROUPS\", \"MOVIES\", \"SETTINGS\"…), or the path to it (\"Live TV › Channel groups\"), so the guide's channel groups are never mistaken for your libraries.", UiOpts.MENU_HEADINGS),
                optItem(opts, "Source in the Live TV heading", "With more than one Live TV source: its name in the heading (\"LIVE TV · DISPATCHARR\").", UiOpts.MENU_HEAD_SOURCE),
                optItem(opts, "TV mark on channel groups", "A small 📺 before each channel group's name.", UiOpts.MENU_GROUP_ICON),
                optItem(opts, "Colour edge", "A thin bar down the edge of each list in your highlight colour.", UiOpts.MENU_EDGE),
                optItem(opts, "Colour tint", "Each list's glass faintly tinted in its section's colour.", UiOpts.MENU_TINT),
                optItem(opts, "LIVE mark", "A red ● LIVE on the channel groups' All channels.", UiOpts.MENU_LIVE_MARK),
                optItem(opts, "Hidden groups row", "A \"Hidden groups\" row at the end of the channel groups. Off: hold OK on any group → Show hidden groups.", UiOpts.MENU_HIDDEN),
                optItem(opts, "Left / Right hints", "What Right does under the logo (Right › go into Movies), how to edit the menu, and ‹ Left: main menu at the top of a list.", UiOpts.MENU_HINTS),
                menuItem("Sports", "menu:Sports", "Your teams' and leagues' games: what's on, coming up and results, and where to watch them."),
                menuItem("Movies", "menu:Movies", "Your films: libraries, catalogues and rows.") { vm.setHideMedia(false) },
                menuItem("Shows", "menu:Shows", "Your TV shows: libraries, catalogues and rows.") { vm.setHideMedia(false) },
                menuItem("Music", "menu:Music", "Your music (when a music source is connected)."),
                menuItem("Recordings", "menu:Recordings", "Recorded and scheduled programmes on Dispatcharr."),
                menuItem("Search", "menu:Search", "Search everything (the search button on the remote works either way)."),
            ) + serviceNames.map { n -> menuItem(n, "menu:svc:$n", "$n's rows (Popular, New, Top rated…) under Shows in the menu.") },
        ),
    )
    return categories
}

/**
 * The display settings (UiOpts) the Appearance page lists, section by section as SettingsMap lays it out (keep the two
 * in step). Settings on other pages (End of a list, the menu's own entries) aren't here, so Reset leaves them alone.
 */
private val AppearanceOpts = UiOpts.run {
    listOf(
        TEXT_SIZE, FONT, CLOCK, DATE_ORDER, TIME_FORMAT, // Theme
        GLASS_TINT, POPUP_SEE, POPUP_GLASS, BANNER_BORDER, // Glass & transparency (Pop-up glass's older form too)
        CHANNEL_LOGOS, GUIDE_EPISODE, CHLIST_WIDTH, CHLIST_PROGRESS, CHLIST_PROGRESS_COLOUR, GUIDE_SPAN, CATCHUP_ICONS, GUIDE_SHADE, GUIDE_ART, GUIDE_ART_PLACE, WALL_SHADE, GUIDE_NEXT, GUIDE_EPISODE_BADGE, GUIDE_CATCHUP_BADGE, TITLE_BG, GUIDE_WALL, GUIDE_TITLE_LOGO, // TV guide
        INFOBAR_STYLE, INFOBAR_CORNERS, INFOBAR_SIZE, INFOBAR_CLOCK, INFOBAR_WIDTH, CARD_SIZE,
        BANNER_NUMBER, BANNER_EPISODE, BANNER_BADGES, BANNER_SOURCE, BANNER_FPS, CONTROLS_SIZE, // Info bar & controls
        MENU_HEADINGS, MENU_HEAD_SOURCE, MENU_EDGE, MENU_TINT, MENU_HINTS, MENU_GROUP_ICON, MENU_LIVE_MARK, MENU_HIDDEN, BUTTON_HINTS, // Menu
        NEWS_INFOBAR, NEWS_GUIDE, NEWS_PAGES, NEWS_SPEED, // News ticker
        HERO_STYLE, ROW_POSTERS, LIBRARY_POSTERS, BADGE_SOURCE, BADGE_PROGRESS, POSTER_DIM, POSTER_ZOOM, AGE_RATING, // Browsing style, Posters, Title pages
        MUSIC_ART, MUSIC_VIS, MUSIC_SAVER, MUSIC_LYRICS, LIST_SCROLL, // Music, Motion
    )
}

/**
 * The Appearance page's Reset: everything it lists back to how a fresh install has it (the Viewarr look the theme
 * migration in ThemeStore writes, so the font and text size don't end up a mix nobody chose). [feedIds]: the news
 * feeds listed, each back to its own on / off and look.
 */
fun appearanceReset(vm: AppearanceViewModel, opts: UiOptsViewModel, playback: PlaybackSettingsViewModel, feedIds: List<String>) {
    vm.apply(ThemePresets[0].main, ThemePresets[0].highlight)
    vm.setTransparency(0.5f); vm.setGuideTransparency(0.7f); vm.setWallpaper(3, 0, 0.7f)
    vm.setShowNumbers(false); vm.setSquareCorners(false); vm.setProgressBars(1)
    // Title pages, ratings (and their order) and title logos by group; the menu's entries are another page's.
    vm.resetTitleParts(); vm.setRatingsOrder(emptyList())
    val u = UiOpts
    opts.reset(AppearanceOpts + feedIds.flatMap { listOf(u.newsFeedKey(it), u.newsStyleKey(it)) })
    // How subtitles look is kept with the playback settings.
    playback.resetKeys(listOf(PlaybackStore.SUB_SIZE, PlaybackStore.SUB_COLOUR, PlaybackStore.SUB_BACKGROUND))
}

private val progressChoices = listOf(Choice("All channels", 0), Choice("Only what you're watching", 1), Choice("Off", 2))

/** Adding your own news feed: a name and its RSS / Atom address, checked for headlines before it's kept. */
@Composable
private fun AddNewsFeedDialog(feeds: NewsFeeds, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { first.requestFocus() } }
    // (The shared pop-up: was a raw Dialog. Peter, 2026-11-17.)
    io.github.petebutty.viewarr.ui.common.PopupPanel(onClose = onClose, width = io.github.petebutty.viewarr.ui.theme.Tokens.PopupWide) {
        Column {
            io.github.petebutty.viewarr.ui.common.PopupTitle("Add a news feed")
            WizardField(url, { url = it }, "Feed address (e.g. feeds.bbci.co.uk/news/world/rss.xml)", first, androidx.compose.ui.text.input.KeyboardType.Uri, androidx.compose.ui.text.input.ImeAction.Next, {})
            Spacer(Modifier.height(8.dp))
            WizardField(name, { name = it }, "Name on the ticker (e.g. BBC World)", null, androidx.compose.ui.text.input.KeyboardType.Text, androidx.compose.ui.text.input.ImeAction.Done, {})
            Spacer(Modifier.height(12.dp))
            MenuItem(text = if (busy) "Checking…" else "Add", selected = false, onClick = {
                if (!busy && url.isNotBlank()) { busy = true; scope.launch { msg = feeds.add(name, url); busy = false; if (msg == null) onClose() } }
            })
            MenuItem(text = "Cancel", selected = false, onClick = onClose)
            msg?.let { Text(it, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

/** Settings → Appearance → News ticker (its own function: Appearance's list was near Android's size limit). */
@Composable
private fun newsTickerCategory(opts: UiOptsViewModel): SettingCategory {
    val newsVm: NewsViewModel = hiltViewModel()
    newsVm.feeds.version.value
    var addingFeed by remember { mutableStateOf(false) }
    if (addingFeed) AddNewsFeedDialog(newsVm.feeds) { addingFeed = false }
    return SettingCategory(
            "News ticker", "Scrolling headlines (BBC, Sky, ESPN, the Guardian, or feeds you add) along the bottom: switch on where you want them, then pick feeds and each one's look.",
            listOf(
                optItem(opts, "With the info bar", "Along the bottom while live TV's info bar is up.", UiOpts.NEWS_INFOBAR),
                optItem(opts, "In the TV guide", "Along the bottom of the guide.", UiOpts.NEWS_GUIDE),
                optItem(opts, "On Movies, Shows and Sports", "Along the bottom of those pages.", UiOpts.NEWS_PAGES),
                optItem(opts, "Speed", "How fast headlines move across.", UiOpts.NEWS_SPEED),
            ) + newsVm.feeds.all().map { f ->
                // One row per feed: Off, or On in a chosen look (your own feeds can be removed here too).
                val looks = TickerStyle.entries
                val choices = listOf(Choice("Off", -1)) + looks.mapIndexed { i, st -> Choice("On · ${st.label}" + if (st == f.defaultStyle) " (its own)" else "", i) } +
                    (if (f.builtIn) emptyList() else listOf(Choice("Remove this feed", -2)))
                val cur = if (!Ticker.feedOn(f.id)) choices[0]
                    else choices.first { (it.value as Int) == looks.indexOf(Ticker.style(f)) }
                SettingItem(f.name, if (f.builtIn) f.url.substringAfter("://").substringBefore('/') else "Your feed · " + f.url, choices, cur) { c ->
                    when (val v = (c as Choice).value as Int) {
                        -1 -> opts.set(UiOpts.newsFeedKey(f.id), 2)
                        -2 -> newsVm.feeds.remove(f.id)
                        else -> { opts.set(UiOpts.newsFeedKey(f.id), 1); opts.set(UiOpts.newsStyleKey(f.id), v + 1) }
                    }
                }
            } + SettingItem("Add your own feed…", "Any news site's RSS or Atom feed address.", listOf(Choice("", 0)), Choice("", 0), action = { addingFeed = true }) {},
        )
}

/** Settings → Appearance → Title logos by group: the guide's channel groups, each On / Off. */
@Composable
private fun titleLogoGroupsCategory(vm: AppearanceViewModel): SettingCategory {
    val palette = AppColors.palette
    val guideVm: GuideViewModel = hiltViewModel()
    // (Real groups only: 📡 Live now's channels belong to their own groups.)
    val groups = guideVm.groups.collectAsStateWithLifecycle().value.filter { it.id >= 0 }.map { it.name }.distinct()
    return SettingCategory(
        "Title logos by group", "Programme title logos in the guide, group by group: switch a group off to always show its programmes' names (sports and event groups, say).",
        groups.map { g ->
            val id = "logo:$g"
            onOffItem(g, "Title logos for this group's programmes.", palette.shows(id)) { on -> vm.setTitlePart(id, on) }
        },
    )
}
