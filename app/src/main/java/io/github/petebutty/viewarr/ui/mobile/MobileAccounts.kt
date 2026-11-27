package io.github.petebutty.viewarr.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.unit.sp
import io.github.petebutty.viewarr.ui.common.drawViewarrMark
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.settings.AddonsSettingsViewModel
import io.github.petebutty.viewarr.ui.settings.LiveSourcesViewModel
import io.github.petebutty.viewarr.ui.settings.PlexServersViewModel
import io.github.petebutty.viewarr.ui.setup.SetupItem
import io.github.petebutty.viewarr.ui.setup.SetupMode
import io.github.petebutty.viewarr.ui.setup.SetupStep
import io.github.petebutty.viewarr.ui.setup.SetupUiState
import io.github.petebutty.viewarr.ui.setup.SetupViewModel
import io.github.petebutty.viewarr.ui.setup.SetupViewModel.Companion.ADDON_URLS
import io.github.petebutty.viewarr.ui.setup.SetupViewModel.Companion.API_KEY
import io.github.petebutty.viewarr.ui.setup.SetupViewModel.Companion.MDBLIST_KEY
import io.github.petebutty.viewarr.ui.setup.SetupViewModel.Companion.PASSWORD
import io.github.petebutty.viewarr.ui.setup.SetupViewModel.Companion.SERVER
import io.github.petebutty.viewarr.ui.setup.SetupViewModel.Companion.TMDB_KEY
import io.github.petebutty.viewarr.ui.setup.SetupViewModel.Companion.USER
import io.github.petebutty.viewarr.ui.setup.available
import io.github.petebutty.viewarr.ui.setup.bodyFor
import io.github.petebutty.viewarr.ui.setup.statusText
import io.github.petebutty.viewarr.ui.setup.titleFor

/**
 * Settings → Accounts & sources on a phone or tablet: a card per service with how it stands; a tap on one that isn't
 * set up starts it, on one that is opens its page (change it, its servers / addons / sources, disconnect). The steps
 * are the TV app's (SetupViewModel), drawn as phone forms: big text boxes, big buttons, and links that open on the
 * phone itself instead of a code to scan.
 */
@Composable
internal fun MobileAccounts(onClose: () -> Unit, firstRun: Boolean = false) {
    val vm: SetupViewModel = hiltViewModel(key = if (firstRun) "setup-first" else "setup-mobile")
    val state by vm.state.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    // (First run: the view model starts on the hub in first-run mode, and finishing marks setup done.)
    LaunchedEffect(Unit) { if (!firstRun) vm.openManage() }
    var service by remember { mutableStateOf<SetupItem?>(null) }
    val svc = service
    when {
        state.step != SetupStep.Choose -> AccountStep(state, accounts, vm)
        svc != null -> ServicePage(svc, accounts, session, vm, onClose = { service = null })
        else -> AccountsHub(accounts, session, firstRun, onBack = { vm.finish(onClose) }, onOpen = { item ->
            if (statusText(item, accounts, session) != null) service = item else vm.open(item)
        })
    }
}

@Composable
private fun AccountsHub(accounts: Accounts, session: Session?, firstRun: Boolean, onBack: () -> Unit, onOpen: (SetupItem) -> Unit) {
    // (Provider films & series come through Dispatcharr: only with a Dispatcharr sign-in.)
    val items = SetupItem.entries.filter { it.available && (it != SetupItem.Vod || (session != null && !session.isDirect)) }
    // First run: no way back out (there's nothing behind it), and Start watching once something is connected.
    val anything = items.any { statusText(it, accounts, session) != null }
    PhonePage(
        if (firstRun) "Set up Previewarr" else "Accounts & sources",
        if (firstRun) null else onBack,
        subtitle = if (firstRun) "Connect the services you use, one at a time. Live TV works with Dispatcharr, an IPTV login or an M3U playlist. You can add the rest later in Settings."
            else "Tap a service to connect it, or to change or check it.",
    ) {
        if (firstRun) item(key = "start") {
            Spacer(Modifier.height(8.dp))
            PhoneButton(if (anything) "Start watching" else "Connect something first", Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = anything) { onBack() }
        }
        item(key = "list") {
            Spacer(Modifier.height(8.dp))
            CardRows(items.map { it0 ->
                @Composable {
                    val status = statusText(it0, accounts, session)
                    PhoneRow(it0.label, it0.description, trailing = status ?: "Not set up", dim = status == null, opens = true) { onOpen(it0) }
                }
            })
        }
    }
}

/** A connected service: change it, its own switches (Plex servers, addons, Live TV sources), or disconnect it. */
@Composable
private fun ServicePage(svc: SetupItem, accounts: Accounts, session: Session?, vm: SetupViewModel, onClose: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val status = statusText(svc, accounts, session).orEmpty()
    PhonePage(svc.label, onClose, subtitle = status) {
        item(key = "main") {
            Spacer(Modifier.height(8.dp))
            PhoneCard {
                PhoneRow(
                    when (svc) { SetupItem.Stremio -> "Add or remove addons"; SetupItem.LiveTv -> "Add another source"; else -> "Change" },
                    svc.description, opens = true,
                ) { onClose(); vm.open(svc) }
            }
        }
        when (svc) {
            SetupItem.LiveTv -> liveSources(this)
            SetupItem.Plex -> item(key = "plex") { PlexServers() }
            SetupItem.Stremio -> item(key = "addons") { AddonsList() }
            else -> Unit
        }
        if (svc != SetupItem.LiveTv) item(key = "disconnect") {
            Spacer(Modifier.height(18.dp))
            PhoneCard { PhoneRow("Disconnect", "Removes ${svc.label} from this device. You can connect it again any time.", danger = true) { confirm = true } }
        }
    }
    if (confirm) Chooser("Disconnect ${svc.label}?", null, listOf("Disconnect", "Cancel"), null,
        onPick = { i -> confirm = false; if (i == 0) { vm.disconnect(svc); onClose() } }, onDismiss = { confirm = false })
}

/** Live TV: every saved source; a tap opens its choices (switch to it, rename, remove). */
private fun liveSources(scope: LazyListScope) = scope.item(key = "sources") {
    val vm: LiveSourcesViewModel = hiltViewModel()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Session?>(null) }
    var renaming by remember { mutableStateOf<Session?>(null) }
    var removing by remember { mutableStateOf<Session?>(null) }
    if (saved.isEmpty()) return@item
    GroupTitle("Your Live TV sources")
    CardRows(saved.map { s -> @Composable { PhoneRow(s.label, s.serverUrl, trailing = if (s.id == active?.id) "Active" else null) { open = s } } })
    open?.let { s ->
        val on = s.id == active?.id
        val opts = listOfNotNull(if (!on) "Switch to this source" else null, "Rename", "Remove")
        Chooser(s.label, s.serverUrl, opts, null, onPick = { i ->
            open = null
            when (opts[i]) { "Switch to this source" -> vm.switchTo(s); "Rename" -> renaming = s; else -> removing = s }
        }, onDismiss = { open = null })
    }
    removing?.let { s ->
        Chooser("Remove ${s.label}?", "Its channels and guide are forgotten on this device.", listOf("Remove", "Cancel"), null,
            onPick = { i -> removing = null; if (i == 0) vm.remove(s) }, onDismiss = { removing = null })
    }
    renaming?.let { s -> TextSheet("Name this source", "For example \"Home\" or \"Backup provider\". Leave it empty for \"${s.label}\".", s.name.orEmpty(),
        onDone = { vm.rename(s, it.trim().ifEmpty { null }); renaming = null }, onDismiss = { renaming = null }) }
}

@Composable
private fun PlexServers() {
    val vm: PlexServersViewModel = hiltViewModel()
    val a by vm.accounts.collectAsStateWithLifecycle()
    val main = a.plex ?: return
    val servers = listOf(main) + a.plexMore.filter { it.machineId != main.machineId }
    if (servers.size < 2) return
    GroupTitle("Plex servers", "A server that's off is left out of rows, search, libraries and playback.")
    CardRows(servers.map { s -> @Composable { PhoneSwitch(s.serverName, on = s.machineId !in a.plexOff) { vm.toggle(s.machineId) } } })
}

@Composable
private fun AddonsList() {
    val vm: AddonsSettingsViewModel = hiltViewModel()
    val a by vm.accounts.collectAsStateWithLifecycle()
    val addons = a?.addons.orEmpty()
    if (addons.isEmpty()) return
    GroupTitle("Addons")
    CardRows(addons.map { ad -> @Composable { PhoneSwitch(ad.name, on = ad.enabled) { vm.setEnabled(ad.baseUrl, it) } } })
}

/** One step of setting a service up, as a phone form. Back: the step before, or the list. */
@Composable
private fun AccountStep(state: SetupUiState, accounts: Accounts, vm: SetupViewModel) {
    val uri = LocalUriHandler.current
    fun open(url: String) { runCatching { uri.openUri(url) } }
    LaunchedEffect(state.step) { if (state.step == SetupStep.Phone) vm.back() }
    val title = if (state.step == SetupStep.SignIn && state.xc) "Sign in to your IPTV provider" else titleFor(state.step, SetupMode.Manage)
    PhonePage(title, onBack = vm::back, subtitle = phoneBody(state)) {
        item(key = "form:${state.step}") {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(8.dp))
                StepForm(state, accounts, vm, ::open)
                state.info?.let { PhoneNote(it, accent = true) }
                state.error?.let { PhoneNote(it, error = true) }
            }
        }
    }
}

/** The step's words, without the TV-only parts ("easier from your phone", the remote). */
private fun phoneBody(state: SetupUiState): String =
    bodyFor(state)
        .replace(Regex("\\s*Long (links|keys) are easier[^.]*\\.(\\s*\\([^)]*\\)\\.?)?"), "")
        .replace(Regex("\\s*The key is long: the phone setup is easier\\."), "")
        .replace(Regex("\\s*Long keys are easier to type on your phone\\."), "")
        .replace("this TV", "this device").replace("This TV", "This device")
        .trim()

@Composable
private fun StepForm(state: SetupUiState, accounts: Accounts, vm: SetupViewModel, open: (String) -> Unit) {
    val f = state.fields
    val busy = state.busy
    when (state.step) {
        SetupStep.Welcome -> ButtonColumn { PhoneButton("Get started", Modifier.fillMaxWidth(), onTap = vm::start) }
        SetupStep.Server -> {
            PhoneField(state.serverInput, vm::onServerInput, "Server address", KeyboardType.Uri, last = true, onDone = vm::submit)
            ButtonColumn { PhoneButton(if (busy) "Checking…" else "Continue", Modifier.fillMaxWidth(), enabled = !busy, onTap = vm::submit) }
        }
        SetupStep.SignIn -> {
            val key = !state.xc && state.useKey
            if (key) PhoneField(f[API_KEY].orEmpty(), { vm.onField(API_KEY, it) }, "API key", last = true, onDone = vm::submit)
            else {
                PhoneField(state.username, vm::onUsername, "Username")
                PhoneField(state.password, vm::onPassword, "Password", KeyboardType.Password, password = true, last = true, onDone = vm::submit)
            }
            ButtonColumn {
                PhoneButton(if (busy) "Signing in…" else "Sign in", Modifier.fillMaxWidth(), enabled = !busy, onTap = vm::submit)
                if (key) state.serverUrl?.let { u -> PhoneButton("Open Dispatcharr's Users page", Modifier.fillMaxWidth(), primary = false) { open(u.trimEnd('/') + "/users") } }
                if (!state.xc) PhoneButton(if (state.useKey) "Use username & password instead" else "Use API key instead", Modifier.fillMaxWidth(), primary = false, enabled = !busy, onTap = vm::toggleKey)
            }
        }
        SetupStep.Tmdb -> {
            PhoneField(f[TMDB_KEY].orEmpty(), { vm.onField(TMDB_KEY, it) }, "TMDB API key", last = true, onDone = vm::submit)
            ButtonColumn {
                PhoneButton(if (busy) "Checking…" else "Save", Modifier.fillMaxWidth(), enabled = !busy, onTap = vm::submit)
                PhoneButton("Open TMDB to get a key", Modifier.fillMaxWidth(), primary = false) { open("https://www.themoviedb.org/settings/api") }
                PhoneButton("Skip", Modifier.fillMaxWidth(), primary = false, onTap = vm::advance)
            }
        }
        SetupStep.Mdblist -> {
            PhoneField(f[MDBLIST_KEY].orEmpty(), { vm.onField(MDBLIST_KEY, it) }, "MDBList API key", last = true, onDone = vm::submit)
            ButtonColumn {
                PhoneButton(if (busy) "Checking…" else "Save", Modifier.fillMaxWidth(), enabled = !busy, onTap = vm::submit)
                PhoneButton("Open MDBList to get a key", Modifier.fillMaxWidth(), primary = false) { open("https://mdblist.com/preferences/") }
                PhoneButton("Skip", Modifier.fillMaxWidth(), primary = false, onTap = vm::advance)
            }
        }
        SetupStep.Trakt, SetupStep.Plex -> CodeForm(state, vm, open)
        SetupStep.Music -> MusicForm(state, accounts, vm)
        SetupStep.Navidrome -> {
            PhoneField(f[SERVER].orEmpty(), { vm.onField(SERVER, it) }, "Navidrome address", KeyboardType.Uri)
            PhoneField(f[USER].orEmpty(), { vm.onField(USER, it) }, "Username")
            PhoneField(f[PASSWORD].orEmpty(), { vm.onField(PASSWORD, it) }, "Password", KeyboardType.Password, password = true, last = true, onDone = vm::submit)
            ButtonColumn {
                PhoneButton(if (busy) "Connecting…" else "Sign in", Modifier.fillMaxWidth(), enabled = !busy, onTap = vm::submit)
                PhoneButton("Back", Modifier.fillMaxWidth(), primary = false, onTap = vm::backToMusic)
            }
        }
        SetupStep.Seerr -> {
            PhoneField(f[SERVER].orEmpty(), { vm.onField(SERVER, it) }, "Seerr address", KeyboardType.Uri)
            PhoneField(f[API_KEY].orEmpty(), { vm.onField(API_KEY, it) }, "Seerr API key", last = true, onDone = vm::submit)
            ButtonColumn {
                PhoneButton(if (busy) "Checking…" else "Save", Modifier.fillMaxWidth(), enabled = !busy, onTap = vm::submit)
                PhoneButton("Skip", Modifier.fillMaxWidth(), primary = false, onTap = vm::advance)
            }
        }
        SetupStep.Stremio -> {
            var removing by remember { mutableStateOf<String?>(null) }
            if (accounts.addons.isNotEmpty()) {
                CardRows(accounts.addons.map { ad -> @Composable { PhoneRow(ad.name, ad.baseUrl, trailing = "Remove") { removing = ad.baseUrl } } })
                Spacer(Modifier.height(10.dp))
            }
            PhoneField(f[ADDON_URLS].orEmpty(), { vm.onField(ADDON_URLS, it) }, "Addon link (manifest.json)", KeyboardType.Uri, last = true, onDone = vm::submit)
            ButtonColumn {
                PhoneButton(if (busy) "Checking…" else "Add", Modifier.fillMaxWidth(), enabled = !busy, onTap = vm::submit)
                PhoneButton(if (accounts.addons.isEmpty()) "Skip" else "Done", Modifier.fillMaxWidth(), primary = false, onTap = vm::advance)
            }
            removing?.let { u ->
                Chooser("Remove this addon?", u, listOf("Remove", "Cancel"), null, onPick = { i -> removing = null; if (i == 0) vm.removeAddon(u) }, onDismiss = { removing = null })
            }
        }
        SetupStep.Jellyfin, SetupStep.Emby -> {
            PhoneField(f[SERVER].orEmpty(), { vm.onField(SERVER, it) }, "Server address", KeyboardType.Uri)
            PhoneField(f[USER].orEmpty(), { vm.onField(USER, it) }, "Username")
            PhoneField(f[PASSWORD].orEmpty(), { vm.onField(PASSWORD, it) }, "Password", KeyboardType.Password, password = true, last = true, onDone = vm::submit)
            state.code?.let { PhoneNote("Quick Connect: in Jellyfin on another device, open your profile → Quick Connect and enter"); PhoneBigCode(it) }
            ButtonColumn {
                PhoneButton(if (busy) "Connecting…" else "Sign in", Modifier.fillMaxWidth(), enabled = !busy, onTap = vm::submit)
                if (state.step == SetupStep.Jellyfin) PhoneButton("Quick Connect (no password)", Modifier.fillMaxWidth(), primary = false, onTap = vm::startQuickConnect)
                PhoneButton("Skip", Modifier.fillMaxWidth(), primary = false, onTap = vm::advance)
            }
        }
        SetupStep.Vod -> {
            val count = state.vodCount
            when {
                count == null && state.error == null -> PhoneNote("Checking your Dispatcharr server…")
                count != null && count > 0 -> {
                    PhoneNote("Your server has $count films and series from your provider.")
                    ButtonColumn {
                        PhoneButton("Turn on", Modifier.fillMaxWidth()) { vm.setVod(true) }
                        PhoneButton("Leave off", Modifier.fillMaxWidth(), primary = false) { vm.setVod(false) }
                    }
                }
                else -> {
                    PhoneNote("Your Dispatcharr server has no films or series from your provider, so there's nothing to add. If your provider adds them later, turn this on here.")
                    ButtonColumn { PhoneButton("Continue", Modifier.fillMaxWidth()) { vm.setVod(false) } }
                }
            }
        }
        SetupStep.Done, SetupStep.Choose, SetupStep.Phone -> ButtonColumn { PhoneButton("Done", Modifier.fillMaxWidth()) { vm.back() } }
    }
}

/** Trakt (a code to enter on its site) and Plex (sign in on its page): opened on this phone with one tap. */
@Composable
private fun CodeForm(state: SetupUiState, vm: SetupViewModel, open: (String) -> Unit) {
    if (state.plexChoices.isNotEmpty()) {
        GroupTitle("Your Plex servers", "Choose which to use.")
        CardRows(state.plexChoices.map { c ->
            @Composable { PhoneSwitch(c.name, c.url.substringAfter("://").substringBefore('/'), on = c.machineId in state.plexOn) { vm.togglePlexServer(c.machineId) } }
        })
        val merge by vm.mergeRows.collectAsStateWithLifecycle()
        Spacer(Modifier.height(10.dp))
        PhoneCard { PhoneSwitch("Combine rows from different servers", "One Continue watching and one Recently added per library, instead of a row for each server.", on = merge) { vm.setMergeRows(it) } }
        ButtonColumn {
            PhoneButton(if (state.plexOn.size > 1) "Continue with ${state.plexOn.size} servers" else "Continue", Modifier.fillMaxWidth(), enabled = state.plexOn.isNotEmpty(), onTap = vm::confirmPlex)
        }
        return
    }
    val url = state.codeUrl
    val code = state.code
    when {
        code != null && url != null -> {
            PhoneNote("Open ${url.removePrefix("https://")} and enter this code:")
            PhoneBigCode(code)
            ButtonColumn { PhoneButton("Open ${url.removePrefix("https://").substringBefore('/')}", Modifier.fillMaxWidth()) { open(url) } }
            PhoneNote("Waiting for you to approve… Come back to Previewarr Mobile afterwards.")
        }
        url != null -> {
            ButtonColumn { PhoneButton("Sign in to Plex", Modifier.fillMaxWidth()) { open(url) } }
            PhoneNote("Sign in on the Plex page that opens, then come back here: Previewarr Mobile links itself as soon as you've signed in.")
        }
        state.busy -> PhoneNote("Getting a code…")
    }
    ButtonColumn { PhoneButton("Skip", Modifier.fillMaxWidth(), primary = false, onTap = vm::advance) }
}

@Composable
private fun MusicForm(state: SetupUiState, accounts: Accounts, vm: SetupViewModel) {
    val on = accounts.musicOn
    PhoneCard { PhoneSwitch("Music section", "Albums, artists and playlists from your servers.", on = on) { vm.setMusic(it) } }
    if (on) {
        val servers = state.musicServers
        GroupTitle("Your servers' music", when {
            servers == null -> "Looking for music libraries on your servers…"
            servers.isEmpty() -> "None of your connected servers (Plex, Jellyfin, Emby) has a music library."
            else -> null
        })
        CardRows(servers.orEmpty().map { (id, name) -> @Composable { PhoneSwitch("$name music", on = id !in accounts.musicOff) { vm.toggleMusicServer(id) } } })
        GroupTitle("Navidrome / Subsonic")
        var removing by remember { mutableStateOf(false) }
        val nd = accounts.navidrome
        if (nd == null) PhoneCard { PhoneRow("Add a Navidrome server", opens = true, onTap = vm::openNavidrome) }
        else CardRows(listOf<@Composable () -> Unit>(
            @Composable { PhoneRow("Navidrome: ${nd.user}", trailing = "Change", onTap = vm::openNavidrome) },
            @Composable { PhoneRow("Remove Navidrome", danger = true) { removing = true } },
        ))
        if (removing) Chooser("Remove Navidrome?", null, listOf("Remove", "Cancel"), null, onPick = { i -> removing = false; if (i == 0) vm.removeNavidrome() }, onDismiss = { removing = false })
    }
    ButtonColumn {
        PhoneButton(if (on) "Done" else "Continue without Music", Modifier.fillMaxWidth(), onTap = vm::advance)
        if (on) PhoneButton("No music", Modifier.fillMaxWidth(), primary = false) { vm.skipMusic() }
    }
}

/**
 * Previewarr Mobile's first run: a welcome, then the services to connect (Accounts & sources, laid out for a phone),
 * then Start watching. (The TV's tour and its 1-2-3 are about the remote and the menu.)
 */
@Composable
internal fun MobileFirstRun() {
    var started by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (started) { MobileAccounts(onClose = {}, firstRun = true); return }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(MobileColors.Background).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)) {
        androidx.compose.foundation.layout.Column(
            Modifier.align(androidx.compose.ui.Alignment.Center).widthIn(max = 520.dp).fillMaxWidth().padding(28.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(84.dp)) { drawViewarrMark() }
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Text("Welcome to Previewarr", color = androidx.compose.ui.graphics.Color.White, fontSize = 28.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Text(
                "Live TV with a proper guide, your films and shows, streaming services, music and recordings, on your phone or tablet.",
                color = io.github.petebutty.viewarr.ui.theme.AppColors.TextSecondary, fontSize = 16.sp, lineHeight = 23.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            PhoneCard {
                listOf(
                    "Live TV" to "Dispatcharr (your server's address, username and password), an IPTV login, or an M3U playlist",
                    "Films and shows" to "Plex, Jellyfin or Emby, and addons such as AIOStreams",
                    "Extras" to "Trakt, TMDB, music, requests: all optional, and can be added later",
                ).forEachIndexed { i, (t, d) -> if (i > 0) RowLine(); PhoneRow(t, d) }
            }
            Spacer(Modifier.height(28.dp))
            PhoneButton("Get started", Modifier.fillMaxWidth()) { started = true }
        }
    }
}
