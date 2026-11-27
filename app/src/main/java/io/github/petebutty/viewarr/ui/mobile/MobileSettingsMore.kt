package io.github.petebutty.viewarr.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.petebutty.viewarr.data.dvr.InstalledPlugin
import io.github.petebutty.viewarr.data.dvr.PluginField
import io.github.petebutty.viewarr.data.homeassistant.HaClient
import io.github.petebutty.viewarr.data.homeassistant.HaStore
import io.github.petebutty.viewarr.data.remote.services.long
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.sports.Sport
import io.github.petebutty.viewarr.data.sports.SportFollow
import io.github.petebutty.viewarr.data.sync.SettingsSync
import io.github.petebutty.viewarr.data.sync.SyncMode
import io.github.petebutty.viewarr.data.sync.SyncOffer
import io.github.petebutty.viewarr.ui.settings.MAX_TEXT
import io.github.petebutty.viewarr.ui.settings.PluginsViewModel
import io.github.petebutty.viewarr.ui.settings.SettingItem
import io.github.petebutty.viewarr.ui.settings.SettingsMap
import io.github.petebutty.viewarr.ui.settings.SportsViewModel
import io.github.petebutty.viewarr.ui.settings.WatchlistViewModel
import io.github.petebutty.viewarr.ui.settings.settingsPool
import io.github.petebutty.viewarr.ui.settings.textProblem
import io.github.petebutty.viewarr.ui.settings.timeWindowHint
import io.github.petebutty.viewarr.ui.sync.SyncViewModel
import io.github.petebutty.viewarr.ui.sync.ago
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/* The rest of Settings for a phone or tablet: Backup & sync, Home Assistant, Dispatcharr plugins, Sports, Search. */

// ---- Backup & sync ----

@Composable
internal fun MobileBackup(onBack: () -> Unit) {
    val vm: SyncViewModel = hiltViewModel()
    val st by vm.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    if (restoring) { MobileRestore(vm, onBack = { restoring = false }); return }
    val on = st.mode == SyncMode.On
    PhonePage("Backup & sync", onBack, subtitle = "Keeps your setup on your Dispatcharr server, so another TV or phone (or this one after a reinstall) can restore it. The last few versions are kept, so you can go back.") {
        item(key = "main") {
            Spacer(Modifier.height(8.dp))
            CardRows(listOfNotNull<@Composable () -> Unit>(
                @Composable { PhoneSwitch("Save settings to Dispatcharr", "Your changes are saved there by themselves.", on = on) { vm.setEnabled(it) } },
                if (on) (@Composable { PhoneSwitch("Include sign-ins", "Plex, Jellyfin, Seerr and the rest, encrypted.", on = st.signIns) { vm.setSignIns(it) } }) else null,
                if (on) (@Composable {
                    PhoneRow(if (saving) "Saving…" else "Save a copy now") {
                        if (!saving) { saving = true; scope.launch { note = vm.saveCopyNow(); saving = false } }
                    }
                }) else null,
                @Composable { PhoneRow("Restore from Dispatcharr", "Choose a saved version.", opens = true) { restoring = true } },
            ))
            (note ?: st.message ?: if (on && st.lastSaved > 0) "Saved ${ago(st.lastSaved)}" else null)?.let { PhoneNote(it) }
        }
    }
}

private class Picked(val copy: JsonObject, val offer: SyncOffer, val version: Boolean)

@Composable
private fun MobileRestore(vm: SyncViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<SettingsSync.RestoreChoices?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.choices().fold({ choices = it }, { error = it.message }) }
    PhonePage("Restore from Dispatcharr", onBack, subtitle = "Pick a version. Your setup now is kept as a version first, so you can go back.") {
        item(key = "body") {
            Spacer(Modifier.height(8.dp))
            val c = choices
            when {
                result != null -> { PhoneNote(result.orEmpty(), accent = true); ButtonColumn { PhoneButton("Done", Modifier.fillMaxWidth(), onTap = onBack) } }
                error != null -> PhoneNote(error.orEmpty(), error = true)
                c == null -> PhoneNote("Loading…")
                else -> {
                    val rows = mutableListOf<@Composable () -> Unit>()
                    c.latest?.let { copy ->
                        rows += @Composable {
                            PhoneRow("Latest", "${copy.str("device") ?: "another TV"} · ${ago(copy.long("savedAt") ?: 0)}", opens = true) {
                                scope.launch { picked = Picked(copy, vm.offerFor(copy), version = false) }
                            }
                        }
                    }
                    c.versions.forEach { v ->
                        rows += @Composable {
                            PhoneRow(v.name ?: v.device, java.time.Instant.ofEpochMilli(v.savedAt).atZone(java.time.ZoneId.systemDefault()).format(Dates.dayTime), opens = true) {
                                scope.launch {
                                    val copy = vm.copyOf(v)
                                    if (copy == null) error = "That version couldn't be read." else picked = Picked(copy, vm.offerFor(copy), version = true)
                                }
                            }
                        }
                    }
                    CardRows(rows)
                    if (rows.isEmpty()) PhoneNote("Nothing saved on Dispatcharr yet.")
                    else if (c.versions.isEmpty()) PhoneNote(if (c.historyUnread) "Earlier versions couldn't be read just now." else "Earlier versions appear here as your setup changes.")
                }
            }
        }
    }
    picked?.let { p ->
        val o = p.offer
        Chooser(
            "Restore this version?",
            "Saved from ${o.device} ${ago(o.savedAt)}: " + (if (o.hasSignIns) "your services (Plex, Jellyfin, Seerr and the rest), " else "") +
                "appearance, home rows, playback and remote settings. Settings that depend on the device stay as they are.",
            listOf(if (busy) "Restoring…" else "Restore", "Cancel"), null,
            onPick = { i ->
                if (i == 1) picked = null
                else if (!busy) { busy = true; scope.launch { result = vm.restoreFrom(p.copy, p.version); busy = false; picked = null } }
            },
            onDismiss = { if (!busy) picked = null },
        )
    }
}

// ---- Home Assistant ----

@Composable
internal fun MobileHomeAssistant(onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { HaStore.start(context) }
    val cfg by HaStore.config.collectAsState()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var forget by remember { mutableStateOf(false) }
    fun test(with: io.github.petebutty.viewarr.data.homeassistant.HaConfig) {
        if (testing) return
        testing = true; status = "Testing…"
        scope.launch { status = HaClient.test(with).fold({ it }, { it.message ?: "Couldn't connect." }); testing = false }
    }
    PhonePage("Home Assistant", onBack, subtitle = "Tell Home Assistant what's playing (for lights and scenes), and show pop-ups it sends, like a doorbell camera. Nothing happens until it's set up.") {
        item(key = "conn") {
            GroupTitle("Connection")
            CardRows(listOf<@Composable () -> Unit>(
                @Composable { PhoneRow("Address", trailing = cfg.address.ifBlank { "Not set" }) { editing = "address" } },
                @Composable { PhoneRow("Access token", "Home Assistant: your profile → Security → Long-lived access tokens.", trailing = if (cfg.token.isBlank()) "Not set" else "•••• " + cfg.token.takeLast(4)) { editing = "token" } },
                @Composable { PhoneRow("Test connection", trailing = if (testing) "…" else null, dim = !cfg.ready) { if (cfg.ready) test(cfg) } },
            ))
            status?.let { PhoneNote(it) }
            GroupTitle("What it does")
            CardRows(listOf<@Composable () -> Unit>(
                @Composable { PhoneSwitch("Send what's playing", "Event viewarr_playback: what starts, pauses and stops.", on = cfg.sendPlaying, enabled = cfg.ready) { HaStore.setSendPlaying(it) } },
                @Composable { PhoneSwitch("Pop-ups from Home Assistant", "Event viewarr_notify: a card in the corner, over films and live TV too.", on = cfg.popups, enabled = cfg.ready) { HaStore.setPopups(it) } },
            ))
            PhoneNote("Events name this device \"${HaStore.tvName(context)}\". The token stays on this device: it isn't in backups or reports.")
            if (cfg.address.isNotBlank() || cfg.token.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                PhoneCard { PhoneRow("Forget Home Assistant", danger = true) { forget = true } }
            }
        }
    }
    when (editing) {
        "address" -> TextSheet("Address", "With its port, e.g. http://192.168.1.10:8123", cfg.address, keyboard = KeyboardType.Uri,
            onDone = { a -> HaStore.setAddress(a); editing = null; val c = cfg.copy(address = HaStore.normalise(a)); if (c.ready) test(c) else status = null },
            onDismiss = { editing = null })
        "token" -> TextSheet("Access token", "Make one in Home Assistant: your profile → Security → Long-lived access tokens.", "", label = "Long-lived access token",
            onDone = { t -> HaStore.setToken(t); editing = null; val c = cfg.copy(token = t.trim()); if (c.ready) test(c) else status = null },
            onDismiss = { editing = null })
    }
    if (forget) Chooser("Forget Home Assistant?", null, listOf("Forget", "Cancel"), null,
        onPick = { i -> forget = false; if (i == 0) { HaStore.clear(); status = "Home Assistant forgotten on this device." } }, onDismiss = { forget = false })
}

// ---- Dispatcharr plugins ----

@Composable
internal fun MobilePlugins(onBack: () -> Unit) {
    val vm: PluginsViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    var openKey by remember { mutableStateOf<String?>(null) }
    var store by remember { mutableStateOf(false) }
    val open = ui.installed.firstOrNull { it.key == openKey }
    if (open != null) { PluginPage(open, ui.store.firstOrNull { it.slug == open.key || it.slug.replace('-', '_') == open.key }, ui.message, vm, onBack = { openKey = null }); return }
    if (store) { PluginStore(vm, onBack = { store = false }); return }
    PhonePage("Dispatcharr plugins", onBack, subtitle = "Plugins run on your Dispatcharr server. Switch them on and off, run their actions and change their settings.") {
        item(key = "list") {
            Spacer(Modifier.height(8.dp))
            when {
                ui.loading -> PhoneNote("Loading…")
                ui.problem != null -> PhoneNote(ui.problem.orEmpty(), error = true)
                else -> {
                    CardRows(ui.installed.map { p ->
                        @Composable {
                            PhoneRow(p.name, "v${p.version}" + (if (p.updateAvailable) " · update available" else "") + (p.description.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                trailing = if (p.enabled) "On" else "Off", dim = !p.enabled, opens = true) { openKey = p.key }
                        }
                    })
                    if (ui.installed.isEmpty()) PhoneNote("No plugins installed.")
                    Spacer(Modifier.height(14.dp))
                    PhoneCard { PhoneRow("Plugin store", "Install or update plugins from your server's plugin repositories.", opens = true) { store = true } }
                }
            }
            ui.message?.let { PhoneNote(it, accent = true) }
        }
    }
}

@Composable
private fun PluginPage(p: InstalledPlugin, storeEntry: io.github.petebutty.viewarr.data.dvr.StorePlugin?, message: String?, vm: PluginsViewModel, onBack: () -> Unit) {
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var choosing by remember { mutableStateOf<PluginField?>(null) }
    var typing by remember { mutableStateOf<PluginField?>(null) }
    var refused by remember { mutableStateOf<String?>(null) }
    fun ask(q: String, go: () -> Unit) { confirm = q to go }
    PhonePage(p.name, onBack, subtitle = listOf("v${p.version}", p.author.takeIf { it.isNotBlank() }?.let { "by $it" }, p.description.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")) {
        item(key = "top") {
            Spacer(Modifier.height(8.dp))
            CardRows(listOfNotNull<@Composable () -> Unit>(
                @Composable { PhoneSwitch("Plugin on", on = p.enabled) { vm.setEnabled(p, it) } },
                if (p.updateAvailable && storeEntry != null) (@Composable {
                    PhoneRow("Update to v${storeEntry.version}", "From ${storeEntry.repoName}.", trailing = "Update") { ask("Update ${p.name} to v${storeEntry.version}?") { vm.install(storeEntry) } }
                }) else null,
            ))
            message?.let { PhoneNote(it, accent = true) }
        }
        if (p.actions.isNotEmpty()) item(key = "actions") {
            GroupTitle("Actions", if (!p.enabled) "Turn the plugin on first." else null)
            CardRows(p.actions.map { a ->
                @Composable {
                    PhoneRow(a.label, a.description, trailing = a.button) {
                        val c = a.confirm
                        if (c != null) ask(c) { vm.run(p, a.id) } else vm.run(p, a.id)
                    }
                }
            })
        }
        if (p.fields.isNotEmpty()) item(key = "fields") {
            GroupTitle("Settings")
            CardRows(p.fields.map { f ->
                @Composable {
                    val current = p.settings[f.id] ?: f.default
                    when (f.type) {
                        "info" -> PhoneRow(f.label, f.description)
                        "boolean" -> PhoneSwitch(f.label, f.description, on = (current as? JsonPrimitive)?.booleanOrNull == true) { vm.setting(p, f, JsonPrimitive(it)) }
                        "select" -> {
                            val value = (current as? JsonPrimitive)?.content
                            PhoneRow(f.label, f.description, trailing = f.options.firstOrNull { it.first == value }?.second ?: value ?: "—") { choosing = f }
                        }
                        else -> {
                            val text = (current as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
                            val editable = !f.internal && !f.id.contains("undo", ignoreCase = true) && text.length <= MAX_TEXT && ('\n' !in text.trim() || p.key == "viewarr")
                            val shown = text.takeIf { it.isNotBlank() }?.lines()?.filter { it.isNotBlank() }?.joinToString(", ") ?: "—"
                            PhoneRow(f.label, f.description.ifEmpty { if (editable) "" else "Change this on Dispatcharr's web page." }, trailing = shown, dim = !editable) {
                                if (editable) { refused = null; typing = f }
                            }
                        }
                    }
                }
            })
        }
    }
    choosing?.let { f ->
        val value = ((p.settings[f.id] ?: f.default) as? JsonPrimitive)?.content
        Chooser(f.label, f.description, f.options.map { it.second }, f.options.indexOfFirst { it.first == value }.takeIf { it >= 0 },
            onPick = { i -> choosing = null; vm.setting(p, f, JsonPrimitive(f.options[i].first)) }, onDismiss = { choosing = null })
    }
    typing?.let { f ->
        val text = ((p.settings[f.id] ?: f.default) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
        TextSheet(f.label, refused ?: timeWindowHint(f) ?: "Empty is allowed. Saved on your Dispatcharr server for everyone.",
            if ('\n' in text.trim()) text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") else text,
            keyboard = if (f.type == "number") KeyboardType.Number else KeyboardType.Text,
            onDone = { typed ->
                val problem = textProblem(f, typed)
                refused = problem
                if (problem == null) {
                    typing = null
                    vm.setting(p, f, if (f.type == "number") typed.toDoubleOrNull()?.let { d -> if (d % 1.0 == 0.0) JsonPrimitive(d.toLong()) else JsonPrimitive(d) } ?: JsonPrimitive(typed) else JsonPrimitive(typed))
                }
            },
            onDismiss = { typing = null })
    }
    confirm?.let { (q, go) -> Chooser(q, null, listOf("Yes", "Cancel"), null, onPick = { i -> confirm = null; if (i == 0) go() }, onDismiss = { confirm = null }) }
}

@Composable
private fun PluginStore(vm: PluginsViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<io.github.petebutty.viewarr.data.dvr.StorePlugin?>(null) }
    val sorted = ui.store.sortedWith(compareBy({ it.status == "installed" }, { -(it.downloads ?: 0) }))
    PhonePage("Plugin store", onBack, subtitle = "Plugins run code on your Dispatcharr server: install ones you trust. ✓ marks a signed, official repository.") {
        item(key = "list") {
            Spacer(Modifier.height(8.dp))
            CardRows(sorted.map { s ->
                @Composable {
                    val installed = s.status == "installed"
                    PhoneRow((if (s.official || s.verified) "✓ " else "") + s.name, listOfNotNull("v${s.version}", s.author.takeIf { it.isNotBlank() }, s.description.takeIf { it.isNotBlank() }).joinToString(" · "),
                        trailing = if (installed) "Installed" else "Install", dim = installed) { if (!installed) confirm = s }
                }
            })
            ui.message?.let { PhoneNote(it, accent = true) }
        }
    }
    confirm?.let { s -> Chooser("Install ${s.name}?", "From ${s.repoName}. It runs on your Dispatcharr server.", listOf("Install", "Cancel"), null,
        onPick = { i -> confirm = null; if (i == 0) vm.install(s) }, onDismiss = { confirm = null }) }
}

// ---- Sports ----

@Composable
internal fun MobileSports(onBack: () -> Unit) {
    val vm: SportsViewModel = hiltViewModel()
    val follows by vm.follows.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<SportFollow?>(null) }
    var leagueFor by remember { mutableStateOf<SportFollow?>(null) }
    val listing = results.isNotEmpty() || searching
    PhonePage("Sports", onBack = { if (listing) { if (vm.back() == null) { vm.clear(); query = "" } } else onBack() },
        subtitle = "Your teams and leagues: their games, and the channel showing each.") {
        item(key = "search") {
            PhoneField(query, { query = it; if (it.isBlank()) vm.clear() else vm.search(it) }, "Search teams and leagues", last = true)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sport.entries.filter { it.words.isNotEmpty() }.forEach { s ->
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(MobileColors.Card).clickable { query = ""; vm.browse(s) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("${s.emoji}  ${s.label}", color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        }
        if (listing) item(key = "results") {
            GroupTitle(heading)
            if (searching) PhoneNote("Searching…")
            CardRows(results.map { r ->
                @Composable {
                    val followed = follows.any { it.shortcutId == r.shortcutId }
                    PhoneRow(r.name, r.detail ?: if (r.kind == SportFollow.Kind.League) "League" else null, trailing = if (followed) "Following" else "Add") {
                        when {
                            followed -> Unit
                            r.kind == SportFollow.Kind.League && vm.hasTeams(r.league) -> leagueFor = r
                            else -> vm.follow(r)
                        }
                    }
                }
            })
            if (!searching && results.isEmpty()) PhoneNote("Nothing found.")
        } else item(key = "follows") {
            GroupTitle("Following", if (follows.isEmpty()) "Nothing yet: search above, or pick a sport." else "Tap one to move or remove it.")
            CardRows(follows.map { f -> @Composable { PhoneRow(f.name, if (f.kind == SportFollow.Kind.League) "League" else vm.league(f.league).name) { picked = f } } })
        }
    }
    leagueFor?.let { l ->
        Chooser(l.name, null, listOf("Follow the whole league", "Choose a team"), null,
            onPick = { i -> leagueFor = null; if (i == 0) vm.follow(l) else vm.teams(l.league) }, onDismiss = { leagueFor = null })
    }
    picked?.let { f ->
        val opts = listOf("Move up", "Move down", "Remove")
        Chooser(f.name, null, opts, null, onPick = { i ->
            picked = null
            when (i) { 0 -> vm.move(f, -1); 1 -> vm.move(f, 1); else -> vm.unfollow(f) }
        }, onDismiss = { picked = null })
    }
}

// ---- Search settings ----

/** Search settings: every setting whose name or description has the words typed, changed right here. */
@Composable
internal fun MobileSearchSettings(onBack: () -> Unit) {
    val pool = settingsPool()
    var query by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf<SettingItem?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val pages = listOf(SettingsMap.APPEARANCE, SettingsMap.LIVE, SettingsMap.PLAYBACK, SettingsMap.NAVIGATION, SettingsMap.SYSTEM, SettingsMap.ADVANCED)
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val hits = if (words.isEmpty()) emptyList() else pages.flatMap { id ->
        val title = SettingsMap.page(id).title
        SettingsMap.categories(id, pool).flatMap { c ->
            c.items.filter { s -> val t = (s.title + " " + s.description + " " + c.name).lowercase(); words.all { it in t } }.map { "$title › ${c.name}" to it }
        }
    }.distinctBy { it.second.title + it.first }.take(60)
    PhonePage("Search settings", onBack) {
        item(key = "box") { PhoneField(query, { query = it }, "Type a word: frame rate, subtitles…", last = true) }
        item(key = "hits") {
            if (words.isNotEmpty() && hits.isEmpty()) PhoneNote("No settings match.")
            hits.groupBy { it.first }.forEach { (where, list) ->
                GroupTitle(where)
                CardRows(list.map { (_, s) ->
                    @Composable {
                        SettingRowMobile(s) {
                            when {
                                s.locked != null -> note = s.locked
                                s.action != null -> s.action.invoke()
                                s.isToggle -> s.onSelect(s.choices.first { it != s.current })
                                s.choices.size > 1 -> choosing = s
                            }
                        }
                    }
                })
            }
        }
    }
    choosing?.let { s -> Chooser(s.title, s.description, s.choices.map { it.label }, s.choices.indexOf(s.current), onPick = { i -> s.onSelect(s.choices[i]); choosing = null }, onDismiss = { choosing = null }) }
    note?.let { n -> Chooser("Can't be changed here", n, listOf("OK"), null, onPick = { note = null }, onDismiss = { note = null }) }
}

// ---- Watchlist ----

/** Which watchlist Add / Remove and "Your watchlist" use, as a row (only with more than one to choose from). */
@Composable
internal fun WatchlistRow(divider: Boolean = false) {
    val vm: WatchlistViewModel = hiltViewModel()
    val a by vm.accounts.collectAsStateWithLifecycle()
    val choices = vm.choices(a)
    if (choices.size < 2) return
    var open by remember { mutableStateOf(false) }
    fun label(id: String) = when (id) { "trakt" -> "Trakt"; "plex" -> "Plex"; "jellyfin" -> "Jellyfin favourites"; "emby" -> "Emby favourites"; else -> id }
    if (divider) RowLine(indent = true)
    PhoneRow("Watchlist", "Which watchlist Add / Remove and \"Your watchlist\" use.", icon = io.github.petebutty.viewarr.ui.common.AppIcons.Bookmark, trailing = vm.current(a)?.let(::label), opens = true) { open = true }
    if (open) Chooser("Watchlist", null, choices.map(::label), choices.indexOf(vm.current(a)).takeIf { it >= 0 },
        onPick = { i -> open = false; vm.set(choices[i]) }, onDismiss = { open = false })
}
