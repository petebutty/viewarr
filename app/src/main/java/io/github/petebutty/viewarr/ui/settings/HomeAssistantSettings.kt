package io.github.petebutty.viewarr.ui.settings

import io.github.petebutty.viewarr.ui.common.touchFocusable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.homeassistant.HaClient
import io.github.petebutty.viewarr.data.homeassistant.HaConfig
import io.github.petebutty.viewarr.data.homeassistant.HaStore
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.PopupPanel
import io.github.petebutty.viewarr.ui.common.PopupTitle
import io.github.petebutty.viewarr.ui.report.TextEntryDialog
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Tokens
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

/** Settings → This TV → Home Assistant: open (from its entry, or from Search settings). */
object HaSettings {
    var open by mutableStateOf(false)
}

/**
 * Settings → This TV → Home Assistant: one entry; OK opens its pop-up (address, token, test, the two switches, how to
 * use it). Optional: until an address and token are set, nothing else in Viewarr does anything with Home Assistant.
 */
@Composable
fun HomeAssistantItem(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { HaStore.start(context) }
    val cfg by HaStore.config.collectAsState()
    val state = when {
        !cfg.ready -> "Not set up"
        cfg.sendPlaying || cfg.popups -> "On"
        else -> "Off"
    }
    MenuItem(text = "Home Assistant", trailing = state, selected = false, onClick = { HaSettings.open = true }, modifier = modifier)
    if (HaSettings.open) HomeAssistantPopup(cfg, onClose = { HaSettings.open = false })
}

@Composable
private fun HomeAssistantPopup(cfg: HaConfig, onClose: () -> Unit) {
    var page by remember { mutableStateOf("main") }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun test(with: HaConfig) {
        if (testing) return
        testing = true; status = "Testing…"
        scope.launch {
            status = HaClient.test(with).fold({ it }, { it.message ?: "Couldn't connect." })
            testing = false
        }
    }
    when (page) {
        "address" -> TextEntryDialog(
            "Address", "Home Assistant's address, with its port, e.g. http://192.168.1.10:8123", "http://192.168.1.10:8123", cfg.address,
            optional = false,
            onDone = { a -> HaStore.setAddress(a); page = "main"; val c = cfg.copy(address = HaStore.normalise(a)); if (c.ready) test(c) else status = null },
            onClose = { page = "main" },
        )
        "token" -> TextEntryDialog(
            "Access token", "Make one in Home Assistant: your profile → Security → Long-lived access tokens.", "Long-lived access token", "",
            optional = false,
            onDone = { t -> HaStore.setToken(t); page = "main"; val c = cfg.copy(token = t.trim()); if (c.ready) test(c) else status = null },
            onClose = { page = "main" },
        )
        "help" -> HelpPage(onBack = { page = "main" })
        else -> MainPage(cfg, status, testing, onPage = { page = it }, onTest = { test(cfg) }, onClose = onClose,
            onForget = { HaStore.clear(); status = "Home Assistant forgotten on this TV." })
    }
}

@Composable
private fun MainPage(cfg: HaConfig, status: String?, testing: Boolean, onPage: (String) -> Unit, onTest: () -> Unit, onClose: () -> Unit, onForget: () -> Unit) {
    val context = LocalContext.current
    PopupPanel(onClose = onClose, width = Tokens.PopupWide) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
        PopupTitle("Home Assistant", "Tell Home Assistant what's playing on this TV, and show pop-ups it sends (a doorbell camera, a reminder). Nothing happens until it's set up.")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuItem(text = "Address", trailing = cfg.address.ifBlank { "Not set" }, selected = false, onClick = { onPage("address") }, modifier = Modifier.focusRequester(first))
            MenuItem(text = "Access token", trailing = if (cfg.token.isBlank()) "Not set" else "•••• " + cfg.token.takeLast(4), selected = false, onClick = { onPage("token") })
            MenuItem(text = "Test connection", trailing = if (testing) "…" else null, selected = false, dim = !cfg.ready, onClick = { if (cfg.ready) onTest() })
            status?.let { Text(it, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp)) }
            MenuItem(text = "Send what's playing", trailing = if (cfg.sendPlaying) "On" else "Off", selected = false, dim = !cfg.ready,
                onClick = { HaStore.setSendPlaying(!cfg.sendPlaying) })
            MenuItem(text = "Pop-ups from Home Assistant", trailing = if (cfg.popups) "On" else "Off", selected = false, dim = !cfg.ready,
                onClick = { HaStore.setPopups(!cfg.popups) })
            MenuItem(text = "How to use it", selected = false, onClick = { onPage("help") })
            Text("Events name this TV \"${HaStore.tvName(context)}\" (its name in Android TV's settings).", fontSize = 12.sp, color = AppColors.TextSecondary,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp))
            Text("The token stays on this TV: it isn't in settings codes, backups or reports.", fontSize = 12.sp, color = AppColors.TextSecondary,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
            if (cfg.address.isNotBlank() || cfg.token.isNotBlank()) MenuItem(text = "Forget Home Assistant", selected = false, onClick = onForget)
            MenuItem(text = "Close", selected = false, onClick = onClose)
        }
    }
}

/** "How to use it": the events, and two example automations. ▲ ▼ scroll it; Down at the end goes to Back. */
@Composable
private fun HelpPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val tv = remember { HaStore.tvName(context) }
    PopupPanel(onClose = onBack, width = Tokens.PopupWide) {
        val text = remember { FocusRequester() }
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        var focused by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { awaitFrame(); runCatching { text.requestFocus() } }
        PopupTitle("How to use it", if (focused) "▲ ▼ scroll  ·  Back: close" else null)
        Column(
            Modifier.fillMaxWidth().heightIn(max = 340.dp)
                .focusRequester(text)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> if (scroll.value >= scroll.maxValue) false else { scope.launch { scroll.animateScrollTo(scroll.value + 160) }; true }
                        Key.DirectionUp -> if (scroll.value <= 0) false else { scope.launch { scroll.animateScrollTo((scroll.value - 160).coerceAtLeast(0)) }; true }
                        else -> false
                    }
                }
                .touchFocusable()
                .then(if (focused) Modifier.border(1.dp, AppColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp)) else Modifier)
                .padding(12.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HelpHeading("What's playing: event viewarr_playback")
            HelpText("Sent when something starts, pauses, carries on or stops, and when live TV goes full screen, changes channel or leaves full screen.")
            HelpText("Its data: tv, state (playing, paused, stopped), kind (film, episode, live, recording, music), title, show, season, episode, channel, full_screen, position and duration (in seconds). Anything not known is left out.")
            HelpHeading("Pop-ups: event viewarr_notify")
            HelpText("Fire it from an automation and this TV shows a card in the corner while Viewarr is open, over films and live TV too. It doesn't take the remote, and goes by itself.")
            HelpText("Its data (all optional): title, message, image (a picture's address, or Home Assistant's own like /local/door.jpg), camera (e.g. camera.front_door: its picture, updated every second), duration (seconds, 10 if left out, 120 at most), position (top_right, top_left, bottom_right, bottom_left or centre) and tv (only the TV with that name shows it; this one is \"$tv\").")
            HelpHeading("Example: lights down when a film starts")
            HelpCode(FILM_EXAMPLE)
            HelpHeading("Example: the doorbell camera on the TV")
            HelpCode(DOORBELL_EXAMPLE)
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(6.dp))
        MenuItem(text = "Back", selected = false, onClick = onBack)
    }
}

@Composable
private fun HelpHeading(s: String) = Text(s, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun HelpText(s: String) = Text(s, fontSize = 13.sp, color = AppColors.TextSecondary)

@Composable
private fun HelpCode(s: String) = Text(s, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextPrimary)

private val FILM_EXAMPLE = """
    triggers:
      - trigger: event
        event_type: viewarr_playback
        event_data:
          state: playing
          kind: film
    actions:
      - action: scene.turn_on
        target:
          entity_id: scene.film_night
""".trimIndent()

private val DOORBELL_EXAMPLE = """
    triggers:
      - trigger: state
        entity_id: binary_sensor.doorbell
        to: "on"
    actions:
      - event: viewarr_notify
        event_data:
          title: Someone's at the door
          camera: camera.front_door
          duration: 30
          position: top_right
""".trimIndent()
