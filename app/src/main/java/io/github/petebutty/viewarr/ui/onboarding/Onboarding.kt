package io.github.petebutty.viewarr.ui.onboarding

import io.github.petebutty.viewarr.ui.common.instantFocusScale
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.setup.SetupViewModel
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.appBackground
import kotlinx.coroutines.android.awaitFrame
import io.github.petebutty.viewarr.ui.setup.WizardField

/** First-run tour: shown once after setup on this TV (and from Settings → About → Show the tour). */
object Tour {
    /** Settings → About → Show the tour. */
    var requested by mutableStateOf(false)
    private fun prefs(c: Context) = c.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    fun done(c: Context) = prefs(c).getBoolean("tour_done", false)
    fun markDone(c: Context) = prefs(c).edit().putBoolean("tour_done", true).apply()
}

/**
 * Puts focus on [r] and keeps asking until [has] says this screen has it (for up to 3 s): these screens open on
 * top of others, which otherwise keep the remote.
 */
@Composable
private fun FocusOnStart(r: FocusRequester, key: Any? = Unit, has: () -> Boolean) {
    LaunchedEffect(key) {
        repeat(40) {
            awaitFrame()
            if (has()) return@LaunchedEffect
            runCatching { r.requestFocus() }
            kotlinx.coroutines.delay(60)
        }
    }
}

// ------------------------------------------------------------------ who are you?

/** The very first screen on a new install: running your own server, or invited by someone who does. */
@Composable
fun RoleScreen(onAdmin: () -> Unit, onInvited: () -> Unit) {
    val first = remember { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    FocusOnStart(first) { inside }
    Box(Modifier.fillMaxSize().appBackground().onFocusChanged { inside = it.hasFocus }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                io.github.petebutty.viewarr.ui.common.ViewarrMark(48.dp)
                Spacer(Modifier.width(10.dp))
                Text("viewarr", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(10.dp))
            Text("Welcome to Viewarr", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("How are you setting it up?", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
            RoleCard("I run my own server", "Set up Viewarr with your Dispatcharr (found on your network), and your other services.", onAdmin, Modifier.focusRequester(first))
            Spacer(Modifier.height(10.dp))
            RoleCard("Someone gave me sign-in details", "A friend or family member shares their Viewarr: enter the address, username and password they sent you.", onInvited)
        }
    }
}

@Composable
private fun RoleCard(title: String, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().instantFocusScale(1.02f),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = io.github.petebutty.viewarr.ui.common.focusScale(1.02f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ------------------------------------------------------------------ invited

/** Invited: the server's outside address and the API key their admin sent, from the phone (or typed here). */
@Composable
fun InvitedSetup(onBack: () -> Unit, vm: SetupViewModel = hiltViewModel(key = "setup-invited")) {
    val state by vm.state.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    FocusOnStart(first) { inside }
    BackHandler { onBack() }
    // The three things their admin sent (Profiles → Add someone shows them): no keys, no codes.
    Row(Modifier.fillMaxSize().appBackground().onFocusChanged { inside = it.hasFocus }.padding(horizontal = 64.dp, vertical = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(0.42f).padding(end = 40.dp)) {
            Text("Viewarr", fontSize = 14.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Sign in with the details you were sent", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 18.dp))
            Text("The address, your username and your password. Everything else is set up for you.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 14.dp))
        }
        Column(Modifier.weight(0.58f)) {
            WizardField(address, { address = it }, "Address (e.g. tv.example.com)", first, KeyboardType.Uri, ImeAction.Next, {})
            Spacer(Modifier.height(10.dp))
            WizardField(username, { username = it }, "Username", null, KeyboardType.Text, ImeAction.Next, {})
            Spacer(Modifier.height(10.dp))
            WizardField(password, { password = it }, "Password", null, KeyboardType.Password, ImeAction.Done, {}, password = true)
            Spacer(Modifier.height(14.dp))
            MenuItem(text = if (state.busy) "Signing in…" else "Sign in", selected = false, onClick = { if (!state.busy) vm.invitedPasswordSignIn(address, username, password) })
            MenuItem(text = "Back", selected = false, onClick = onBack)
            state.error?.let { Text(it, fontSize = 14.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 10.dp, start = 12.dp)) }
        }
    }
}

// ------------------------------------------------------------------ the tour

/**
 * [art]: the picture; pulse goes 0…1…0 (buttons), wave runs 0…1 over and over (things in turn). Both are read where
 * they're drawn (graphicsLayer), so the page isn't composed again every frame.
 */
private data class TourPage(val title: String, val text: String, val art: @Composable BoxScope.(pulse: () -> Float, wave: () -> Float) -> Unit)

/**
 * Short pages on how Viewarr works: Left / Right move, OK on the last one (or Skip, Down from Next) finishes. [pages]: the tour, or the
 * "Before you set up" pages; [finishLabel]: the last page's button.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TourScreen(onDone: () -> Unit, prep: Boolean = false, finishLabel: String = "Start watching", onBack: () -> Unit = onDone) {
    val pages = remember(prep) { if (prep) prepPages() else tourPages() }
    var page by remember { mutableIntStateOf(0) }
    val next = remember { FocusRequester() }
    // Down from Next reaches Skip (Right turns the page, so Skip couldn't be reached with the remote at all).
    val skip = remember { FocusRequester() }
    var onSkip by remember { mutableStateOf(false) }
    var inside by remember { mutableStateOf(false) }
    FocusOnStart(next) { inside }
    // Home loads underneath and can take the remote when it's ready: take it back whenever it's lost.
    LaunchedEffect(inside) {
        if (!inside) { kotlinx.coroutines.delay(150); repeat(20) { if (inside) return@LaunchedEffect; runCatching { next.requestFocus() }; kotlinx.coroutines.delay(100) } }
    }
    BackHandler { if (page > 0) page-- else onBack() }
    val anim = rememberInfiniteTransition(label = "tour")
    // Kept as State and read only in graphicsLayer (through these lambdas): composition doesn't run per frame.
    val pulseState = anim.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val waveState = anim.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "wave")
    val pulse = remember(pulseState) { { pulseState.value } }
    val wave = remember(waveState) { { waveState.value } }
    Box(
        Modifier.fillMaxSize().background(AppColors.Background).appBackground().onFocusChanged { inside = it.hasFocus }.focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()
            .onPreviewKeyEvent { e ->
                // On Skip the arrows move the highlight as usual (Left / Up: back to Next).
                if (e.type != KeyEventType.KeyDown || (onSkip && page < pages.lastIndex)) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { if (page < pages.lastIndex) page++; true }
                    Key.DirectionLeft -> { if (page > 0) page--; true }
                    else -> false
                }
            },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 30.dp, bottom = 70.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = page, modifier = Modifier.weight(1f),
                transitionSpec = { (slideInHorizontally { it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 } + fadeOut()) }, label = "page",
            ) { i ->
                val p = pages[i]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(340.dp).padding(end = 28.dp)) {
                        Text("${i + 1} of ${pages.size}", fontSize = 12.sp, color = AppColors.Accent, maxLines = 1, softWrap = false)
                        Text(p.title, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 8.dp))
                        Text(p.text, fontSize = 14.sp, lineHeight = 21.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 14.dp))
                    }
                    Box(Modifier.weight(1f).height(380.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(18.dp)) { p.art(this, pulse, wave) }
                }
            }
        }
        // Dots, and the buttons.
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            pages.indices.forEach { i ->
                Box(Modifier.padding(horizontal = 5.dp).size(if (i == page) 12.dp else 8.dp).background(if (i == page) AppColors.Accent else AppColors.TextSecondary.copy(alpha = 0.5f), CircleShape))
            }
            Spacer(Modifier.width(30.dp))
            MenuItem(text = if (page == pages.lastIndex) finishLabel else "Next  ›", selected = false, onClick = { if (page == pages.lastIndex) onDone() else page++ }, modifier = Modifier.width(180.dp).focusRequester(next)
                .focusProperties { down = if (page < pages.lastIndex) skip else FocusRequester.Default })
            Spacer(Modifier.width(10.dp))
            if (page < pages.lastIndex) MenuItem(text = "Skip", selected = false, onClick = onDone,
                modifier = Modifier.width(120.dp).focusRequester(skip).onFocusChanged { onSkip = it.isFocused }.focusProperties { up = next })
        }
    }
}

/** A remote button drawn as a pill, gently pulsing ([pulse] 0…1, read while drawing). */
@Composable
private fun RemoteKey(label: String, pulse: () -> Float, modifier: Modifier = Modifier) {
    Box(
        modifier.graphicsLayer { val p = pulse(); scaleX = 1f + p * 0.08f; scaleY = 1f + p * 0.08f }
            .background(AppColors.Accent, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.OnAccent, maxLines = 1, softWrap = false) }
}

/** A spot on a tour screenshot (x, y as 0…1 of its width / height) with what it does. */
internal data class Spot(val x: Float, val y: Float, val text: String, val labelLeft: Boolean = false)

private fun tourPages(): List<TourPage> = listOf(
    TourPage(
        "Welcome to Viewarr",
        "Live TV with a proper TV guide, your films, shows and streaming services, and your music, in one place, made for the remote. One see-through menu on the left takes you everywhere, and whatever's playing keeps going in the window at the top left. Use Right to go on.",
    ) { pulse, wave ->
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionCard.entries.forEachIndexed { i, c ->
                    // A smooth wave across the cards, each in turn (drawn, not laid out again: smooth on weak boxes).
                    Box(
                        Modifier.graphicsLayer {
                            val phase = ((wave() - i / 4f) % 1f + 1f) % 1f
                            val lift = if (phase < 0.5f) kotlin.math.sin(phase * 2f * Math.PI.toFloat()) else 0f
                            translationY = -lift * 12.dp.toPx(); scaleX = 1f + lift * 0.04f; scaleY = 1f + lift * 0.04f
                        }.size(104.dp, 146.dp)
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.top, c.bottom)), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.Canvas(Modifier.size(54.dp).offset(y = (-12).dp)) { c.draw(this) }
                        Text(c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Next page: ", fontSize = 15.sp, color = AppColors.TextSecondary, maxLines = 1, softWrap = false); RemoteKey("Right ›", pulse) }
        }
    },
    TourPage(
        "The menu",
        "Press Left to open the menu (it slides in, pushing the page aside). Moving up and down changes the page behind straight away: Live TV, Movies, Shows, your streaming services, Sports, Music, Recordings. On an entry, Right goes into its page and Left opens its own list (channel groups, libraries…). Hold OK on an entry to hide, show or move it.",
    ) { pulse, _ ->
        Mock(listOf(
            // (The ✎ Edit menus button is gone: hold OK edits the menu. Peter, 2026-11-17.)
            Spot(0.03f, 0.175f, "Right: into its page"),
            Spot(0.03f, 0.236f, "Up / Down: the page changes"),
            Spot(0.03f, 0.345f, "Hold OK: hide, show or move"),
            Spot(0.38f, 0.18f, "What's playing stays here"),
        ), pulse) { MenuScreen() }
    },
    TourPage(
        "The TV guide",
        "Move with the arrows and press OK to watch: the picture plays in the window at the top left, OK again goes full screen. Hold OK on a programme for more: record it or the series, a reminder, start over, go to the show. A programme already recorded plays with OK.",
    ) { pulse, _ ->
        Mock(listOf(
            Spot(0.23f, 0.42f, "OK: watch · Hold OK: more"),
            Spot(0.16f, 0.18f, "The picture, top left"),
        ), pulse) { GuideScreen() }
    },
    TourPage(
        "Channel groups",
        "Left from the guide shows your channel groups. Moving through them changes the guide behind as you go; OK picks one and takes you back into the guide. Left again brings the main menu.",
    ) { pulse, _ ->
        Mock(listOf(
            Spot(0.05f, 0.19f, "The guide follows as you move"),
        ), pulse) { GroupsScreen() }
    },
    TourPage(
        "Watching live TV",
        "Full screen, one info bar does it all: what's on now and next, the picture and sound, and the controls. Change any button in Settings → Remote buttons.",
    ) { pulse, _ ->
        Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("OK" to "The controls (record, start over, audio & subtitles…)", "▲ ▼" to "Change channel", "Left" to "The channel list, or skip back (your choice)",
                "Back" to "Mini player (Back again: full screen)", "Hold Back" to "Close the player", "Down" to "Next episodes, when it's a show").forEach { (k, t) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(120.dp), contentAlignment = Alignment.CenterEnd) { RemoteKey(k, pulse) }
                    Spacer(Modifier.width(18.dp))
                    Text(t, fontSize = 16.sp, color = AppColors.TextPrimary)
                }
            }
        }
    },
    TourPage(
        "Films, shows and your services",
        "Rows of what you're watching, what's new and what's popular, from your Plex, Jellyfin or Emby and your addons. Netflix, Prime Video and the rest are in the menu with their own rows. As you move, the details show at the top and a trailer plays in the window.",
    ) { pulse, _ ->
        Mock(listOf(
            Spot(0.09f, 0.49f, "OK: open · Hold OK: quick menu"),
            Spot(0.16f, 0.18f, "Trailers play here"),
        ), pulse) { ServiceScreen() }
    },
    TourPage(
        "A title page",
        "Play picks the best copy (your own servers first). Hold OK on Play to choose the source yourself. Seasons and episodes are right below. While an episode plays, Down shows the next ones; Back sends the film or show to the window at the top left and Stop ends it.",
    ) { pulse, _ ->
        Mock(listOf(
            Spot(0.22f, 0.286f, "OK: play · Hold OK: pick a source"),
            Spot(0.07f, 0.375f, "Seasons and episodes"),
        ), pulse) { TitleScreen() }
    },
    TourPage(
        "Make it yours",
        "Settings has it all: Appearance for fonts, text size, the glass and its colour. Menu & navigation for what the menu lists. Remote buttons changes what every button does. Sports follows your teams. Search is the remote's search button or Search in the menu. You can see this tour again in Settings → About.",
    ) { pulse, _ ->
        Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf("Appearance" to "Fonts, glass, colours, the menu", "Remote buttons" to "Every button, your way", "Sports" to "Your teams' games and channels",
                "Home page & menu" to "Rows and libraries, in your order", "Playback" to "Players, subtitles, trailers").forEach { (k, t) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(190.dp), contentAlignment = Alignment.CenterEnd) { RemoteKey(k, pulse) }
                    Spacer(Modifier.width(18.dp))
                    Text(t, fontSize = 16.sp, color = AppColors.TextPrimary)
                }
            }
        }
    },
)

/** Before the setup wizard: what you'll be asked for, so it's to hand. */
private fun prepPages(): List<TourPage> = listOf(
    TourPage(
        "1 · Live TV",
        "Next you'll connect your live TV. Have one of these ready:",
    ) { _, _ -> PrepList(listOf(
        "Dispatcharr" to "Your server's address (e.g. http://192.168.1.10:9191), and your username and password",
        "IPTV (Xtream codes)" to "The server address, username and password from your provider",
        "M3U playlist" to "The playlist link, and the guide (EPG) link if it has one",
    )) },
    TourPage(
        "2 · Your libraries (optional)",
        "Films and shows you already have. Skip any you don't use; you can add them later.",
    ) { _, _ -> PrepList(listOf(
        "Plex" to "Nothing to type: a code appears, approve it on your phone",
        "Jellyfin" to "The server address and your login, or Quick Connect",
        "Emby" to "The server address and your login",
        "Navidrome" to "For music: the address and your login",
    )) },
    TourPage(
        "3 · Extras (optional)",
        "These make Viewarr richer. The easiest way is \"Set up on your phone\": scan the code and paste them in there. All can be added later in Settings → Accounts & sources.",
    ) { _, _ -> PrepList(listOf(
        "Addons" to "AIOStreams, AIOMetadata or other Stremio addon links: streams and the streaming services' rows",
        "Trakt" to "A code to approve: your watch history and watchlist",
        "TMDB / MDBList" to "API keys for artwork, details and ratings",
        "Seerr" to "Its address and API key, to request films and shows",
    )) },
)

/** The prep pages' list: what, and what you'll need for it. */
@Composable
private fun BoxScope.PrepList(items: List<Pair<String, String>>) {
    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items.forEach { (what, need) ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(10.dp).background(AppColors.Accent, CircleShape))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(what, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                    Text(need, fontSize = 14.sp, lineHeight = 20.sp, color = AppColors.TextSecondary)
                }
            }
        }
    }
}

/** The four parts of Viewarr on the tour's first page: a colour each and a simple drawn picture. */
private enum class SectionCard(val label: String, val top: Color, val bottom: Color) {
    LiveTv("Live TV", Color(0xFF3E63DD), Color(0xFF1B2A5E)) {
        override fun draw(d: androidx.compose.ui.graphics.drawscope.DrawScope) = with(d) {
            val w = size.width; val h = size.height
            drawRoundRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(w * 0.05f, h * 0.12f), size = androidx.compose.ui.geometry.Size(w * 0.9f, h * 0.62f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.07f))
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.35f, h * 0.9f), androidx.compose.ui.geometry.Offset(w * 0.65f, h * 0.9f), strokeWidth = w * 0.07f)
            drawCircle(Color(0xFFE5484D), radius = w * 0.1f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.43f))
        }
    },
    Movies("Movies", Color(0xFFF97316), Color(0xFF6B2A05)) {
        override fun draw(d: androidx.compose.ui.graphics.drawscope.DrawScope) = with(d) {
            val w = size.width; val h = size.height
            drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.38f), size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.5f))
            // The clapper: a tilted striped bar.
            rotate(-14f, pivot = androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.34f)) {
                drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.18f), size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.16f))
                for (k in 0..3) drawRect(Color(0xFF6B2A05), topLeft = androidx.compose.ui.geometry.Offset(w * (0.14f + k * 0.2f), h * 0.18f), size = androidx.compose.ui.geometry.Size(w * 0.09f, h * 0.16f))
            }
        }
    },
    Shows("Shows", Color(0xFF30A46C), Color(0xFF0E3B25)) {
        override fun draw(d: androidx.compose.ui.graphics.drawscope.DrawScope) = with(d) {
            val w = size.width; val h = size.height
            for (k in 2 downTo 0) {
                val inset = k * w * 0.08f
                drawRoundRect(if (k == 0) Color.White else Color.White.copy(alpha = 0.45f - k * 0.12f),
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.12f + inset, h * 0.22f - inset), size = androidx.compose.ui.geometry.Size(w * 0.62f, h * 0.62f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
            }
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.36f, h * 0.38f); lineTo(w * 0.36f, h * 0.68f); lineTo(w * 0.58f, h * 0.53f); close() }
            drawPath(p, Color(0xFF0E3B25))
        }
    },
    Music("Music", Color(0xFF8E4EC6), Color(0xFF331A4D)) {
        override fun draw(d: androidx.compose.ui.graphics.drawscope.DrawScope) = with(d) {
            val w = size.width; val h = size.height
            drawCircle(Color.White, radius = w * 0.16f, center = androidx.compose.ui.geometry.Offset(w * 0.3f, h * 0.78f))
            drawCircle(Color.White, radius = w * 0.16f, center = androidx.compose.ui.geometry.Offset(w * 0.74f, h * 0.68f))
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.44f, h * 0.78f), androidx.compose.ui.geometry.Offset(w * 0.44f, h * 0.14f), strokeWidth = w * 0.07f)
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.68f), androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.04f), strokeWidth = w * 0.07f)
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(w * 0.44f, h * 0.16f), androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.06f), strokeWidth = w * 0.14f)
        }
    };
    abstract fun draw(d: androidx.compose.ui.graphics.drawscope.DrawScope)
}
