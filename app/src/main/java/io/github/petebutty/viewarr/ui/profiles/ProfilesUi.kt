package io.github.petebutty.viewarr.ui.profiles

import io.github.petebutty.viewarr.ui.common.instantFocusScale
import io.github.petebutty.viewarr.ui.common.stopAt
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.petebutty.viewarr.data.profiles.DChannelProfile
import io.github.petebutty.viewarr.data.profiles.DUser
import io.github.petebutty.viewarr.data.profiles.DispatcharrUsers
import io.github.petebutty.viewarr.data.profiles.Profile
import io.github.petebutty.viewarr.data.profiles.ProfileManager
import io.github.petebutty.viewarr.data.profiles.ProfileStore
import io.github.petebutty.viewarr.data.profiles.SharedService
import io.github.petebutty.viewarr.data.profiles.only
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.data.sync.SettingsSync
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Which profile screen is up (opened from Settings or on start). */
object ProfileUi {
    var picker by mutableStateOf(false)
    var users by mutableStateOf(false)
    /** Settings → Profile PIN. */
    var pin by mutableStateOf(false)
    /** "Who's watching?" has been answered since Viewarr opened. */
    var chosen = false
}

/** Picker colours. */
private val COLOURS = listOf(
    Color(0xFFE5484D), Color(0xFF3E63DD), Color(0xFF30A46C), Color(0xFFF76B15),
    Color(0xFF8E4EC6), Color(0xFF12A594), Color(0xFFD6409F), Color(0xFFFFC53D),
)

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val manager: ProfileManager,
    private val store: ProfileStore,
    private val sessions: SessionStore,
    private val users: DispatcharrUsers,
    private val sync: SettingsSync,
    private val json: Json,
    private val invites: io.github.petebutty.viewarr.data.profiles.Invites,
    private val accountStore: io.github.petebutty.viewarr.data.accounts.AccountStore,
    private val subsonic: io.github.petebutty.viewarr.data.music.SubsonicClient,
) : ViewModel() {
    val profiles: StateFlow<List<Profile>> = manager.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeId: StateFlow<String?> = manager.activeId.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val askOnStart: StateFlow<Boolean> = store.askOnStart.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The signed-in account is a Dispatcharr admin: the Users page is shown (never to anyone else). */
    private val _admin = MutableStateFlow(false)
    val admin: StateFlow<Boolean> = _admin
    private val _list = MutableStateFlow<List<DUser>?>(null)
    val list: StateFlow<List<DUser>?> = _list
    private val _channelProfiles = MutableStateFlow<List<DChannelProfile>>(emptyList())
    val channelProfiles: StateFlow<List<DChannelProfile>> = _channelProfiles
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    init {
        // Whoever is signed in is the active profile (first run, a new sign-in, or a switch).
        viewModelScope.launch {
            sessions.session.distinctUntilChanged().collect { manager.adoptCurrent(); checkAdmin() }
        }
    }

    /** Dispatcharr sign-in (profiles are Dispatcharr users; XC and M3U have none). */
    val hasProfiles: StateFlow<Boolean> = sessions.session.map { it != null && !it.isDirect }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private suspend fun me(): Session? = sessions.session.first()?.takeIf { !it.isDirect }

    fun checkAdmin() = viewModelScope.launch { _admin.value = me()?.let { users.myLevel(it) } == 10 }
    fun chooseLater() { ProfileUi.chosen = true }

    fun switchTo(p: Profile, done: () -> Unit) = viewModelScope.launch {
        _busy.value = true
        manager.switchTo(p)
        _busy.value = false
        checkAdmin()
        done()
    }

    fun setAskOnStart(on: Boolean) = viewModelScope.launch { store.setAskOnStart(on) }
    fun removeFromTv(p: Profile) = viewModelScope.launch { manager.remove(p.id) }

    // ---- Admin: Dispatcharr users

    fun loadUsers() = viewModelScope.launch {
        val s = me() ?: return@launch
        if (users.myLevel(s) != 10) { _admin.value = false; return@launch }
        _list.value = runCatching { users.users(s) }.onFailure { _message.value = it.message }.getOrNull()
        _channelProfiles.value = users.channelProfiles(s)
    }

    private fun work(block: suspend (Session) -> String?) = viewModelScope.launch {
        val s = me() ?: return@launch
        _busy.value = true
        _message.value = runCatching { block(s) }.getOrElse { it.message ?: "That didn't work." }
        _busy.value = false
        loadUsers()
    }

    fun create(username: String, password: String, then: (DUser) -> Unit) = work { s ->
        users.create(s, username, password).fold(onSuccess = { then(it); "Created ${it.username}." }, onFailure = { it.message })
    }
    fun resetPassword(u: DUser, password: String) = work { s ->
        if (password.length < 4) "Use at least 4 characters." else users.update(s, u.id, password = password).fold({ "New password set for ${u.username}." }, { it.message })
    }
    fun setLimits(u: DUser, streamLimit: Int, profiles: List<Int>) = work { s ->
        users.update(s, u.id, streamLimit = streamLimit, channelProfiles = profiles).fold({ "Saved ${u.username}'s live TV." }, { it.message })
    }
    fun delete(u: DUser) = work { s ->
        if (u.username == s.username) "You can't delete the account you're signed in with."
        else if (users.delete(s, u.id)) { manager.remove(s.serverUrl.trimEnd('/') + "|" + u.username); "Deleted ${u.username}." } else "Dispatcharr wouldn't delete ${u.username}."
    }
    /** Adds the user to this TV's profiles (their own key: their limits and their saved setup apply). */
    fun addToTv(u: DUser) = work { s ->
        val key = users.apiKeyFor(s, u) ?: return@work "Dispatcharr wouldn't give a key for ${u.username}."
        manager.add(Profile(s.serverUrl, u.username, key))
        "${u.username} is on this TV now. Switch to them from Who's watching."
    }
    /**
     * Sets up the user's Viewarr: this TV's look and settings, and the services ticked (never Trakt), saved to
     * their Dispatcharr copy, so any TV they sign in on (here or away) restores it.
     */
    fun prepare(u: DUser, keep: Set<SharedService>) = work { s ->
        val key = users.apiKeyFor(s, u) ?: return@work "Dispatcharr wouldn't give a key for ${u.username}."
        if (saveSetupFor(s, u, key, keep)) "Set up ${u.username}'s Viewarr: ${keep.joinToString { it.label }.ifEmpty { "live TV only" }}."
        else "Dispatcharr wouldn't save ${u.username}'s setup (needs Dispatcharr 0.33 or newer)."
    }

    /** Their setup (this TV's look and settings, and the services in [keep]) saved on Dispatcharr and on this TV. */
    private suspend fun saveSetupFor(s: Session, u: DUser, key: String, keep: Set<SharedService>, music: io.github.petebutty.viewarr.data.music.NavidromeAuth? = null): Boolean {
        val target = Session(s.serverUrl, u.username, key)
        // Music: their own Navidrome login (never this TV's).
        val accounts = sync.currentAccounts().only(keep - SharedService.Music).let { a ->
            if (music != null) a.copy(navidrome = music, musicEnabled = true) else a
        }
        val signIns = json.encodeToString(io.github.petebutty.viewarr.data.accounts.Accounts.serializer(), accounts)
        // Their copy on this TV is replaced too, so switching to them here picks it up.
        store.saveSnapshot(target.let { Profile(it.serverUrl, it.username, it.apiKey).id }, kotlinx.serialization.json.buildJsonObject {
            put("prefs", sync.currentPrefs()); put("accounts", kotlinx.serialization.json.JsonPrimitive(signIns)); put("setupComplete", kotlinx.serialization.json.JsonPrimitive(true))
        })
        return sync.writeFor(s, u.id, target, sync.currentPrefs(), signIns)
    }

    /** What [u] may do (Viewarr's locks, and Dispatcharr's recording / catch-up / VOD switches). */
    fun setPerms(u: DUser, p: io.github.petebutty.viewarr.data.profiles.Perms) = work { s ->
        users.update(s, u.id, props = p.toProps()).fold({ "Saved what ${u.username} can do." }, { it.message })
    }

    /** Dispatcharr's outside address, if set (needed to invite someone in another house). */
    suspend fun dispatcharrAway(): String? {
        val s = me() ?: return null
        val home = io.github.petebutty.viewarr.data.net.Reach.normalise(s.serverUrl, away = false)
        return accountStore.current().away.entries.firstOrNull { io.github.petebutty.viewarr.data.net.Reach.normalise(it.key, false) == home }?.value
    }

    /** Sign-in details to hand someone in another house: (who, address, API key). */
    data class Share(val who: String, val address: String, val key: String)
    private val _share = MutableStateFlow<Share?>(null)
    val share: StateFlow<Share?> = _share
    fun clearShare() { _share.value = null }

    /** The address a TV outside the home uses for Dispatcharr (its away address), else this TV's. */
    private suspend fun outsideAddress(s: Session) = (dispatcharrAway() ?: s.serverUrl).removePrefix("https://").removeSuffix(":443").trimEnd('/')

    /** [u]'s sign-in details for another house (their existing key: making a new one would lock out their setup). */
    fun shareFor(u: DUser) = work { s ->
        val key = users.apiKeyFor(s, u) ?: return@work "Dispatcharr wouldn't give a key for ${u.username}."
        _share.value = Share(u.username, outsideAddress(s), key); null
    }

    private val _inviteFor = MutableStateFlow<String?>(null)
    /** Whose invite [invite] is. */
    val inviteFor: StateFlow<String?> = _inviteFor

    /**
     * Add someone, all in one go: their Dispatcharr user, what they may do, their setup (the services ticked), then
     * on this TV (a profile) or in another house (an invite, made with the password just set: no typing it again).
     */
    fun addSomeone(name: String, password: String, away: Boolean, awayAddress: String, keep: Set<SharedService>, perms: io.github.petebutty.viewarr.data.profiles.Perms, music: MusicChoice = MusicChoice.None) = work { s ->
        if (name.isBlank() || password.length < 4) return@work "Enter a name, and a password of at least 4 characters."
        // Their Navidrome login first: if that's wrong, nothing is made yet.
        val nd = navidromeFor(name.trim(), password, music).getOrElse { return@work it.message }
        if (away && dispatcharrAway() == null) {
            val a = io.github.petebutty.viewarr.data.net.Reach.normalise(awayAddress, away = true) ?: return@work "Enter your Dispatcharr's outside address (e.g. tv.example.com)."
            val home = io.github.petebutty.viewarr.data.net.Reach.normalise(s.serverUrl, away = false) ?: return@work "This TV's Dispatcharr address isn't usable."
            accountStore.update { it.copy(away = it.away + (home to a)) }
        }
        val u = users.create(s, name.trim(), password).getOrElse { return@work it.message }
        users.update(s, u.id, props = perms.toProps())
        val key = users.apiKeyFor(s, u) ?: return@work "Added ${u.username}, but Dispatcharr wouldn't give a key for them."
        saveSetupFor(s, u, key, keep, nd)
        if (!away) {
            manager.add(Profile(s.serverUrl, u.username, key))
            "${u.username} is ready: pick them on Who's watching."
        } else {
            // In another house: they sign in with the address and their key (their setup goes on by itself).
            _share.value = Share(u.username, outsideAddress(s), key); null
        }
    }

    // ---- Music: each person's own Navidrome login

    /** This TV has Navidrome (so music can be given). */
    suspend fun hasNavidrome() = accountStore.current().navidrome != null

    /** Their Navidrome login: one they have already, or a new one (same name and password as their Viewarr). */
    private suspend fun navidromeFor(name: String, password: String, m: MusicChoice): Result<io.github.petebutty.viewarr.data.music.NavidromeAuth?> {
        val mine = accountStore.current().navidrome ?: return Result.success(null)
        return when (m) {
            MusicChoice.None -> Result.success(null)
            is MusicChoice.Existing -> subsonic.connect(mine.url, m.user, m.password).map { it }
            is MusicChoice.Create -> subsonic.createUser(mine.url, mine.user, m.adminPassword, name, password)
                .fold(onSuccess = { subsonic.connect(mine.url, name, password).map { it } }, onFailure = { Result.failure(it) })
        }
    }

    /** Give [u] music (their own Navidrome login) or take it away, in the setup saved for them. */
    fun setMusic(u: DUser, password: String, m: MusicChoice) = work { s ->
        val nd = navidromeFor(u.username, password, m).getOrElse { return@work it.message }
        val key = users.apiKeyFor(s, u) ?: return@work "Dispatcharr wouldn't give a key for ${u.username}."
        val target = Session(s.serverUrl, u.username, key)
        // Their saved setup, with only the music changed (or a fresh one if they have none yet).
        val snap = sync.savedCopy(target)?.let { sync.snapshotOf(it, target) }
        val prefs = (snap?.get("prefs") as? kotlinx.serialization.json.JsonObject) ?: sync.currentPrefs()
        val current = (snap?.get("accounts") as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.let { runCatching { json.decodeFromString(io.github.petebutty.viewarr.data.accounts.Accounts.serializer(), it) }.getOrNull() }
            ?: sync.currentAccounts().only(emptySet())
        val updated = if (nd != null) current.copy(navidrome = nd, musicEnabled = true) else current.copy(navidrome = null)
        val signIns = json.encodeToString(io.github.petebutty.viewarr.data.accounts.Accounts.serializer(), updated)
        store.saveSnapshot(Profile(target.serverUrl, target.username, target.apiKey).id, kotlinx.serialization.json.buildJsonObject {
            put("prefs", prefs); put("accounts", kotlinx.serialization.json.JsonPrimitive(signIns)); put("setupComplete", kotlinx.serialization.json.JsonPrimitive(true))
        })
        if (!sync.writeFor(s, u.id, target, prefs, signIns)) return@work "Dispatcharr wouldn't save ${u.username}'s setup."
        if (nd != null) "${u.username} has music now, as ${nd.user} on Navidrome." else "Music taken away from ${u.username}."
    }

    // ---- Profile PIN (asked for when switching to that profile)

    fun setPin(pin: String?) = viewModelScope.launch {
        val id = activeId.value ?: return@launch
        store.setPin(id, pin)
        _message.value = if (pin.isNullOrBlank()) "PIN removed." else "PIN set: it's asked for when anyone switches to you."
    }
    /** This TV's servers by their home address, with the away address set for each (if any). */
    suspend fun homes(): List<Triple<String, String, String>> {
        val a = accountStore.current()
        val s = me()
        val list = listOfNotNull(
            s?.let { "Live TV (Dispatcharr)" to it.serverUrl },
            a.navidrome?.let { "Navidrome" to it.url },
            a.seerr?.let { "Seerr" to it.url },
            a.plex?.let { "Plex (only if Plex's own remote access fails)" to it.serverUrl },
            a.jellyfin?.let { "Jellyfin" to it.serverUrl },
            a.emby?.let { "Emby" to it.serverUrl },
        )
        return list.mapNotNull { (label, url) ->
            val home = io.github.petebutty.viewarr.data.net.Reach.normalise(url, away = false) ?: return@mapNotNull null
            // Already a public address (https, not a private one): nothing to set.
            if (url.startsWith("https://")) return@mapNotNull null
            Triple(label, home, a.away.entries.firstOrNull { io.github.petebutty.viewarr.data.net.Reach.normalise(it.key, false) == home }?.value.orEmpty())
        }.distinctBy { it.second }
    }

    fun saveAway(values: Map<String, String>) = viewModelScope.launch {
        val clean = values.mapNotNull { (h, a) -> io.github.petebutty.viewarr.data.net.Reach.normalise(a, away = true)?.let { h to it } }.toMap()
        accountStore.update { it.copy(away = clean) }
        _message.value = if (clean.isEmpty()) "Away addresses cleared." else "Saved ${clean.size} away address${if (clean.size == 1) "" else "es"}."
    }

    private val _invite = MutableStateFlow<String?>(null)
    /** The invite just made (shown as a QR and text); cleared when the dialog closes. */
    val invite: StateFlow<String?> = _invite
    fun makeInvite(u: DUser, password: String) = viewModelScope.launch {
        val s = me() ?: return@launch
        _busy.value = true
        invites.create(s, u, password).fold(onSuccess = { _invite.value = it; _message.value = null }, onFailure = { _message.value = it.message })
        _busy.value = false
    }
    fun clearInvite() { _invite.value = null; _inviteFor.value = null }

    suspend fun sharable() = SharedService.entries.filter { it.has(sync.currentAccounts()) }
    fun clearMessage() { _message.value = null }
}

/** Keeps asking for focus until the screen has it (its first row can appear a moment late), for up to 3 s. */
private suspend fun FocusRequester.grab(has: () -> Boolean) {
    repeat(40) {
        awaitFrame()
        if (has()) return
        runCatching { requestFocus() }
        kotlinx.coroutines.delay(60)
        if (has()) return
    }
}

/** "Who's watching?": one circle per person on this TV. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WhoIsWatching(onDone: () -> Unit, vm: ProfilesViewModel = hiltViewModel()) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.activeId.collectAsStateWithLifecycle()
    val ask by vm.askOnStart.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    // A profile with a PIN: asked for before switching to it.
    var askPin by remember { mutableStateOf<Profile?>(null) }
    LaunchedEffect(profiles.size, askPin == null) { if (askPin == null) first.grab { inside } }
    BackHandler(enabled = askPin == null) { onDone() }
    Box(
        Modifier.fillMaxSize().background(AppColors.Background).onFocusChanged { inside = it.hasFocus }.focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (busy) "Switching…" else "Who's watching?", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                profiles.forEachIndexed { i, p ->
                    val on = p.id == active
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            onClick = { if (!busy) { if (p.pin != null && !on) askPin = p else vm.switchTo(p) { onDone() } } },
                            modifier = (if (on || (active == null && i == 0)) Modifier.focusRequester(first) else Modifier).size(120.dp).instantFocusScale(1.1f),
                            shape = ClickableSurfaceDefaults.shape(CircleShape),
                            scale = io.github.petebutty.viewarr.ui.common.focusScale(1.1f),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(4.dp, Color.White), shape = CircleShape),
                            ),
                            colors = ClickableSurfaceDefaults.colors(containerColor = COLOURS[p.color % COLOURS.size], focusedContainerColor = COLOURS[p.color % COLOURS.size]),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(p.initial.toString(), fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                        Text(p.username, fontSize = 18.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 12.dp))
                        if (on) Text("Current", fontSize = 12.sp, color = AppColors.Accent)
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            MenuItem(text = "Ask each time Viewarr opens", trailing = if (ask) "On" else "Off", selected = false, onClick = { vm.setAskOnStart(!ask) }, modifier = Modifier.width(420.dp))
        }
        askPin?.let { p -> PinEntry(p, onCancel = { askPin = null }) { askPin = null; vm.switchTo(p) { onDone() } } }
    }
}

/** The PIN for [p]; [onOk] once it's right. */
@Composable
private fun PinEntry(p: Profile, onCancel: () -> Unit, onOk: () -> Unit) = DialogBox("PIN for ${p.username}", onCancel) { first ->
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    io.github.petebutty.viewarr.ui.setup.WizardField(pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, "PIN", first, KeyboardType.NumberPassword, ImeAction.Done, { if (pin == p.pin) onOk() else wrong = true }, password = true)
    if (wrong) Text("That's not the PIN.", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp, start = 12.dp))
    Spacer(Modifier.height(10.dp))
    MenuItem(text = "OK", selected = false, onClick = { if (pin == p.pin) onOk() else wrong = true })
    MenuItem(text = "Cancel", selected = false, onClick = onCancel)
}

/** Settings → Profile PIN: set or remove the PIN for the profile in use. */
@Composable
private fun PinSetDialog(vm: ProfilesViewModel, onClose: () -> Unit) = DialogBox("Profile PIN", onClose) { first ->
    var pin by remember { mutableStateOf("") }
    Text("Optional: asked for when someone switches to your profile on this TV (4–8 digits). Handy on a TV shared with others in your house.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 10.dp))
    io.github.petebutty.viewarr.ui.setup.WizardField(pin, { pin = it.filter(Char::isDigit).take(8) }, "New PIN", first, KeyboardType.NumberPassword, ImeAction.Done, {}, password = true)
    Spacer(Modifier.height(10.dp))
    MenuItem(text = "Save", selected = false, onClick = { if (pin.length >= 4) { vm.setPin(pin); onClose() } })
    MenuItem(text = "Remove PIN", selected = false, onClick = { vm.setPin(null); onClose() })
    MenuItem(text = "Cancel", selected = false, onClick = onClose)
}

/** Settings → Users & profiles: this TV's profiles, and (admins only) the Dispatcharr users. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun UsersPage(onClose: () -> Unit, vm: ProfilesViewModel = hiltViewModel()) {
    val admin by vm.admin.collectAsStateWithLifecycle()
    val list by vm.list.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.activeId.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf<DUser?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var askPin by remember { mutableStateOf<Profile?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(admin) { if (admin) vm.loadUsers() }
    // Admins only: anyone else who reaches it (signed out of admin meanwhile) is sent back.
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(1_500); checked = true }
    LaunchedEffect(admin, checked) { if (checked && !admin) onClose() }
    var inside by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.checkAdmin() }
    // On opening, and back from a dialog: focus onto the page (never left on Home underneath).
    LaunchedEffect(dialog == null) { if (dialog == null) first.grab { inside } }
    BackHandler(enabled = dialog == null) { onClose() }
    Box(Modifier.fillMaxSize().background(AppColors.Background).onFocusChanged { inside = it.hasFocus }.focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()) {
        Row(Modifier.fillMaxSize().padding(start = 56.dp, top = 40.dp, end = 56.dp)) {
            // Down past the last profile stays put (it used to jump across to Add someone).
            Column(Modifier.width(380.dp).stopAt(androidx.compose.ui.focus.FocusDirection.Down)) {
                Text("Profiles on this TV", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text("Each person's settings and sign-ins are their own.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                profiles.forEachIndexed { i, p ->
                    MenuItem(
                        text = p.username, trailing = if (p.id == active) "Current" else "Switch", selected = p.id == active,
                        onClick = { if (p.id != active) { if (p.pin != null) { askPin = p; dialog = "pin" } else vm.switchTo(p) {} } },
                        onLongClick = { if (p.id != active) vm.removeFromTv(p) },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
                Text("Hold OK on someone to remove them from this TV (their Dispatcharr user stays).", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 10.dp, start = 12.dp))
                message?.let { Text(it, fontSize = 14.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 18.dp, start = 12.dp)) }
                if (busy) Text("Working…", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp, start = 12.dp))
            }
            // The users list: Dispatcharr admins only (never shown to anyone else).
            if (admin) {
                Spacer(Modifier.width(48.dp))
                Column(Modifier.weight(1f)) {
                    Text("Users (Dispatcharr)", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                    Text("Everyone with a user can sign in to Viewarr: at home with their username and password, in another house with the server address and their API key (Sign-in details for another house).", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    MenuItem(text = "Add someone…", trailing = "+", selected = false, onClick = { dialog = "add" })
                    MenuItem(text = "Away addresses…", trailing = "for other houses", selected = false, onClick = { dialog = "away" })
                    Spacer(Modifier.height(8.dp))
                    when {
                        list == null -> Text("Loading…", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(12.dp))
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            items(list.orEmpty(), key = { it.id }) { u ->
                                val onTv = profiles.any { it.username == u.username }
                                MenuItem(
                                    text = u.username,
                                    trailing = listOfNotNull(u.levelName, if (u.streamLimit > 0) "${u.streamLimit} stream${if (u.streamLimit == 1) "" else "s"}" else null, if (onTv) "on this TV" else null).joinToString(" · "),
                                    selected = false, onClick = { picked = u; dialog = "user" },
                                )
                            }
                        }
                    }
                }
            }
        }
        val u = picked
        when (dialog) {
            "add" -> AddSomeoneDialog(vm, onClose = { vm.clearInvite(); dialog = null })
            "music" -> if (u != null) MusicDialog(u, vm, onCancel = { dialog = "user" }) { dialog = null }
            "perms" -> if (u != null) PermsDialog(u, onCancel = { dialog = "user" }) { p -> vm.setPerms(u, p); dialog = null }
            "pin" -> askPin?.let { p -> PinEntry(p, onCancel = { askPin = null; dialog = null }) { askPin = null; dialog = null; vm.switchTo(p) {} } }
            "user" -> if (u != null) UserDialog(u, onClose = { dialog = null }, onAction = { dialog = it })
            "password" -> if (u != null) PasswordDialog(u, onCancel = { dialog = "user" }) { vm.resetPassword(u, it); dialog = null }
            "limits" -> if (u != null) LimitsDialog(u, vm, onCancel = { dialog = "user" }) { limit, profs -> vm.setLimits(u, limit, profs); dialog = null }
            "services" -> if (u != null) ServicesDialog(u, vm, onCancel = { dialog = null }) { keep -> vm.prepare(u, keep); dialog = null }
            "delete" -> if (u != null) ConfirmDialog("Delete ${u.username}?", "Their Dispatcharr user and saved Viewarr setup go. This can't be undone.", "Delete", onCancel = { dialog = "user" }) { vm.delete(u); dialog = null }
            "away" -> AwayDialog(vm, onClose = { dialog = null })
            "invite" -> if (u != null) InviteDialog(u, vm, onClose = { vm.clearInvite(); dialog = null })
            "share" -> if (u != null) {
                val sh by vm.share.collectAsStateWithLifecycle()
                LaunchedEffect(u.id) { vm.shareFor(u) }
                sh?.let { KeyShown(it, onClose = { vm.clearShare(); dialog = null }) }
            }
            "addtv" -> if (u != null) { LaunchedEffect(u.id) { vm.addToTv(u); dialog = null } }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DialogBox(title: String, onBack: () -> Unit, content: @Composable (FocusRequester) -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler { onBack() }
    var inside by remember { mutableStateOf(false) }
    LaunchedEffect(title) { first.grab { inside } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp)
                .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.92f).dp)
                .background(AppColors.Panel, RoundedCornerShape(14.dp)).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp)
                .onFocusChanged { inside = it.hasFocus }.focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 14.dp))
            content(first)
        }
    }
}

@Composable
private fun UserDialog(u: DUser, onClose: () -> Unit, onAction: (String) -> Unit) = DialogBox(u.username, onClose) { first ->
    MenuItem(text = "Set up their Viewarr…", trailing = "services", selected = false, onClick = { onAction("services") }, modifier = Modifier.focusRequester(first))
    MenuItem(text = "What they can do…", trailing = "locks", selected = false, onClick = { onAction("perms") })
    MenuItem(text = "Music…", trailing = "their Navidrome", selected = false, onClick = { onAction("music") })
    MenuItem(text = "Sign-in details for another house…", selected = false, onClick = { onAction("share") })
    MenuItem(text = "Add to this TV", selected = false, onClick = { onAction("addtv") })
    MenuItem(text = "Live TV limits…", selected = false, onClick = { onAction("limits") })
    MenuItem(text = "Reset password…", selected = false, onClick = { onAction("password") })
    MenuItem(text = "Delete user…", selected = false, onClick = { onAction("delete") })
}

@Composable
private fun NewUserDialog(onCancel: () -> Unit, onCreate: (String, String) -> Unit) = DialogBox("New user", onCancel) { first ->
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    io.github.petebutty.viewarr.ui.setup.WizardField(name, { name = it }, "Username", first, KeyboardType.Text, ImeAction.Next, {})
    Spacer(Modifier.height(8.dp))
    io.github.petebutty.viewarr.ui.setup.WizardField(pass, { pass = it }, "Password", null, KeyboardType.Password, ImeAction.Done, {}, password = true)
    Text("Letters, numbers, . _ @ - only. They'll sign in with these on any TV.", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
    MenuItem(text = "Create", selected = false, onClick = { if (name.isNotBlank() && pass.length >= 4) onCreate(name.trim(), pass) })
    MenuItem(text = "Cancel", selected = false, onClick = onCancel)
}

@Composable
private fun PasswordDialog(u: DUser, onCancel: () -> Unit, onSave: (String) -> Unit) = DialogBox("New password for ${u.username}", onCancel) { first ->
    var pass by remember { mutableStateOf("") }
    io.github.petebutty.viewarr.ui.setup.WizardField(pass, { pass = it }, "New password", first, KeyboardType.Password, ImeAction.Done, {}, password = true)
    Spacer(Modifier.height(10.dp))
    MenuItem(text = "Save", selected = false, onClick = { onSave(pass) })
    MenuItem(text = "Cancel", selected = false, onClick = onCancel)
}

@Composable
private fun LimitsDialog(u: DUser, vm: ProfilesViewModel, onCancel: () -> Unit, onSave: (Int, List<Int>) -> Unit) = DialogBox("${u.username}: live TV", onCancel) { first ->
    val all by vm.channelProfiles.collectAsStateWithLifecycle()
    var limit by remember { mutableStateOf(u.streamLimit) }
    var profs by remember { mutableStateOf(u.channelProfiles.toSet()) }
    MenuItem(text = "Streams at once", trailing = if (limit == 0) "No limit" else limit.toString(), selected = false, onClick = { limit = (limit + 1) % 6 }, modifier = Modifier.focusRequester(first))
    Text("Channels they see (none ticked: all)", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp))
    LazyColumn(Modifier.height(240.dp)) {
        items(all, key = { it.id }) { p ->
            val on = p.id in profs
            MenuItem(text = p.name, trailing = if (on) "✓" else null, selected = on, onClick = { profs = if (on) profs - p.id else profs + p.id })
        }
    }
    MenuItem(text = "Save", selected = false, onClick = { onSave(limit, profs.toList()) })
}

@Composable
private fun ServicesDialog(u: DUser, vm: ProfilesViewModel, onCancel: () -> Unit, onSave: (Set<SharedService>) -> Unit) = DialogBox("Set up ${u.username}'s Viewarr", onCancel) { first ->
    var options by remember { mutableStateOf<List<SharedService>>(emptyList()) }
    var keep by remember { mutableStateOf<Set<SharedService>>(emptySet()) }
    LaunchedEffect(Unit) { options = vm.sharable(); keep = options.toSet() }
    Text("Live TV is theirs already (their own user). Tick what else they get from this TV. Trakt is never shared: they can link their own.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 10.dp))
    LazyColumn(Modifier.height(300.dp)) {
        items(options.size) { i ->
            val s = options[i]
            val on = s in keep
            MenuItem(text = s.label, trailing = if (on) "✓" else "–", selected = on, onClick = { keep = if (on) keep - s else keep + s }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
        }
    }
    MenuItem(text = "Save their setup", selected = false, onClick = { onSave(keep) })
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onCancel: () -> Unit, onYes: () -> Unit) = DialogBox(title, onCancel) { first ->
    Text(body, fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 12.dp))
    MenuItem(text = "Cancel", selected = false, onClick = onCancel, modifier = Modifier.focusRequester(first))
    MenuItem(text = action, selected = false, onClick = onYes)
}

/** On top of Home: "Who's watching?" on start (when there's more than one person) and from Settings, and the admin page. */
@Composable
fun ProfileHost(vm: ProfilesViewModel = hiltViewModel()) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val ask by vm.askOnStart.collectAsStateWithLifecycle()
    val has by vm.hasProfiles.collectAsStateWithLifecycle()
    val admin by vm.admin.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(600); started = true }
    // Decided once, as Viewarr opens: adding someone later doesn't pop the picker up.
    if (started && !ProfileUi.chosen) {
        ProfileUi.chosen = true
        if (has && ask && profiles.size > 1) ProfileUi.picker = true
    }
    if (ProfileUi.picker && has) WhoIsWatching(onDone = { ProfileUi.picker = false })
    else if (ProfileUi.users && admin) UsersPage(onClose = { ProfileUi.users = false })
    else if (ProfileUi.users && !admin) LaunchedEffect(Unit) { ProfileUi.users = false }
    if (ProfileUi.pin && has) PinSetDialog(vm, onClose = { ProfileUi.pin = false })
}

/** Settings rows: Switch profile for everyone signed in to Dispatcharr; Users & profiles only for its admins. */
@Composable
fun ProfileSettingsItems(modifier: Modifier = Modifier, vm: ProfilesViewModel = hiltViewModel()) {
    val has by vm.hasProfiles.collectAsStateWithLifecycle()
    val admin by vm.admin.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    if (!has) return
    if (profiles.size > 1) {
        MenuItem(text = "Switch profile", selected = false, onClick = { ProfileUi.picker = true }, modifier = modifier)
        // Off (the default): Viewarr opens on the last profile used.
        val ask by vm.askOnStart.collectAsStateWithLifecycle()
        MenuItem(text = "Ask who's watching", trailing = if (ask) "On" else "Off", selected = false, onClick = { vm.setAskOnStart(!ask) })
    }
    // Back from Users & profiles or the PIN dialog: focus back on the row you came from (not the top of the menu).
    val usersRow = remember { FocusRequester() }
    val pinRow = remember { FocusRequester() }
    var back by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(ProfileUi.users, ProfileUi.pin) {
        val target = back ?: return@LaunchedEffect
        if (ProfileUi.users || ProfileUi.pin) return@LaunchedEffect
        repeat(6) { kotlinx.coroutines.android.awaitFrame(); runCatching { target.requestFocus() } }
        back = null
    }
    // Only admins see it, so no "admin" tag.
    if (admin) MenuItem(text = "Users & profiles", selected = false, onClick = { back = usersRow; ProfileUi.users = true }, modifier = Modifier.focusRequester(usersRow))
    MenuItem(text = "Profile PIN", trailing = if (profiles.firstOrNull { it.id == vm.activeId.collectAsStateWithLifecycle().value }?.pin != null) "On" else "Off", selected = false, onClick = { back = pinRow; ProfileUi.pin = true }, modifier = Modifier.focusRequester(pinRow))
}

/** The signed-in Dispatcharr user is an admin (false until Dispatcharr says so). */
@Composable
fun isAdmin(vm: ProfilesViewModel = hiltViewModel()): Boolean = vm.admin.collectAsStateWithLifecycle().value

/** Each home server's away address: how a TV in another house reaches it. */
@Composable
private fun AwayDialog(vm: ProfilesViewModel, onClose: () -> Unit) = DialogBox("Away addresses", onClose) { first ->
    var rows by remember { mutableStateOf<List<Triple<String, String, String>>?>(null) }
    val values = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) { rows = vm.homes().also { r -> r.forEach { values[it.second] = it.third } } }
    Text("What each server is called from outside your home, e.g. tv.example.com. TVs at home keep using the home address; away, Viewarr switches by itself.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 10.dp))
    when {
        rows == null -> Text("Loading…", fontSize = 14.sp, color = AppColors.TextSecondary)
        rows!!.isEmpty() -> Text("Nothing on a home address here.", fontSize = 14.sp, color = AppColors.TextSecondary)
        else -> rows!!.forEachIndexed { i, (label, home, _) ->
            Text("$label · ${home.removePrefix("http://")}", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            io.github.petebutty.viewarr.ui.setup.WizardField(values[home].orEmpty(), { values[home] = it }, "Away address", if (i == 0) first else null, KeyboardType.Uri, ImeAction.Next, {})
        }
    }
    Spacer(Modifier.height(10.dp))
    MenuItem(text = "Save", selected = false, onClick = { vm.saveAway(values.toMap()); onClose() }, modifier = if (rows.isNullOrEmpty()) Modifier.focusRequester(first) else Modifier)
}

/** An invite for [u]: checked against their password, then shown big as a QR to scan and send on. */
@Composable
private fun InviteDialog(u: DUser, vm: ProfilesViewModel, onClose: () -> Unit) {
    val code by vm.invite.collectAsStateWithLifecycle()
    val c = code
    if (c != null) { InviteShown(u.username, c, onClose); return }
    DialogBox("Invite ${u.username}", onClose) { first ->
        val busy by vm.busy.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        var pass by remember { mutableStateOf("") }
        Text("For a TV in another house. Type ${u.username}'s password (it's checked, and locks the invite: they'll type it once on their TV).", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 10.dp))
        io.github.petebutty.viewarr.ui.setup.WizardField(pass, { pass = it }, "Their password", first, KeyboardType.Password, ImeAction.Done, {}, password = true)
        Spacer(Modifier.height(10.dp))
        MenuItem(text = if (busy) "Making the invite…" else "Make invite", selected = false, onClick = { if (!busy && pass.isNotEmpty()) vm.makeInvite(u, pass) })
        message?.let { Text(it, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp, start = 12.dp)) }
    }
}

/** What a person may do: Viewarr's locks, and Dispatcharr's own switches for recording, catch-up and IPTV films & series. */
@Composable
private fun PermsDialog(u: DUser, onCancel: () -> Unit, onSave: (io.github.petebutty.viewarr.data.profiles.Perms) -> Unit) = DialogBox("What ${u.username} can do", onCancel) { first ->
    var p by remember { mutableStateOf(u.perms) }
    PermsList(p, first) { p = it }
    Spacer(Modifier.height(10.dp))
    MenuItem(text = "Save", selected = false, onClick = { onSave(p) })
}

/** The switches, shared by Add someone and What they can do. */
@Composable
private fun PermsList(p: io.github.petebutty.viewarr.data.profiles.Perms, first: FocusRequester?, onChange: (io.github.petebutty.viewarr.data.profiles.Perms) -> Unit) {
    val rows = listOf<Triple<String, Boolean, (Boolean) -> io.github.petebutty.viewarr.data.profiles.Perms>>(
        Triple("Request on Seerr", p.seerr) { v -> p.copy(seerr = v) },
        Triple("Record programmes", p.record) { v -> p.copy(record = v) },
        Triple("Catch-up", p.catchup) { v -> p.copy(catchup = v) },
        Triple("IPTV films & series", p.vod) { v -> p.copy(vod = v) },
        Triple("Change accounts & sources", p.accounts) { v -> p.copy(accounts = v) },
        Triple("Switch Live TV source", p.sources) { v -> p.copy(sources = v) },
        Triple("Change settings", p.settings) { v -> p.copy(settings = v) },
        Triple("Sign out & backup", p.signout) { v -> p.copy(signout = v) },
    )
    rows.forEachIndexed { i, (label, on, set) ->
        MenuItem(text = label, trailing = if (on) "Yes" else "No", selected = on, onClick = { onChange(set(!on)) }, modifier = if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier)
    }
    Text("Recording, catch-up and IPTV films & series are enforced by Dispatcharr too.", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp, start = 12.dp))
}

/**
 * Add someone, in one go: name and password, here or in another house, what they get and what they can do.
 * Another house: the invite appears straight away (made with the password just set).
 */
@Composable
private fun AddSomeoneDialog(vm: ProfilesViewModel, onClose: () -> Unit) {
    val shared by vm.share.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    shared?.let { sh -> KeyShown(sh, onClose = { vm.clearShare(); onClose() }); return }
    DialogBox("Add someone", onClose) { first ->
        var name by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var away by remember { mutableStateOf(false) }
        var awayAddress by remember { mutableStateOf("") }
        var needAway by remember { mutableStateOf(false) }
        var options by remember { mutableStateOf<List<SharedService>>(emptyList()) }
        var keep by remember { mutableStateOf<Set<SharedService>>(emptySet()) }
        var perms by remember { mutableStateOf(io.github.petebutty.viewarr.data.profiles.Perms()) }
        var sent by remember { mutableStateOf(false) }
        // Seerr isn't ticked here: "Request on Seerr" below is its one switch (on: their Seerr connection comes too).
        var hasNd by remember { mutableStateOf(false) }
        var musicMode by remember { mutableIntStateOf(0) }
        var ndUser by remember { mutableStateOf("") }
        var ndPass by remember { mutableStateOf("") }
        var ndAdmin by remember { mutableStateOf("") }
        // Music isn't ticked here either: it has its own choice below (their own Navidrome login).
        LaunchedEffect(Unit) {
            options = vm.sharable().filter { it != SharedService.Seerr && it != SharedService.Music }; keep = options.toSet()
            needAway = vm.dispatcharrAway() == null; hasNd = vm.hasNavidrome()
        }
        // On this TV: done once it's added.
        LaunchedEffect(sent, busy) { if (sent && !busy && !away && message?.contains("ready") == true) onClose() }
        io.github.petebutty.viewarr.ui.setup.WizardField(name, { name = it }, "Name (their username)", first, KeyboardType.Text, ImeAction.Next, {})
        Spacer(Modifier.height(8.dp))
        io.github.petebutty.viewarr.ui.setup.WizardField(pass, { pass = it }, "Password", null, KeyboardType.Password, ImeAction.Done, {}, password = true)
        Spacer(Modifier.height(10.dp))
        MenuItem(text = "Where", trailing = if (away) "In another house" else "On this TV", selected = false, onClick = { away = !away })
        if (away && needAway) {
            Text("Your Dispatcharr's address from outside your home (asked once):", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 12.dp))
            io.github.petebutty.viewarr.ui.setup.WizardField(awayAddress, { awayAddress = it }, "e.g. tv.example.com", null, KeyboardType.Uri, ImeAction.Done, {})
        }
        if (options.isNotEmpty()) {
            Text("They get (live TV is theirs already)", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 12.dp))
            options.forEach { s ->
                val on = s in keep
                MenuItem(text = s.label, trailing = if (on) "✓" else "–", selected = on, onClick = { keep = if (on) keep - s else keep + s })
            }
        }
        if (hasNd) {
            Spacer(Modifier.height(8.dp))
            MusicPicker(musicMode, { musicMode = it }, ndUser, { ndUser = it }, ndPass, { ndPass = it }, ndAdmin, { ndAdmin = it }, canCreate = true)
        }
        Text("They can", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 12.dp))
        PermsList(perms, null) { perms = it }
        Spacer(Modifier.height(12.dp))
        MenuItem(text = if (busy) "Adding…" else if (away) "Add and make their invite" else "Add to this TV", selected = false, onClick = {
            if (!busy) { sent = true; vm.addSomeone(name, pass, away, awayAddress, if (perms.seerr) keep + SharedService.Seerr else keep, perms, musicChoice(musicMode, ndUser, ndPass, ndAdmin)) }
        })
        message?.let { Text(it, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp, start = 12.dp)) }
    }
}

/** An invite as a big QR (scan with your phone, send it on) with what to do with it: its own screen, so it's large. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun InviteShown(who: String, c: String, onClose: () -> Unit) {
    val done = remember { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    BackHandler { onClose() }
    LaunchedEffect(c) { done.grab { inside } }
    // A short QR to a page on this TV with the invite and a Copy button (a dense QR of the invite itself was hard
    // for phones to read off a TV). No network address: the invite's own QR instead.
    var shareUrl by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(c) {
        val server = io.github.petebutty.viewarr.setup.InviteShareServer(who, listOf("Invite" to c))
        shareUrl = server.start()
        onDispose { server.stop() }
    }
    Box(
        Modifier.fillMaxSize().background(AppColors.Background).onFocusChanged { inside = it.hasFocus }
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
            val side = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.82f).dp
            Box(Modifier.size(side).background(Color.White, RoundedCornerShape(10.dp)).padding(12.dp)) {
                io.github.petebutty.viewarr.ui.common.QrImage(shareUrl ?: c, Modifier.fillMaxSize(), dense = shareUrl == null)
            }
            Spacer(Modifier.width(36.dp))
            Column(Modifier.width(380.dp)) {
                Text("Invite for $who", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text(if (shareUrl != null) "1. Scan this with your phone's camera (phone on this Wi-Fi): a page opens with the invite. Tap Copy invite and send it to $who." else "1. Scan this with your phone's camera and send the text to $who.", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 14.dp))
                Text("2. On their TV they install Viewarr, scan its setup code with their phone and choose I have an invite.", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 8.dp))
                Text("3. They paste it and type their password. That's it.", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(20.dp))
                MenuItem(text = "Done", selected = false, onClick = onClose, modifier = Modifier.focusRequester(done))
            }
        }
    }
}

/** How someone gets music: not at all, their existing Navidrome login, or a new Navidrome user made for them. */
sealed interface MusicChoice {
    data object None : MusicChoice
    data class Existing(val user: String, val password: String) : MusicChoice
    data class Create(val adminPassword: String) : MusicChoice
}

/** The music part of Add someone and Music…: which way, and the logins it needs. */
@Composable
private fun MusicPicker(mode: Int, onMode: (Int) -> Unit, user: String, onUser: (String) -> Unit, pass: String, onPass: (String) -> Unit, admin: String, onAdmin: (String) -> Unit, canCreate: Boolean) {
    val labels = listOf("No music", "Their existing Navidrome login", "Make a Navidrome user for them")
    MenuItem(text = "Music", trailing = labels[mode], selected = false, onClick = { onMode((mode + 1) % (if (canCreate) 3 else 2)) })
    when (mode) {
        1 -> {
            Text("Their Navidrome username and password (checked with Navidrome).", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
            io.github.petebutty.viewarr.ui.setup.WizardField(user, onUser, "Navidrome username", null, KeyboardType.Text, ImeAction.Next, {})
            Spacer(Modifier.height(6.dp))
            io.github.petebutty.viewarr.ui.setup.WizardField(pass, onPass, "Navidrome password", null, KeyboardType.Password, ImeAction.Done, {}, password = true)
        }
        2 -> {
            Text("Same name and password as their Viewarr login. Your Navidrome password is used once to make it, not kept.", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
            io.github.petebutty.viewarr.ui.setup.WizardField(admin, onAdmin, "Your Navidrome password", null, KeyboardType.Password, ImeAction.Done, {}, password = true)
        }
    }
}

fun musicChoice(mode: Int, user: String, pass: String, admin: String): MusicChoice = when (mode) {
    1 -> MusicChoice.Existing(user.trim(), pass)
    2 -> MusicChoice.Create(admin)
    else -> MusicChoice.None
}

/** Music… for someone already added (e.g. give them their own Navidrome login). */
@Composable
private fun MusicDialog(u: DUser, vm: ProfilesViewModel, onCancel: () -> Unit, onDone: () -> Unit) = DialogBox("Music for ${u.username}", onCancel) { first ->
    var mode by remember { mutableIntStateOf(1) }
    var user by remember { mutableStateOf(u.username) }
    var pass by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf("") }
    var viewarrPass by remember { mutableStateOf("") }
    Text("Their own Navidrome login, so their plays, favourites and playlists are theirs.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
    MusicPicker(mode, { mode = it }, user, { user = it }, pass, { pass = it }, admin, { admin = it }, canCreate = true)
    if (mode == 2) {
        Spacer(Modifier.height(6.dp))
        io.github.petebutty.viewarr.ui.setup.WizardField(viewarrPass, { viewarrPass = it }, "Their password for the new Navidrome user", null, KeyboardType.Password, ImeAction.Done, {}, password = true)
    }
    Spacer(Modifier.height(10.dp))
    MenuItem(text = "Save", selected = false, onClick = { vm.setMusic(u, viewarrPass, musicChoice(mode, user, pass, admin)); onDone() }, modifier = Modifier.focusRequester(first))
}

/** Sign-in details for someone in another house: the address and their API key, with a short QR to copy them. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun KeyShown(sh: ProfilesViewModel.Share, onClose: () -> Unit) {
    val done = remember { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    BackHandler { onClose() }
    LaunchedEffect(sh) { done.grab { inside } }
    var shareUrl by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(sh) {
        val server = io.github.petebutty.viewarr.setup.InviteShareServer(sh.who, listOf("Address" to sh.address, "API key" to sh.key))
        shareUrl = server.start()
        onDispose { server.stop() }
    }
    Box(
        Modifier.fillMaxSize().background(AppColors.Background).onFocusChanged { inside = it.hasFocus }
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(40.dp), verticalAlignment = Alignment.CenterVertically) {
            shareUrl?.let { url ->
                Box(Modifier.size(300.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(12.dp)) {
                    io.github.petebutty.viewarr.ui.common.QrImage(url, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(36.dp))
            }
            Column(Modifier.width(560.dp)) {
                Text("Sign-in for ${sh.who}", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                if (shareUrl != null) Text("Scan with your phone (on this Wi-Fi) to copy these, then send them to ${sh.who}.", fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                Text("Address", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 18.dp))
                Text(sh.address, fontSize = 20.sp, color = AppColors.TextPrimary)
                Text("API key", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 12.dp))
                Text(sh.key, fontSize = 16.sp, color = AppColors.TextPrimary, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text("On their TV: Set up on your phone → Live TV → Dispatcharr → type the address, paste the API key. Their setup and limits go on by themselves. Change the key in Dispatcharr to cut them off.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 16.dp))
                Spacer(Modifier.height(18.dp))
                MenuItem(text = "Done", selected = false, onClick = onClose, modifier = Modifier.focusRequester(done))
            }
        }
    }
}
