package io.github.petebutty.viewarr.ui.settings

import io.github.petebutty.viewarr.ui.common.instantFocusScale
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.settings.ThemeStore
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.HighlightChoices
import io.github.petebutty.viewarr.ui.theme.MainColourChoices
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.ThemePresets
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import javax.inject.Inject

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

private class Choice(override val label: String, val value: Any) : io.github.petebutty.viewarr.data.settings.RemoteChoice {
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
    val onOff = listOf(Choice("On", true), Choice("Off", false))
    val corners = listOf(Choice("Rounded", false), Choice("Square", true))
    val transparencies = (0..100 step 10).map { Choice(if (it == 0) "Solid" else "$it%", it) }
    val currentT = ((palette.transparency * 100).roundToInt() / 10 * 10).coerceIn(0, 100)
    val currentG = ((palette.guideTransparency * 100).roundToInt() / 10 * 10).coerceIn(0, 100)
    val wallModes = io.github.petebutty.viewarr.ui.theme.WallpaperModes.map { (v, n) -> Choice(n, v) }
    val wallStyles = io.github.petebutty.viewarr.ui.theme.WallpaperStyles.mapIndexed { i, w -> Choice(w.name, i) }
    val dims = (30..90 step 10).map { Choice("$it%", it) }
    // Low-memory mode: settings for what it turns off are hidden.
    val low = palette.lowMemory
    // Low-memory mode: its settings stay listed, locked, with how to get them back.
    val lowLock = if (low) Locks.LOW_MEMORY else null
    // The main menu's choices: its pages and the streaming services from your addons (AIOMetadata's rows).
    val moviesM: io.github.petebutty.viewarr.ui.media.MediaViewModel = hiltViewModel(key = "media-Movie")
    val showsM: io.github.petebutty.viewarr.ui.media.MediaViewModel = hiltViewModel(key = "media-Show")
    val mRows by moviesM.menuRows.collectAsStateWithLifecycle()
    val sRows by showsM.menuRows.collectAsStateWithLifecycle()
    val serviceNames = (io.github.petebutty.viewarr.ui.player.RowSets.sets(sRows.rows) + io.github.petebutty.viewarr.ui.player.RowSets.sets(mRows.rows))
        .filter { it.startsWith("name:") }.map { it.removePrefix("name:") }.distinct()
    fun menuItem(label: String, id: String, description: String, alsoOn: () -> Unit = {}) =
        SettingItem(label, description, onOff, if (palette.shows(id)) onOff[0] else onOff[1]) { c ->
            val on = (c as Choice).value == true
            vm.setTitlePart(id, on)
            if (on) alsoOn()
        }
    val categories = listOfNotNull(
        SettingCategory(
            "Colours & text", "Colours, text size, the clock and how dates and times show. Changes show straight away.",
            listOf(
                // The background behind everything (the glass is tinted by it too): Viewarr's charcoal, or a colour.
                SettingItem("Background colour", "The colour behind the guide, menus and pages. Classic blue, Ocean blue and Sky blue are the brighter looks from before the glass design.", mains,
                    mains.firstOrNull { it.value == palette.main } ?: Choice("Custom", palette.main)) { c ->
                    vm.apply((c as Choice).value as androidx.compose.ui.graphics.Color, palette.highlight)
                },
                SettingItem("Highlight colour", "The colour of whatever is selected.", highs,
                    highs.firstOrNull { it.value == palette.highlight } ?: Choice("Custom", palette.highlight)) { c ->
                    vm.apply(palette.main, (c as Choice).value as androidx.compose.ui.graphics.Color)
                },
                optItem(opts, "Text size", "How big text and everything else on screen is. Larger is easier to read from the sofa.", io.github.petebutty.viewarr.ui.theme.UiOpts.TEXT_SIZE),
                optItem(opts, "Font", "The lettering for all text, from the fonts built into your box (a box without one shows the standard font).", io.github.petebutty.viewarr.ui.theme.UiOpts.FONT),
                optItem(opts, "List scrolling", "How lists follow the highlight. Instant: the list moves with it in one go (no trail when you go through quickly). Smooth: it slides there.", io.github.petebutty.viewarr.ui.theme.UiOpts.LIST_SCROLL),
                optItem(opts, "Clock", "The time in the top corner.", io.github.petebutty.viewarr.ui.theme.UiOpts.CLOCK),
                optItem(opts, "Date format", "How dates show across Viewarr: the guide, recordings, episodes and reviews.", io.github.petebutty.viewarr.ui.theme.UiOpts.DATE_ORDER),
                optItem(opts, "Time format", "24-hour or 12-hour times: the guide, the clock, programme times and recordings.", io.github.petebutty.viewarr.ui.theme.UiOpts.TIME_FORMAT),
            ),
        ),
        // Low-memory mode: no wallpaper at all (plain Viewarr colours): locked.
        SettingCategory(
            "Wallpaper", "A picture behind the guide, menus and settings, darkened so text stays easy to read.",
            buildList {
                add(SettingItem("Wallpaper", "None, a built-in design, or the artwork of the programme you've highlighted. (Movies and Shows always show the highlighted title's artwork.)", wallModes,
                    wallModes.firstOrNull { it.value == palette.wallpaper } ?: wallModes[0]) { c -> vm.setWallpaper(((c as Choice).value as Int), palette.wallpaperStyle, palette.wallpaperDim) })
                if (palette.wallpaper == 1) add(SettingItem("Design", "Which built-in wallpaper.", wallStyles,
                    wallStyles[palette.wallpaperStyle.coerceIn(0, wallStyles.lastIndex)]) { c -> vm.setWallpaper(1, (c as Choice).value as Int, palette.wallpaperDim) })
            }.map { it.locked(lowLock) },
        ),
        // Every transparency in one place, as sliders (Left / Right).
        SettingCategory(
            "Transparency", "Left / Right on each: how much shows through. Solid hides what's behind completely.",
            buildList {
                add(SettingItem("Glass", "The menu strip on the left and the channel list, on every page and over video.", transparencies,
                    transparencies.first { it.value == currentT }, slider = true) { c -> vm.setTransparency(((c as Choice).value as Int) / 100f) })
                add(optItem(opts, "Glass colour", "A colour through the glass: the menu, the channel list, the info bar and every pop-up. Plain is the Viewarr look.", io.github.petebutty.viewarr.ui.theme.UiOpts.GLASS_TINT))
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
                SettingItem("Channel numbers", "Show each channel's number next to its name.", onOff,
                    if (palette.showNumbers) onOff[0] else onOff[1]) { c -> vm.setShowNumbers((c as Choice).value == true) },
                SettingItem("Programme corners", "The guide's programmes and channel names: rounded, or square-cornered boxes.", corners,
                    if (palette.squareCorners) corners[1] else corners[0]) { c -> vm.setSquareCorners((c as Choice).value == true) },
                SettingItem("Progress bars", "The bar showing how far through each programme on now is: on every channel, only the channel you're watching, or none.", progressChoices,
                    progressChoices[palette.progressBars.coerceIn(0, 2)]) { c -> vm.setProgressBars((c as Choice).value as Int) },
                optItem(opts, "Channel logos", "Logos beside the channel names.", io.github.petebutty.viewarr.ui.theme.UiOpts.CHANNEL_LOGOS),
                optItem(opts, "Info bar size", "How big the info bar is drawn over live TV and films: its text, buttons and preview rows together. Smaller covers less of the picture.", io.github.petebutty.viewarr.ui.theme.UiOpts.INFOBAR_SIZE),
                optItem(opts, "Info bar width", "How wide the info bar is: the whole screen, or narrower and centred at the bottom (live TV and films alike).", io.github.petebutty.viewarr.ui.theme.UiOpts.INFOBAR_WIDTH),
                optItem(opts, "Preview cards", "The size of the cards in the info bar's rows (next episodes, shortcuts).", io.github.petebutty.viewarr.ui.theme.UiOpts.CARD_SIZE),
                optItem(opts, "Episode numbers", "Series and episode numbers after programme names (\"S15E4\") in the guide and the channel list.", io.github.petebutty.viewarr.ui.theme.UiOpts.GUIDE_EPISODE),
                optItem(opts, "End of a list", "The TV guide, the channel list and its groups, past the last channel (or above the first). Jump to the other end: one more press goes to the top (holding the button stops at the end, so a long scroll doesn't fly round). Stop: nothing more.", io.github.petebutty.viewarr.ui.theme.UiOpts.LIST_ENDS),
                optItem(opts, "Catch-up icons", "The replay icon on past programmes you can watch again, and beside channels that have catch-up. Catch-up still works with it off.", io.github.petebutty.viewarr.ui.theme.UiOpts.CATCHUP_ICONS),
            ) + listOfNotNull(
                // Low-memory mode: no programme pictures at all, so no setting for them.
                optItem(opts, "Programme pictures", "The highlighted programme's picture at the top of the guide. Off: no pictures are looked up (the channel's logo shows instead).", io.github.petebutty.viewarr.ui.theme.UiOpts.GUIDE_ART).locked(lowLock),
            ) + listOf(
                // The info bar over live TV.
                // The glass: the info bar and controls, and every pop-up (next episode, skip intro, reminders, notes).
                optSlider(opts, "Pop-up glass", "Left / Right: how see-through the info bar, the player's controls and every pop-up are (record options, next episode, skip intro, reminders), up to solid.", io.github.petebutty.viewarr.ui.theme.UiOpts.POPUP_GLASS, io.github.petebutty.viewarr.ui.theme.AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.POPUP_GLASS)),
                optItem(opts, "Info bar: border", "The thin light edge round the glass.", io.github.petebutty.viewarr.ui.theme.UiOpts.BANNER_BORDER),
                optItem(opts, "Info bar: channel number", "The channel's number at the left of the info bar.", io.github.petebutty.viewarr.ui.theme.UiOpts.BANNER_NUMBER),
                optItem(opts, "Info bar: episode numbers", "Series and episode numbers after programme names (\"S2026E180\") in the info bar.", io.github.petebutty.viewarr.ui.theme.UiOpts.BANNER_EPISODE),
                optItem(opts, "Info bar: picture quality", "Badges for the channel's picture (HD, FHD, 4K, or 720p, 1080p, 2160p) and 5.1 sound, from the stream itself.", io.github.petebutty.viewarr.ui.theme.UiOpts.BANNER_BADGES),
                optItem(opts, "Info bar: frame rate", "A badge with the stream's frames per second (25, 50…).", io.github.petebutty.viewarr.ui.theme.UiOpts.BANNER_FPS),
            ),
        ),
        SettingCategory(
            "Posters", "How big library posters are, and what's drawn on posters in Movies, Shows and search.",
            listOf(
                optItem(opts, "Library posters", "How big the posters are in a library's grid (Plex, Jellyfin, Emby, IPTV). Small is the same size as the posters in the rows; as many fit across as there's room for, with the mini player or menu beside it too.", io.github.petebutty.viewarr.ui.theme.UiOpts.LIBRARY_POSTERS),
                optItem(opts, "Where it's from", "The label saying which of your servers has it (e.g. \"Plex · Living room\").", io.github.petebutty.viewarr.ui.theme.UiOpts.BADGE_SOURCE),
                optItem(opts, "Progress bars", "How far through you are, along the bottom of posters and episodes.", io.github.petebutty.viewarr.ui.theme.UiOpts.BADGE_PROGRESS),
            ),
        ),
        SettingCategory(
            "Title pages", "What a film's or show's page shows. Anything off isn't fetched either, so pages open with less to do.",
            io.github.petebutty.viewarr.ui.theme.TitleParts.sections.map { (id, text) ->
                SettingItem(text.first, text.second, onOff, if (palette.shows(id)) onOff[0] else onOff[1]) { c -> vm.setTitlePart(id, (c as Choice).value == true) }
                    .locked(if (id == io.github.petebutty.viewarr.ui.theme.TitleParts.LOGO) lowLock else null)
            } + optItem(opts, "Age ratings", "Which country's age ratings title pages show.", io.github.petebutty.viewarr.ui.theme.UiOpts.AGE_RATING),
        ),
        // Low-memory mode fetches only TMDB's rating: the others locked.
        SettingCategory(
            "Ratings", "Which ratings show on title pages and the Movies / Shows header, and in what order. With all but TMDB off, the other ratings aren't fetched.",
            // In the order they show (Right picks one up, Up / Down move it, Right puts it down); OK switches it on / off.
            io.github.petebutty.viewarr.ui.theme.TitleParts.ratings.sortedBy { palette.ratingRank(it.first) }.let { ordered ->
                ordered.mapIndexed { i, (src, name) ->
                    val id = io.github.petebutty.viewarr.ui.theme.TitleParts.rating(src)
                    SettingItem(name, "${i + 1} of ${ordered.size}  ·  Right to move it", onOff, if (palette.shows(id)) onOff[0] else onOff[1],
                        reorder = { delta ->
                            val list = ordered.map { it.first }.toMutableList()
                            val j = (i + delta).coerceIn(0, list.lastIndex)
                            if (j != i) { list.add(j, list.removeAt(i)); vm.setRatingsOrder(list) }
                        },
                    ) { c -> vm.setTitlePart(id, (c as Choice).value == true) }
                        .locked(if (src != "TMDB") lowLock else null)
                }
            },
        ),
        // What the main menu lists (Live TV and Settings are always there).
        SettingCategory(
            "Main menu", "What's listed in the menu on the left. Live TV and Settings are always there; streaming services appear once your addons' rows have loaded.",
            listOf(
                optItem(opts, "List headings", "A heading at the top of each list in the menu (\"LIVE TV · CHANNEL GROUPS\", \"MOVIES\", \"SETTINGS\"…), or the path to it (\"Live TV › Channel groups\"), so the guide's channel groups are never mistaken for your libraries.", io.github.petebutty.viewarr.ui.theme.UiOpts.MENU_HEADINGS),
                optItem(opts, "Source in the Live TV heading", "With more than one Live TV source: its name in the heading (\"LIVE TV · DISPATCHARR\").", io.github.petebutty.viewarr.ui.theme.UiOpts.MENU_HEAD_SOURCE),
                optItem(opts, "TV mark on channel groups", "A small 📺 before each channel group's name.", io.github.petebutty.viewarr.ui.theme.UiOpts.MENU_GROUP_ICON),
optItem(opts, "Colour edge", "A thin bar down the edge of each list in your highlight colour.", io.github.petebutty.viewarr.ui.theme.UiOpts.MENU_EDGE),
                optItem(opts, "Colour tint", "Each list's glass faintly tinted in its section's colour.", io.github.petebutty.viewarr.ui.theme.UiOpts.MENU_TINT),
                optItem(opts, "Icon column", "In a section's list, the main menu stays as a narrow column of icons with the one you're in lit, instead of folding away.", io.github.petebutty.viewarr.ui.theme.UiOpts.MENU_RAIL),
                optItem(opts, "LIVE mark", "A red ● LIVE on the channel groups' All channels.", io.github.petebutty.viewarr.ui.theme.UiOpts.MENU_LIVE_MARK),
                                optItem(opts, "Left / Right hints", "\"Right › open its list\" under the logo and \"‹ Left: main menu\" at the foot of a list.", io.github.petebutty.viewarr.ui.theme.UiOpts.MENU_HINTS),
                menuItem("Live now", "menu:Live now", "The TV guide on the event channels: sport and events on now and soon."),
                menuItem("Sports", "menu:Sports", "The TV guide on your sports channels."),
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

/** Appearance's settings back to how they come (the Appearance page's Reset). */
fun appearanceReset(vm: AppearanceViewModel, opts: UiOptsViewModel) {
        vm.apply(ThemePresets[0].main, ThemePresets[0].highlight); vm.setTransparency(0f); vm.setGuideTransparency(0f)
        vm.setShowNumbers(true); vm.setHideMedia(false); vm.setWallpaper(0, 0, 0.6f); vm.resetTitleParts()
        opts.reset(listOf(io.github.petebutty.viewarr.ui.theme.UiOpts.TEXT_SIZE, io.github.petebutty.viewarr.ui.theme.UiOpts.CLOCK, io.github.petebutty.viewarr.ui.theme.UiOpts.CHANNEL_LOGOS, io.github.petebutty.viewarr.ui.theme.UiOpts.CATCHUP_ICONS, io.github.petebutty.viewarr.ui.theme.UiOpts.GUIDE_ART, io.github.petebutty.viewarr.ui.theme.UiOpts.BADGE_SOURCE, io.github.petebutty.viewarr.ui.theme.UiOpts.BADGE_PROGRESS))
}

/** Settings → Appearance on its own (Settings now opens it through the layout: SettingsMap). */
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit, vm: AppearanceViewModel = hiltViewModel(), opts: UiOptsViewModel = hiltViewModel()) {
    SettingsPage(title = "Appearance", categories = appearanceCategories(vm, opts), onBack = onBack, onReset = { appearanceReset(vm, opts) })
}

@Composable
fun AppearancePanel(onBack: () -> Unit, vm: AppearanceViewModel = hiltViewModel()) {
    val palette = AppColors.palette
    val firstFocus = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { firstFocus.requestFocus() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel("Presets")
        ThemePresets.forEachIndexed { i, preset ->
            MenuItem(
                text = preset.name,
                selected = palette.main == preset.main && palette.highlight == preset.highlight,
                onClick = { vm.apply(preset.main, preset.highlight) },
                modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                leading = {
                    Row {
                        Dot(preset.main)
                        Dot(preset.highlight, Modifier.padding(start = 3.dp))
                    }
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        SectionLabel("Main colour")
        SwatchRow(MainColourChoices, palette.main) { vm.apply(it, palette.highlight) }
        Spacer(Modifier.height(10.dp))
        SectionLabel("Highlight colour")
        SwatchRow(HighlightChoices, palette.highlight) { vm.apply(palette.main, it) }
        Spacer(Modifier.height(10.dp))
        ValueSlider(
            label = "Menu transparency",
            value = (palette.transparency * 100).roundToInt(),
            onChange = { vm.setTransparency(it / 100f) },
        )
        Spacer(Modifier.height(12.dp))
        MenuItem(text = "Back", selected = false, onClick = onBack)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 4.dp))
}

@Composable
private fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).background(color, CircleShape))
}

@Composable
private fun SwatchRow(colors: List<Color>, current: Color, onPick: (Color) -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        colors.forEach { c ->
            val selected = c == current
            Surface(
                onClick = { onPick(c) },
                modifier = Modifier.size(22.dp).instantFocusScale(1.15f),
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                scale = io.github.petebutty.viewarr.ui.common.focusScale(1.15f),
                colors = ClickableSurfaceDefaults.colors(containerColor = c, focusedContainerColor = c),
                border = ClickableSurfaceDefaults.border(
                    border = if (selected) Border(BorderStroke(2.dp, Color.White), shape = CircleShape) else Border.None,
                    focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CircleShape),
                ),
            ) {}
        }
    }
}

@Composable
private fun ValueSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val text = if (focused) AppColors.OnAccent else AppColors.TextPrimary
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (focused) AppColors.Accent else Color.Transparent, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> onChange((value - STEP).coerceIn(0, 100))
                    Key.DirectionRight -> onChange((value + STEP).coerceIn(0, 100))
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, color = text, modifier = Modifier.weight(1f))
            Text("$value", fontSize = 14.sp, color = text)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).background(text.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(value / 100f).fillMaxHeight().background(text, RoundedCornerShape(2.dp)))
        }
        if (focused) {
            Text("◀ ▶ to adjust", fontSize = 11.sp, color = text.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private const val STEP = 5

private val progressChoices = listOf(Choice("All channels", 0), Choice("Only what you're watching", 1), Choice("Off", 2))
