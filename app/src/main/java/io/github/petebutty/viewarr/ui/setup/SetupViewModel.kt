package io.github.petebutty.viewarr.ui.setup

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.remote.ApiFactory
import io.github.petebutty.viewarr.data.remote.services.MediaServerService
import io.github.petebutty.viewarr.data.remote.services.PlexServerChoice
import io.github.petebutty.viewarr.data.remote.services.PlexService
import io.github.petebutty.viewarr.data.remote.services.QuickConnectCode
import io.github.petebutty.viewarr.data.remote.services.StremioService
import io.github.petebutty.viewarr.data.remote.services.TmdbService
import io.github.petebutty.viewarr.data.remote.services.TraktPoll
import io.github.petebutty.viewarr.data.remote.services.TraktService
import io.github.petebutty.viewarr.data.repository.AuthRepository
import io.github.petebutty.viewarr.data.repository.ProbeResult
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.setup.PhoneSetupServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupMode { FirstRun, Manage }

enum class SetupStep { Phone, Welcome, Choose, Server, SignIn, Tmdb, Trakt, Plex, Stremio, Jellyfin, Emby, Vod, Done }

enum class SetupItem(val id: String, val label: String, val description: String, val steps: List<SetupStep>) {
    LiveTv("livetv", "Live TV", "Dispatcharr: channels, TV guide, catch-up and recordings", listOf(SetupStep.Server, SetupStep.SignIn)),
    Tmdb("tmdb", "TMDB", "Posters, descriptions and discovery for Movies & Shows", listOf(SetupStep.Tmdb)),
    Trakt("trakt", "Trakt", "Watchlist, history, recommendations and continue watching", listOf(SetupStep.Trakt)),
    Plex("plex", "Plex", "Play films and shows from your Plex library", listOf(SetupStep.Plex)),
    Stremio("stremio", "Stremio addons", "Streams from addons such as AIOStreams", listOf(SetupStep.Stremio)),
    Jellyfin("jellyfin", "Jellyfin", "Play from a Jellyfin server", listOf(SetupStep.Jellyfin)),
    Emby("emby", "Emby", "Play from an Emby server", listOf(SetupStep.Emby)),
    Vod("vod", "Provider VOD", "Films and series from your IPTV provider, via Dispatcharr", listOf(SetupStep.Vod)),
}

/** Trakt needs a client id built into the app (secrets.properties); without one it isn't offered. */
val SetupItem.available: Boolean
    get() = this != SetupItem.Trakt || io.github.petebutty.viewarr.data.remote.services.TraktService.CLIENT_ID.isNotBlank()

data class SetupUiState(
    val mode: SetupMode = SetupMode.FirstRun,
    val step: SetupStep = SetupStep.Phone,
    val selected: Set<SetupItem> = setOf(SetupItem.LiveTv, SetupItem.Tmdb),
    val serverInput: String = "",
    val serverUrl: String? = null,
    val serverVersion: String? = null,
    val username: String = "",
    val password: String = "",
    val fields: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val code: String? = null,
    val codeUrl: String? = null,
    val qrUrl: String? = null,
    val plexChoices: List<PlexServerChoice> = emptyList(),
    val vodCount: Int? = null,
    /** Phone setup: the phone has talked to the TV at least once. */
    val phoneConnected: Boolean = false,
    /** Phone setup: what the phone is doing right now, shown on the TV. */
    val phoneActivity: String? = null,
    /** Phone setup: the phone tapped Finish. */
    val phoneFinished: Boolean = false,
)

/** Where a Trakt / Plex link started from the phone has got to. */
private data class PhoneLink(
    val state: String = "pending",
    val message: String? = null,
    val choices: List<PlexServerChoice> = emptyList(),
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val sessions: SessionStore,
    private val store: AccountStore,
    private val apiFactory: ApiFactory,
    private val tmdb: TmdbService,
    private val trakt: TraktService,
    private val plex: PlexService,
    private val mediaServers: MediaServerService,
    private val stremio: StremioService,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state = _state.asStateFlow()
    val accounts = store.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, Accounts())
    val session = sessions.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var queue: List<SetupStep> = emptyList()
    private var phoneServer: PhoneSetupServer? = null
    private var stepJob: Job? = null
    private var quickConnect: QuickConnectCode? = null
    private var phoneJob: Job? = null
    @Volatile private var traktLink: PhoneLink? = null
    @Volatile private var plexLink: PhoneLink? = null
    @Volatile private var jellyfinLink: PhoneLink? = null

    init {
        // A fresh install opens straight on the phone screen. Mark setup as unfinished first so that signing in to
        // Live TV from the phone doesn't count as "already set up" and jump to the guide mid-setup.
        viewModelScope.launch {
            if (store.setupComplete.first() == null && sessions.session.first() == null) store.setSetupComplete(false)
        }
        openPhone()
    }

    fun openManage() {
        cleanUp()
        stopPhone()
        _state.value = SetupUiState(mode = SetupMode.Manage, step = SetupStep.Choose, selected = emptySet())
    }

    /** "Use the remote instead": the on-TV checklist. */
    fun start() {
        viewModelScope.launch { store.setSetupComplete(false) }
        go(SetupStep.Choose)
    }

    /** The QR screen: the whole setup runs on the phone while this is open. */
    fun openPhone() {
        go(SetupStep.Phone)
    }

    fun toggle(item: SetupItem) = _state.update {
        val next = if (item in it.selected) it.selected - item else it.selected + item
        // Movies & Shows are built on TMDB, so picking any media source brings it along.
        val media = setOf(SetupItem.Trakt, SetupItem.Plex, SetupItem.Stremio, SetupItem.Jellyfin, SetupItem.Emby, SetupItem.Vod)
        it.copy(selected = if (item in media && item !in it.selected) next + SetupItem.Tmdb else next)
    }

    fun beginChosen() {
        queue = SetupItem.entries.filter { it in _state.value.selected }.flatMap { it.steps }
        advance()
    }

    /** Manage mode: run one item's steps, then come back to the list. */
    fun open(item: SetupItem) {
        queue = item.steps
        advance()
    }

    fun advance() {
        val next = queue.firstOrNull()
        queue = queue.drop(1)
        go(next ?: if (_state.value.mode == SetupMode.Manage) SetupStep.Choose else SetupStep.Done)
    }

    fun back() {
        when (_state.value.step) {
            SetupStep.SignIn -> go(SetupStep.Server)
            SetupStep.Choose -> if (_state.value.mode == SetupMode.FirstRun) go(SetupStep.Phone)
            SetupStep.Phone -> if (_state.value.mode == SetupMode.Manage) go(SetupStep.Choose)
            else -> go(SetupStep.Choose)
        }
    }

    fun finish(onClose: () -> Unit) {
        cleanUp()
        stopPhone()
        viewModelScope.launch {
            if (_state.value.mode == SetupMode.FirstRun) store.setSetupComplete(true)
            onClose()
        }
    }

    fun onField(key: String, value: String) = _state.update { it.copy(fields = it.fields + (key to value), error = null) }
    fun onServerInput(value: String) = _state.update { it.copy(serverInput = value, error = null) }
    fun onUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    private fun go(step: SetupStep) {
        cleanUp()
        if (step != SetupStep.Phone) stopPhone()
        _state.update {
            it.copy(step = step, busy = false, error = null, info = null, code = null, codeUrl = null, plexChoices = emptyList(), fields = emptyMap())
        }
        when (step) {
            SetupStep.Phone -> startPhone()
            SetupStep.Trakt -> startTrakt()
            SetupStep.Plex -> startPlex()
            SetupStep.Vod -> checkVod()
            else -> Unit
        }
    }

    private fun startPhone() {
        if (phoneServer != null) return
        val server = PhoneSetupServer(context, ::handlePhone)
        phoneServer = server
        val url = server.start()
        _state.update { it.copy(qrUrl = url, error = if (url == null) "This TV isn't on a network, so phone setup can't start." else null) }
    }

    private fun stopPhone() {
        phoneServer?.stop()
        phoneServer = null
        phoneJob?.cancel()
        phoneJob = null
        traktLink = null
        plexLink = null
        jellyfinLink = null
        _state.update { it.copy(qrUrl = null, phoneConnected = false, phoneActivity = null) }
    }

    private fun cleanUp() {
        stepJob?.cancel()
        stepJob = null
        quickConnect = null
    }

    /** Runs the current step's primary action with whatever is in the fields. */
    fun submit() {
        val s = _state.value
        if (s.busy) return
        when (s.step) {
            SetupStep.Server -> checkServer()
            SetupStep.SignIn -> signIn()
            SetupStep.Tmdb -> work {
                val key = s.fields[TMDB_KEY].orEmpty().trim()
                if (key.isEmpty()) return@work fail("Enter your TMDB API key.")
                if (!tmdb.validate(key)) return@work fail("TMDB didn't accept that key. Check it and try again.")
                store.update { it.copy(tmdbKey = key) }
                advance()
            }
            SetupStep.Stremio -> work {
                val urls = s.fields[ADDON_URLS].orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (urls.isEmpty()) return@work fail("Enter an addon URL.")
                val added = ArrayList<String>()
                val failed = ArrayList<String>()
                for (url in urls) {
                    val addon = stremio.fetchAddon(url)
                    if (addon == null) failed += url else {
                        store.update { a -> a.copy(addons = a.addons.filterNot { it.baseUrl == addon.baseUrl } + addon) }
                        added += addon.name
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        fields = it.fields - ADDON_URLS,
                        info = if (added.isEmpty()) null else "Added ${added.joinToString()}.",
                        error = if (failed.isEmpty()) null else "Couldn't load ${failed.size} addon URL(s). Check the link.",
                    )
                }
            }
            SetupStep.Jellyfin, SetupStep.Emby -> work {
                val emby = s.step == SetupStep.Emby
                val name = if (emby) "Emby" else "Jellyfin"
                val server = mediaServers.findServer(s.fields[SERVER].orEmpty(), emby)
                    ?: return@work fail("No $name server found at that address.")
                val deviceId = store.current().deviceId
                val signedIn = mediaServers.signIn(server, emby, deviceId, s.fields[USER].orEmpty(), s.fields[PASSWORD].orEmpty())
                    ?: return@work fail("$name didn't accept that username or password.")
                store.update { if (emby) it.copy(emby = signedIn) else it.copy(jellyfin = signedIn) }
                advance()
            }
            else -> Unit
        }
    }

    fun removeAddon(baseUrl: String) {
        viewModelScope.launch { store.update { a -> a.copy(addons = a.addons.filterNot { it.baseUrl == baseUrl }) } }
    }

    fun startQuickConnect() {
        val s = _state.value
        work {
            val server = mediaServers.findServer(s.fields[SERVER].orEmpty(), emby = false)
                ?: return@work fail("Enter your Jellyfin server address first.")
            val deviceId = store.current().deviceId
            val qc = mediaServers.startQuickConnect(server, deviceId)
                ?: return@work fail("Quick Connect is turned off on this server. Use your username and password.")
            quickConnect = qc
            _state.update { it.copy(busy = false, code = qc.code, info = "In Jellyfin, open your profile → Quick Connect and enter this code.") }
            stepJob = viewModelScope.launch {
                repeat(120) {
                    delay(3_000)
                    val signedIn = mediaServers.finishQuickConnect(server, deviceId, qc)
                    if (signedIn != null) {
                        store.update { it.copy(jellyfin = signedIn) }
                        advance()
                        return@launch
                    }
                }
                fail("The Quick Connect code expired. Try again.")
            }
        }
    }

    private fun startTrakt() {
        stepJob = viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val code = trakt.deviceCode() ?: return@launch fail("Couldn't reach Trakt. Check your connection.")
            _state.update { it.copy(busy = false, code = code.userCode, codeUrl = code.verificationUrl) }
            var interval = code.intervalSec * 1000L
            while (System.currentTimeMillis() < code.expiresAt) {
                delay(interval)
                when (val r = trakt.poll(code.deviceCode)) {
                    TraktPoll.Pending -> Unit
                    TraktPoll.SlowDown -> interval += 1_000
                    is TraktPoll.Failed -> return@launch fail(r.message)
                    is TraktPoll.Done -> {
                        store.update { it.copy(trakt = r.auth) }
                        _state.update { it.copy(info = "Signed in as ${r.auth.username}.") }
                        delay(1_200)
                        advance()
                        return@launch
                    }
                }
            }
            fail("The code expired. Go back and try again.")
        }
    }

    private fun startPlex() {
        stepJob = viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val deviceId = store.current().deviceId
            val pin = plex.createPin(deviceId) ?: return@launch fail("Couldn't reach plex.tv. Check your connection.")
            _state.update { it.copy(busy = false, code = pin.code, codeUrl = "https://plex.tv/link") }
            repeat(150) {
                delay(2_000)
                val token = plex.checkPin(deviceId, pin.id) ?: return@repeat
                _state.update { it.copy(code = null, busy = true, info = "Linked. Finding your Plex server…") }
                val servers = plex.servers(deviceId, token)
                when (servers.size) {
                    0 -> fail("No Plex server could be reached from this TV.")
                    1 -> pickPlex(servers.first())
                    else -> _state.update { it.copy(busy = false, plexChoices = servers, info = "Choose your server.") }
                }
                return@launch
            }
            fail("The code expired. Go back and try again.")
        }
    }

    fun pickPlex(choice: PlexServerChoice) {
        viewModelScope.launch {
            store.update { it.copy(plex = plex.toAuth(choice)) }
            _state.update { it.copy(busy = false, plexChoices = emptyList(), info = "Connected to ${choice.name}.") }
            delay(1_200)
            advance()
        }
    }

    private fun checkVod() = work {
        val s = sessions.session.first() ?: return@work fail("Set up Live TV first. Provider VOD comes through Dispatcharr.")
        val api = apiFactory.create(s.serverUrl, s.apiKey)
        val count = runCatching { api.vodMovies().count + api.vodSeries().count }.getOrNull()
            ?: return@work fail("Couldn't check your Dispatcharr server for VOD.")
        _state.update { it.copy(busy = false, vodCount = count) }
    }

    fun setVod(enabled: Boolean) {
        viewModelScope.launch {
            store.update { it.copy(vodEnabled = enabled) }
            advance()
        }
    }

    private fun checkServer() = work {
        when (val result = auth.probe(_state.value.serverInput)) {
            is ProbeResult.Found -> {
                _state.update { it.copy(busy = false, serverUrl = result.url, serverVersion = result.version) }
                go(SetupStep.SignIn)
                _state.update { it.copy(serverUrl = result.url, serverVersion = result.version) }
            }
            is ProbeResult.Failed -> fail(result.message)
        }
    }

    private fun signIn() {
        val s = _state.value
        if (s.serverUrl == null) return
        if (s.username.isBlank() || s.password.isEmpty()) {
            _state.update { it.copy(error = "Enter your username and password.") }
            return
        }
        work {
            auth.signIn(s.serverUrl, s.username, s.password)
                .onSuccess {
                    _state.update { it.copy(password = "") }
                    advance()
                }
                .onFailure { e -> fail(e.message ?: "Sign-in failed.") }
        }
    }


    // ---------------------------------------------------------------- phone setup API

    private fun reply(ok: Boolean, message: String? = null, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject {
            put("ok", ok)
            message?.let { put("message", it) }
            extra()
        }

    private fun activity(text: String?) = _state.update { it.copy(phoneConnected = true, phoneActivity = text) }

    /** Runs one sealed request from the phone. Replies are plain status text: no keys or tokens go back. */
    private suspend fun handlePhone(action: String, v: Map<String, String>): JsonObject {
        activity(_state.value.phoneActivity)
        return when (action) {
            "status" -> {
                val a = store.current()
                val session = sessions.session.first()
                reply(true) {
                    putJsonArray("items") {
                        SetupItem.entries.filter { it.available }.forEach { item ->
                            add(buildJsonObject {
                                put("id", item.id)
                                put("label", item.label)
                                put("desc", item.description)
                                put("status", statusText(item, a, session))
                            })
                        }
                    }
                }
            }
            "livetv" -> {
                activity("Signing in to Dispatcharr…")
                val server = v["server"].orEmpty()
                val user = v["username"].orEmpty().trim()
                val pass = v["password"].orEmpty()
                val apiKey = v["apiKey"].orEmpty().trim()
                if (server.isBlank()) return reply(false, "Enter your Dispatcharr server address.")
                if (apiKey.isEmpty() && (user.isBlank() || pass.isEmpty())) return reply(false, "Enter your username and password.")
                when (val probe = auth.probe(server)) {
                    is ProbeResult.Failed -> reply(false, probe.message).also { activity("Couldn't find Dispatcharr") }
                    is ProbeResult.Found -> (if (apiKey.isNotEmpty()) auth.signInWithKey(probe.url, apiKey) else auth.signIn(probe.url, user, pass)).fold(
                        onSuccess = { s -> activity("Live TV: signed in as ${s.username}"); reply(true, "Signed in as ${s.username}.") },
                        onFailure = { e -> activity("Live TV sign-in failed"); reply(false, e.message ?: "Sign-in failed.") },
                    )
                }
            }
            "tmdb" -> {
                activity("Checking the TMDB key…")
                val key = v["key"].orEmpty().trim()
                when {
                    key.isEmpty() -> reply(false, "Paste your TMDB API key.")
                    !tmdb.validate(key) -> reply(false, "TMDB didn't accept that key. Check it and try again.")
                    else -> { store.update { it.copy(tmdbKey = key) }; activity("TMDB connected"); reply(true, "TMDB connected.") }
                }
            }
            "trakt-start" -> {
                activity("Linking Trakt…")
                val code = trakt.deviceCode() ?: return reply(false, "Couldn't reach Trakt. Try again in a moment.")
                traktLink = PhoneLink()
                phoneJob?.cancel()
                phoneJob = viewModelScope.launch {
                    var interval = code.intervalSec * 1000L
                    while (System.currentTimeMillis() < code.expiresAt) {
                        delay(interval)
                        when (val r = trakt.poll(code.deviceCode)) {
                            TraktPoll.Pending -> Unit
                            TraktPoll.SlowDown -> interval += 1_000
                            is TraktPoll.Failed -> { traktLink = PhoneLink("failed", r.message); return@launch }
                            is TraktPoll.Done -> {
                                store.update { it.copy(trakt = r.auth) }
                                traktLink = PhoneLink("done", "Signed in to Trakt as ${r.auth.username}.")
                                activity("Trakt: signed in as ${r.auth.username}")
                                return@launch
                            }
                        }
                    }
                    traktLink = PhoneLink("failed", "The code expired. Tap Back and try again.")
                }
                reply(true) { put("code", code.userCode); put("url", code.verificationUrl) }
            }
            "trakt-status" -> linkReply(traktLink)
            "plex-start" -> {
                activity("Linking Plex…")
                val deviceId = store.current().deviceId
                val pin = plex.createPin(deviceId, strong = true) ?: return reply(false, "Couldn't reach plex.tv. Try again in a moment.")
                plexLink = PhoneLink()
                phoneJob?.cancel()
                phoneJob = viewModelScope.launch {
                    repeat(150) {
                        delay(2_000)
                        val token = plex.checkPin(deviceId, pin.id) ?: return@repeat
                        val servers = plex.servers(deviceId, token)
                        plexLink = when (servers.size) {
                            0 -> PhoneLink("failed", "Plex linked, but no Plex server could be reached from the TV.")
                            1 -> { savePlex(servers.first()); PhoneLink("done", "Connected to ${servers.first().name}.") }
                            else -> PhoneLink("choose", choices = servers)
                        }
                        return@launch
                    }
                    plexLink = PhoneLink("failed", "The code expired. Tap Back and try again.")
                }
                reply(true) { put("url", plex.authUrl(deviceId, pin.code)) }
            }
            "plex-status" -> linkReply(plexLink)
            "jellyfin-qc" -> {
                activity("Jellyfin Quick Connect…")
                val server = mediaServers.findServer(v["server"].orEmpty(), emby = false)
                    ?: return reply(false, "No Jellyfin server found at that address.")
                val deviceId = store.current().deviceId
                val qc = mediaServers.startQuickConnect(server, deviceId)
                    ?: return reply(false, "Quick Connect is turned off on this server (Jellyfin Dashboard → General → Quick Connect). Use your password instead.")
                jellyfinLink = PhoneLink()
                phoneJob?.cancel()
                phoneJob = viewModelScope.launch {
                    repeat(150) {
                        delay(2_000)
                        val signedIn = mediaServers.finishQuickConnect(server, deviceId, qc) ?: return@repeat
                        store.update { it.copy(jellyfin = signedIn) }
                        activity("Jellyfin: signed in as ${signedIn.userName}")
                        jellyfinLink = PhoneLink("done", "Signed in as ${signedIn.userName} on ${signedIn.serverName}.")
                        return@launch
                    }
                    jellyfinLink = PhoneLink("failed", "The code expired. Tap Back and try again.")
                }
                reply(true) { put("code", qc.code); put("url", "${server.url}/web/#/quickconnect") }
            }
            "jellyfin-qc-status" -> linkReply(jellyfinLink)
            "plex-pick" -> {
                val choice = plexLink?.choices?.getOrNull(v["index"]?.toIntOrNull() ?: -1)
                    ?: return reply(false, "That server isn't available any more. Tap Back and try again.")
                savePlex(choice)
                plexLink = PhoneLink("done", "Connected to ${choice.name}.")
                reply(true, "Connected to ${choice.name}.")
            }
            "stremio-list" -> { val list = store.current().addons; reply(true) { addons(list) } }
            "stremio" -> {
                activity("Adding Stremio addons…")
                val urls = v["urls"].orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (urls.isEmpty()) return reply(false, "Paste an addon manifest URL.")
                val added = ArrayList<String>()
                var failed = 0
                for (url in urls) {
                    val addon = stremio.fetchAddon(url)
                    if (addon == null) failed++ else {
                        store.update { a -> a.copy(addons = a.addons.filterNot { it.baseUrl == addon.baseUrl } + addon) }
                        added += addon.name
                    }
                }
                if (added.isNotEmpty()) activity("Stremio: added ${added.joinToString()}")
                val message = listOfNotNull(
                    added.takeIf { it.isNotEmpty() }?.let { "Added ${it.joinToString()}." },
                    if (failed > 0) "Couldn't load $failed link${if (failed == 1) "" else "s"}. Check it's the manifest URL." else null,
                ).joinToString(" ")
                val list = store.current().addons
                reply(failed == 0, message) { addons(list) }
            }
            "stremio-remove" -> {
                val base = v["baseUrl"].orEmpty()
                store.update { a -> a.copy(addons = a.addons.filterNot { it.baseUrl == base }) }
                val list = store.current().addons
                reply(true) { addons(list) }
            }
            "jellyfin", "emby" -> {
                val emby = action == "emby"
                val name = if (emby) "Emby" else "Jellyfin"
                activity("Signing in to $name…")
                val server = mediaServers.findServer(v["server"].orEmpty(), emby)
                    ?: return reply(false, "No $name server found at that address.")
                val signedIn = mediaServers.signIn(server, emby, store.current().deviceId, v["username"].orEmpty().trim(), v["password"].orEmpty())
                    ?: return reply(false, "$name didn't accept that username or password.")
                store.update { if (emby) it.copy(emby = signedIn) else it.copy(jellyfin = signedIn) }
                activity("$name: signed in as ${signedIn.userName}")
                reply(true, "Signed in as ${signedIn.userName} on ${signedIn.serverName}.")
            }
            "vod-check" -> {
                val s = sessions.session.first() ?: return reply(false, "Set up Live TV first. Provider VOD comes through Dispatcharr.")
                val api = apiFactory.create(s.serverUrl, s.apiKey)
                val count = runCatching { api.vodMovies().count + api.vodSeries().count }.getOrNull()
                    ?: return reply(false, "Couldn't check your Dispatcharr server for VOD.")
                reply(true) { put("count", count) }
            }
            "vod-set" -> {
                val on = v["enabled"] == "true"
                store.update { it.copy(vodEnabled = on) }
                reply(true, if (on) "Provider VOD turned on." else "Provider VOD left off.")
            }
            "finish" -> {
                activity("Finished on your phone")
                _state.update { it.copy(phoneFinished = true) }
                reply(true)
            }
            else -> reply(false, "Unknown request.")
        }
    }

    private suspend fun savePlex(choice: PlexServerChoice) {
        store.update { it.copy(plex = plex.toAuth(choice)) }
        activity("Plex: connected to ${choice.name}")
    }

    private fun linkReply(link: PhoneLink?) = if (link == null) reply(false, "Start again from the phone.") { put("state", "failed") }
    else reply(true, link.message) {
        put("state", link.state)
        putJsonArray("choices") {
            link.choices.forEach { c -> add(buildJsonObject { put("name", c.name); put("address", c.url.substringAfter("://").substringBefore('/')) }) }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.addons(list: List<io.github.petebutty.viewarr.data.accounts.AddonInfo>) {
        putJsonArray("addons") { list.forEach { a -> add(buildJsonObject { put("name", a.name); put("baseUrl", a.baseUrl) }) } }
    }

    private fun work(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { block() }
    }

    private fun fail(message: String) = _state.update { it.copy(busy = false, error = message) }

    override fun onCleared() {
        cleanUp()
        stopPhone()
    }

    companion object {
        const val TMDB_KEY = "tmdb_key"
        const val ADDON_URLS = "addon_urls"
        const val SERVER = "server"
        const val USER = "username"
        const val PASSWORD = "password"
    }
}

/** What's connected for one item, or null. Shared by the TV screens and the phone page. */
fun statusText(item: SetupItem, a: Accounts, session: io.github.petebutty.viewarr.data.session.Session?): String? = when (item) {
    SetupItem.LiveTv -> session?.let { "Signed in as ${it.username}" }
    SetupItem.Tmdb -> a.tmdbKey?.let { "Connected" }
    SetupItem.Trakt -> a.trakt?.let { "Signed in as ${it.username}" }
    SetupItem.Plex -> a.plex?.let { "Connected to ${it.serverName}" }
    SetupItem.Stremio -> a.addons.takeIf { it.isNotEmpty() }?.let { "${it.size} addon${if (it.size == 1) "" else "s"}" }
    SetupItem.Jellyfin -> a.jellyfin?.let { "${it.userName} on ${it.serverName}" }
    SetupItem.Emby -> a.emby?.let { "${it.userName} on ${it.serverName}" }
    SetupItem.Vod -> if (a.vodEnabled) "On" else null
}
