package io.github.petebutty.viewarr.ui.settings

import io.github.petebutty.viewarr.ui.common.instantFocusScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
                        modifier = androidx.compose.ui.Modifier.instantFocusScale(1.04f),
                        scale = io.github.petebutty.viewarr.ui.common.focusScale(1.04f),
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
