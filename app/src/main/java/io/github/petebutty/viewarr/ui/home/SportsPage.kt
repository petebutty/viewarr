package io.github.petebutty.viewarr.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.sports.SportFollow
import io.github.petebutty.viewarr.data.sports.SportGame
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates

/** A game on the Sports page, with the channel it's on (your guide's, else the one the UK listings name). */
private class GameCard(val game: SportGame, val inGuide: Pair<ChannelListItem, GuideProgram>?, val named: List<String>, val channel: ChannelListItem?)

/**
 * The Sports page (Sports in the menu): what you follow (Settings → Sports) as rows of game panels, each with both
 * teams' badges, like preview pictures. On now first, then a row per team or league. OK on a panel goes to the channel
 * showing it: the one in your guide, else the one the UK Sport on TV plugin names. The top-left window stays clear for
 * live TV, with the highlighted game's details beside it.
 */
@Composable
internal fun SportsPage(
    hub: SportsHub,
    follows: List<SportFollow>,
    channels: List<ChannelListItem>,
    besidePreview: Boolean,
    focusRequester: FocusRequester,
    focusEnabled: Boolean,
    onWatch: (ChannelListItem) -> Unit,
    onToast: (String) -> Unit,
    onOpenMenu: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = focusEnabled) { onBack() }
    // Every channel's name cleaned up once (not on every move: thousands of channels, for every panel, made moving
    // along the rows crawl).
    val byNorm = remember(channels) { channels.map { it.name.lowercase().replace(NON_ALNUM, "") to it } }
    val exact = remember(byNorm) { byNorm.toMap() }
    // A channel by the name the listings give ("Sky Sports Main Event", "TNT Sports 1"…): exact first, then close.
    fun byName(name: String): ChannelListItem? {
        val n = name.lowercase().replace(NON_ALNUM, "")
        return exact[n] ?: byNorm.firstOrNull { (cn, c) -> cn.startsWith(n) || (n.startsWith(cn) && c.name.length > 4) }?.second
    }
    // An event channel named after the match (the EPG & Sports Editor plugin's "Home @ Away", or a provider's
    // "UEFA 20: 12:45 KuPS vs Gent"), for a game on now or within a few hours.
    fun eventChannel(g: SportGame): ChannelListItem? {
        if (g.state == "post" || g.start - System.currentTimeMillis() > 6 * 3_600_000L) return null
        val a = teamKey(g.home); val b = teamKey(g.away)
        return channels.firstOrNull { it.name.contains(a, true) && it.name.contains(b, true) }
    }
    // Where OK goes: the game in your guide (the Sports Editor's Pregame / Live listings count), else the channel the
    // UK Sport on TV plugin names, else an event channel named after the match.
    fun cardOf(g: SportGame, ch: Pair<ChannelListItem, GuideProgram>?): GameCard {
        val named = hub.tv[g.id].orEmpty().filter { it != GuideViewModel.NOT_ON_UK_TV }
        return GameCard(g, ch, named, ch?.first ?: named.firstNotNullOfOrNull { byName(it) } ?: eventChannel(g))
    }
    // Worked out again only when the games, their channels or your follows change, not as the highlight moves.
    val gamesNow = hub.games.toMap()
    val tvNow = hub.tv.toMap()
    val rows: List<Pair<String, List<GameCard>>> = remember(follows, gamesNow, tvNow, byNorm) {
        buildList {
            val onNow = follows.flatMap { gamesNow[it.shortcutId].orEmpty() }.filter { it.first.state == "in" }.distinctBy { it.first.id }
            if (onNow.isNotEmpty()) add("On now" to onNow.map { (g, c) -> cardOf(g, c) })
            // (A row's games once each: a game listed twice crashed the row.)
            follows.forEach { f ->
                val list = gamesNow[f.shortcutId].orEmpty()
                val ordered = list.filter { it.first.state != "post" } + list.filter { it.first.state == "post" }.reversed().take(3)
                if (ordered.isNotEmpty() && none { it.first == f.name }) add(f.name to ordered.distinctBy { it.first.id }.map { (g, c) -> cardOf(g, c) })
            }
        }
    }
    var hover by remember { mutableStateOf<GameCard?>(null) }
    LaunchedEffect(focusEnabled, rows.isNotEmpty()) {
        if (!focusEnabled || rows.isEmpty()) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
    Column(Modifier.fillMaxSize().background(AppColors.Background)) {
        // The header: the top-left window's place (live TV) and the highlighted game beside it.
        Row(Modifier.fillMaxWidth().height(232.dp)) {
            Spacer(Modifier.width(if (besidePreview) 356.dp else 48.dp))
            Column(Modifier.padding(top = 26.dp, end = 48.dp)) {
                Text("Sports", fontSize = 14.sp, color = AppColors.Accent)
                Spacer(Modifier.height(8.dp))
                val c = hover ?: rows.firstOrNull()?.second?.firstOrNull()
                Text(c?.game?.let { g -> if (g.score != null) "${g.home} ${g.score} ${g.away}" else g.title } ?: "Your teams and leagues",
                    fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(c?.let { whenLine(it) } ?: "Follow teams and leagues in Settings → Sports.", fontSize = 15.sp, color = AppColors.TextSecondary, maxLines = 2)
                c?.let { Spacer(Modifier.height(4.dp)); Text(it.game.leagueName, fontSize = 13.sp, color = AppColors.TextSecondary) }
                c?.let { Spacer(Modifier.height(10.dp)); Text(if (it.channel != null) "OK: watch on ${it.channel.name}" else "Not on any of your channels", fontSize = 13.sp, color = if (it.channel != null) AppColors.Accent else AppColors.TextSecondary) }
            }
        }
        if (rows.isEmpty()) Text(
            if (follows.isEmpty()) "Nothing followed yet: add teams and leagues in Settings → Sports." else "Loading games…",
            fontSize = 16.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 48.dp, top = 8.dp),
        )
        else LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            rows.forEachIndexed { r, (title, cards) ->
                item(key = "row-$title") {
                    Column {
                        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(cards, key = { _, c -> "g-$title-${c.game.id}" }) { i, c ->
                                GamePanel(
                                    c,
                                    modifier = (if (r == 0 && i == 0) Modifier.focusRequester(focusRequester) else Modifier)
                                        .onFocusChanged { if (it.isFocused) hover = c }
                                        .onPreviewKeyEvent { e ->
                                            // Left on the first panel of a row: the menu, as on every page.
                                            if (i == 0 && e.key == Key.DirectionLeft) { if (e.type == KeyEventType.KeyDown) onOpenMenu(); true } else false
                                        },
                                ) {
                                    val ch = c.channel
                                    when {
                                        ch != null -> onWatch(ch)
                                        c.game.state == "post" -> onToast("${c.game.home} ${c.game.score ?: ""} ${c.game.away}")
                                        c.named.isNotEmpty() -> onToast("${c.game.title}: on ${c.named.joinToString(", ")}, not one of your channels")
                                        else -> onToast("${c.game.title}: not in your TV guide yet")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val NON_ALNUM = Regex("[^a-z0-9]")
private val TEAM_SPLIT = Regex("[^\\p{L}0-9]+")
private val SKIP_WORDS = setOf("fc", "afc", "cf", "sc", "united", "city", "town", "athletic", "county", "rovers", "the", "de", "real", "club")
/** A team's telling word ("Celtic", "Rangers", "Lakers"), as the guide's own game search uses. */
private fun teamKey(team: String) = team.split(TEAM_SPLIT).filter { it.length > 2 && it.lowercase() !in SKIP_WORDS }.maxByOrNull { it.length } ?: team

/** When it's on and where: "Live 63'  ·  Sky Sports Main Event", "Sat 15:00  ·  TNT Sports 1", "Full time". */
private fun whenLine(c: GameCard): String {
    val where = c.channel?.name ?: c.named.firstOrNull()
    return when (c.game.state) {
        "in" -> "Live" + (c.game.detail?.let { " $it" } ?: "") + (where?.let { "  ·  $it" } ?: "")
        "post" -> "Full time  ·  " + Dates.dayTime(c.game.start)
        else -> Dates.dayTime(c.game.start) + (where?.let { "  ·  $it" } ?: "  ·  not on your channels yet")
    }
}

/** A game as a picture-shaped panel: both badges with the score (or the kick-off time) between, live marked. */
@Composable
private fun GamePanel(c: GameCard, modifier: Modifier, onClick: () -> Unit) {
    val g = c.game
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(208.dp)) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(width = 208.dp, height = 117.dp),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            shape = ClickableSurfaceDefaults.shape(shape),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, AppColors.Accent), shape = shape)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        ) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1E2A44), Color(0xFF0F1626))))) {
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Team(g.homeLogo, g.home, Modifier.weight(1f))
                    Text(
                        g.score ?: if (g.state == "pre") Dates.dayTime(g.start).substringAfterLast(' ') else "v",
                        fontSize = if (g.score != null) 18.sp else 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        textAlign = TextAlign.Center, modifier = Modifier.width(50.dp),
                    )
                    Team(g.awayLogo, g.away, Modifier.weight(1f))
                }
                val tag = when (g.state) { "in" -> "LIVE"; "post" -> "FT"; else -> null }
                if (tag != null) Text(
                    tag, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(if (g.state == "in") Color(0xFFD9363E) else Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(whenLine(c), fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).heightIn(min = 16.dp))
    }
}

@Composable
private fun Team(logo: String?, name: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (logo != null) AsyncImage(model = logo, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Text(name.take(3).uppercase(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(4.dp))
        Text(name, fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
