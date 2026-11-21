package io.github.petebutty.viewarr.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.media.PersonCredit
import io.github.petebutty.viewarr.data.media.PersonDetails
import io.github.petebutty.viewarr.data.media.PersonPicture
import io.github.petebutty.viewarr.data.media.Review
import io.github.petebutty.viewarr.ui.common.AppPill
import io.github.petebutty.viewarr.ui.common.BackLayer
import io.github.petebutty.viewarr.ui.common.CardAspect
import io.github.petebutty.viewarr.ui.common.FocusCard
import io.github.petebutty.viewarr.ui.common.LiveBehind
import io.github.petebutty.viewarr.ui.common.ViewarrPopup
import io.github.petebutty.viewarr.ui.common.leftOpens
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates
import io.github.petebutty.viewarr.ui.theme.Tokens
import io.github.petebutty.viewarr.ui.theme.Wallpaper
import java.time.LocalDate
import java.time.Period
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.withContext

/** A person's page as the model holds it: the name and photo from the card at once, [details] once TMDB answers. */
internal data class PersonPageUi(val id: String, val name: String, val photo: String?, val details: PersonDetails?, val loading: Boolean)

private enum class PersonTab(val label: String) { Overview("Overview"), Filmography("Filmography"), Media("Media") }
private enum class CreditFilter(val label: String) { All("All"), Films("Films"), Shows("Shows") }
private enum class CreditSort(val label: String) { NewFirst("Release date ↓"), OldFirst("Release date ↑"), RatingHigh("Rating ↓"), RatingLow("Rating ↑") }

/**
 * The page's choices (tab, filter, sort) and where the highlight is: every focusable part registers itself by id
 * ([track]), so the highlight can come back to the one you were on (from a title, the menu, a pop-up).
 */
private class PersonState {
    var tab by mutableIntStateOf(0)
    var filter by mutableStateOf(CreditFilter.All)
    var sort by mutableStateOf(CreditSort.NewFirst)
    val targets = HashMap<String, FocusRequester>()
    var last: String? = null
    var first: String? = null
    fun focus(id: String) { targets[id]?.let { runCatching { it.requestFocus() } } }
    fun focusTab() = focus("tab|$tab")
    fun entry(): FocusRequester = last?.let { targets[it] } ?: first?.let { targets[it] } ?: targets["tab|$tab"] ?: FocusRequester.Default
}

/** Registers this part as a focus target [id] of the page (see [PersonState]). */
@Composable
private fun Modifier.track(s: PersonState, id: String): Modifier {
    val r = remember(id) { FocusRequester() }
    DisposableEffect(id) {
        s.targets[id] = r
        onDispose { if (s.targets[id] === r) s.targets.remove(id) }
    }
    return this.focusRequester(r).onFocusChanged { if (it.isFocused) s.last = id }
}

/** Up goes to [to] (once per press) instead of the nearest thing above. */
private fun Modifier.upTo(to: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    if (e.key != Key.DirectionUp) return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyDown) to()
    true
}

/** Subtle panel behind the facts and the biography. */
private val cardBack get() = AppColors.Panel.copy(alpha = 0.55f)

/**
 * A person's page (from search, or a title's cast), in place of the rows on Movies / Shows / search: who they are on
 * the left; their picture, and Overview / Filmography / Media on the right. Back is the screen's (back to the results).
 */
@Composable
internal fun PersonPageHost(vm: MediaViewModel, focusRequester: FocusRequester, canGrab: Boolean, onOpenMenu: () -> Unit) {
    val ui = vm.personPage.collectAsStateWithLifecycle().value ?: return
    // The page draws its own picture: not the last title's artwork behind it as well (Movies / Shows background).
    DisposableEffect(Unit) {
        Wallpaper.title = null
        Wallpaper.titleArt.clear()
        onDispose { }
    }
    androidx.compose.runtime.key(ui.id) { PersonPage(ui, focusRequester, canGrab, onOpen = { vm.open(it) }, onOpenMenu = onOpenMenu) }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PersonPage(ui: PersonPageUi, focusRequester: FocusRequester, canGrab: Boolean, onOpen: (MediaItem) -> Unit, onOpenMenu: () -> Unit) {
    val d = ui.details
    val s = remember { PersonState() }
    var bioOpen by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    val known = remember(d) {
        d?.credits.orEmpty().filter { it.item.poster != null }
            .sortedWith(compareByDescending<PersonCredit> { it.votes }.thenByDescending { it.popularity }).take(12).map { it.item }
    }
    val latest = remember(d) { d?.credits.orEmpty().filter { it.item.poster != null && it.date != null }.sortedByDescending { it.date?.toEpochDay() }.take(20).map { it.item } }
    s.first = known.firstOrNull()?.let { "known|${it.key}" }
    // Left from the left-most title or tab: Read more, else the menu (as on the other pages).
    val hasBio = d?.bio != null
    val toLeft: () -> Unit = { if (hasBio) s.focus("more") else onOpenMenu() }
    // The first title highlighted once the details are in (once: later the screen brings the highlight back itself).
    var grabbed by remember { mutableStateOf(false) }
    LaunchedEffect(d != null, canGrab) {
        if (grabbed || d == null || !canGrab) return@LaunchedEffect
        awaitFrame(); awaitFrame()
        if (runCatching { focusRequester.requestFocus() }.isSuccess) grabbed = true
    }
    // (Beside the corner window, live TV top left: everything starts under it.)
    val beside = LiveBehind.preview
    Row(Modifier.fillMaxSize().focusRequester(focusRequester).focusProperties { enter = { s.entry() } }.focusGroup()) {
        PersonSide(ui, s, beside, onReadMore = { bioOpen = true }, onOpenMenu = onOpenMenu, modifier = Modifier.fillMaxHeight().weight(0.3f))
        Box(Modifier.fillMaxHeight().weight(0.7f)) {
            PersonBackdrop(d?.backdrop, Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.TopEnd))
            Column(Modifier.fillMaxSize().padding(top = if (beside) 208.dp else 150.dp)) {
                PersonTabs(s, toLeft)
                when {
                    d == null -> Text(
                        if (ui.loading) "Loading…" else "Couldn't load this person from TMDB.",
                        fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 8.dp, top = 12.dp),
                    )
                    s.tab == 0 -> PersonOverview(known, latest, s, onOpen, toLeft)
                    s.tab == 1 -> PersonFilmography(d, s, onOpen, toLeft)
                    else -> PersonMedia(d.pictures, s, onPick = { viewer = it }, toLeft = toLeft)
                }
            }
        }
    }
    if (bioOpen && d?.bio != null) ReviewDialog(Review(author = d.name, rating = null, text = d.bio, date = null), onClose = { bioOpen = false })
    viewer?.let { start -> d?.pictures?.takeIf { it.isNotEmpty() }?.let { PictureViewer(it, start.coerceIn(0, it.lastIndex), onClose = { viewer = null }) } }
}

/** The left column: portrait, name, what they do, the facts and the biography. */
@Composable
private fun PersonSide(ui: PersonPageUi, s: PersonState, beside: Boolean, onReadMore: () -> Unit, onOpenMenu: () -> Unit, modifier: Modifier) {
    val d = ui.details
    Column(
        modifier.padding(start = 40.dp, end = 16.dp, top = if (beside) 208.dp else 26.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!beside) Box(Modifier.width(104.dp).aspectRatio(CardAspect.Poster).clip(Tokens.CardShape).background(AppColors.Panel)) {
            (d?.photo ?: ui.photo)?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column {
            Text(d?.name ?: ui.name, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            d?.roles?.let { Text(it, fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
        }
        if (d != null) PersonFacts(d)
        d?.bio?.let { PersonBio(it, s, beside, onReadMore, onOpenMenu, Modifier.weight(1f, fill = false)) }
    }
}

@Composable
private fun PersonFacts(d: PersonDetails) {
    val rows = buildList {
        d.born?.let { b -> add("Born" to b.format(Dates.medium) + (if (d.died == null) " (${Period.between(b, LocalDate.now()).years} years)" else "")) }
        d.died?.let { x -> add("Died" to x.format(Dates.medium) + (d.born?.let { " (aged ${Period.between(it, x).years})" } ?: "")) }
        d.place?.let { add("Birthplace" to it) }
        if (d.titleCount > 0) add("Credits" to "${d.titleCount} title${if (d.titleCount == 1) "" else "s"}" + (d.since?.let { " · since $it" } ?: ""))
    }
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(cardBack, Tokens.CardShape).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { (k, v) ->
            Row {
                Text(k, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, modifier = Modifier.width(74.dp))
                Text(v, fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PersonBio(bio: String, s: PersonState, beside: Boolean, onReadMore: () -> Unit, onOpenMenu: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().background(cardBack, Tokens.CardShape).padding(12.dp)) {
        Text("Biography", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            bio, fontSize = 13.sp, lineHeight = 19.sp, color = AppColors.TextPrimary.copy(alpha = 0.9f),
            maxLines = if (beside) 4 else 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        AppPill(
            "Read more", selected = false, onClick = onReadMore, height = 30.dp,
            modifier = Modifier.padding(top = 8.dp).track(s, "more").free()
                .leftOpens(onMenu = onOpenMenu)
                // Right: the tabs (not whichever title happens to be level with it).
                .onPreviewKeyEvent { e ->
                    if (e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) s.focusTab()
                    true
                },
        )
    }
}

/** Overview / Filmography / Media: moving onto a tab shows it. */
@Composable
private fun PersonTabs(s: PersonState, toLeft: () -> Unit) {
    Row(Modifier.padding(start = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PersonTab.entries.forEachIndexed { i, t ->
            AppPill(
                t.label, selected = s.tab == i, onClick = { s.tab = i },
                modifier = Modifier.track(s, "tab|$i").free()
                    .onFocusChanged { if (it.isFocused) s.tab = i }
                    .then(if (i == 0) Modifier.leftOpens(onMenu = toLeft) else Modifier),
            )
        }
    }
}

/** "Known for" (their best-known titles) and "Latest" (newest first), as the app's poster rows. */
@Composable
private fun PersonOverview(known: List<MediaItem>, latest: List<MediaItem>, s: PersonState, onOpen: (MediaItem) -> Unit, toLeft: () -> Unit) {
    if (known.isEmpty() && latest.isEmpty()) {
        Text("No films or shows found.", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(RowGap)) {
        if (known.isNotEmpty()) item(key = "known") { PersonRow("known", "Known for", known, s, onOpen, toLeft, upToTabs = true) }
        if (latest.isNotEmpty()) item(key = "latest") { PersonRow("latest", "Latest", latest, s, onOpen, toLeft, upToTabs = known.isEmpty()) }
    }
}

@Composable
private fun PersonRow(id: String, title: String, items: List<MediaItem>, s: PersonState, onOpen: (MediaItem) -> Unit, toLeft: () -> Unit, upToTabs: Boolean) {
    Column {
        RowTitle(title, Modifier.padding(start = 8.dp))
        LazyRow(contentPadding = PaddingValues(start = 8.dp, end = 48.dp, top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
                PosterCard(
                    item = item,
                    modifier = Modifier.track(s, "$id|${item.key}")
                        .then(if (i == 0) Modifier.leftOpens(onMenu = toLeft) else Modifier)
                        .then(if (upToTabs) Modifier.upTo { s.focusTab() } else Modifier),
                    onClick = { onOpen(item) },
                    trailer = false,
                )
            }
        }
    }
}

/** How many grid columns fit [widthPx] (4–6), at the library grid's poster size. */
private fun gridColumns(widthPx: Int, density: Float): Int {
    val wDp = widthPx / density - 8f - 40f + 12f
    return (wDp / (AppColors.palette.libraryPosterDp + 12f)).toInt().coerceIn(4, 6)
}

/** Every title they've acted in, directed or written, as a grid; filter and sort pills above it. */
@Composable
private fun PersonFilmography(d: PersonDetails, s: PersonState, onOpen: (MediaItem) -> Unit, toLeft: () -> Unit) {
    val list = remember(d, s.filter, s.sort) {
        val shown = d.credits.filter {
            when (s.filter) { CreditFilter.All -> true; CreditFilter.Films -> it.item.type == MediaType.Movie; CreditFilter.Shows -> it.item.type == MediaType.Show }
        }
        when (s.sort) {
            CreditSort.NewFirst -> shown.sortedWith(compareByDescending<PersonCredit> { it.date?.toEpochDay() })
            CreditSort.OldFirst -> shown.sortedWith(compareBy<PersonCredit, Long?>(nullsLast()) { it.date?.toEpochDay() })
            CreditSort.RatingHigh -> shown.sortedWith(compareByDescending<PersonCredit> { it.item.rating }.thenByDescending { it.votes })
            CreditSort.RatingLow -> shown.sortedWith(compareBy<PersonCredit, Double?>(nullsLast()) { it.item.rating }.thenByDescending { it.votes })
        }
    }
    val density = LocalDensity.current.density
    var cols by remember { mutableIntStateOf(5) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppPill(
                s.filter.label, selected = false, height = 30.dp,
                onClick = { s.filter = CreditFilter.entries[(s.filter.ordinal + 1) % CreditFilter.entries.size] },
                modifier = Modifier.track(s, "filter").free().leftOpens(onMenu = toLeft),
            )
            AppPill(
                s.sort.label, selected = false, height = 30.dp,
                onClick = { s.sort = CreditSort.entries[(s.sort.ordinal + 1) % CreditSort.entries.size] },
                modifier = Modifier.track(s, "sort").free(),
            )
            Text("${list.size} title${if (list.size == 1) "" else "s"}", fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1)
        }
        if (list.isEmpty()) {
            Text("Nothing here.", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            modifier = Modifier.fillMaxSize().onSizeChanged { cols = gridColumns(it.width, density) },
            contentPadding = PaddingValues(start = 8.dp, end = 40.dp, top = 4.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(list, key = { _, c -> c.item.key }) { i, c ->
                CreditCard(c, Modifier.track(s, "grid|${c.item.key}").then(if (i % cols == 0) Modifier.leftOpens(onMenu = toLeft) else Modifier), onOpen)
            }
        }
    }
}

/** A filmography poster with its title, year · Film / Show · rating, and the job when it isn't acting. */
@Composable
private fun CreditCard(c: PersonCredit, modifier: Modifier, onOpen: (MediaItem) -> Unit) {
    Column {
        PosterCard(item = c.item, modifier = modifier, onClick = { onOpen(c.item) }, fill = true, trailer = false)
        Text(c.item.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        val meta = listOfNotNull(
            c.item.year?.toString(),
            if (c.item.type == MediaType.Movie) "Film" else "Show",
            c.item.rating?.let { "★ %.1f".format(it) },
        ).joinToString(" · ")
        Text(meta, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (c.jobs.isNotEmpty()) Text(c.jobs.joinToString(", "), fontSize = 11.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Their portraits and the pictures they're tagged in; OK shows one full screen. */
@Composable
private fun PersonMedia(pics: List<PersonPicture>, s: PersonState, onPick: (Int) -> Unit, toLeft: () -> Unit) {
    if (pics.isEmpty()) {
        Text("No pictures on TMDB.", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
        return
    }
    val density = LocalDensity.current.density
    var cols by remember { mutableIntStateOf(5) }
    // Which column each picture starts in (wide ones take two, and move to the next line if only one is left), so Left
    // from the first column can go to Read more / the menu.
    val firstCol = remember(pics, cols) {
        var col = 0
        pics.map { p ->
            val span = if (p.wide) 2 else 1
            if (col + span > cols) col = 0
            val start = col
            col = (col + span) % cols
            start == 0
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(cols),
        modifier = Modifier.fillMaxSize().onSizeChanged { cols = gridColumns(it.width, density) },
        contentPadding = PaddingValues(start = 8.dp, end = 40.dp, top = 4.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(pics, key = { i, _ -> i }, span = { _, p -> GridItemSpan(if (p.wide) 2.coerceAtMost(maxLineSpan) else 1) }) { i, p ->
            FocusCard(
                onClick = { onPick(i) },
                modifier = Modifier.track(s, "pic|$i").free().then(if (firstCol.getOrElse(i) { false }) Modifier.leftOpens(onMenu = toLeft) else Modifier),
                aspect = if (p.wide) CardAspect.Wide else CardAspect.Poster,
            ) {
                AsyncImage(model = p.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** One picture full screen (decoded at no more than 1920 × 1080); Left / Right for the others, Back closes. */
@Composable
private fun PictureViewer(pics: List<PersonPicture>, start: Int, onClose: () -> Unit) {
    var i by remember { mutableIntStateOf(start) }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    BackLayer()
    ViewarrPopup(onClose = onClose) {
        LaunchedEffect(Unit) { awaitFrame(); runCatching { focus.requestFocus() } }
        Box(
            Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { i = (i - 1).coerceAtLeast(0); true }
                        Key.DirectionRight -> { i = (i + 1).coerceAtMost(pics.lastIndex); true }
                        else -> false
                    }
                },
        ) {
            val url = pics[i].full
            AsyncImage(
                model = remember(url) { ImageRequest.Builder(context).data(url).size(1920, 1080).precision(coil.size.Precision.INEXACT).build() },
                contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
            )
            if (pics.size > 1) Text(
                "${i + 1} / ${pics.size}", fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).background(Color.Black.copy(alpha = 0.5f), Tokens.PillShape).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * The big picture at the top right, fading into whatever is behind it at its left and bottom. The fade is baked
 * into the picture once (no see-through layer drawn every frame).
 */
@Composable
private fun PersonBackdrop(url: String?, modifier: Modifier) {
    val context = LocalContext.current
    val img by produceState<ImageBitmap?>(null, url) {
        value = null
        val u = url ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val r = runCatching { context.imageLoader.execute(ImageRequest.Builder(context).data(u).size(1280, 720).allowHardware(false).build()) }
                .getOrNull() as? SuccessResult ?: return@withContext null
            val src = runCatching { r.drawable.toBitmap() }.getOrNull() ?: return@withContext null
            runCatching { fadedBackdrop(src).asImageBitmap() }.getOrNull()
        }
    }
    img?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier) }
}

/** [src] cropped to 1280 × 720, a touch darker, see-through toward its left and bottom edges. */
private fun fadedBackdrop(src: android.graphics.Bitmap): android.graphics.Bitmap {
    val w = 1280; val h = 720
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
    val sw = (w / scale).toInt().coerceAtMost(src.width); val sh = (h / scale).toInt().coerceAtMost(src.height)
    val sx = (src.width - sw) / 2; val sy = (src.height - sh) / 2
    c.drawBitmap(src, android.graphics.Rect(sx, sy, sx + sw, sy + sh), android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    c.drawColor(android.graphics.Color.argb(50, 0, 0, 0))
    val mask = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }
    val clear = 0x00000000; val half = 0x99000000.toInt(); val solid = 0xFF000000.toInt()
    mask.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(clear, half, solid), floatArrayOf(0f, 0.3f, 0.6f), android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
    mask.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(solid, solid, clear), floatArrayOf(0f, 0.4f, 1f), android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
    return out
}
