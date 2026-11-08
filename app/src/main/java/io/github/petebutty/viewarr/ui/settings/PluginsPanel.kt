package io.github.petebutty.viewarr.ui.settings

import io.github.petebutty.viewarr.ui.theme.appBackground

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.dvr.InstalledPlugin
import io.github.petebutty.viewarr.data.dvr.PluginField
import io.github.petebutty.viewarr.data.dvr.PluginsRepository
import io.github.petebutty.viewarr.data.dvr.StorePlugin
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import javax.inject.Inject

@HiltViewModel
class PluginsViewModel @Inject constructor(private val repo: PluginsRepository) : ViewModel() {
    data class Ui(
        val loading: Boolean = true,
        val installed: List<InstalledPlugin> = emptyList(),
        val store: List<StorePlugin> = emptyList(),
        val problem: String? = null,
        val message: String? = null,
        val busy: Boolean = false,
        /** Counts reloads, so the screen can put focus back after one. */
        val reloads: Int = 0,
    )
    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()

    fun load(message: String? = null) {
        val n = _ui.value.reloads + 1
        viewModelScope.launch {
            val r = runCatching { repo.installed() to repo.store() }
            _ui.value = r.fold(
                { (inst, store) ->
                    if (inst == null) Ui(loading = false, problem = "Plugins live on a Dispatcharr server; sign in to one first.")
                    else Ui(loading = false, installed = inst, store = store, message = message, reloads = n)
                },
                { e ->
                    Ui(loading = false, problem = if (e is SecurityException) "Dispatcharr only lets admin users manage plugins." else "Couldn't reach Dispatcharr.")
                },
            )
        }
    }

    private fun act(work: suspend () -> io.github.petebutty.viewarr.data.dvr.PluginResult, reload: Boolean = true) {
        // One thing at a time: a second OK while the server is still answering would act on stale values.
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, message = "Working…")
        viewModelScope.launch {
            val r = work()
            if (reload) load(r.message) else _ui.value = _ui.value.copy(busy = false, message = r.message)
        }
    }

    fun setEnabled(p: InstalledPlugin, on: Boolean) = act({ repo.setEnabled(p.key, on) })
    fun run(p: InstalledPlugin, actionId: String) = act({ repo.run(p.key, actionId) }, reload = false)
    fun setting(p: InstalledPlugin, f: PluginField, value: kotlinx.serialization.json.JsonElement) = act({ repo.saveSetting(p, f.id, value) })
    fun install(p: StorePlugin) = act({ repo.install(p) })
}

/**
 * Settings → Dispatcharr plugins. Plugins run on the server; here you switch them on and off, press their action
 * buttons, change simple settings, and install or update them from the server's plugin store.
 */
@Composable
fun PluginsSettingsScreen(onBack: () -> Unit, vm: PluginsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    // Selected: an installed plugin's key, or STORE.
    var selected by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val memory = remember { FocusMemory() }
    BackHandler(onBack = onBack)
    LaunchedEffect(ui.reloads) {
        if (ui.reloads <= 1) return@LaunchedEffect
        awaitFrame(); awaitFrame()
        val fallback = selected?.let { if (it == STORE) "side:store" else "enabled:$it" }
        val ok = listOfNotNull(memory.last, fallback).any { k -> memory.rows[k]?.let { runCatching { it.requestFocus() }.isSuccess } == true }
        if (!ok) runCatching { firstFocus.requestFocus() }
    }
    LaunchedEffect(ui.loading) { if (!ui.loading) { awaitFrame(); runCatching { firstFocus.requestFocus() } } }
    androidx.compose.runtime.CompositionLocalProvider(LocalFocusMemory provides memory) {
    Box(Modifier.fillMaxSize().appBackground().trapFocus()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            Column(Modifier.width(300.dp).fillMaxHeight()) {
                Text("Settings", fontSize = 14.sp, color = AppColors.Accent)
                Text("Dispatcharr plugins", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 20.dp))
                if (ui.loading) Text("Loading…", fontSize = 14.sp, color = AppColors.TextSecondary)
                ui.problem?.let { Text(it, fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.focusRequester(firstFocus).focusable()) }
                if (!ui.loading && ui.problem == null) {
                    Text("Installed", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        items(ui.installed, key = { it.key }) { p ->
                            MenuItem(
                                text = p.name,
                                selected = selected == p.key,
                                trailing = when { p.updateAvailable -> "Update"; p.enabled -> "On"; else -> "Off" },
                                onClick = { selected = p.key },
                                modifier = (if (p == ui.installed.first()) Modifier.focusRequester(firstFocus) else Modifier)
                                    .focusRequester(memory.of("side:" + p.key))
                                    .onFocusChanged { if (it.isFocused) { selected = p.key; memory.last = "side:" + p.key } },
                            )
                        }
                        item(key = "store") {
                            Text("More", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
                            MenuItem(
                                text = "Get plugins",
                                selected = selected == STORE,
                                trailing = ui.store.count { it.status == "not_installed" }.toString(),
                                onClick = { selected = STORE },
                                modifier = (if (ui.installed.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier)
                                    .focusRequester(memory.of("side:store"))
                                    .onFocusChanged { if (it.isFocused) { selected = STORE; memory.last = "side:store" } },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ui.message?.let { Text(it, fontSize = 14.sp, color = AppColors.Accent, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 26.dp, bottom = 6.dp, start = 4.dp)) }
                    ?: Spacer(Modifier.padding(top = 26.dp))
                val plugin = ui.installed.firstOrNull { it.key == selected }
                when {
                    plugin != null -> PluginDetail(plugin, ui.store.firstOrNull { it.slug == plugin.key || it.slug.replace('-', '_') == plugin.key }, vm)
                    selected == STORE -> StoreList(ui.store, vm)
                }
            }
        }
    }
    }
}

private const val STORE = "\u0000store"

/** Which row had focus, so it gets it back after the list reloads (rows are rebuilt, or vanish, e.g. Update). */
private class FocusMemory {
    val rows = HashMap<String, FocusRequester>()
    var last: String? = null
    fun of(key: String) = rows.getOrPut(key) { FocusRequester() }
}

private val LocalFocusMemory = androidx.compose.runtime.staticCompositionLocalOf { FocusMemory() }

@Composable
private fun PluginDetail(p: InstalledPlugin, storeEntry: StorePlugin?, vm: PluginsViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "head") {
            Column(Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                Text(p.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text(listOf("v${p.version}", p.author.takeIf { it.isNotEmpty() }?.let { "by $it" }).filterNotNull().joinToString("  ·  "), fontSize = 13.sp, color = AppColors.TextSecondary)
                if (p.description.isNotEmpty()) Text(p.description, fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item(key = "enabled") { Row2("enabled:${p.key}", "Enabled", "Runs on your Dispatcharr server while on.", toggle = p.enabled) { vm.setEnabled(p, !p.enabled) } }
        if (p.updateAvailable && storeEntry != null) item(key = "update") {
            ConfirmRow("update:${p.key}", "Update to v${storeEntry.version}", "From ${storeEntry.repoName}.", "Update", "Press OK again to update") { vm.install(storeEntry) }
        }
        if (p.actions.isNotEmpty()) item(key = "ah") { Header("Actions" + if (!p.enabled) " (turn the plugin on first)" else "") }
        items(p.actions, key = { "a:" + it.id }) { a ->
            if (a.confirm != null) ConfirmRow("a:${p.key}:${a.id}", a.label, a.description, a.button, "OK again: ${a.confirm}") { vm.run(p, a.id) }
            else Row2("a:${p.key}:${a.id}", a.label, a.description, trailing = a.button) { vm.run(p, a.id) }
        }
        val fields = p.fields
        if (fields.isNotEmpty()) item(key = "sh") { Header("Settings") }
        items(fields, key = { "f:" + it.id }) { f -> FieldRow(p, f, vm) }
    }
}

@Composable
private fun FieldRow(p: InstalledPlugin, f: PluginField, vm: PluginsViewModel) {
    val current = p.settings[f.id] ?: f.default
    when (f.type) {
        "info" -> Column(Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)) {
            Text(f.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary)
            if (f.description.isNotEmpty()) Text(f.description, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        "boolean" -> {
            val on = (current as? JsonPrimitive)?.booleanOrNull == true
            Row2("f:${p.key}:${f.id}", f.label, f.description, toggle = on) { vm.setting(p, f, JsonPrimitive(!on)) }
        }
        "select" -> {
            val value = (current as? JsonPrimitive)?.content
            val i = f.options.indexOfFirst { it.first == value }
            Row2("f:${p.key}:${f.id}", f.label, f.description, trailing = f.options.getOrNull(i)?.second ?: value ?: "—") {
                // OK steps through the choices.
                f.options.getOrNull((i + 1) % f.options.size.coerceAtLeast(1))?.let { vm.setting(p, f, JsonPrimitive(it.first)) }
            }
        }
        else -> {
            val shown = (current as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.lines()?.first() ?: "—"
            Row2("f:${p.key}:${f.id}", f.label, f.description.ifEmpty { "Change this on Dispatcharr's web page." }, trailing = shown, enabled = false) {}
        }
    }
}

@Composable
private fun StoreList(store: List<StorePlugin>, vm: PluginsViewModel) {
    val sorted = store.sortedWith(compareBy({ it.status == "installed" }, { -(it.downloads ?: 0) }))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "note") {
            Text("Plugins run code on your Dispatcharr server: install ones you trust. ✓ marks a signed, official repository.",
                fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        items(sorted, key = { it.repoId.toString() + it.slug }) { s ->
            val meta = listOfNotNull(
                "v${s.version}",
                s.author.takeIf { it.isNotEmpty() },
                s.downloads?.let { "$it downloads" },
                s.repoName + if (s.official && s.verified) " ✓" else "",
            ).joinToString("  ·  ")
            when (s.status) {
                "installed" -> Row2("s:" + s.slug, s.name, meta + "\n" + s.description, trailing = "Installed", enabled = false) {}
                "update_available" -> ConfirmRow("s:" + s.slug, s.name, meta + "\n" + s.description, "Update", "Press OK again to update") { vm.install(s) }
                else -> ConfirmRow("s:" + s.slug, s.name, meta + "\n" + s.description, "Install", "Press OK again to install") { vm.install(s) }
            }
        }
    }
}

@Composable
private fun Header(text: String) =
    Text(text, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 4.dp, top = 10.dp))

/** OK once arms it (the label asks again), OK again does it. */
@Composable
private fun ConfirmRow(key: String, title: String, description: String, button: String, armed: String, onConfirm: () -> Unit) {
    var arm by remember { mutableStateOf(false) }
    Row2(key, title, description, trailing = if (arm) armed else button, onFocusLost = { arm = false }) {
        if (arm) { arm = false; onConfirm() } else arm = true
    }
}

@Composable
private fun Row2(
    key: String,
    title: String,
    description: String,
    trailing: String? = null,
    toggle: Boolean? = null,
    enabled: Boolean = true,
    onFocusLost: () -> Unit = {},
    onClick: () -> Unit,
) {
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.fillMaxWidth().focusRequester(LocalFocusMemory.current.of(key)).let { m ->
            val mem = LocalFocusMemory.current
            m.onFocusChanged { if (it.isFocused) mem.last = key else onFocusLost() }
        },
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Panel,
            contentColor = if (enabled) AppColors.TextPrimary else AppColors.TextSecondary,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (description.isNotEmpty()) Text(description, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(20.dp))
            if (toggle != null) Box(
                Modifier.size(width = 48.dp, height = 26.dp)
                    .background(if (toggle) Color(0xFF2EBD5A) else Color(0xFF6B6F7A), RoundedCornerShape(13.dp))
                    .padding(3.dp),
                contentAlignment = if (toggle) Alignment.CenterEnd else Alignment.CenterStart,
            ) { Box(Modifier.size(20.dp).background(Color.White, CircleShape)) }
            else trailing?.let { Text(it, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(240.dp)) }
        }
    }
}
