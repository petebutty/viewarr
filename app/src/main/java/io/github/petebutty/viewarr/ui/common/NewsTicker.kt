package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.news.Headline
import io.github.petebutty.viewarr.data.news.NewsFeed
import io.github.petebutty.viewarr.data.news.NewsFeeds
import io.github.petebutty.viewarr.data.news.TickerStyle
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class NewsViewModel @Inject constructor(val feeds: NewsFeeds) : androidx.lifecycle.ViewModel() {
    /** The headlines (taking turns between the feeds on), kept here so the ticker shows at once and carries on. */
    var headlines by mutableStateOf<List<Headline>>(emptyList()); private set
    /** Where the ticker had got to (the info bar's comes and goes: it carries on rather than starting again). */
    var index = 0
    private var loading: Job? = null
    /** Tickers on screen now: the feeds are only fetched while one is (switched off: no polling at all). */
    private var users = 0
    fun shown() { users++; LiveScores.onScreen++ }
    fun hidden() { users = (users - 1).coerceAtLeast(0); LiveScores.onScreen = (LiveScores.onScreen - 1).coerceAtLeast(0); if (users == 0) { loading?.cancel(); loading = null; loadingFor = null } }
    private var misses = 0
    private var loadingFor: List<NewsFeed>? = null

    /** Keeps [on]'s headlines fresh: every 10 minutes, or every 45 s while none have come (network not up yet). */
    fun keep(on: List<NewsFeed>) {
        if (on == loadingFor && loading?.isActive == true) return
        loading?.cancel(); loadingFor = on
        if (on.isEmpty()) { headlines = emptyList(); return }
        loading = viewModelScope.launch {
            while (true) {
                val per = coroutineScope { on.map { f -> async { feeds.headlines(f).map { Headline(f, it) } } }.awaitAll() }
                val list = (0 until (per.maxOfOrNull { it.size } ?: 0)).flatMap { i -> per.mapNotNull { it.getOrNull(i) } }
                if (list.isNotEmpty() || headlines.isEmpty()) headlines = list
                // Nothing came: tried again less and less often (45 s, 90 s… up to 10 min), never hammering a dead feed.
                misses = if (list.isEmpty()) misses + 1 else 0
                // (With Live scores on: every minute, for the scores; the web feeds are only fetched every 10 minutes
                // anyway, NewsFeeds keeping their copies.)
                val scores = on.any { it.id == NewsFeeds.LIVE_SCORES }
                delay(if (list.isEmpty() && !scores) minOf(45_000L shl (misses - 1).coerceIn(0, 4), 10 * 60_000L) else if (scores) 60_000L else 10 * 60_000L)
            }
        }
    }
}

/**
 * Goals and other moments for the ticker (the plugin's score alerts): the next thing to scroll across, ahead of the
 * news. With no ticker on screen (or Live scores off) they show as a banner instead (ServerSports decides).
 */
object LiveScores {
    val pending = androidx.compose.runtime.mutableStateListOf<String>()
    /** Tickers on screen now. */
    var onScreen by androidx.compose.runtime.mutableIntStateOf(0)
    val feed by lazy { NewsFeed(NewsFeeds.LIVE_SCORES, "Live scores", "viewarr://scores", true, TickerStyle.SkySports) }
}

/** The news ticker's settings (Settings → Appearance → News ticker), read from the app's options. */
object Ticker {
    /** Built-in feeds on until you change them (Live scores only shows anything while your teams play). */
    private val defaultOn = setOf("bbc-news", "sky-sports", NewsFeeds.LIVE_SCORES)
    fun feedOn(id: String) = when (AppColors.palette.opt(UiOpts.newsFeedKey(id))) { 1 -> true; 2 -> false; else -> id in defaultOn }
    fun style(f: NewsFeed): TickerStyle = AppColors.palette.opt(UiOpts.newsStyleKey(f.id)).let { if (it in 1..TickerStyle.entries.size) TickerStyle.entries[it - 1] else f.defaultStyle }
    val withInfoBar get() = AppColors.palette.opt(UiOpts.NEWS_INFOBAR) == 1
    val inGuide get() = AppColors.palette.opt(UiOpts.NEWS_GUIDE) == 1
    val onPages get() = AppColors.palette.opt(UiOpts.NEWS_PAGES) == 1
    /** Pixels a second the headline moves: normal, slow, fast. */
    val speed get() = floatArrayOf(90f, 60f, 130f)[AppColors.palette.opt(UiOpts.NEWS_SPEED).coerceIn(0, 2)]

    /** Label background, label text, strip background, strip text. */
    fun colours(s: TickerStyle): List<Color> = when (s) {
        TickerStyle.Glass -> listOf(AppColors.Accent, AppColors.OnAccent, Color(0xD9101418), Color.White)
        TickerStyle.SkySports -> listOf(Color(0xFFFFD200), Color.Black, Color(0xF20B1E3F), Color.White)
        TickerStyle.Bbc -> listOf(Color(0xFFBB1919), Color.White, Color(0xF2141414), Color.White)
        TickerStyle.Espn -> listOf(Color(0xFFD00000), Color.White, Color(0xF2000000), Color.White)
        TickerStyle.SkyNews -> listOf(Color(0xFFC8102E), Color.White, Color(0xF2002240), Color.White)
    }
}

/**
 * A news ticker strip: one headline at a time scrolling across, the feed's name in its label, in that feed's look
 * (Settings → Appearance → News ticker), then the next headline (taking turns between feeds). Nothing when no feed is on.
 */
@Composable
fun NewsTicker(modifier: Modifier = Modifier, vm: NewsViewModel = hiltViewModel()) {
    val version by vm.feeds.version
    val on = remember(version, AppColors.palette) { vm.feeds.all().filter { Ticker.feedOn(it.id) } }
    androidx.compose.runtime.DisposableEffect(vm) { vm.shown(); onDispose { vm.hidden() } }
    LaunchedEffect(on) { vm.keep(on) }
    val headlines = vm.headlines
    // A goal / kick-off / full time waiting: shown next, ahead of the news (LiveScores).
    var override by remember { mutableStateOf<Headline?>(null) }
    if (headlines.isEmpty() && override == null && LiveScores.pending.isEmpty()) return
    var index by remember { mutableIntStateOf(if (headlines.isEmpty()) 0 else vm.index % headlines.size) }
    // Goes up with every headline shown (index alone stays 0 → 0 with a single headline, and it stopped for good).
    var run by remember { mutableIntStateOf(0) }
    LaunchedEffect(LiveScores.pending.size) {
        if (override == null && LiveScores.pending.isNotEmpty()) { override = Headline(LiveScores.feed, LiveScores.pending.removeAt(0)); run++ }
    }
    val h = override ?: headlines.getOrNull(index % headlines.size.coerceAtLeast(1)) ?: return
    val (labelBg, labelFg, stripBg, stripFg) = Ticker.colours(Ticker.style(h.feed))
    val density = androidx.compose.ui.platform.LocalDensity.current
    var boxW by remember { mutableIntStateOf(0) }
    var textW by remember { mutableIntStateOf(0) }
    val x = remember { androidx.compose.animation.core.Animatable(0f) }
    // Each headline comes in from the right and goes off the left, at the chosen speed, then the next.
    LaunchedEffect(run, boxW, textW, headlines) {
        if (boxW == 0 || textW == 0) return@LaunchedEffect
        x.snapTo(boxW.toFloat())
        val pxPerSec = with(density) { Ticker.speed.dp.toPx() }
        val ms = ((boxW + textW) / pxPerSec * 1000f).toInt().coerceAtLeast(1_000)
        x.animateTo(-textW.toFloat(), androidx.compose.animation.core.tween(ms, easing = androidx.compose.animation.core.LinearEasing))
        if (override != null) override = null
        else if (headlines.isNotEmpty()) { index = (index + 1) % headlines.size; vm.index = index }
        // (A moment waiting: next, before the news carries on.)
        if (LiveScores.pending.isNotEmpty()) override = Headline(LiveScores.feed, LiveScores.pending.removeAt(0))
        run++
    }
    Row(modifier.height(34.dp).background(stripBg), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.fillMaxHeight().background(labelBg).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(h.feed.name.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = labelFg, maxLines = 1)
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().onSizeChanged { boxW = it.width }, contentAlignment = Alignment.CenterStart) {
            key(run) {
                Text(
                    h.text, fontSize = 15.sp, color = stripFg, maxLines = 1, softWrap = false,
                    modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                        .onSizeChanged { textW = it.width }
                        .offset { androidx.compose.ui.unit.IntOffset(x.value.toInt(), 0) },
                )
            }
        }
    }
}

/** Whether the ticker has headlines to show (so a place can make room only when it will draw). */
@Composable
fun newsTickerHasHeadlines(vm: NewsViewModel = hiltViewModel()) = vm.headlines.isNotEmpty()
