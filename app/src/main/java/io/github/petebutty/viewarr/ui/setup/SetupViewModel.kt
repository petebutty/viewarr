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

enum class SetupStep { Phone, Welcome, Choose, Server, SignIn, Tmdb, Trakt, Plex, Stremio, Jellyfin, Emby, Vod, Seerr, Mdblist, Music, Navidrome, Done }

enum class SetupItem(val id: String, val label: String, val description: String, val steps: List<SetupStep>) {
    LiveTv("livetv", "Live TV", "Dispatcharr, an IPTV login (Xtream Codes) or an M3U playlist: channels, TV guide and catch-up (recordings with Dispatcharr)", listOf(SetupStep.Server, SetupStep.SignIn)),
    Tmdb("tmdb", "TMDB", "Posters, descriptions and discovery for Movies & Shows", listOf(SetupStep.Tmdb)),
    Trakt("trakt", "Trakt", "Watchlist, history, recommendations and continue watching", listOf(SetupStep.Trakt)),
    Plex("plex", "Plex", "Play films and shows from your Plex library", listOf(SetupStep.Plex)),
    Stremio("stremio", "Stremio addons", "Streams from addons such as AIOStreams", listOf(SetupStep.Stremio)),
    Jellyfin("jellyfin", "Jellyfin", "Play from a Jellyfin server", listOf(SetupStep.Jellyfin)),
    Emby("emby", "Emby", "Play from an Emby server", listOf(SetupStep.Emby)),
    Vod("vod", "IPTV films & series", "Films and series from your IPTV provider, through Dispatcharr (an IPTV login includes them already)", listOf(SetupStep.Vod)),
    Seerr("seerr", "Seerr", "Request films and shows to keep (Overseerr / Jellyseerr)", listOf(SetupStep.Seerr)),
    Navidrome("music", "Music", "Add a Music section: your music from Plex, Jellyfin or Emby music libraries, and/or a Navidrome server", listOf(SetupStep.Music)),
    Mdblist("mdblist", "MDBList ratings", "IMDb, Rotten Tomatoes, Metacritic and Letterboxd ratings on every title (free key)", listOf(SetupStep.Mdblist)),
}

/** Trakt needs a client id built into the app (secrets.properties); without one it isn't offered. */
val SetupItem.available: Boolean
    get() = this != SetupItem.Trakt || io.github.petebutty.viewarr.data.remote.services.TraktService.CLIENT_ID.isNotBlank()

data class SetupUiState(
    val mode: SetupMode = SetupMode.FirstRun,
    val step: SetupStep = SetupStep.Choose,
    /** The service last set up from the hub, to put focus back on it. */
    val lastItem: SetupItem? = null,
    val selected: Set<SetupItem> = setOf(SetupItem.LiveTv, SetupItem.Tmdb),
    val serverInput: String = "",
    val serverUrl: String? = null,
    val serverVersion: String? = null,
    /** No Dispatcharr at the address: treat it as an IPTV provider (Xtream Codes) login. */
    val xc: Boolean = false,
    /** Dispatcharr sign-in on the TV: API key (default) or username & password. */
    val useKey: Boolean = true,
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
    /** Machine ids of the Plex servers ticked on the chooser (all of them to start with). */
    val plexOn: Set<String> = emptySet(),
    val vodCount: Int? = null,
    /** Music step: your servers with a music library (id, name); null while looking. */
    val musicServers: List<Pair<String, String>>? = null,
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
    private val seerr: io.github.petebutty.viewarr.data.remote.services.SeerrService,
    private val subsonic: io.github.petebutty.viewarr.data.music.SubsonicClient,
    private val musicLib: io.github.petebutty.viewarr.data.music.MusicLibrary,
    private val httpJson: io.github.petebutty.viewarr.data.remote.services.HttpJson,
    private val rowLayouts: io.github.petebutty.viewarr.data.settings.RowLayoutStore,
    private val invites: io.github.petebutty.viewarr.data.profiles.Invites,
) : ViewModel() {
    /** Several Plex servers: show their repeated rows as one (also in Settings → Home page & menu). */
    val mergeRows = rowLayouts.mergeRows.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun setMergeRows(on: Boolean) { viewModelScope.launch { rowLayouts.setMergeRows(on) } }

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
        // First run opens on the hub (Accounts & sources): pick each service, or "Set up on your phone".
        go(SetupStep.Choose)
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

    /** Invited: the phone page asks only for the address and API key they were sent. */
    fun openInvited() { startPhone() }
    fun closeInvited() { stopPhone() }

    /** Invited, on the TV itself (no phone): the address and key typed with the remote. */
    fun invitedSignIn(address: String, key: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        invites.signInWithDetails(address, key).onFailure { e -> _state.update { it.copy(error = e.message) } }
        _state.update { it.copy(busy = false) }
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
        _state.update { it.copy(lastItem = item) }
        queue = item.steps
        advance()
    }

    /** Removes one service from this TV (it can be connected again any time). */
    fun disconnect(item: SetupItem) {
        _state.update { it.copy(lastItem = item) }
        viewModelScope.launch {
            when (item) {
                SetupItem.LiveTv -> auth.signOut()
                SetupItem.Tmdb -> store.update { it.copy(tmdbKey = null) }
                SetupItem.Trakt -> store.update { it.copy(trakt = null) }
                SetupItem.Plex -> store.update { it.copy(plex = null, plexMore = emptyList()) }
                SetupItem.Stremio -> store.update { it.copy(addons = emptyList()) }
                SetupItem.Jellyfin -> store.update { it.copy(jellyfin = null) }
                SetupItem.Emby -> store.update { it.copy(emby = null) }
                SetupItem.Vod -> store.update { it.copy(vodEnabled = false) }
                SetupItem.Seerr -> store.update { it.copy(seerr = null) }
                SetupItem.Mdblist -> store.update { it.copy(mdblistKey = null) }
                SetupItem.Navidrome -> { io.github.petebutty.viewarr.playback.MusicPlayer.stop(); store.update { it.copy(navidrome = null, musicEnabled = false) } }
            }
        }
    }

    fun advance() {
        val next = queue.firstOrNull()
        queue = queue.drop(1)
        go(next ?: SetupStep.Choose)
    }

    fun back() {
        when (_state.value.step) {
            SetupStep.SignIn -> go(SetupStep.Server)
            SetupStep.Choose -> Unit
            SetupStep.Phone -> go(SetupStep.Choose)
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

    /** Music step: the Music section on or off (off: nothing music-related anywhere). */
    fun setMusic(on: Boolean) = viewModelScope.launch {
        store.update { it.copy(musicEnabled = on) }
        if (!on) io.github.petebutty.viewarr.playback.MusicPlayer.stop()
    }
    /** Music step: one server's music library in use or not. */
    fun toggleMusicServer(id: String) = viewModelScope.launch {
        store.update { a -> a.copy(musicOff = if (id in a.musicOff) a.musicOff - id else a.musicOff + id) }
    }
    /** Music step: "No music" leaves it off and moves on. */
    fun skipMusic() = viewModelScope.launch { store.update { it.copy(musicEnabled = false) }; io.github.petebutty.viewarr.playback.MusicPlayer.stop(); advance() }
    fun openNavidrome() = go(SetupStep.Navidrome)
    fun backToMusic() = go(SetupStep.Music)
    fun removeNavidrome() = viewModelScope.launch { store.update { it.copy(navidrome = null, musicOff = it.musicOff - "nd") } }
    private fun findMusicServers() = viewModelScope.launch {
        _state.update { it.copy(musicServers = null) }
        val found = runCatching { musicLib.servers() }.getOrDefault(emptyList())
        _state.update { it.copy(musicServers = found) }
    }
    private fun findNavidrome() = viewModelScope.launch {
        if (!_state.value.fields[SERVER].isNullOrBlank()) return@launch
        _state.update { it.copy(info = "Looking for Navidrome on your network…") }
        val found = runCatching { subsonic.discover() }.getOrDefault(emptyList()).firstOrNull()
        if (_state.value.step != SetupStep.Navidrome || !_state.value.fields[SERVER].isNullOrBlank()) return@launch
        _state.update {
            if (found == null) it.copy(info = "Couldn't find Navidrome automatically. Type its address (usually port 4533).")
            else it.copy(fields = it.fields + (SERVER to found.removePrefix("http://")), info = "Found Navidrome at ${found.removePrefix("http://")}. Now your username and password (or scan the code).")
        }
    }

    fun onField(key: String, value: String) = _state.update { it.copy(fields = it.fields + (key to value), error = null) }
    fun onServerInput(value: String) = _state.update { it.copy(serverInput = value, error = null) }
    fun onUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    private fun go(step: SetupStep) {
        cleanUp()
        // SignIn too: its username & password QR opens the phone page.
        if (step !in setOf(SetupStep.Phone, SetupStep.SignIn, SetupStep.Stremio, SetupStep.Tmdb, SetupStep.Seerr, SetupStep.Mdblist, SetupStep.Jellyfin, SetupStep.Emby, SetupStep.Music, SetupStep.Navidrome)) stopPhone()
        _state.update {
            it.copy(step = step, busy = false, error = null, info = null, code = null, codeUrl = null, plexChoices = emptyList(), fields = emptyMap())
        }
        when (step) {
            SetupStep.Phone -> startPhone()
            SetupStep.Stremio, SetupStep.Tmdb, SetupStep.Mdblist, SetupStep.SignIn -> startPhone()
            SetupStep.Trakt -> startTrakt()
            SetupStep.Plex -> startPlex()
            SetupStep.Vod -> checkVod()
            SetupStep.Jellyfin, SetupStep.Emby -> { startPhone(); findMediaServer(step == SetupStep.Emby) }
            SetupStep.Seerr -> { startPhone(); findSeerr() }
            SetupStep.Music -> { startPhone(); findMusicServers() }
            SetupStep.Navidrome -> { startPhone(); findNavidrome() }
            SetupStep.Server -> if (_state.value.serverInput.isBlank()) viewModelScope.launch {
                _state.update { it.copy(info = "Looking for Dispatcharr on your network…") }
                val found = runCatching { auth.discover() }.getOrDefault(emptyList())
                if (_state.value.step != SetupStep.Server) return@launch
                if (found.isEmpty()) {
                    _state.update { it.copy(info = "Couldn't find Dispatcharr automatically. Type its address, or your IPTV provider's.") }
                } else if (_state.value.serverInput.isBlank()) {
                    // Found it: filled in, but you press Continue (with several Dispatcharr instances the one found may
                    // be the one you already have: change the address first).
                    val more = if (found.size > 1) " (and ${found.size - 1} more: ${found.drop(1).joinToString(", ") { it.url.removePrefix("http://") }})" else ""
                    _state.update { it.copy(serverInput = found.first().url.removePrefix("http://"), info = "Found Dispatcharr at ${found.first().url.removePrefix("http://")}$more. Press Continue, or change the address first.") }
                }
            }
            else -> Unit
        }
    }

    private fun findSeerr() {
        viewModelScope.launch {
                _state.update { it.copy(info = "Looking for Seerr on your network…") }
                val found = runCatching { seerr.discover() }.getOrDefault(emptyList()).firstOrNull()
                if (_state.value.step != SetupStep.Seerr || !_state.value.fields[SERVER].isNullOrBlank()) return@launch
                _state.update {
                    if (found == null) it.copy(info = "Couldn't find Seerr automatically. Type its address (usually port 5055).")
                    else it.copy(
                        fields = it.fields + (SERVER to found.removePrefix("http://")),
                        info = "Found Seerr at ${found.removePrefix("http://")}. Now its API key: scan the code, or in Seerr open Settings → General.",
                    )
                }
            }
    }

    /** Hosts worth trying for Jellyfin / Emby when nothing answers the broadcast: the box Dispatcharr is on. */
    private suspend fun likelyHosts(): List<String> =
        listOfNotNull(sessions.session.first()?.serverUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() })

    /** Looks for the server on the network and fills in the address; for Jellyfin, starts Quick Connect straight away. */
    private fun findMediaServer(emby: Boolean) {
        val step = if (emby) SetupStep.Emby else SetupStep.Jellyfin
        val name = if (emby) "Emby" else "Jellyfin"
        // Signed in from the phone: move on here too (the TV's own sign-in moves on by itself, so wait a moment first).
        viewModelScope.launch {
            val before = store.current().let { if (emby) it.emby else it.jellyfin }
            store.accounts.first { a -> (if (emby) a.emby else a.jellyfin).let { it != null && it != before } }
            delay(1_500)
            if (_state.value.step == step) { cleanUp(); advance() }
        }
        viewModelScope.launch {
            _state.update { it.copy(info = "Looking for $name on your network…") }
            val found = runCatching { mediaServers.discover(emby, likelyHosts()) }.getOrDefault(emptyList())
            if (_state.value.step != step || !_state.value.fields[SERVER].isNullOrBlank()) return@launch
            val server = found.firstOrNull()
            if (server == null) {
                _state.update { it.copy(info = "Couldn't find $name automatically. Type its address, or scan the code to do it on your phone.") }
                return@launch
            }
            val address = server.url.removePrefix("http://")
            _state.update { it.copy(fields = it.fields + (SERVER to address), info = "Found ${server.name} at $address.") }
            if (!emby) startQuickConnect()
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
            SetupStep.Mdblist -> work {
                val key = s.fields[MDBLIST_KEY].orEmpty().trim()
                if (key.isEmpty()) return@work fail("Enter your MDBList API key.")
                if (!mdblistValid(key)) return@work fail("MDBList didn't accept that key. Check it and try again.")
                store.update { it.copy(mdblistKey = key) }
                advance()
            }
            SetupStep.Navidrome -> work {
                subsonic.connect(s.fields[SERVER].orEmpty(), s.fields[USER].orEmpty(), s.fields[PASSWORD].orEmpty()).fold(
                    onSuccess = { auth -> store.update { it.copy(navidrome = auth, musicEnabled = true) }; go(SetupStep.Music) },
                    onFailure = { e -> fail(e.message ?: "Couldn't connect to Navidrome.") },
                )
            }
            SetupStep.Seerr -> work {
                seerr.connect(s.fields[SERVER].orEmpty(), s.fields[API_KEY].orEmpty()).fold(
                    onSuccess = { auth -> store.update { it.copy(seerr = auth) }; advance() },
                    onFailure = { e -> fail(e.message ?: "Couldn't connect to Seerr.") },
                )
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
            _state.update { it.copy(busy = false, code = qc.code, info = "No password needed: in the Jellyfin app (phone or browser), open your profile → Quick Connect and enter this code. Or sign in with your username and password.") }
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
            // A "strong" PIN: the QR opens Plex's own sign-in page on the phone, and signing in there links this TV.
            val pin = plex.createPin(deviceId, strong = true) ?: return@launch fail("Couldn't reach plex.tv. Check your connection.")
            _state.update { it.copy(busy = false, code = null, codeUrl = plex.authUrl(deviceId, pin.code), info = null) }
            repeat(150) {
                delay(2_000)
                val token = plex.checkPin(deviceId, pin.id) ?: return@repeat
                _state.update { it.copy(code = null, busy = true, info = "Linked. Finding your Plex server…") }
                val servers = plex.servers(deviceId, token)
                when (servers.size) {
                    0 -> fail("No Plex server could be reached from this TV.")
                    1 -> pickPlex(servers.first())
                    else -> _state.update { it.copy(busy = false, plexChoices = servers, plexOn = servers.map { s -> s.machineId }.toSet(), info = "You have ${servers.size} Plex servers. Untick any you don't want, then Continue.") }
                }
                return@launch
            }
            fail("The code expired. Go back and try again.")
        }
    }

    fun pickPlex(choice: PlexServerChoice) = savePlexChoice(listOf(choice), listOf(choice))

    fun togglePlexServer(machineId: String) = _state.update {
        it.copy(plexOn = if (machineId in it.plexOn) it.plexOn - machineId else it.plexOn + machineId, error = null)
    }

    fun confirmPlex() {
        val s = _state.value
        val on = s.plexChoices.filter { it.machineId in s.plexOn }
        if (on.isEmpty()) { _state.update { it.copy(error = "Tick at least one server.") }; return }
        savePlexChoice(s.plexChoices, on)
    }

    private fun savePlexChoice(all: List<PlexServerChoice>, on: List<PlexServerChoice>) {
        viewModelScope.launch {
            savePlex(all, on)
            _state.update { it.copy(busy = false, plexChoices = emptyList(), plexOn = emptySet(), info = "Connected to ${on.joinToString { c -> c.name }}.") }
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
        // A playlist link (M3U, or a provider's get.php link): signs in with it straight away, nothing else to ask.
        val typed = _state.value.serverInput.trim()
        if (auth.looksLikePlaylist(typed)) {
            auth.signInM3u(typed, null)
                .onSuccess { queue = queue.toMutableList().apply { remove(SetupStep.SignIn) }; _state.update { it.copy(busy = false) }; advance() }
                .onFailure { e -> fail(e.message ?: "Couldn't load that playlist.") }
            return@work
        }
        when (val result = auth.probe(_state.value.serverInput)) {
            is ProbeResult.Found -> {
                _state.update { it.copy(busy = false, serverUrl = result.url, serverVersion = result.version, xc = false) }
                // Sign-in is the next queued step: take it off the queue, or a successful sign-in shows it again (empty).
                queue = queue.toMutableList().apply { remove(SetupStep.SignIn) }
                go(SetupStep.SignIn)
                _state.update { it.copy(serverUrl = result.url, serverVersion = result.version) }
            }
            // Not Dispatcharr: it may be an IPTV provider's server. The provider login is checked at sign-in.
            is ProbeResult.Failed -> {
                val raw = _state.value.serverInput.trim()
                if (raw.isEmpty()) return@work fail(result.message)
                _state.update { it.copy(busy = false, serverUrl = raw, serverVersion = null, xc = true) }
                // Sign-in is the next queued step: take it off the queue, or a successful sign-in shows it again (empty).
                queue = queue.toMutableList().apply { remove(SetupStep.SignIn) }
                go(SetupStep.SignIn)
                _state.update { it.copy(serverUrl = raw, serverVersion = null, xc = true) }
            }
        }
    }

    /** MDBList keys are checked against the account endpoint. */
    private suspend fun mdblistValid(key: String): Boolean = runCatching {
        httpJson.get("https://api.mdblist.com/user?apikey=" + java.net.URLEncoder.encode(key, "UTF-8")).ok
    }.getOrDefault(false)

    fun toggleKey() = _state.update { it.copy(useKey = !it.useKey, error = null) }

    private fun signIn() {
        val s = _state.value
        if (s.serverUrl == null) return
        if (!s.xc && s.useKey) {
            val key = s.fields[API_KEY].orEmpty().trim()
            if (key.isEmpty()) { _state.update { it.copy(error = "Enter your API key.") }; return }
            work {
                auth.signInWithKey(s.serverUrl, key)
                    .onSuccess { advance() }
                    .onFailure { e -> fail(e.message ?: "Sign-in failed.") }
            }
            return
        }
        if (s.username.isBlank() || s.password.isEmpty()) {
            _state.update { it.copy(error = "Enter your username and password.") }
            return
        }
        work {
            (if (s.xc) auth.signInXc(s.serverUrl, s.username, s.password) else auth.signIn(s.serverUrl, s.username, s.password))
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
            "music-status" -> {
                val a = store.current()
                val servers = runCatching { musicLib.servers() }.getOrDefault(emptyList())
                reply(true) {
                    put("enabled", a.musicOn)
                    put("navidrome", a.navidrome?.let { "${it.user} at ${it.url.removePrefix("http://")}" } ?: "")
                    putJsonArray("servers") { servers.forEach { (id, n) -> add(buildJsonObject { put("id", id); put("name", n); put("on", id !in a.musicOff) }) } }
                }
            }
            "music-set" -> {
                val on = v["enabled"] == "1"
                val off = v["off"].orEmpty().split(',').filter { it.isNotBlank() }.toSet()
                // The phone lists only the servers; Navidrome's own switch (in the Music menu) is kept as it was.
                store.update { it.copy(musicEnabled = on, musicOff = off + it.musicOff.filter { id -> id == "nd" }) }
                if (!on) io.github.petebutty.viewarr.playback.MusicPlayer.stop()
                activity(if (on) "Music turned on" else "Music turned off")
                reply(true, if (on) "Music is on." else "Music is off.")
            }
            "navidrome-discover" -> {
                val found = runCatching { subsonic.discover() }.getOrDefault(emptyList())
                reply(true) { putJsonArray("servers") { found.forEach { add(JsonPrimitive(it)) } } }
            }
            "navidrome" -> {
                activity("Connecting Navidrome…")
                subsonic.connect(v["server"].orEmpty(), v["user"].orEmpty(), v["password"].orEmpty()).fold(
                    onSuccess = { auth -> store.update { it.copy(navidrome = auth, musicEnabled = true) }; activity("Navidrome connected"); reply(true, "Navidrome connected.") },
                    onFailure = { e -> activity("Navidrome didn't connect"); reply(false, e.message ?: "Couldn't connect to Navidrome.") },
                )
            }
            "seerr-discover" -> {
                val found = runCatching { seerr.discover() }.getOrDefault(emptyList())
                reply(true) { putJsonArray("servers") { found.forEach { add(JsonPrimitive(it)) } } }
            }
            "media-discover" -> {
                val emby = v["kind"] == "emby"
                val found = runCatching { mediaServers.discover(emby, likelyHosts()) }.getOrDefault(emptyList())
                reply(true) {
                    putJsonArray("servers") { found.forEach { f -> add(buildJsonObject { put("url", f.url); put("name", f.name) }) } }
                }
            }
            "discover" -> {
                activity("Looking for Dispatcharr on your network…")
                val found = auth.discover()
                activity(if (found.isEmpty()) "No Dispatcharr found automatically" else "Found Dispatcharr")
                reply(true) {
                    putJsonArray("servers") {
                        found.forEach { f -> add(buildJsonObject { put("url", f.url); put("version", f.version) }) }
                    }
                }
            }
            "livetv-m3u" -> {
                activity("Loading your playlist…")
                auth.signInM3u(v["playlist"].orEmpty(), v["guide"]).fold(
                    onSuccess = { s -> activity(if (s.isM3u) "Live TV: playlist loaded" else "Live TV: signed in to your provider as ${s.username}"); reply(true, if (s.isM3u) "Playlist loaded." else "That's an Xtream Codes login: signed in to your provider as ${s.username}.") },
                    onFailure = { e -> activity("Playlist didn't load"); reply(false, e.message ?: "Couldn't load that playlist.") },
                )
            }
            // Invited: the server's address and the API key their admin sent (their setup then goes on by itself).
            "invited" -> {
                activity("Signing in…")
                invites.signInWithDetails(v["server"].orEmpty(), v["apiKey"].orEmpty()).fold(
                    onSuccess = { s -> activity("Signed in as ${s.username}"); reply(true, "Signed in as ${s.username}.") },
                    onFailure = { e -> activity("Sign-in didn't work"); reply(false, e.message ?: "Sign-in didn't work.") },
                )
            }
            // Someone else's server: the invite they were sent, and their password.
            "livetv-invite" -> {
                activity("Opening your invite…")
                invites.redeem(v["invite"].orEmpty(), v["password"].orEmpty()).fold(
                    onSuccess = { s -> activity("Live TV: signed in as ${s.username}"); reply(true, "Signed in as ${s.username}.") },
                    onFailure = { e -> activity("The invite didn't work"); reply(false, e.message ?: "The invite didn't work.") },
                )
            }
            "livetv-xc" -> {
                activity("Signing in to your IPTV provider…")
                auth.signInXc(v["server"].orEmpty(), v["username"].orEmpty(), v["password"].orEmpty()).fold(
                    onSuccess = { s -> activity("Live TV: signed in to your provider as ${s.username}"); reply(true, "Signed in to your provider as ${s.username}.") },
                    onFailure = { e -> activity("Provider sign-in failed"); reply(false, e.message ?: "Sign-in failed.") },
                )
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
            "mdblist" -> {
                activity("Checking the MDBList key…")
                val key = v["key"].orEmpty().trim()
                when {
                    key.isEmpty() -> reply(false, "Paste your MDBList API key.")
                    !mdblistValid(key) -> reply(false, "MDBList didn't accept that key. Check it and try again.")
                    else -> { store.update { it.copy(mdblistKey = key) }; activity("MDBList connected"); reply(true, "MDBList connected.") }
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
            "seerr" -> {
                activity("Connecting Seerr…")
                seerr.connect(v["server"].orEmpty(), v["key"].orEmpty()).fold(
                    onSuccess = { auth -> store.update { it.copy(seerr = auth) }; activity("Seerr connected"); reply(true, "Seerr connected.") },
                    onFailure = { e -> activity("Seerr didn't connect"); reply(false, e.message ?: "Couldn't connect to Seerr.") },
                )
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
                            1 -> { savePlex(servers, servers); PhoneLink("done", "Connected to ${servers.first().name}.") }
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
                val all = plexLink?.choices.orEmpty()
                val on = (v["indices"] ?: v["index"]).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull()?.let(all::getOrNull) }.distinct()
                if (on.isEmpty()) return reply(false, if (all.isEmpty()) "That server isn't available any more. Tap Back and try again." else "Tick at least one server.")
                savePlex(all, on)
                val names = on.joinToString { it.name }
                plexLink = PhoneLink("done", "Connected to $names.")
                reply(true, "Connected to $names.")
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

    /** The first ticked server is the main one; the rest are kept too, with unticked ones switched off (as in Settings). */
    private suspend fun savePlex(all: List<PlexServerChoice>, on: List<PlexServerChoice>) {
        val main = on.first()
        val onIds = on.map { it.machineId }.toSet()
        store.update { a ->
            // Servers this run didn't list (offline just now, or only one offered) stay as they were.
            val listed = all.map { it.machineId }.toSet()
            val kept = a.plexMore.filter { it.machineId !in listed && it.machineId != main.machineId }
            a.copy(
                plex = plex.toAuth(main),
                plexMore = all.filter { it.machineId != main.machineId }.map(plex::toAuth) + kept,
                plexOff = a.plexOff - all.map { it.machineId }.toSet() + all.map { it.machineId }.filter { it !in onIds },
            )
        }
        activity("Plex: connected to ${on.joinToString { it.name }}")
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
        const val MDBLIST_KEY = "mdblist_key"
        const val ADDON_URLS = "addon_urls"
        const val SERVER = "server"
        const val USER = "username"
        const val PASSWORD = "password"
        const val API_KEY = "api_key"
    }
}

/** What's connected for one item, or null. Shared by the TV screens and the phone page. */
fun statusText(item: SetupItem, a: Accounts, session: io.github.petebutty.viewarr.data.session.Session?): String? = when (item) {
    SetupItem.LiveTv -> session?.let { when { it.isM3u -> "M3U playlist (${it.username})"; it.isXc -> "IPTV login: ${it.username}"; else -> "Dispatcharr: ${it.username}" } }
    SetupItem.Tmdb -> a.tmdbKey?.let { "Connected" }
    SetupItem.Trakt -> a.trakt?.let { "Signed in as ${it.username}" }
    SetupItem.Plex -> a.plex?.let { "Connected to ${it.serverName}" + if (a.plexMore.isNotEmpty()) " + ${a.plexMore.joinToString { m -> m.serverName }}" else "" }
    SetupItem.Stremio -> a.addons.takeIf { it.isNotEmpty() }?.let { "${it.size} addon${if (it.size == 1) "" else "s"}" }
    SetupItem.Jellyfin -> a.jellyfin?.let { "${it.userName} on ${it.serverName}" }
    SetupItem.Emby -> a.emby?.let { "${it.userName} on ${it.serverName}" }
    SetupItem.Vod -> if (a.vodEnabled) "On" else null
    SetupItem.Mdblist -> a.mdblistKey?.let { "Connected" }
    SetupItem.Navidrome -> if (!a.musicOn) null else "On" + (a.navidrome?.let { " · Navidrome (${it.user})" } ?: "")
    SetupItem.Seerr -> a.seerr?.let { s -> s.userName?.let { "Signed in as $it" } ?: "Connected" }
}
