package io.github.petebutty.viewarr.ui.dvr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.dvr.DvrRecording
import io.github.petebutty.viewarr.data.dvr.DvrState
import io.github.petebutty.viewarr.data.dvr.RecordingProgress
import io.github.petebutty.viewarr.data.dvr.RecordingTags
import io.github.petebutty.viewarr.data.dvr.SeriesRule
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.ui.common.FocusCard
import io.github.petebutty.viewarr.ui.common.LiveBehind
import io.github.petebutty.viewarr.ui.media.RowTitle
import io.github.petebutty.viewarr.ui.media.VodHost
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Tokens
import io.github.petebutty.viewarr.ui.theme.Wallpaper
import io.github.petebutty.viewarr.ui.theme.appBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One card on the page: a recording, a show's recordings together, or a series rule. */
private class WideCard(
    val key: String,
    val title: String,
    val line: String,
    val art: String?,
    /** The header's longer text for it (its description, or what OK does). */
    val about: String?,
    val progress: Float? = null,
    val live: Boolean = false,
    val watched: Boolean = false,
    val rec: DvrRecording? = null,
    val rule: SeriesRule? = null,
    /** A show with several recordings: OK opens its episodes as a row of their own. */
    val group: String? = null,
)

private class WideRow(val key: String, val title: String, val cards: List<WideCard>)

/**
 * The Recordings page as rows of wide cards (the home-screen layout: Previewarr, 2026-11-25): the highlighted
 * recording's artwork in the corner and its details at the top, then Recording now, Continue watching, Shows (one card
 * a show; OK opens its episodes as a row), Films, Sport & other, Coming up and Series recordings. OK plays; hold OK:
 * the same options as the list page ([RecordingOptions]). Back: the row's start, then the top, then [onBack].
 */
@Composable
fun RecordingsWide(
    vm: DvrViewModel,
    focusRequester: FocusRequester,
    focusEnabled: Boolean,
    onBack: () -> Unit,
    onPlay: (VodStream) -> Unit,
) {
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val allowed by vm.allowed.collectAsStateWithLifecycle()
    val xc by vm.xc.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val progressVersion by RecordingProgress.version.collectAsStateWithLifecycle()
    val tagsVersion by RecordingTags.version.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<DvrRow?>(null) }
    // The show whose episodes are open as a row (its tag's TMDB id).
    var openShow by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }

    val rows = remember(recordings, rules, channels, progressVersion, tagsVersion, openShow) {
        fun tagOf(r: DvrRecording) = RecordingTags.of(context, r.id)
        fun channel(r: DvrRecording) = channels[r.channelId]?.name ?: "Channel"
        fun recCard(r: DvrRecording, prefix: String, showName: Boolean = true): WideCard {
            val t = tagOf(r)
            val ep = episodeTag(r.season, r.episode).removePrefix(" · ")
            val epName = t?.episodeName ?: r.subTitle
            return WideCard(
                key = "$prefix-${r.id}",
                title = if (showName) r.title else listOfNotNull(ep.ifEmpty { null }, epName).joinToString("  ").ifEmpty { r.title },
                line = listOfNotNull(if (showName) ep.ifEmpty { null } else null, channel(r), span(r.start, r.end)).joinToString("  ·  "),
                art = t?.backdrop ?: t?.poster,
                about = r.description ?: epName,
                progress = RecordingProgress.fraction(context, r.id)?.takeIf { r.state == DvrState.Recorded },
                live = r.state == DvrState.Recording,
                watched = r.state == DvrState.Recorded && RecordingProgress.watched(context, r.id),
                rec = r,
            )
        }
        val now = recordings.filter { it.state == DvrState.Recording }.sortedBy { it.start }
        val scheduled = recordings.filter { it.state == DvrState.Scheduled }.sortedBy { it.start }
        val done = recordings.filter { it.state == DvrState.Recorded || it.state == DvrState.Failed }.sortedByDescending { it.start }
        val started = done.filter { r -> RecordingProgress.fraction(context, r.id)?.let { it > 0.01f } == true && !RecordingProgress.watched(context, r.id) }
        val shows = done.filter { tagOf(it)?.kind == "show" }
        val films = done.filter { tagOf(it)?.kind == "film" }
        val other = done.filter { tagOf(it)?.kind.let { k -> k == null || k == "other" } }
        val groups = shows.groupBy { tagOf(it)?.tmdbId ?: 0 }.values.sortedByDescending { g -> g.maxOf { it.start } }
        buildList {
            if (now.isNotEmpty()) add(WideRow("now", "Recording now", now.map { recCard(it, "now") }))
            if (started.isNotEmpty()) add(WideRow("cont", "Continue watching", started.map { recCard(it, "cont") }))
            if (groups.isNotEmpty()) {
                add(WideRow("shows", "Shows", groups.map { g ->
                    val t = tagOf(g.first())
                    if (g.size == 1) recCard(g.first(), "show")
                    else {
                        val seen = g.count { RecordingProgress.watched(context, it.id) }
                        WideCard(
                            key = "group-${t?.tmdbId}", title = t?.name ?: g.first().title,
                            line = "${g.size} episodes" + (if (seen > 0) "  ·  $seen watched" else ""),
                            art = t?.backdrop ?: t?.poster, about = "OK: its episodes", group = "${t?.tmdbId}",
                        )
                    }
                }))
                // The opened show's episodes, right under Shows, newest first.
                groups.firstOrNull { "${tagOf(it.first())?.tmdbId}" == openShow && it.size > 1 }?.let { g ->
                    add(WideRow("episodes", (tagOf(g.first())?.name ?: g.first().title) + "  ·  episodes", g.sortedByDescending { it.start }.map { recCard(it, "ep", showName = false) }))
                }
            }
            if (films.isNotEmpty()) add(WideRow("films", "Films", films.map { recCard(it, "film") }))
            if (other.isNotEmpty()) add(WideRow("other", if (groups.isEmpty() && films.isEmpty()) "Recorded" else "Sport & other", other.map { recCard(it, "other") }))
            if (scheduled.isNotEmpty()) add(WideRow("soon", "Coming up", scheduled.map { recCard(it, "soon") }))
            if (rules.isNotEmpty()) add(WideRow("rules", "Series recordings", rules.sortedBy { it.label.lowercase() }.map { r ->
                WideCard(
                    key = "rule-${r.title}-${r.tvgId}", title = r.label,
                    line = (if (r.mode == "new") "New episodes" else "Every episode") + (r.tvgId.takeIf { it.isNotEmpty() }?.let { "  ·  $it" } ?: "  ·  Any channel"),
                    art = null, about = "A series recording. Hold OK to stop it.", rule = r,
                )
            }))
        }
    }

    // Where the highlight is, for the header, for Back, and for coming back after the options close.
    var at by remember { mutableStateOf<Pair<String, String>?>(null) } // row key, card key
    val column = rememberLazyListState()
    val rowStates = remember { HashMap<String, LazyListState>() }
    val cardFocus = remember { HashMap<String, FocusRequester>() }
    fun focusOf(key: String) = cardFocus.getOrPut(key) { FocusRequester() }
    val hovered = at?.let { (rk, ck) -> rows.firstOrNull { it.key == rk }?.cards?.firstOrNull { it.key == ck } }
    suspend fun frames(n: Int) { repeat(n) { withFrameNanos { } } }
    suspend fun focusCard(rowKey: String, cardKey: String?) {
        val i = rows.indexOfFirst { it.key == rowKey }.takeIf { it >= 0 } ?: return
        val row = rows[i]
        val ci = row.cards.indexOfFirst { it.key == cardKey }.coerceAtLeast(0)
        runCatching { column.scrollToItem(i) }
        frames(2)
        runCatching { rowStates[rowKey]?.scrollToItem(ci) }
        frames(2)
        row.cards.getOrNull(ci)?.let { c -> runCatching { focusOf(c.key).requestFocus() } }
    }
    // The first card, to start with; the card you were on (or the nearest left) once the options close or a card goes.
    val firstKey = rows.firstOrNull()?.cards?.firstOrNull()?.key
    LaunchedEffect(firstKey != null, focusEnabled, picked == null) {
        if (!focusEnabled || picked != null || firstKey == null) return@LaunchedEffect
        frames(2)
        val a = at
        if (a != null && rows.any { r -> r.key == a.first && r.cards.any { it.key == a.second } }) focusCard(a.first, a.second)
        else if (a != null && rows.any { it.key == a.first }) focusCard(a.first, null)
        else focusCard(rows.first().key, null)
    }
    // A show's episodes just opened: onto its first episode.
    LaunchedEffect(openShow) { if (openShow != null && rows.any { it.key == "episodes" }) { frames(2); focusCard("episodes", null) } }

    // Back: the row's first card, then the top of the page, then out. (The episodes row: closed, back on its show.)
    BackHandler(enabled = focusEnabled && picked == null) {
        val a = at
        val row = a?.let { (rk, _) -> rows.firstOrNull { it.key == rk } }
        when {
            row != null && row.key == "episodes" -> { val g = openShow; openShow = null; scope.launch { frames(2); focusCard("shows", "group-$g") } }
            row != null && row.cards.firstOrNull()?.key != a?.second -> scope.launch { focusCard(row.key, null) }
            row != null && rows.firstOrNull()?.key != row.key -> scope.launch { focusCard(rows.first().key, null) }
            else -> onBack()
        }
    }
    // The highlighted recording's artwork in the page's corner, once you've stopped on it.
    LaunchedEffect(hovered?.art) { hovered?.art?.let { delay(250); Wallpaper.title = it } }

    Box(Modifier.fillMaxSize().appBackground(titleArt = true)) {
        Column(Modifier.fillMaxSize()) {
            // The header, as tall as the home screen's banner, so the rows start at the same height.
            // (Live TV or a film in the top-left window: the text starts beside it.)
            val beside = LiveBehind.preview || (VodHost.active && VodHost.mini)
            Column(Modifier.fillMaxWidth().height(300.dp).padding(start = if (beside) Tokens.BesideWindowStart else 48.dp, top = 54.dp, end = 48.dp)
                .width(520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Recordings", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Accent)
                Text(hovered?.title ?: "Recordings", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width(460.dp))
                hovered?.let { h ->
                    val state = when {
                        h.live -> "● Recording now"
                        h.watched -> "✓ Watched"
                        h.progress != null && h.progress > 0.01f -> "${(h.progress * 100).toInt()}% watched"
                        else -> null
                    }
                    Text(listOfNotNull(h.line, state).joinToString("  ·  "), fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(460.dp))
                    h.about?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = AppColors.TextPrimary.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(460.dp).padding(top = 4.dp)) }
                }
            }
            when {
                allowed == false -> Text(
                    if (xc) "Recordings need a Dispatcharr server. Your live TV comes straight from your IPTV provider, which can't record. " +
                        "You can still watch catch-up from the TV guide if your provider offers it."
                    else "Your Dispatcharr account isn't allowed to use recordings. In Dispatcharr, open Users, edit this account and set DVR access to Manage.",
                    fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(horizontal = 48.dp).width(620.dp),
                )
                allowed == null && recordings.isEmpty() -> Text("Loading…", color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp))
                rows.isEmpty() -> Text("Nothing recorded or scheduled yet. Hold OK on a programme in the TV guide to record it.",
                    fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp))
                else -> LazyColumn(state = column, modifier = Modifier.fillMaxSize().clipToBounds(), verticalArrangement = Arrangement.spacedBy(10.dp),
                    // (Bottom 110 as the other pages: the music mini player covers the bottom of the screen.)
                    contentPadding = PaddingValues(bottom = 110.dp)) {
                    items(rows.size, key = { rows[it].key }) { i ->
                        val row = rows[i]
                        Column {
                            RowTitle(row.title, Modifier.padding(start = 48.dp, bottom = 6.dp))
                            LazyRow(state = rowStates.getOrPut(row.key) { LazyListState() }, horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 6.dp, bottom = 8.dp)) {
                                items(row.cards.size, key = { row.cards[it].key }) { ci ->
                                    val c = row.cards[ci]
                                    WideCardView(
                                        c,
                                        modifier = (if (i == 0 && ci == 0) Modifier.focusRequester(focusRequester) else Modifier)
                                            .focusRequester(focusOf(c.key)).onFocusChanged { if (it.hasFocus) at = row.key to c.key },
                                        onClick = {
                                            val r = c.rec
                                            when {
                                                c.group != null -> openShow = if (openShow == c.group) null else c.group
                                                // OK plays a finished recording straight away; anything else shows its options.
                                                r != null && r.state == DvrState.Recorded && r.hasFile -> scope.launch { vm.playback(r)?.let(onPlay) }
                                                r != null -> { picked = DvrRow.Rec(r); vm.clearMessage() }
                                                c.rule != null -> { picked = DvrRow.Rule(c.rule); vm.clearMessage() }
                                            }
                                        },
                                        onLongClick = {
                                            when {
                                                c.rec != null -> { picked = DvrRow.Rec(c.rec); vm.clearMessage() }
                                                c.rule != null -> { picked = DvrRow.Rule(c.rule); vm.clearMessage() }
                                                c.group != null -> openShow = if (openShow == c.group) null else c.group
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        RecordingOptions(picked, vm, channels, message, scope, onPlay, close = { picked = null })
    }
}

/** A wide (16:9) card as on the home screen: the picture, the name and a line over its foot, a progress bar. */
@Composable
private fun WideCardView(c: WideCard, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val w = AppColors.palette.rowPosterWidth.dp * 16f / 9f * 1.05f
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(w).height(w * 9f / 16f), shape = RoundedCornerShape(10.dp),
        containerColor = AppColors.Panel, focusedScale = 1.05f) {
        Box(Modifier.fillMaxSize()) {
            if (c.art != null) AsyncImage(model = c.art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // No picture (sport, news, a series rule): a tinted card, so the row still reads as cards.
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                if (c.rule != null) listOf(Color(0xFF3B2A6B), Color(0xFF16122E)) else listOf(Color(0xFF1F3A5F), Color(0xFF0E1A2C)))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))
                .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = if (c.progress != null) 10.dp else 6.dp)) {
                Text(c.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.line, fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Recording now: a red dot; watched: a tick; a show's recordings: how many.
            if (c.live) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(12.dp).background(Color(0xFFE11D48), CircleShape))
            else if (c.watched) Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(horizontal = 6.dp, vertical = 1.dp))
            c.progress?.takeIf { it > 0.01f && !c.watched }?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p.coerceIn(0f, 1f)).height(3.dp).background(AppColors.Accent))
            }
        }
    }
}
