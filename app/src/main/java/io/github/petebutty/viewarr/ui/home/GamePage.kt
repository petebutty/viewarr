package io.github.petebutty.viewarr.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import io.github.petebutty.viewarr.ui.theme.Tokens
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.sports.GameDetails
import io.github.petebutty.viewarr.data.sports.GameSide
import io.github.petebutty.viewarr.data.sports.TableRow
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A game's page (OK on a game in Sports; Settings → Sports → Game page): the teams and score, where it's on, buttons
 * to watch / be reminded / record (also before it's in the guide, and every game of a team), then three columns: form,
 * top scorers, this season's meetings and the ground; the league table around both teams and the stats; the match
 * report, goals and cards, line-ups and live commentary (before kick-off: line-ups and news). Down goes into the details and
 * scrolls them; Up at their top goes back to the buttons; Back closes. [besidePreview]: the corner window is up.
 */
@Composable
internal fun GamePageHost(hub: SportsHub, besidePreview: Boolean) {
    val open = hub.openGame ?: return
    val (g, ch) = open
    val gw = hub.sportsVm.gameWatch
    val version by gw.version
    val reminders by hub.dvr.reminders.collectAsState()
    BackHandler { hub.closeGame() }
    // (Its fallback channel goes with it when the page closes, so the next game's page can't pick it up.)
    DisposableEffect(g.id) { onDispose { if (hub.openGame?.first?.id != g.id) hub.openChannel = null } }
    // Live: fetched again every minute while the page is open (score, goals, cards).
    var detailsTried by remember(g.id) { mutableStateOf(false) }
    val details by produceState<GameDetails?>(null, g.id) {
        while (true) { value = hub.sportsVm.gameInfo.details(g) ?: value; detailsTried = true; if (g.state != "in") break; delay(60_000) }
    }
    val first = remember { FocusRequester() }
    val detailsFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var inside by remember { mutableStateOf(false) }
    var detailsFocused by remember { mutableStateOf(false) }
    LaunchedEffect(g.id) { repeat(20) { if (inside) return@LaunchedEffect; runCatching { first.requestFocus() }; delay(60) } }
    val now = System.currentTimeMillis()
    val started = ch != null && (g.state == "in" || ch.second.startTime <= now)
    val onTv = hub.tv[g.id].orEmpty().filter { it != GuideViewModel.NOT_ON_UK_TV }

    Box(
        // (Focus stays in the page: nothing behind it can be reached while it's open.)
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)).onFocusChanged { inside = it.hasFocus }
            .trapFocus(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.92f).background(AppColors.Panel, RoundedCornerShape(Tokens.PopupRadius)).padding(28.dp)) {
            // The header and buttons clear of the live window in the top-left corner (it covered the first buttons:
            // Watch, Remind me. Peter, 2026-11-16); the details below use the whole width. ([besidePreview]: worked out
            // where the page is drawn, live TV or a film in the corner; it was set only by the Sports page and never
            // reset, so a page opened from the info bar could get a stale one. UI review, 2026-11-17.)
            Column(Modifier.padding(start = if (besidePreview) 272.dp else 0.dp)) {
            // The teams and the score (or kick-off).
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Team(g.home, g.homeLogo, Modifier.weight(1f), alignEnd = true)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                    Text(g.score ?: Dates.time(g.start), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, maxLines = 1, softWrap = false)
                    Text(
                        when (g.state) { "in" -> "Live" + (g.detail?.let { " · $it" } ?: ""); "post" -> "Full time"; else -> Dates.dayTime(g.start) },
                        fontSize = 14.sp, color = if (g.state == "in") AppColors.Accent else AppColors.TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Team(g.away, g.awayLogo, Modifier.weight(1f), alignEnd = false)
            }
            Text(
                listOfNotNull(g.leagueName, details?.venue, ch?.first?.name?.let { "On $it" } ?: onTv.joinToString(", ").ifEmpty { null }?.let { "On $it" }
                    ?: details?.broadcasts?.joinToString(", ")?.ifEmpty { null }?.let { "TV: $it" }).joinToString("  ·  "),
                fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
            )
            // Why this channel (where it was found, and how surely), so a wrong one is plain to see.
            if (hub.openWhy.isNotBlank()) Text("ⓘ " + hub.openWhy, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 10.dp))
            // What you can do with it.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { runCatching { detailsFocus.requestFocus() }; true } else false
            }) {
                val buttons = buildList<Pair<String, () -> Unit>> {
                    // (Its channel by name or an event channel when the guide doesn't list it.)
                    val watchCh = ch?.first ?: hub.openChannel
                    if (watchCh != null && g.state != "post" && (started || ch == null)) add("Watch" to { hub.closeGame(); hub.watch(watchCh) })  // (closeGame: focus back on its game first, not lost to the menu)
                    if (g.state == "pre" && !started) {
                        if (ch != null) {
                            val r = reminders.firstOrNull { it.channelId == ch.first.id && it.start == ch.second.startTime }
                            add((if (r != null) "✓ Reminder" else "Remind me") to {
                                if (r != null) { hub.dvr.forget(r.id); hub.toast("Reminder cancelled: ${g.title}") }
                                else { hub.dvr.remind(ch.first, ch.second); hub.toast("🔔 Reminder set: ${g.title} on ${ch.first.name}") }
                            })
                            add("Record" to { hub.dvr.record(ch.first, ch.second); gw.markDone(g.id); hub.toast("Recording ${g.title} on ${ch.first.name}") })
                        } else {
                            // A channel known without the guide (listings, club stream): reminded at kick-off on it. Else
                            // set once the guide lists the game. (It only saved a wish, with no word, and for games the
                            // guide never lists that way nothing came. Peter, 2026-11-16.)
                            if (watchCh != null) {
                                val set = reminders.any { it.channelId == watchCh.id && it.start == g.start }
                                add((if (set) "✓ Reminder" else "Remind me") to { hub.toggleKickOffReminder(g, watchCh) })
                            } else add((if (gw.wantsReminder(g.id)) "✓ Reminder" else "Remind me") to {
                                val want = !gw.wantsReminder(g.id); gw.setReminder(g.id, want)
                                hub.toast(if (want) "We'll remind you of ${g.title} once it's in your TV guide" else "Reminder cancelled")
                            })
                            add((if (gw.wantsRecording(g.id)) "✓ Recording" else "Record") to {
                                val want = !gw.wantsRecording(g.id); gw.setRecording(g.id, want)
                                hub.toast(if (want) "${g.title} will be recorded once it's in your TV guide" + io.github.petebutty.viewarr.data.sports.TvGameRecorder.whileRunning else "Won't record ${g.title}")
                            })
                        }
                    }
                    listOf(g.home, g.away).forEach { team ->
                        add((if (gw.recordsTeam(team)) "✓ Record all $team" else "Record all $team") to {
                            val want = !gw.recordsTeam(team); gw.setTeam(team, want)
                            hub.toast(if (want) "Recording every $team game once it's in your TV guide" + io.github.petebutty.viewarr.data.sports.TvGameRecorder.whileRunning else "Won't record every $team game")
                        })
                    }
                    add("Close" to { hub.closeGame() })
                }
                version // (redraw ticks when the choices change)
                buttons.forEachIndexed { i, (label, act) ->
                    Box(Modifier.width(if (label.length > 14) 240.dp else 150.dp)) {
                        MenuItem(text = label, selected = label.startsWith("✓"), onClick = act, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
                    }
                }
            }
            if (g.state == "pre" && ch == null) Text(
                "Not in your TV guide yet: a recording asked for here is set as soon as the guide lists it" +
                    (if (io.github.petebutty.viewarr.data.sync.ServerSports.pluginAlive) "." else ", while Viewarr is running on this TV (the Viewarr plugin on Dispatcharr does it with every TV off)."),
                fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp),
            )
            }
            Spacer(Modifier.height(16.dp))
            // The details: Down into them, Up / Down scroll, Up at the top back to the buttons.
            Box(
                Modifier.fillMaxWidth().weight(1f).focusRequester(detailsFocus).onFocusChanged { detailsFocused = it.isFocused }.focusable()
                    // (Outlined while you're in the details, so you can see where you are.)
                    .then(if (detailsFocused) Modifier.border(2.dp, AppColors.Accent, RoundedCornerShape(10.dp)).padding(10.dp) else Modifier.padding(10.dp))
                    .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(220f) }; true }
                        Key.DirectionUp -> { if (scroll.value == 0) runCatching { first.requestFocus() } else scope.launch { scroll.animateScrollBy(-220f) }; true }
                        else -> false
                    }
                },
            ) {
                val d = details
                // (A failed fetch said "Getting…" for ever. UI review, 2026-11-18.)
                if (d == null) Text(if (detailsTried) "Couldn't get the details (ESPN isn't answering). Back to close." else "Getting the line-ups, form and table…", fontSize = 14.sp, color = AppColors.TextSecondary)
                else Row(Modifier.fillMaxWidth().verticalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    // 1: form, top scorers, this season's meetings, the ground.
                    Column(Modifier.weight(1f)) {
                        Heading("Form", first = true)
                        listOf(d.home, d.away).forEach { FormLine(it) }
                        if (d.home.leaders.isNotEmpty() || d.away.leaders.isNotEmpty()) {
                            Heading("Top scorers")
                            listOf(d.home, d.away).forEach { side ->
                                side.leaders.forEach { (what, who) -> Line("${side.name} · ${what.lowercase()}", who) }
                            }
                        }
                        d.series?.let { Heading("This season"); Text(it, fontSize = 14.sp, color = AppColors.TextPrimary) }
                        if (d.venue != null) {
                            Heading("Ground")
                            Text(listOfNotNull(d.venue, d.city).joinToString(", "), fontSize = 14.sp, color = AppColors.TextPrimary)
                            d.attendance?.let { Text("Attendance %,d".format(it), fontSize = 13.sp, color = AppColors.TextSecondary) }
                        }
                    }
                    // 2: the league table around both teams, then the stats.
                    Column(Modifier.weight(1f)) {
                        if (d.table.isNotEmpty()) { Heading(d.tableName ?: "Table", first = true); TableWindow(d.table) }
                        if (d.stats.isNotEmpty()) {
                            Heading("Stats", first = d.table.isEmpty())
                            d.stats.forEach { (k, h, a) -> StatBar(k, h, a) }
                        }
                    }
                    // 3: the report, goals and cards, line-ups, commentary (or before: line-ups and news).
                    Column(Modifier.weight(1.25f)) {
                        var firstHeading = true
                        @Composable fun H(t: String) { Heading(t, first = firstHeading); firstHeading = false }
                        d.report?.let { (head, sum) ->
                            H("Match report")
                            Text(head, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                            if (sum.isNotBlank()) Text(sum, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
                        }
                        if (d.moments.isNotEmpty()) {
                            H("Goals, cards and changes")
                            d.moments.forEach { m -> Text("${m.clock}  ${m.kind}  ·  ${m.text}", fontSize = 13.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(vertical = 1.dp)) }
                        }
                        H("Line-ups")
                        listOf(d.home, d.away).forEach { side ->
                            Text(side.name + (side.formation?.let { "  ($it)" } ?: ""), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 4.dp))
                            Text(if (side.starters.isEmpty()) "Not announced yet (usually an hour before kick-off)" else side.starters.joinToString(", "),
                                fontSize = 13.sp, color = if (side.starters.isEmpty()) AppColors.TextSecondary else AppColors.TextPrimary)
                            if (side.subs.isNotEmpty()) Text("Subs: " + side.subs.joinToString(", "), fontSize = 12.sp, color = AppColors.TextSecondary)
                        }
                        if (d.commentary.isNotEmpty() && g.state != "pre") {
                            H("Commentary")
                            d.commentary.forEach { (t, text) ->
                                Row(Modifier.padding(vertical = 2.dp)) {
                                    Text(t, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Accent, modifier = Modifier.width(40.dp), maxLines = 1, softWrap = false)
                                    Text(text, fontSize = 13.sp, color = AppColors.TextPrimary)
                                }
                            }
                        }
                        if (d.news.isNotEmpty() && g.state == "pre") {
                            H("News")
                            d.news.forEach { (head, sum) ->
                                Text(head, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 4.dp))
                                if (sum.isNotBlank()) Text(sum, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Team(name: String, logo: String?, modifier: Modifier, alignEnd: Boolean) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        if (!alignEnd) logo?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(56.dp)); Spacer(Modifier.width(12.dp)) }
        Text(name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (alignEnd) logo?.let { Spacer(Modifier.width(12.dp)); AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(56.dp)) }
    }
}

@Composable
private fun Heading(text: String, first: Boolean = false) =
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = AppColors.Accent,
        modifier = Modifier.padding(top = if (first) 0.dp else 16.dp, bottom = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun Line(label: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
    Text(label, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Text(value, fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 1)
}

/** A side's last five as coloured W / D / L marks (newest last, as read left to right), with the latest result. */
@Composable
private fun FormLine(s: GameSide) = Column(Modifier.padding(bottom = 6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(s.name, fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (s.lastFive.isEmpty()) Text("—", fontSize = 13.sp, color = AppColors.TextSecondary)
        s.lastFive.reversed().forEach { r ->
            val c = when (r.result) { "W" -> Color(0xFF2E7D32); "L" -> Color(0xFFC62828); else -> Color(0xFF616161) }
            Text(r.result, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.padding(start = 3.dp).background(c, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp), maxLines = 1, softWrap = false)
        }
    }
    s.lastFive.firstOrNull()?.let { r -> Text("Last: ${r.result} ${r.score} ${if (r.home) "v" else "@"} ${r.opponent}", fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** The table's top, then two places either side of each team (theirs highlighted), gaps marked. */
@Composable
private fun TableWindow(rows: List<TableRow>) {
    val idx = rows.withIndex().filter { it.value.mine }.map { it.index }
    val show = (listOf(0) + idx.flatMap { i -> (i - 2..i + 2) }).filter { it in rows.indices }.toSortedSet()
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Text("", modifier = Modifier.width(28.dp))
        Text("", modifier = Modifier.weight(1f))
        listOf("P", "GD", "Pts").forEach { Text(it, fontSize = 11.sp, color = AppColors.TextSecondary, modifier = Modifier.width(36.dp), maxLines = 1, softWrap = false) }
    }
    var last = -1
    show.forEach { i ->
        if (last >= 0 && i > last + 1) Text("···", fontSize = 11.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 28.dp))
        last = i
        val r = rows[i]
        Row(Modifier.fillMaxWidth().then(if (r.mine) Modifier.background(AppColors.Accent.copy(alpha = 0.18f), RoundedCornerShape(4.dp)) else Modifier).padding(vertical = 2.dp)) {
            val w = if (r.mine) FontWeight.SemiBold else FontWeight.Normal
            Text("${r.rank}", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.width(28.dp).padding(start = 4.dp), maxLines = 1, softWrap = false)
            Text(r.team, fontSize = 13.sp, fontWeight = w, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(r.played, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.width(36.dp), maxLines = 1, softWrap = false)
            Text(r.gd, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.width(36.dp), maxLines = 1, softWrap = false)
            Text(r.points, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.width(36.dp), maxLines = 1, softWrap = false)
        }
    }
}

/** A stat with both values and a bar split between the sides. */
@Composable
private fun StatBar(label: String, h: String, a: String) = Column(Modifier.padding(vertical = 3.dp)) {
    Row(Modifier.fillMaxWidth()) {
        Text(h, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.width(48.dp), maxLines = 1, softWrap = false)
        Text(label, fontSize = 12.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(a, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, textAlign = TextAlign.End, modifier = Modifier.width(48.dp), maxLines = 1, softWrap = false)
    }
    val x = h.trimEnd('%').toFloatOrNull() ?: 0f
    val y = a.trimEnd('%').toFloatOrNull() ?: 0f
    if (x + y > 0f) Row(Modifier.fillMaxWidth().height(4.dp).padding(top = 1.dp)) {
        if (x > 0f) Box(Modifier.weight(x).fillMaxHeight().background(AppColors.Accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(3.dp))
        if (y > 0f) Box(Modifier.weight(y).fillMaxHeight().background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(2.dp)))
    }
}
