package io.github.petebutty.viewarr.ui.media

import io.github.petebutty.viewarr.ui.common.glass
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.common.EdgeBlocked
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.petebutty.viewarr.ui.common.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.media.StreamOption
import io.github.petebutty.viewarr.ui.theme.AppColors
import coil.compose.AsyncImage
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import io.github.petebutty.viewarr.data.media.CastMember
import io.github.petebutty.viewarr.data.media.EpisodeInfo
import io.github.petebutty.viewarr.data.media.MediaDetail
import io.github.petebutty.viewarr.data.media.PROVIDER
import io.github.petebutty.viewarr.data.media.PersonInfo
import io.github.petebutty.viewarr.data.media.RatingBadge
import io.github.petebutty.viewarr.data.media.Review
import io.github.petebutty.viewarr.data.media.TitleTracks
import io.github.petebutty.viewarr.data.remote.services.SeerrService
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.common.LiveBehind
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.Dates
import io.github.petebutty.viewarr.ui.theme.TitleParts
import io.github.petebutty.viewarr.ui.theme.UiOpts
import io.github.petebutty.viewarr.ui.common.CardAspect
import io.github.petebutty.viewarr.ui.common.FocusCard
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.PopupPanel
import io.github.petebutty.viewarr.ui.common.PopupTitle
import io.github.petebutty.viewarr.ui.common.RoundIconButton
import io.github.petebutty.viewarr.ui.theme.Tokens

/** A short notice from the model, along the bottom for three seconds. */
@Composable
internal fun BoxScope.MediaNotice(vm: MediaViewModel) {
    val notice by vm.notice.collectAsStateWithLifecycle()
    notice?.let { text ->
        LaunchedEffect(text) { delay(3_000); vm.clearNotice() }
        Text(
            text, fontSize = 16.sp, color = AppColors.TextPrimary,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                .glass(24.dp)
                .padding(horizontal = 24.dp, vertical = 10.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A pop-up of the title page closed ([open] went false): the highlight back on [target] (Play) once the buttons can take it. */
@Composable
internal fun FocusBackWhenClosed(open: Boolean, target: FocusRequester) {
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) { wasOpen = true; return@LaunchedEffect }
        if (!wasOpen) return@LaunchedEffect
        wasOpen = false
        awaitFrame(); awaitFrame()
        runCatching { target.requestFocus() }
    }
}

/** The title page's one-line facts: year, age rating (when it isn't a badge), runtime, seasons, genres. */
internal fun detailMeta(item: MediaItem, d: MediaDetail): String = listOfNotNull(
    releaseLabel(item) ?: item.year?.toString(),
    // The age rating is a badge below (Settings → Appearance → Title pages: Badges); in the line when they're off.
    if (AppColors.palette.shows(TitleParts.BADGES)) null else ageRating(d),
    d.runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
    d.seasons.takeIf { it.isNotEmpty() }?.let { s -> "${s.size} season${if (s.size == 1) "" else "s"} · ${s.sumOf { it.episodes }} episodes" },
    d.genres.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", "),
).joinToString("  ·  ")

/** The line under the facts: who made it, and which of your servers have it. */
internal fun detailLibraryLine(ui: DetailUi, show: Boolean): String = listOfNotNull(
    ui.detail.directors.takeIf { it.isNotEmpty() }?.let { (if (show) "Created by " else "Directed by ") + it.take(2).joinToString(", ") },
    when (val copies = ui.copies) {
        null -> "Checking your library…"
        // One entry per source, its versions together: "Plex (4K DV, 1080p) · Jellyfin (1080p)".
        else -> if (copies.isEmpty()) "Not in your library" else "In your library: " + copies.groupBy { it.source }.entries.joinToString(" · ") { (src, cs) ->
            val vs = cs.mapNotNull { c -> c.version?.let { v -> listOfNotNull(v.resolution, v.hdr?.let { h -> if (h == "Dolby Vision") "DV" else h }).joinToString(" ").ifEmpty { null } } ?: c.detail }.distinct()
            if (vs.isEmpty()) src else "$src (${vs.joinToString(", ")})"
        }
    },
).joinToString("   ·   ")

/** The episode you're up to ([next]: season, episode) when it isn't out yet (its air date from the season's list, and
 *  not in your library), else null. */
internal fun notOutYet(ui: DetailUi, next: Triple<Int, Int, Boolean>?): java.time.LocalDate? =
    // (Already on one of your servers, this box or IPTV: it's out, whatever the listed air date says; an episode
    // added the day before its date said "out tomorrow".)
    next?.takeUnless { n -> (n.first to n.second).let { k -> k in ui.plexEpisodes || k in ui.serverEpisodes || k in ui.localEpisodes || k in ui.providerEpisodes } }
    ?.let { n -> ui.episodes?.takeIf { ui.season == n.first }?.firstOrNull { it.number == n.second }?.airDate }
        ?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() }
        ?.takeIf { it.isAfter(java.time.LocalDate.now()) }

/** When an unreleased episode comes out: "tomorrow", a weekday this week, else the date. */
internal fun outWhen(upDate: java.time.LocalDate): String {
    val today = java.time.LocalDate.now()
    return when {
        upDate == today.plusDays(1) -> "tomorrow"
        upDate.isBefore(today.plusDays(7)) -> upDate.format(java.time.format.DateTimeFormatter.ofPattern("EEEE", java.util.Locale.UK))
        else -> upDate.format(Dates.day)
    }
}

/** Seerr: ask for it to be kept (Radarr / Sonarr fetch it), or show it's already on the way. A button in the title page's row. */
@Composable
internal fun SeerrAction(ui: DetailUi, vm: MediaViewModel) {
    val seerrState by produceState<SeerrService.Status?>(null, ui.detail.item.key, ui.message) { value = vm.seerrStatus(ui.detail.item) }
    // Not for something already on your own server (only on IPTV can still be requested).
    if (ui.copies.orEmpty().none { it.source != PROVIDER }) seerrState?.takeIf { it != SeerrService.Status.Available }?.let { st ->
        val canAsk = st == SeerrService.Status.None ||
            st == SeerrService.Status.Partial
        IconAction(if (canAsk) AppIcons.Add else AppIcons.Clock, if (canAsk) "Request on Seerr" else "Requested on Seerr", { if (canAsk) vm.requestOnSeerr(ui.detail.item) }, Modifier.free(), on = !canAsk)
    }
}

/** '#' for titles starting with a number or symbol, else the first letter (ignoring "The "). */
internal fun firstLetter(title: String): Char {
    val t = title.trim().removePrefix("The ").removePrefix("the ").uppercase()
    val c = t.firstOrNull { !it.isWhitespace() } ?: return '#'
    return if (c in 'A'..'Z') c else '#'
}

/** A poster still on its way (the grid holds every position so you can scroll or jump anywhere). */
@Composable
internal fun PlaceholderPoster(modifier: Modifier) {
    FocusCard(
        onClick = {}, modifier = modifier.fillMaxWidth(), aspect = CardAspect.Poster,
        containerColor = AppColors.Panel.copy(alpha = 0.6f), focusedContainerColor = AppColors.Panel,
    ) {}
}

/** The age rating from the country chosen in Settings → Appearance (UK or US). */
internal fun ageRating(d: MediaDetail): String? =
    if (AppColors.palette.opt(UiOpts.AGE_RATING) == 1) d.certificationUs else d.certification

/**
 * Netflix-style outlined badges under a title's info line: age rating, picture (4K, Dolby Vision…), sound (the
 * default soundtrack's format and channels), CC when the file has subtitles, then when a film would end if
 * played now (the time left, for one part-watched). Drawn here, not logos: nothing to licence.
 */
@Composable
internal fun TitleBadges(item: MediaItem, d: MediaDetail, media: TitleTracks?) {
    if (!AppColors.palette.shows(TitleParts.BADGES)) return
    val audio = media?.audio?.let { a -> a.firstOrNull { it.default } ?: a.firstOrNull() }
    val boxes = listOfNotNull(
        ageRating(d),
        media?.resolution,
        media?.hdr,
        audio?.format,
        audio?.channels,
        if (media?.subtitles?.isNotEmpty() == true) "CC" else null,
    ).distinct()
    // Recomposes about once a minute, so "Ends at" keeps up with the clock while the page is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = System.currentTimeMillis() } }
    val ends = d.runtimeMin?.takeIf { it > 0 && item.type == MediaType.Movie }?.let { mins ->
        val left = (mins * 60_000L * (1f - (item.progress ?: 0f).coerceIn(0f, 1f))).toLong()
        Dates.time(now + left)
    }
    if (boxes.isEmpty() && ends == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        boxes.forEach { b ->
            Text(
                b, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, maxLines = 1,
                modifier = Modifier.border(1.dp, AppColors.TextSecondary.copy(alpha = 0.8f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        ends?.let { Text("Ends at $it", fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1) }
    }
}

/**
 * A film's release date in full ("18 Dec 2026"; not out yet: "Coming 18 Dec 2026"), so a new film in cinemas shows
 * when it came out (2026-11-24); null without one (the year is shown instead).
 */
internal fun releaseLabel(item: MediaItem): String? {
    val d = item.releaseDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return null
    val text = d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK))
    return if (d.isAfter(java.time.LocalDate.now())) "Coming $text" else text
}

internal fun metaLine(item: MediaItem) = listOfNotNull(
    releaseLabel(item) ?: item.year?.toString(),
    // Library titles carry their server's own score (Plex's is the audience score, already a badge): only TMDB's,
    // labelled, so it isn't mistaken for the others.
    item.rating?.takeIf { item.origin !in LIBRARY_ORIGINS }?.let { "TMDB %.1f".format(it) },
    item.origin,
).joinToString("  ·  ")

/** "Next episode in 10 s": OK plays now, Back cancels. */
@Composable
internal fun UpNextOverlay(ep: EpisodeInfo, onPlay: () -> Unit, onCancel: () -> Unit) {
    // Settings → Playback → Next episode countdown.
    var left by remember(ep) { androidx.compose.runtime.mutableIntStateOf(AppColors.palette.upNextSeconds) }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onCancel)
    LaunchedEffect(ep) {
        // (With the traps open: the title page's own refused to let the highlight out onto this.)
        awaitFrame(); EdgeBlocked.lettingOut { runCatching { focus.requestFocus() } }
        while (left > 0) { delay(1_000); left-- }
        onPlay()
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f * AppColors.palette.popupAlpha)).trapFocus(), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier.padding(56.dp).width(520.dp).glass(Tokens.PopupRadius).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Next episode in $left s", fontSize = 14.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("S${ep.season} · E${ep.number}  ${ep.name}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ep.overview?.let { Text(it, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onPlay, modifier = Modifier.focusRequester(focus)) { Text("Play now") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/**
 * Hold OK on a poster: play, details, trailer and watchlist without opening the title page first. The shared options
 * pop-up (first option highlighted, Cancel last). (2026-11-17: the collection card's menu wasn't in its own
 * window at all, and "Close" is now "Cancel".)
 */
@Composable
internal fun QuickOptions(item: MediaItem, vm: MediaViewModel, onTrailer: () -> Unit, onClose: () -> Unit) {
    // A collection / playlist card: open it, or give it its own row on this page.
    if (item.key.startsWith("col:")) {
        val hasRow = remember(item.key) { vm.hasCollectionRow(item) }
        OptionsPopup(
            title = item.title,
            subtitle = listOfNotNull(item.subtitle, item.origin).joinToString("  ·  "),
            options = listOf(
                PopupOption("Open") { vm.open(item) },
                PopupOption(if (hasRow) "Remove its row from this page" else "Show as a row on this page") { vm.setCollectionRow(item, !hasRow) },
            ),
            onClose = onClose,
        )
        return
    }
    val trailerId by produceState<String?>(null, item.key) { value = vm.trailers.youtubeId(item) }
    // null = no Trakt (no watchlist option); loading shows nothing until known.
    val watchlisted by produceState<Boolean?>(null, item.key) { value = vm.watchlistState(item) }
    // Seerr set up: request the title to keep (or see that it's already requested / available).
    val seerrStatus by produceState<SeerrService.Status?>(null, item.key) { value = vm.seerrStatus(item) }
    val show = item.type == MediaType.Show
    // IPTV keeps no history: Mark as watched saves everywhere the title is, and on the box (films; episodes on the title page).
    val iptv by produceState(false, item.key) { value = !show && vm.hasIptv(item) }
    val localEntries by vm.localWatch.entries.collectAsState()
    // (Watched anywhere: Trakt, your servers or this box's record.)
    val done by produceState(false, localEntries, item.key) { value = vm.quickWatched(item) }
    val seerrAsk = seerrStatus == SeerrService.Status.None || seerrStatus == SeerrService.Status.Partial
    val options = buildList {
        add(PopupOption(if (show) "Episodes" else if (item.progress != null) "Resume" else "Play") { if (show) vm.quickEpisodes(item) else vm.quickPlay(item) })
        add(PopupOption("Details") { vm.open(item) })
        // (Stays open: the trailer replaces this pop-up itself, and closing would send the highlight back to the rows.)
        if (trailerId != null) add(PopupOption("Watch trailer", keepOpen = true, onClick = onTrailer))
        watchlisted?.let { on -> add(PopupOption(if (on) "Remove from watchlist" else "Add to watchlist") { vm.quickWatchlist(item, add = !on) }) }
        seerrStatus?.takeIf { seerrAsk }?.let { st -> add(PopupOption(st.label) { vm.requestOnSeerr(item) }) }
        if (iptv) {
            add(PopupOption(if (done) "Mark as not watched" else "Mark as watched") { vm.markIptvWatched(item, !done) })
            if (item.origin == "IPTV" && item.progress != null) add(PopupOption("Remove from Continue watching") { vm.hideIptv(item) })
        }
        // A show you're done with (or never watched: Trakt's calendar has every show in your history): out of Continue
        // watching and Upcoming episodes for good, and dropped on Trakt. Watching any episode of it again brings it back.
        if (show && vm.canDrop(item)) add(PopupOption("Drop show") { vm.dropShow(item) })
    }
    OptionsPopup(
        title = item.title,
        subtitle = listOfNotNull(item.year?.toString(), item.origin, seerrStatus?.takeIf { !seerrAsk }?.label).joinToString("  ·  "),
        options = options,
        onClose = onClose,
    )
}

/**
 * A title's name as its logo artwork (TMDB), left-aligned; the name as text until the logo arrives, or when there's
 * none. [waitMs]: a short pause first, so moving quickly along a row doesn't fetch every logo.
 */
@Composable
internal fun TitleLogo(item: MediaItem, logoOf: (suspend (MediaItem) -> String?)?, height: androidx.compose.ui.unit.Dp, maxWidth: androidx.compose.ui.unit.Dp, fontSize: Int, waitMs: Long = 0,
                      /** The logo already known (the hero's lookup): looked at again when it arrives. */
                      known: String? = null,
                      /** Right-aligned (the hero's details beside the live TV window). */
                      end: Boolean = false) {
    // Settings → Appearance → Title logo off: the name as text, and no logo lookup.
    val logosOn = AppColors.palette.shows(TitleParts.LOGO) && !AppColors.palette.lowMemory
    val logo by produceState<String?>(null, item.key, logosOn, logoOf == null, known) {
        value = null
        if (logoOf == null || !logosOn) return@produceState
        if (waitMs > 0) delay(waitMs)
        value = logoOf(item)
    }
    var loaded by remember(logo) { mutableStateOf(false) }
    // Decoded to fit its space (not the original, thousands of pixels wide), sized here as the space stays 0 high
    // until it arrives.
    val request = rememberLogoRequest(logo, maxWidth.value.toInt(), height.value.toInt())
    Box {
        request?.let { req ->
            AsyncImage(
                model = req,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                alignment = if (end) Alignment.CenterEnd else Alignment.CenterStart,
                onSuccess = { loaded = true },
                modifier = Modifier.height(if (loaded) height else 0.dp).width(maxWidth),
            )
        }
        if (!loaded) Text(item.title, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else null)
    }
}

/**
 * "TMDB 8.6 · IMDb 7.9 · Rotten Tomatoes 87% · RT audience 91% · Trakt 82%". [oneLine]: never wraps, the ones that
 * don't fit are left off (the Movies / Shows hero, which has a fixed height).
 */
@Composable
internal fun RatingsLine(tmdb: Double?, more: List<RatingBadge>, oneLine: Boolean = false) {
    val all = buildList {
        // Scores only (no vote counts), so up to seven sources fit on one line.
        // Settings → Appearance → Ratings: only the ones switched on.
        val p = AppColors.palette
        tmdb?.takeIf { it > 0 && p.shows(TitleParts.rating("TMDB")) }?.let { add("TMDB" to "%.1f".format(it)) }
        more.filter { p.shows(TitleParts.rating(it.source)) }.forEach { add(it.source to it.value) }
    }.sortedBy { AppColors.palette.ratingRank(it.first) }
    if (all.isEmpty()) return
    if (oneLine) { OneLine(12.dp) { all.forEach { (src, v) -> RatingBadgeView(src, v) } }; return }
    // Wraps onto a second line rather than running past its column (e.g. under the trailer on the Movies page).
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        all.forEach { (src, v) -> RatingBadgeView(src, v) }
    }
}

@Composable
internal fun RatingBadgeView(src: String, v: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(src, fontSize = 11.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold, color = Color.Black,
            modifier = Modifier.background(ratingColour(src), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        Spacer(Modifier.width(6.dp))
        Text(v, fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 1, softWrap = false)
    }
}

/** Its children side by side, [gap] apart, as many as fit the width whole; the rest aren't drawn. */
@Composable
internal fun OneLine(gap: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content) { measurables, c ->
        val g = gap.roundToPx()
        val placeables = measurables.map { it.measure(c.copy(minWidth = 0, minHeight = 0)) }
        val xs = ArrayList<Int>()
        var x = 0
        for (pl in placeables) {
            val at = if (xs.isEmpty()) 0 else x + g
            if (at + pl.width > c.maxWidth) break
            xs.add(at); x = at + pl.width
        }
        val h = placeables.take(xs.size).maxOfOrNull { it.height } ?: 0
        layout(x.coerceAtLeast(c.minWidth), h) { xs.forEachIndexed { i, px -> placeables[i].place(px, 0) } }
    }
}

internal fun ratingColour(source: String) = when (source) {
    "IMDb" -> Color(0xFFF5C518)
    "Rotten Tomatoes", "RT" -> Color(0xFFFA6C4F)
    "RT audience", "Audience" -> Color(0xFFF9D361)
    "Trakt" -> Color(0xFFED6E83)
    "Metacritic" -> Color(0xFF66CC33)
    "Letterboxd" -> Color(0xFF40BCF4)
    else -> Color(0xFF90CEA1)
}

internal fun money(n: Long): String = when {
    n >= 1_000_000_000 -> "$%.1fbn".format(n / 1e9)
    n >= 1_000_000 -> "$%.0fm".format(n / 1e6)
    else -> "$%,d".format(n)
}

/** A cast member: round photo (initials when TMDB has none), name and character. OK opens their films and shows. */
@Composable
// (Name and part one line each, "(voice)" left off: ragged three-line captions. UI review, 2026-11-18.)
internal fun CastCard(c: CastMember, free: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // The shared card, round (no growing: one card rule everywhere).
        FocusCard(
            onClick = onClick,
            // (64, was 76: with the smaller buttons, More like this fits below. 2026-11-18.)
            modifier = Modifier.size(64.dp).onFocusChanged { focused = it.isFocused }.focusProperties { canFocus = free },
            shape = Tokens.RoundShape,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(c.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), fontSize = 20.sp, color = AppColors.TextSecondary, maxLines = 1, softWrap = false)
                c.photo?.let { AsyncImage(model = it, contentDescription = c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Text(c.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (focused) AppColors.TextPrimary else AppColors.TextPrimary.copy(alpha = 0.85f),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        c.character?.let { Text(it, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
    }
}

@Composable
internal fun ReviewCard(r: Review, free: Boolean, onClick: () -> Unit) {
    FocusCard(
        onClick = onClick,
        modifier = Modifier.size(width = 380.dp, height = 140.dp).focusProperties { canFocus = free },
        containerColor = AppColors.Panel.copy(alpha = 0.7f), focusedContainerColor = AppColors.Panel,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.author, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                r.rating?.let { Text("★ %.0f/10".format(it), fontSize = 13.sp, color = AppColors.Accent, maxLines = 1, softWrap = false) }
            }
            Text(listOfNotNull(r.source, r.date, r.likes.takeIf { it > 0 }?.let { "♥ $it" }).joinToString("  ·  "), fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.spoiler) Text("Contains spoilers  ·  OK: read", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(r.text.replace(Regex("\\s+"), " "), fontSize = 12.sp, lineHeight = 17.sp, color = AppColors.TextPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** A whole review, scrolled with Up/Down; Back or OK closes it. On the shared pop-up (PopupPanel, wide). */
@Composable
internal fun ReviewDialog(r: Review, onClose: () -> Unit) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    val f = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { awaitFrame(); runCatching { f.requestFocus() } }
    PopupPanel(onClose = onClose, width = Tokens.PopupWide + 120.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.author, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            r.rating?.let { Text("★ %.0f/10".format(it), fontSize = 16.sp, color = AppColors.Accent, maxLines = 1, softWrap = false) }
        }
        r.date?.let { Text(it, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        // (Its own scroll, held under the panel's height, moved by Up / Down on the text.)
        Column(
            Modifier.heightIn(max = 380.dp).verticalScroll(scroll)
                .focusRequester(f).focusable()
                .onPreviewKeyEvent { e ->
                    // OK closes when it's let go: closing on the press, the release landed on the review's card and
                    // opened it again.
                    if (e.key == Key.DirectionCenter || e.key == Key.Enter) {
                        if (e.type == KeyEventType.KeyUp) onClose()
                        return@onPreviewKeyEvent true
                    }
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(240f) }; true }
                        Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-240f) }; true }
                        else -> false
                    }
                },
        ) {
            Text(r.text, fontSize = 16.sp, lineHeight = 24.sp, color = AppColors.TextPrimary)
        }
    }
}

/**
 * Other sources: play from one particular place instead of the automatic choice. Each version on your servers is
 * listed with what it is (4K · Dolby Vision · Atmos 7.1 · 58 GB), the best for this TV first and marked.
 */
@Composable
internal fun SourcesDialog(ui: DetailUi, vm: MediaViewModel, onDone: () -> Unit) {
    val first = remember { FocusRequester() }
    val ranked by produceState<List<MediaViewModel.RankedCopy>?>(null, ui.copies) { value = vm.rankedCopies(ui.copies.orEmpty()) }
    // The addons are searched as soon as it opens, their streams listed with your copies (null: still searching).
    val addonStreams by produceState<List<StreamOption>?>(if (ui.hasAddons) null else emptyList(), ui.detail.item.key) { value = vm.addonOptions() }
    LaunchedEffect(ranked != null) { if (ranked != null) { awaitFrame(); runCatching { first.requestFocus() } } }
    // Nothing of your own above them: once the addons answer, the first stream is highlighted (not Cancel).
    LaunchedEffect(addonStreams?.isNotEmpty() == true) {
        if (addonStreams?.isNotEmpty() == true && ranked.isNullOrEmpty()) { awaitFrame(); runCatching { first.requestFocus() } }
    }
    // The shared pop-up (its own window; the held OK that opened it is ignored, so letting go doesn't pick the first
    // source). (2026-11-17: was a raw Dialog with a solid panel.)
    PopupPanel(onClose = onDone, width = Tokens.PopupWide + 80.dp) {
        PopupTitle("Play from")
        var firstUsed = false
        fun mod(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
        if (ranked == null) Text("Checking your copies…", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
        // In Play order (Settings → Playback), as Play tries them: each place in turn (Plex in your house, IPTV, addons…;
        // 2026-11-24), the places switched off too, where you put them (here you choose). (Recordings go with IPTV.)
        val (iptv, servers) = ranked.orEmpty().partition { it.copy.source == io.github.petebutty.viewarr.data.media.PROVIDER || it.copy.source == "Recording" }
        @Composable fun copies(list: List<MediaViewModel.RankedCopy>) = list.forEach { r ->
            MenuItem(
                text = (if (r.best) "★ " else "") + vm.copyLabel(r.copy), selected = false,
                trailing = if (r.best) "Best for this TV" else r.note, dim = r.note != null && !r.best,
                onClick = { onDone(); vm.playCopy(r.copy) }, modifier = mod(),
            )
        }
        PlayOrder.ordered().forEach { src ->
            when (src) {
                PlayOrder.Source.Iptv -> copies(iptv)
                PlayOrder.Source.Addons -> AddonStreamItems(ui.hasAddons, addonStreams, ::mod) { opt -> onDone(); vm.playStream(opt) }
                else -> copies(servers.filter { it.place == src })
            }
        }
        MenuItem(text = "Cancel", selected = false, onClick = onDone, modifier = mod())
    }
}

/**
 * The addons' streams in a Play from list: "Searching your addons…" while they're asked, then each stream (addon ·
 * its name, the details beside it), or a line saying they found nothing.
 */
@Composable
internal fun AddonStreamItems(hasAddons: Boolean, streams: List<StreamOption>?, mod: () -> Modifier, onPick: (StreamOption) -> Unit) {
    if (!hasAddons) return
    when {
        streams == null -> Text("Searching your addons…", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(vertical = 6.dp, horizontal = 12.dp))
        streams.isEmpty() -> Text("Your addons found nothing for this.", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(vertical = 6.dp, horizontal = 12.dp))
        else -> streams.take(25).forEach { s ->
            MenuItem(
                // (Addons' own names run to several lines of emoji: a short summary, and where it comes from.)
                text = StreamLabel.summary(s), selected = false, trailing = StreamLabel.from(s),
                onClick = { onPick(s) }, modifier = mod(),
            )
        }
    }
}

/**
 * A person's films and shows: who they are at the top (photo, born / died, where, biography). Beside the corner
 * window (live TV, or a film made small): no photo, the text from x 356 as the Movies hero, and a shorter header that
 * ends under the window (it sat under the window, photo and name hidden). (2026-11-17.)
 */
@Composable
internal fun PersonHeader(p: PersonInfo) {
    val beside = LiveBehind.preview
    Row(
        Modifier.fillMaxWidth()
            .then(if (beside) Modifier.heightIn(max = 200.dp) else Modifier)
            .padding(start = if (beside) Tokens.BesideWindowStart else Tokens.Gutter, end = Tokens.Gutter, top = if (beside) 26.dp else 36.dp, bottom = if (beside) 8.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (!beside) Box(Modifier.size(width = 150.dp, height = 210.dp).clip(Tokens.CardShape).background(AppColors.Panel)) {
            p.photo?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (beside) 4.dp else 6.dp)) {
            Text(p.name, fontSize = if (beside) 26.sp else 34.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val life = listOfNotNull(
                p.knownFor,
                p.born?.let { b -> if (p.died != null) "$b – ${p.died}" else "Born $b" },
                p.place,
            ).joinToString("  ·  ")
            if (life.isNotEmpty()) Text(life, fontSize = if (beside) 14.sp else 15.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            (p.bio ?: if (p.loading) "Loading…" else null)?.let { bio -> Text(
                bio,
                fontSize = if (beside) 14.sp else 15.sp, lineHeight = if (beside) 20.sp else 21.sp, color = AppColors.TextPrimary,
                maxLines = if (beside) 4 else 6, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (beside) 2.dp else 6.dp),
            ) }
        }
    }
}

/**
 * A title page action as an icon in a circle (like the player's controls), its name underneath while it's
 * highlighted. [on]: a state that's set (watched, on your watchlist, tracks chosen) shows filled in. The shared
 * RoundIconButton with the title page's look (thin white ring, dark fill). (2026-11-17: was its own copy.)
 */
@Composable
internal fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false) {
    RoundIconButton(
        icon = icon, label = label, onClick = onClick, modifier = modifier, on = on, outlined = true,
        // (A size down: 2026-11-18. Was 44 wide, icon 22.)
        width = 38.dp, columnWidth = 42.dp, iconSize = 19.dp, focusedScale = 1.08f, containerColor = Color.Black.copy(alpha = 0.25f),
    )
}

/**
 * Lite's top: no panel, but one line naming the page and the highlighted title (its name, year and where it's from),
 * which otherwise appeared nowhere until the title was opened; and the first row clear of the screen's top edge
 * (UI review, 2026-11-18).
 */
@Composable
internal fun LiteStrip(type: MediaType, item: MediaItem?, label: String?) {
    Column(Modifier.fillMaxWidth().padding(start = Tokens.Gutter, end = Tokens.Gutter, top = 24.dp, bottom = 6.dp)) {
        Text(label ?: if (type == MediaType.Movie) "Movies" else "Shows", fontSize = 14.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item?.let { "${it.title}   ${metaLine(it)}" } ?: " ", fontSize = 15.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}
