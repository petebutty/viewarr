package io.github.petebutty.viewarr.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.media.NetworkSetup
import io.github.petebutty.viewarr.data.media.ServiceLogo
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LiveHubSetupViewModel @Inject constructor(
    val setup: NetworkSetup,
    private val store: AccountStore,
    private val sessions: io.github.petebutty.viewarr.data.session.SessionStore,
    private val http: io.github.petebutty.viewarr.data.remote.services.HttpJson,
    private val stremio: io.github.petebutty.viewarr.data.remote.services.StremioService,
) : ViewModel() {
    val accounts = store.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val session = sessions.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    suspend fun tmdbKey() = store.current().tmdbKey

    /** What the addons already added give: network rows ("<Name> · …", two or more per name) and streams. */
    data class AddonCheck(val networks: List<String> = emptyList(), val streams: Boolean = false, val done: Boolean = false)
    val check = kotlinx.coroutines.flow.MutableStateFlow(AddonCheck())
    fun checkAddons() = viewModelScope.launch {
        val addons = store.current().activeAddons
        val names = ArrayList<String>(); var streams = false
        addons.forEach { a ->
            val m = runCatching { http.get(a.baseUrl.trimEnd('/') + "/manifest.json") }.getOrNull()?.takeIf { it.ok }?.body ?: return@forEach
            val cats = (m as? kotlinx.serialization.json.JsonObject)?.get("catalogs") as? kotlinx.serialization.json.JsonArray
            cats?.mapNotNull { c -> ((c as? kotlinx.serialization.json.JsonObject)?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.mapNotNull { n -> n.substringBefore(" · ", "").trim().takeIf { it.isNotEmpty() } }
                ?.groupingBy { it }?.eachCount()?.filter { it.value >= 2 }?.keys?.let { names += it }
            val res = (m as? kotlinx.serialization.json.JsonObject)?.get("resources") as? kotlinx.serialization.json.JsonArray
            if (res?.any { r -> (r as? kotlinx.serialization.json.JsonPrimitive)?.content == "stream" ||
                    ((r as? kotlinx.serialization.json.JsonObject)?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content == "stream" } == true) streams = true
        }
        check.value = AddonCheck(names.distinct(), streams, done = true)
    }

    /** "Use my own addon": a pasted manifest link, checked and added. Null: added; otherwise why not. */
    suspend fun addOwn(url: String): String? {
        val addon = stremio.fetchAddon(url) ?: return "That link didn't load an addon. Paste its manifest.json link."
        store.update { a -> a.copy(addons = a.addons.filterNot { it.baseUrl == addon.baseUrl } + addon) }
        checkAddons()
        return null
    }
}

/** The Live TV hub's checklist: what it needs, what's set up, and the way in. Shown before the hub can be turned on. */
@Composable
fun LiveHubGuide(onTurnOn: () -> Unit, onNetworks: () -> Unit, onClose: () -> Unit, vm: LiveHubSetupViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val check by vm.check.collectAsStateWithLifecycle()
    // Look through the addons already added (their manifests) each time the guide opens.
    LaunchedEffect(Unit) { vm.checkAddons() }
    val a = accounts
    val liveTv = session != null
    val servers = a != null && (a.plex != null || a.jellyfin != null || a.emby != null)
    val networks = check.networks.isNotEmpty()
    val streams = check.streams
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var pasteNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { first.requestFocus() } }
        Column(
            Modifier.width(720.dp).background(AppColors.Panel, RoundedCornerShape(16.dp)).padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Live TV hub", fontSize = 24.sp, color = AppColors.TextPrimary)
            Text(
                "Viewarr opens on your last channel, Back from any page goes back to it, and shortcuts under the live TV " +
                    "controls (OK, then Down) take you anywhere: your libraries, Continue watching, and networks like Netflix " +
                    "or BBC One. Opening one keeps the channel playing in a corner.",
                fontSize = 14.sp, color = AppColors.TextSecondary,
            )
            Text("What it uses", fontSize = 16.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp))
            Check(liveTv, "Live TV", "Needed: Dispatcharr or a provider login (Settings → Accounts & sources → Live TV).")
            Check(servers, "Plex, Jellyfin or Emby", "Optional: Continue watching, Recently added and your libraries as shortcuts.")
            Check(networks, "Network shortcuts",
                if (check.networks.isNotEmpty()) "From your addons: ${check.networks.take(6).joinToString()}${if (check.networks.size > 6) " and ${check.networks.size - 6} more" else ""}."
                else if (!check.done) "Checking your addons…"
                else "Optional: a shortcut per network (Netflix, BBC One…), each opening its own rows. They come from a catalogue addon you add: look for one like AIOMetadata (host it yourself or use an addon host). If you have AIOMetadata, Viewarr can fill it in for you.")
            Check(streams, "Streams for addon titles", "Optional: to play titles from those rows that aren't in your libraries, a stream addon you add, set up with your own accounts (look for one like AIOStreams).")
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(200.dp)) {
                    MenuItem(
                        text = if (liveTv) "Turn on" else "Turn on (needs live TV)",
                        selected = false, dim = !liveTv,
                        modifier = Modifier.focusRequester(first),
                        onClick = { if (liveTv) onTurnOn() },
                    )
                }
                Box(Modifier.width(240.dp)) {
                    MenuItem(text = "Network shortcuts", selected = false, onClick = onNetworks)
                }
                Box(Modifier.width(200.dp)) { MenuItem(text = "Use my own addon", selected = false, onClick = { pasting = !pasting }) }
                Box(Modifier.width(140.dp)) { MenuItem(text = "Not now", selected = false, onClick = onClose) }
            }
            // Paste your own addon's manifest link (or add it from your phone: Accounts & sources → Addons, QR code).
            if (pasting) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = pasted, onValueChange = { pasted = it },
                    label = { androidx.compose.material3.Text("Addon manifest link (…/manifest.json)") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(120.dp)) {
                    MenuItem(text = "Add", selected = false, onClick = {
                        scope.launch { pasteNote = vm.addOwn(pasted) ?: "Added."; if (pasteNote == "Added.") { pasted = ""; pasting = false } }
                    })
                }
            }
            pasteNote?.let { Text(it, fontSize = 13.sp, color = AppColors.Accent) }
            if (pasting) Text("Easier from a phone: Settings → Accounts & sources → Addons has a QR code to paste links on your phone.", fontSize = 12.sp, color = AppColors.TextSecondary)
        }
    }
}

@Composable
private fun Check(done: Boolean, title: String, note: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 2.dp).size(22.dp).background(if (done) Color(0xFF2EBD5A) else Color.White.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (done) Text("✓", fontSize = 13.sp, color = Color.White) }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontSize = 15.sp, color = AppColors.TextPrimary)
            Text(note, fontSize = 12.sp, color = AppColors.TextSecondary)
        }
    }
}

/**
 * Network shortcuts: the user ticks the streaming services and channels they like (Viewarr's list for their country,
 * most popular first, plus any other networks their addons have). Those their addons already have rows for become
 * shortcuts as they are; for the rest, Viewarr makes the rows in the user's own AIOMetadata (found among their addons,
 * or its address typed once), then the shortcuts. Viewarr provides no addon of its own.
 */
@Composable
fun NetworkSetupScreen(onDone: (List<String>) -> Unit, onClose: () -> Unit, vm: LiveHubSetupViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val setup = vm.setup
    val check by vm.check.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.checkAddons() }
    var region by remember { mutableStateOf(setup.lastRegion() ?: if (java.util.Locale.getDefault().country == "US") NetworkSetup.Region.US else NetworkSetup.Region.UK) }
    val have = check.networks.toSet()
    val names = remember(region, check.networks) {
        val list = setup.recommended(region).map { it.name }
        list + setup.ranked(region, check.networks.filter { it !in list })
    }
    val picked = remember(region, check.done) {
        mutableStateListOf<String>().apply {
            addAll(setup.lastPicked()?.takeIf { setup.lastRegion() == region } ?: setup.recommended(region).take(12).map { it.name })
        }
    }
    // The user's AIOMetadata: one Viewarr filled in before, else one among their addons, else typed here.
    val aioFromAddons = accounts?.addons?.firstOrNull { it.id.contains("aiometadata", true) || it.id.contains("aio-metadata", true) || it.name.contains("AIOMetadata", true) }
        ?.baseUrl?.substringBefore("/stremio/")
    var instance by remember(aioFromAddons) { mutableStateOf(setup.lastInstance() ?: aioFromAddons.orEmpty()) }
    // Networks to make rows for: ticked ones no addon has, plus those Viewarr made before that are still ticked
    // (they show as "in your addons" now, but filling the setup in again would drop them otherwise).
    val madeBefore = remember { setup.lastMade() }
    val toMake = picked.filter { it !in have && setup.known(it) != null }
    val toFill = (toMake + madeBefore.filter { it in picked }).distinct()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val logos = remember { mutableStateMapOf<String, ServiceLogo>() }
    LaunchedEffect(names) {
        val key = vm.tmdbKey() ?: return@LaunchedEffect
        names.forEach { n -> if (n !in logos) setup.known(n)?.let { net -> setup.logo(net, key)?.let { logos[n] = it } } }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) onClose() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { first.requestFocus() } }
        Column(
            Modifier.fillMaxWidth(0.9f).height(640.dp).background(AppColors.Panel, RoundedCornerShape(16.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Network shortcuts", fontSize = 24.sp, color = AppColors.TextPrimary)
            Text(
                "Tick the streaming services and channels you like. Each becomes a shortcut that opens its own rows " +
                    "(Popular, New, Top rated…), in order of popularity. Ones marked ✓ are already in your addons.",
                fontSize = 14.sp, color = AppColors.TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Popular in:", fontSize = 14.sp, color = AppColors.TextSecondary)
                NetworkSetup.Region.entries.forEachIndexed { i, r ->
                    Box(Modifier.width(120.dp)) {
                        MenuItem(text = r.label, selected = r == region, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier, onClick = { region = r })
                    }
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(names, key = { _, n -> n }) { _, n ->
                    val on = n in picked
                    Surface(
                        onClick = { if (on) picked.remove(n) else picked.add(n) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (on) AppColors.Accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f),
                            focusedContainerColor = AppColors.Accent, contentColor = AppColors.TextPrimary, focusedContentColor = AppColors.OnAccent,
                        ),
                    ) {
                        Column(Modifier.padding(10.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(64.dp).clip(CircleShape).background(if (logos[n]?.square == false) Color.White else Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                                logos[n]?.let { l ->
                                    AsyncImage(l.url, n, contentScale = if (l.square) ContentScale.Crop else ContentScale.Fit,
                                        modifier = if (l.square) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(8.dp))
                                } ?: Text(n.take(2), fontSize = 18.sp)
                            }
                            Text(n, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                            Text((if (on) "Ticked" else "") + (if (n in have) "  ✓" else ""), fontSize = 11.sp)
                        }
                    }
                }
            }
            // Rows to make for ticked networks the addons don't have: in the user's own AIOMetadata.
            if (toMake.isNotEmpty()) {
                Text(
                    "${toMake.size} of these aren't in your addons yet (${toMake.take(4).joinToString()}${if (toMake.size > 4) "…" else ""}). " +
                        "Viewarr can make their rows in your AIOMetadata, using your TMDB key. No AIOMetadata? Look for it: you can host it yourself or use an addon host. Or leave these unticked.",
                    fontSize = 12.sp, color = AppColors.TextSecondary,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = instance, onValueChange = { instance = it },
                    label = { androidx.compose.material3.Text("Your AIOMetadata address (e.g. http://192.168.0.10:3232)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            message?.let { Text(it, fontSize = 13.sp, color = AppColors.Accent) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(280.dp)) {
                    MenuItem(
                        text = if (busy) "Setting up…" else "Done (${picked.size})",
                        selected = false, dim = picked.isEmpty() || busy,
                        onClick = {
                            if (picked.isEmpty() || busy) return@MenuItem
                            val chosen = names.filter { it in picked }
                            if (toMake.isEmpty()) { setup.remember(region, chosen); onDone(chosen); return@MenuItem }
                            busy = true; message = null
                            scope.launch {
                                val nets = toFill.mapNotNull { n -> setup.recommended(region).firstOrNull { it.name == n } ?: setup.known(n) }
                                when (val r = setup.apply(instance, region, nets)) {
                                    is NetworkSetup.Result.Done -> { busy = false; setup.remember(region, chosen); onDone(chosen) }
                                    is NetworkSetup.Result.Failed -> { busy = false; message = r.why }
                                }
                            }
                        },
                    )
                }
                Box(Modifier.width(160.dp)) { MenuItem(text = "Cancel", selected = false, onClick = { if (!busy) onClose() }) }
            }
        }
    }
}
