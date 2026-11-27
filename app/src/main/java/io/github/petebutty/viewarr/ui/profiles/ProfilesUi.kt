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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import io.github.petebutty.viewarr.ui.common.Surface
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
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.accounts.AddonInfo
import io.github.petebutty.viewarr.data.accounts.PlexAuth
import io.github.petebutty.viewarr.data.music.MusicLibrary
import io.github.petebutty.viewarr.data.music.NavidromeAuth
import io.github.petebutty.viewarr.data.music.SubsonicClient
import io.github.petebutty.viewarr.data.net.Reach
import io.github.petebutty.viewarr.data.profiles.House
import io.github.petebutty.viewarr.data.profiles.Invites
import io.github.petebutty.viewarr.data.profiles.Perms
import io.github.petebutty.viewarr.data.profiles.ShareChoice
import io.github.petebutty.viewarr.data.remote.services.PlexService
import io.github.petebutty.viewarr.setup.InviteShareServer
import io.github.petebutty.viewarr.ui.common.QrImage
import io.github.petebutty.viewarr.ui.setup.WizardField

/** Which profile screen is up (opened from Settings or on start). */
object ProfileUi {
    /**
     * Profiles (Who's watching?, Users & profiles, PINs, friends in another house, invites) are switched off for now:
     * Settings shows "Profiles · Coming soon" instead (2026-11-15), until the sharing page is made safe
     * (it served a friend's API key as plain text on the home Wi-Fi). Set true to bring it all back.
     */
    const val ENABLED = false

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
    private val invites: Invites,
    private val accountStore: AccountStore,
    private val subsonic: SubsonicClient,
    private val plexService: PlexService,
    private val house: House,
    private val musicLib: MusicLibrary,
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
            sessions.session.distinctUntilChanged().collect { if (ProfileUi.ENABLED) manager.adoptCurrent(); checkAdmin() }
        }
    }

    /** Dispatcharr sign-in (profiles are Dispatcharr users; XC and M3U have none). */
    val hasProfiles: StateFlow<Boolean> = sessions.session.map { it != null && !it.isDirect }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private suspend fun me(): Session? = sessions.session.first()?.takeIf { !it.isDirect }

    fun checkAdmin() = viewModelScope.launch { _admin.value = me()?.let { users.myLevel(it) } == 10 }

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
        // (A message left from the last action mustn't look like this one's: "…is ready" closed a second Add at once.)
        _message.value = null
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
    /** Someone's saved setup as Dispatcharr has it: their key's session, their settings, their sign-ins (null: none saved). */
    private data class Their(val target: Session, val prefs: kotlinx.serialization.json.JsonObject, val accounts: Accounts?)

    /**
     * [u]'s saved setup. Success null: they have none yet (no key, or Dispatcharr says nothing is saved), so starting
     * fresh is right. Failure: it couldn't be read (no answer, or sign-ins under another key): anything that changes
     * their setup must stop, never write a fresh one over theirs.
     */
    private suspend fun theirSetup(s: Session, u: DUser): Result<Their?> {
        val key = u.apiKey ?: return Result.success(null)
        val target = Session(s.serverUrl, u.username, key)
        val copy = sync.copyResult(target).getOrElse { return Result.failure(IllegalStateException("Couldn't read ${u.username}'s saved setup (${it.message}). Nothing was changed; try again.")) }
            ?: return Result.success(null)
        val accounts = sync.signInsOf(copy, target).getOrElse { return Result.failure(IllegalStateException("${u.username}'s saved sign-ins can't be read (their key changed). Nothing was changed: use Reset their setup… after Set up their Viewarr… to start them again.")) }
        val prefs = copy["prefs"] as? kotlinx.serialization.json.JsonObject ?: sync.currentPrefs()
        return Result.success(Their(target, prefs, accounts))
    }

    /** What you gave [u]: remembered, or (set up before that was kept) worked out from what they have. */
    private fun choiceFor(u: DUser, theirs: Accounts?, mine: Accounts) = u.share ?: ShareChoice(
        if (theirs == null) emptySet() else SharedService.entries.filter { it.has(theirs) }.toSet(),
        if (theirs == null) emptySet() else mine.addons.map { it.baseUrl }.toSet() - theirs.addons.map { it.baseUrl }.toSet(),
    )

    /** Your services and addons as [choice] gives them, with everything personal of [theirs] kept as it is. */
    private fun merged(mine: Accounts, choice: ShareChoice, theirs: Accounts?) =
        mine.only(choice.keep - SharedService.Music, choice.addonsOff).let { a ->
            if (theirs == null) a else a.copy(
                deviceId = theirs.deviceId, plex = theirs.plex, plexMore = theirs.plexMore, plexOff = theirs.plexOff,
                trakt = theirs.trakt, traktToPlex = theirs.traktToPlex, watchlist = theirs.watchlist,
                navidrome = theirs.navidrome, musicEnabled = theirs.musicEnabled, musicOff = theirs.musicOff,
            )
        }

    /** Writes [accounts] and [prefs] as [u]'s setup (on Dispatcharr, and their profile copy on this TV). */
    private suspend fun writeSetup(s: Session, u: DUser, target: Session, prefs: kotlinx.serialization.json.JsonObject, accounts: Accounts): Boolean {
        val signIns = json.encodeToString(Accounts.serializer(), accounts)
        if (!sync.writeFor(s, u.id, target, prefs, signIns)) return false
        store.saveSnapshot(Profile(target.serverUrl, target.username, target.apiKey).id, kotlinx.serialization.json.buildJsonObject {
            put("prefs", prefs); put("accounts", kotlinx.serialization.json.JsonPrimitive(signIns)); put("setupComplete", kotlinx.serialization.json.JsonPrimitive(true))
        })
        return true
    }

    /**
     * Sets up the user's Viewarr: this TV's look and settings, and the services ticked (never Trakt), saved to
     * their Dispatcharr copy, so any TV they sign in on (here or away) restores it.
     */
    fun prepare(u: DUser, keep: Set<SharedService>) = work { s ->
        val their = theirSetup(s, u).getOrElse { return@work it.message }
        val key = their?.target?.apiKey ?: users.apiKeyFor(s, u) ?: return@work "Dispatcharr wouldn't give a key for ${u.username}."
        val target = their?.target ?: Session(s.serverUrl, u.username, key)
        val mine = sync.currentAccounts()
        // Their remembered choice with these services; addons left out and their Plex user stay as they were.
        val choice = choiceFor(u, their?.accounts, mine).copy(keep = keep - SharedService.Music)
        users.update(s, u.id, props = choice.toProps()).getOrElse { return@work it.message }
        if (!writeSetup(s, u, target, sync.currentPrefs(), merged(mine, choice, their?.accounts))) return@work "Dispatcharr wouldn't save ${u.username}'s setup (needs Dispatcharr 0.33 or newer)."
        "Set up ${u.username}'s Viewarr: ${(keep - SharedService.Music).joinToString { it.label }.ifEmpty { "live TV only" }}."
    }

    /** Their setup (this TV's look and settings, and the services in [keep]) saved on Dispatcharr and on this TV. */
    private suspend fun saveSetupFor(
        s: Session, u: DUser, key: String, keep: Set<SharedService>, music: NavidromeAuth? = null,
        addonsOff: Set<String> = emptySet(), plex: PlexAuth? = null,
        musicFrom: Set<String>? = null,
    ): Boolean {
        val target = Session(s.serverUrl, u.username, key)
        // Music: their own Navidrome login (never this TV's). Plex: their own Plex Home user (never this account).
        val accounts = sync.currentAccounts().only(keep - SharedService.Music, addonsOff).let { a ->
            if (music != null) a.copy(navidrome = music, musicEnabled = true) else a
        }.let { a -> if (plex != null) a.copy(plex = plex) else a }
            .let { a -> if (musicFrom != null) withMusic(a, music, musicFrom) else a }
        val signIns = json.encodeToString(Accounts.serializer(), accounts)
        // Their copy on this TV is replaced too, so switching to them here picks it up.
        store.saveSnapshot(target.let { Profile(it.serverUrl, it.username, it.apiKey).id }, kotlinx.serialization.json.buildJsonObject {
            put("prefs", sync.currentPrefs()); put("accounts", kotlinx.serialization.json.JsonPrimitive(signIns)); put("setupComplete", kotlinx.serialization.json.JsonPrimitive(true))
        })
        return sync.writeFor(s, u.id, target, sync.currentPrefs(), signIns)
    }

    /** What [u] may do (Viewarr's locks, and Dispatcharr's recording / catch-up / VOD switches). */
    fun setPerms(u: DUser, p: Perms) = work { s ->
        users.update(s, u.id, props = p.toProps()).fold({ "Saved what ${u.username} can do." }, { it.message })
    }

    /** Dispatcharr's outside address, if set (needed to invite someone in another house). */
    suspend fun dispatcharrAway(): String? {
        val s = me() ?: return null
        val home = Reach.normalise(s.serverUrl, away = false)
        return accountStore.current().away.entries.firstOrNull { Reach.normalise(it.key, false) == home }?.value
    }

    /** The address a TV outside the home uses for Dispatcharr (its away address), else this TV's. */
    private suspend fun outsideAddress(s: Session) = (dispatcharrAway() ?: s.serverUrl).removePrefix("https://").removeSuffix(":443").trimEnd('/')

    /** What to send someone in another house: the address, their username and password. */
    data class Details(val who: String, val address: String, val password: String)
    private val _details = MutableStateFlow<Details?>(null)
    val details: StateFlow<Details?> = _details
    fun clearDetails() { _details.value = null }

    private val _inviteFor = MutableStateFlow<String?>(null)
    /** Whose invite [invite] is. */
    val inviteFor: StateFlow<String?> = _inviteFor

    /**
     * Add someone, all in one go: their Dispatcharr user, what they may do, their setup (the services ticked), then
     * on this TV (a profile) or in another house (an invite, made with the password just set: no typing it again).
     */
    fun addSomeone(
        name: String, password: String, away: Boolean, awayAddress: String, keep: Set<SharedService>, perms: Perms,
        music: MusicChoice = MusicChoice.None, addonsOff: Set<String> = emptySet(), plex: PlexChoice = PlexChoice.None,
        musicFrom: Set<String> = emptySet(),
    ) = work { s ->
        // On this TV nobody types a password: one is made for them (reset it later to sign them in elsewhere).
        val password = if (!away && password.isBlank()) randomPassword() else password
        if (name.isBlank() || password.length < 4) return@work "Enter a name, and a password of at least 4 characters."
        // Signing in from outside is open to the internet: a password long enough that guessing gets nowhere.
        if (away && password.length < 6) return@work "For another house, use a password of at least 6 characters."
        if (away && dispatcharrAway() == null) {
            val a = Reach.normalise(awayAddress, away = true) ?: return@work "Enter your Dispatcharr's public address (e.g. tv.example.com)."
            val home = Reach.normalise(s.serverUrl, away = false) ?: return@work "This TV's Dispatcharr address isn't usable."
            accountStore.update { it.copy(away = it.away + (home to a)) }
        }
        // Their Dispatcharr user first (a taken name stops here, before anything is made on Navidrome or Plex);
        // if a later step fails, it's removed again so nothing is left half made.
        val u = users.create(s, name.trim(), password).getOrElse { return@work it.message }
        suspend fun undo(why: String?): String { users.delete(s, u.id); return (why ?: "That didn't work.") + " Nothing was added." }
        val nd = navidromeFor(name.trim(), password, music).getOrElse { return@work undo(it.message) }
        val plexAuth = plexFor(plex).getOrElse { return@work undo(it.message) }
        val props = kotlinx.serialization.json.JsonObject(perms.toProps() + ShareChoice(keep, addonsOff, plexAuth?.let { plexTitle(plex) }).toProps())
        users.update(s, u.id, props = props).getOrElse { return@work undo(it.message) }
        val key = users.apiKeyFor(s, u) ?: return@work undo("Dispatcharr wouldn't give a key for ${u.username}.")
        if (!saveSetupFor(s, u, key, keep, nd, addonsOff, plexAuth, musicFrom)) return@work undo("Dispatcharr wouldn't save ${u.username}'s setup.")
        if (!away) {
            manager.add(Profile(s.serverUrl, u.username, key))
            "${u.username} is ready: pick them on Who's watching."
        } else {
            // In another house: the address, their username and password to send them (their TV signs in with
            // those and their setup goes on by itself).
            _details.value = Details(u.username, outsideAddress(s), password); null
        }
    }

    private fun randomPassword(): String {
        val chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = java.security.SecureRandom()
        return (1..20).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    // (Backups in the Viewarr plugin: ui/sync/PluginBackupsUi.kt, shared with Settings → Backup & sync.)

    // ---- Resetting someone (admins)

    /** [u]'s profile on this TV, if they have one. */
    private fun profileOf(u: DUser) = profiles.value.firstOrNull { it.username == u.username }

    /** Clears a forgotten profile PIN (PINs are kept only on this TV). */
    fun clearPinFor(u: DUser) = viewModelScope.launch {
        val p = profileOf(u)
        _message.value = if (p == null) "${u.username} isn't a profile on this TV." else if (p.pin == null) "${u.username} has no PIN here." else { store.setPin(p.id, null); "${u.username}'s PIN is cleared on this TV." }
    }

    /** A new password; [show] then shows what to send (the address, username and password). */
    fun newPassword(u: DUser, password: String, show: Boolean) = work { s ->
        if (password.length < 6) return@work "Use at least 6 characters."
        users.update(s, u.id, password = password).getOrElse { return@work it.message }
        if (show) { _details.value = Details(u.username, outsideAddress(s), password); null } else "New password set for ${u.username}."
    }

    /**
     * Signs [u] out everywhere: a new Dispatcharr key, so every TV signed in as them stops working until they sign
     * in again with their password (a lost TV, say). Their saved setup is moved to the new key first, so nothing of
     * it is lost; their profile on this TV gets the new key too.
     */
    fun signOutEverywhere(u: DUser) = work { s ->
        if (u.username == s.username) return@work "That's you: sign this TV out from Settings → Sign out of everything instead."
        // Their setup first: if it can't be read now, stop (a new key would leave it unreadable for good).
        val their = theirSetup(s, u).getOrElse { return@work it.message }
        val key = users.newKey(s, u.id) ?: return@work "Dispatcharr wouldn't make ${u.username} a new key. Nothing was changed."
        val fresh = Session(s.serverUrl, u.username, key)
        profileOf(u)?.let { manager.add(it.copy(apiKey = key)) }
        if (their != null) {
            val saved = sync.writeFor(s, u.id, fresh, their.prefs, their.accounts?.let { json.encodeToString(Accounts.serializer(), it) })
            if (!saved) return@work "${u.username} is signed out everywhere, but their setup couldn't be saved again: use Reset their setup… to put it back."
        }
        "${u.username} is signed out everywhere. They sign in again with their password; their setup is kept."
    }

    /** Puts your setup back on [u]'s TVs, replacing what they've changed (their own Plex, music and Trakt stay). */
    fun resetSetup(u: DUser) = sendSetup(listOf(u))

    // ---- Sending this TV's setup to the people you've added

    /**
     * This TV's setup to each of [targets]: settings (look, home rows, menus, playback, remote) and the services and
     * addons you gave them, as they are on this TV now (new addons reach them; ones you left out stay out). What's
     * theirs stays theirs: Plex Home user, music login, Trakt. Their TVs pick it up when Viewarr next starts. People
     * with no Viewarr setup yet, or whose saved sign-ins can't be read, are skipped.
     */
    fun sendSetup(targets: List<DUser>) = work { s ->
        val sent = mutableListOf<String>(); val none = mutableListOf<String>(); val failed = mutableListOf<String>()
        val prefs = sync.currentPrefs()
        val mine = sync.currentAccounts()
        for (u in targets) {
            if (u.username == s.username) continue
            val their = theirSetup(s, u).getOrElse { failed += u.username; null }
            if (their == null) { if (u.username !in failed) none += u.username; continue }
            if (their.accounts == null) { none += u.username; continue }
            if (writeSetup(s, u, their.target, prefs, merged(mine, choiceFor(u, their.accounts, mine), their.accounts))) sent += u.username else failed += u.username
        }
        buildString {
            if (sent.isNotEmpty()) append("Sent your setup to ${sent.joinToString()}: their TVs pick it up when Viewarr next starts.")
            else if (none.isEmpty() && failed.isEmpty()) append("There's nobody else to send to yet.")
            else append("Nothing sent.")
            if (none.isNotEmpty()) append(" ${none.joinToString()}: no Viewarr setup yet (Set up their Viewarr first).")
            if (failed.isNotEmpty()) append(" ${failed.joinToString()}: couldn't be read or saved, left as they were.")
        }
    }

    // ---- Plex: each person's own Plex Home user

    /** The managed users in this TV's Plex Home (null: no Plex here, so nothing to offer). */
    suspend fun plexHomeUsers(): List<PlexService.HomeUser>? {
        val a = accountStore.current()
        val p = a.plex ?: return null
        return plexService.homeUsers(a.deviceId, p.token).filter { !it.admin }
    }

    /** The Plex Home user's name for a choice (remembered on their Dispatcharr user, shown in their settings). */
    private fun plexTitle(c: PlexChoice): String? = when (c) { is PlexChoice.New -> c.name.trim(); is PlexChoice.Existing -> c.title; PlexChoice.None -> null }

    /**
     * Changes [u]'s Plex: a Home user (new or existing) or none, in their saved setup (everything else of theirs
     * stays). Their TVs pick it up when Viewarr next starts.
     */
    fun setPlex(u: DUser, choice: PlexChoice) = work { s ->
        val their = theirSetup(s, u).getOrElse { return@work it.message } ?: return@work "${u.username} has no Viewarr setup yet: Set up their Viewarr first."
        val theirs = their.accounts ?: return@work "${u.username} has no sign-ins saved yet: Set up their Viewarr first."
        val auth = plexFor(choice).getOrElse { return@work it.message }
        val next = theirs.copy(plex = auth, plexMore = if (auth == null) emptyList() else theirs.plexMore)
        if (!writeSetup(s, u, their.target, their.prefs, next)) return@work "Dispatcharr wouldn't save ${u.username}'s setup."
        users.update(s, u.id, props = choiceFor(u, theirs, sync.currentAccounts()).copy(plexUser = plexTitle(choice)).toProps())
        if (auth != null) "${u.username} now watches Plex as ${plexTitle(choice)}." else "Plex taken away from ${u.username}."
    }

    /**
     * What would reach someone in another house only at home: the services and addons they'd get that this TV uses by
     * a home address (192.168…, .local). Shown as a warning in Add someone: add them with a public address to share.
     */
    suspend fun homeOnly(keep: Set<SharedService>, addonsOff: Set<String>, music: Boolean): List<String> {
        val a = accountStore.current()
        fun home(url: String?) = url != null && io.github.petebutty.viewarr.data.media.isHomeAddress(url)
        fun host(url: String) = runCatching { java.net.URI(url).let { u -> u.host + (if (u.port > 0) ":" + u.port else "") } }.getOrDefault(url)
        return buildList {
            if (SharedService.Jellyfin in keep && home(a.jellyfin?.serverUrl)) add("Jellyfin · " + host(a.jellyfin!!.serverUrl))
            if (SharedService.Emby in keep && home(a.emby?.serverUrl)) add("Emby · " + host(a.emby!!.serverUrl))
            if (SharedService.Seerr in keep && home(a.seerr?.url)) add("Seerr · " + host(a.seerr!!.url))
            if (music && home(a.navidrome?.url)) add("Navidrome · " + host(a.navidrome!!.url))
            if (SharedService.Addons in keep) a.addons.filter { it.baseUrl !in addonsOff && home(it.baseUrl) }.forEach { add(it.name + " · " + host(it.baseUrl)) }
        }
    }

    /** This TV's addons (each can be given or left out). */
    suspend fun myAddons() = accountStore.current().addons

    /**
     * Their Plex sign-in: none, an existing Plex Home user, or a new managed user (one already in the Home with
     * that name is used rather than making a second). Their libraries are shared, then their own token fetched.
     */
    private suspend fun plexFor(choice: PlexChoice): Result<PlexAuth?> = runCatching {
        if (choice == PlexChoice.None) return@runCatching null
        val a = accountStore.current()
        val main = a.plex ?: error("Sign this TV in to Plex first.")
        // Only the Plex account that owns the Home can add people to it (not a Home user's sign-in on this TV).
        if (main.accountToken != null) error("This TV's Plex sign-in is a Home user's: sign this TV in to Plex as the owner to manage Plex Home.")
        val owner = main.token
        val userId = when (choice) {
            is PlexChoice.Existing -> choice.id
            is PlexChoice.New -> {
                val name = choice.name.trim().ifEmpty { error("Enter a name for their Plex user.") }
                plexService.homeUsers(a.deviceId, owner).firstOrNull { !it.admin && it.title.equals(name, ignoreCase = true) }?.id
                    ?: plexService.createManagedUser(a.deviceId, owner, name).getOrThrow()
            }
            PlexChoice.None -> error("")
        }
        plexService.shareAllLibraries(a.deviceId, owner, main.machineId, userId).getOrThrow()
        // A new user's server can take a moment to show up on plex.tv.
        var last: Throwable? = null
        repeat(3) { attempt ->
            plexService.homeUserAuth(a.deviceId, owner, main, userId).fold(onSuccess = { return@runCatching it }, onFailure = { last = it })
            if (attempt < 2) kotlinx.coroutines.delay(3_000)
        }
        throw last ?: IllegalStateException("Plex didn't give their sign-in.")
    }

    // ---- Music: Navidrome (their own login) and the music on Plex, Jellyfin and Emby

    /** Music on this TV's servers by kind (Plex, Jellyfin, Emby): each kind's source ids. Navidrome is chosen apart. */
    suspend fun musicFamilies(): Map<String, List<String>> = runCatching { musicLib.allSources() }.getOrDefault(emptyList())
        .mapNotNull { src -> musicFamily(src.id)?.let { it to src.id } }
        .groupBy({ it.first }, { it.second })

    /** The music kinds [u] can reach (Plex through their Plex user, Jellyfin / Emby if given), and those switched on. */
    suspend fun musicKindsFor(u: DUser): Pair<List<String>, Set<String>> {
        val s = me() ?: return emptyList<String>() to emptySet()
        val theirs = theirSetup(s, u).getOrNull()?.accounts ?: return emptyList<String>() to emptySet()
        val fams = musicFamilies()
        val reach = fams.keys.filter { k -> when (k) { "Plex" -> theirs.plex != null; "Jellyfin" -> theirs.jellyfin != null; "Emby" -> theirs.emby != null; else -> false } }
        val on = reach.filter { k -> theirs.musicOn && fams[k].orEmpty().any { it !in theirs.musicOff } }.toSet()
        return reach to on
    }

    private fun musicFamily(id: String) = when {
        id.startsWith("px") -> "Plex"; id.startsWith("jf") -> "Jellyfin"; id.startsWith("emby") -> "Emby"; else -> null
    }

    /** [a] with music from Navidrome ([nd]) and the kinds in [from]: on if any is, the rest switched off. */
    private suspend fun withMusic(a: Accounts, nd: NavidromeAuth?, from: Set<String>): Accounts {
        val fams = musicFamilies()
        val off = fams.filterKeys { it !in from }.values.flatten().toSet() + (if (nd == null && a.navidrome == null) setOf("nd") else emptySet())
        val any = (nd ?: a.navidrome) != null || fams.keys.any { it in from }
        return a.copy(navidrome = nd ?: a.navidrome, musicEnabled = any, musicOff = off)
    }

    // ---- Music: each person's own Navidrome login

    /** This TV has Navidrome (so music can be given). */
    suspend fun hasNavidrome() = accountStore.current().navidrome != null

    /** Their Navidrome login: one they have already, or a new one (same name and password as their Viewarr). */
    private suspend fun navidromeFor(name: String, password: String, m: MusicChoice): Result<NavidromeAuth?> {
        val mine = accountStore.current().navidrome
            ?: return if (m is MusicChoice.Existing || m is MusicChoice.Create) Result.failure(IllegalStateException("This TV has no Navidrome: add it in Settings → Accounts & sources first.")) else Result.success(null)
        return when (m) {
            MusicChoice.None, MusicChoice.Keep -> Result.success(null)
            is MusicChoice.Existing -> subsonic.connect(mine.url, m.user, m.password).map { it }
            is MusicChoice.Create -> subsonic.createUser(mine.url, mine.user, m.adminPassword, name, password)
                .fold(onSuccess = { subsonic.connect(mine.url, name, password).map { it } }, onFailure = { Result.failure(it) })
        }
    }

    /** Give [u] music (their own Navidrome login) or take it away, in the setup saved for them. */
    fun setMusic(u: DUser, password: String, m: MusicChoice, from: Set<String>? = null) = work { s ->
        // Their saved setup first: if it can't be read, nothing is changed (never a fresh one over theirs).
        val their = theirSetup(s, u).getOrElse { return@work it.message }
        val nd = navidromeFor(u.username, password, m).getOrElse { return@work it.message }
        val key = their?.target?.apiKey ?: users.apiKeyFor(s, u) ?: return@work "Dispatcharr wouldn't give a key for ${u.username}."
        val target = their?.target ?: Session(s.serverUrl, u.username, key)
        val current = their?.accounts ?: sync.currentAccounts().only(emptySet())
        val base = when {
            nd != null -> current.copy(navidrome = nd, musicEnabled = true)
            m == MusicChoice.Keep -> current
            else -> current.copy(navidrome = null)
        }
        val updated = if (from != null) withMusic(base, nd, from) else base
        if (!writeSetup(s, u, target, their?.prefs ?: sync.currentPrefs(), updated)) return@work "Dispatcharr wouldn't save ${u.username}'s setup."
        val kinds = listOfNotNull(updated.navidrome?.let { "Navidrome (${it.user})" }) + from.orEmpty().sorted()
        if (updated.musicOn) "${u.username}'s music: ${kinds.joinToString()}." else "Music taken away from ${u.username}."
    }

    // ---- Profile PIN (asked for when switching to that profile)

    fun setPin(pin: String?) = viewModelScope.launch {
        val id = activeId.value ?: return@launch
        store.setPin(id, pin)
        _message.value = if (pin.isNullOrBlank()) "PIN removed." else "PIN set: it's asked for when anyone switches to you."
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
    val admin by vm.admin.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(profiles.size, askPin == null, adding) { if (askPin == null && !adding) first.grab { inside } }
    BackHandler(enabled = askPin == null && !adding) { onDone() }
    Box(
        Modifier.fillMaxSize().background(AppColors.Background).onFocusChanged { inside = it.hasFocus }.focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (busy) "Switching…" else "Who's watching?", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                profiles.forEachIndexed { i, p ->
                    val on = p.id == active
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // The one card highlight (FocusCard: the 3 dp accent ring, no growing; was a 4 dp white ring
                        // that grew. 2026-11-17).
                        io.github.petebutty.viewarr.ui.common.FocusCard(
                            onClick = { if (!busy) { if (p.pin != null && !on) askPin = p else vm.switchTo(p) { onDone() } } },
                            modifier = (if (on || (active == null && i == 0)) Modifier.focusRequester(first) else Modifier).size(120.dp),
                            shape = io.github.petebutty.viewarr.ui.theme.Tokens.RoundShape,
                            containerColor = COLOURS[p.color % COLOURS.size],
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(p.initial.toString(), fontSize = 52.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false)
                            }
                        }
                        Text(p.username, fontSize = 18.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (on) Text("Current", fontSize = 12.sp, color = AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Admins: a new person for this TV in one step (just a name).
                if (admin) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = { adding = true },
                        modifier = Modifier.size(120.dp),
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        scale = io.github.petebutty.viewarr.ui.theme.NoScale,
                        border = ClickableSurfaceDefaults.border(
                            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, AppColors.TextSecondary), shape = CircleShape),
                            focusedBorder = io.github.petebutty.viewarr.ui.common.focusRing(CircleShape),
                        ),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White.copy(alpha = 0.12f)),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("+", fontSize = 56.sp, color = Color.White)
                        }
                    }
                    Text("Add profile", fontSize = 18.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(40.dp))
            MenuItem(text = "Ask each time Viewarr opens", trailing = if (ask) "On" else "Off", selected = false, onClick = { vm.setAskOnStart(!ask) }, modifier = Modifier.width(420.dp))
        }
        askPin?.let { p -> PinEntry(p, onCancel = { askPin = null }) { askPin = null; vm.switchTo(p) { onDone() } } }
        if (adding) QuickAddProfile(vm, onClose = { adding = false })
    }
}

/**
 * A home profile in one step: a name. They get everything this TV has, their own Plex Home user (their own watch
 * history), Live TV and the usual limits; no password to make up. More choices: Settings → Users & profiles.
 */
@Composable
private fun QuickAddProfile(vm: ProfilesViewModel, onClose: () -> Unit) = DialogBox("Add profile", onClose) { first ->
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var hasPlex by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<Set<SharedService>>(emptySet()) }
    var addonsOff by remember { mutableStateOf<Set<String>>(emptySet()) }
    var families by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        options = vm.sharable().filter { it != SharedService.Music }.toSet()
        addonsOff = vm.myAddons().filter { !it.enabled }.map { it.baseUrl }.toSet()
        hasPlex = vm.plexHomeUsers() != null
        families = vm.musicFamilies().keys
    }
    LaunchedEffect(sent, busy) { if (sent && !busy && message?.contains("ready") == true) onClose() }
    WizardField(name, { name = it }, "Name", first, KeyboardType.Text, ImeAction.Done, {})
    Text(
        "They get everything this TV has" + (if (hasPlex) ", their own Plex watch history" else "") + " and Live TV. More choices (limits, music, what they can do) in Settings → Users & profiles.",
        fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 10.dp, start = 4.dp),
    )
    MenuItem(text = if (busy) "Adding…" else "Add", selected = false, onClick = {
        if (!busy && name.isNotBlank()) {
            sent = true
            val plex = if (hasPlex) PlexChoice.New(name.trim().replaceFirstChar { it.uppercase() }) else PlexChoice.None
            vm.addSomeone(name, "", away = false, awayAddress = "", keep = options, perms = Perms(), addonsOff = addonsOff, plex = plex,
                musicFrom = families.filter { it != "Plex" || hasPlex }.toSet())
        }
    })
    message?.let { Text(it, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp, start = 12.dp)) }
}

/** The PIN for [p]; [onOk] once it's right. */
@Composable
private fun PinEntry(p: Profile, onCancel: () -> Unit, onOk: () -> Unit) = DialogBox("PIN for ${p.username}", onCancel) { first ->
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    WizardField(pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, "PIN", first, KeyboardType.NumberPassword, ImeAction.Done, { if (pin == p.pin) onOk() else wrong = true }, password = true)
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
    WizardField(pin, { pin = it.filter(Char::isDigit).take(8) }, "New PIN", first, KeyboardType.NumberPassword, ImeAction.Done, {}, password = true)
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
        Row(Modifier.fillMaxSize().padding(start = io.github.petebutty.viewarr.ui.theme.Tokens.Gutter, top = 40.dp, end = io.github.petebutty.viewarr.ui.theme.Tokens.Gutter)) {
            // Down past the last profile stays put (it used to jump across to Add someone).
            Column(Modifier.width(380.dp).stopAt(androidx.compose.ui.focus.FocusDirection.Down)) {
                Text("Profiles on this TV", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    Text("Users (Dispatcharr)", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Everyone with a user can sign in to Viewarr with your server's address, their username and password.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    MenuItem(text = "Add someone…", trailing = "+", selected = false, onClick = { dialog = "add" })
                    MenuItem(text = "Send my setup to everyone…", selected = false, onClick = { dialog = "sendall" })
                    MenuItem(text = "Backups…", trailing = "Viewarr plugin", selected = false, onClick = { dialog = "backups" })
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
            "backups" -> BackupsDialog(vm, onClose = { dialog = null })
            "sendall" -> ConfirmDialog(
                "Send my setup to everyone?",
                "Your look, home rows, menus, playback and remote settings, and the services and addons you gave each person, as they are on this TV now. Their own Plex, music and Trakt stay theirs. Their TVs update when Viewarr next starts.",
                "Send", onCancel = { dialog = null },
            ) { vm.sendSetup(list.orEmpty().filter { it.level < 10 }); dialog = null }
            "send" -> if (u != null) { LaunchedEffect(u.id) { vm.sendSetup(listOf(u)); dialog = null } }
            "invite" -> if (u != null) InviteDialog(u, vm, onClose = { vm.clearInvite(); dialog = null })
            // Another house: a new password you can send (it's never stored to show again), then what to send.
            "signin" -> if (u != null) {
                val d by vm.details.collectAsStateWithLifecycle()
                val dd = d
                if (dd != null) DetailsShown(dd, onClose = { vm.clearDetails(); dialog = null })
                else PasswordDialog(u, onCancel = { dialog = "user" }) { vm.newPassword(u, it, show = true) }
            }
            "plex" -> if (u != null) PlexDialog(u, vm, onCancel = { dialog = "user" }) { c -> vm.setPlex(u, c); dialog = null }
            "resetsetup" -> if (u != null) ConfirmDialog(
                "Reset ${u.username}'s setup?",
                "Your settings, services and addons go back on their TVs, replacing anything they've changed. Their own Plex, music and Trakt stay. Their TVs update when Viewarr next starts.",
                "Reset", onCancel = { dialog = "user" },
            ) { vm.resetSetup(u); dialog = null }
            "clearpin" -> if (u != null) { LaunchedEffect(u.id) { vm.clearPinFor(u); dialog = null } }
            "signoutall" -> if (u != null) ConfirmDialog(
                "Sign ${u.username} out everywhere?",
                "Every TV signed in as ${u.username} stops working until they sign in again with their password. Their setup is kept. Set a new password too if a TV was lost with it written down.",
                "Sign out", onCancel = { dialog = "user" },
            ) { vm.signOutEverywhere(u); dialog = null }
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
                .heightIn(max = (io.github.petebutty.viewarr.ui.common.LocalCanvas.current.height * 0.92f))
                .background(AppColors.Panel, RoundedCornerShape(14.dp)).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp)
                .onFocusChanged { inside = it.hasFocus }.focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            content(first)
        }
    }
}

@Composable
private fun UserDialog(u: DUser, onClose: () -> Unit, onAction: (String) -> Unit) = DialogBox(u.username, onClose) { first ->
    MenuItem(text = "Set up their Viewarr…", trailing = "services", selected = false, onClick = { onAction("services") }, modifier = Modifier.focusRequester(first))
    MenuItem(text = "Plex…", trailing = u.share?.plexUser?.let { "Home user: $it" } ?: "their Plex Home user", selected = false, onClick = { onAction("plex") })
    MenuItem(text = "Send my setup", trailing = "settings and services", selected = false, onClick = { onAction("send") })
    MenuItem(text = "What they can do…", trailing = "locks", selected = false, onClick = { onAction("perms") })
    MenuItem(text = "Music…", trailing = "their Navidrome", selected = false, onClick = { onAction("music") })
    MenuItem(text = "New password…", trailing = "and what to send them", selected = false, onClick = { onAction("signin") })
    MenuItem(text = "Add to this TV", selected = false, onClick = { onAction("addtv") })
    MenuItem(text = "Live TV limits…", selected = false, onClick = { onAction("limits") })
    MenuItem(text = "Reset their setup…", trailing = "back to yours", selected = false, onClick = { onAction("resetsetup") })
    MenuItem(text = "Clear their PIN", trailing = "this TV", selected = false, onClick = { onAction("clearpin") })
    MenuItem(text = "Sign them out everywhere…", trailing = "lost TV", selected = false, onClick = { onAction("signoutall") })
    MenuItem(text = "Delete user…", selected = false, onClick = { onAction("delete") })
}

@Composable
private fun PasswordDialog(u: DUser, onCancel: () -> Unit, onSave: (String) -> Unit) = DialogBox("New password for ${u.username}", onCancel) { first ->
    var pass by remember { mutableStateOf("") }
    WizardField(pass, { pass = it }, "New password", first, KeyboardType.Password, ImeAction.Done, {}, password = true)
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
    // Profiles off for now (ProfileUi.ENABLED): no picker on start, no Users page, no PIN, and the TV back on its main
    // sign-in (the first profile: whoever set it up). A TV left on someone else's profile kept their limits with no
    // way to switch back: on one profile, recording was hidden, so holding OK in the guide just tuned the channel.
    // (Nothing at all while off: switching back to the "main" profile bounced a change of Live TV source, and could
    // restore an empty setup. Review, 2026-11-16.)
    if (!ProfileUi.ENABLED) return
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
    // Profiles off for now: one row saying they're coming (OK does nothing).
    if (!ProfileUi.ENABLED) {
        MenuItem(text = "Profiles", trailing = "Coming soon", selected = false, onClick = {}, modifier = modifier)
        return
    }
    val has by vm.hasProfiles.collectAsStateWithLifecycle()
    val admin by vm.admin.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    if (!has) return
    // Admins always (it's where "Add profile" is); everyone else once there's someone to switch to.
    if (profiles.size > 1 || admin) {
        MenuItem(text = "Who's watching", trailing = if (profiles.size > 1) "switch" else "add a profile", selected = false, onClick = { ProfileUi.picker = true }, modifier = modifier)
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
        WizardField(pass, { pass = it }, "Their password", first, KeyboardType.Password, ImeAction.Done, {}, password = true)
        Spacer(Modifier.height(10.dp))
        MenuItem(text = if (busy) "Making the invite…" else "Make invite", selected = false, onClick = { if (!busy && pass.isNotEmpty()) vm.makeInvite(u, pass) })
        message?.let { Text(it, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp, start = 12.dp)) }
    }
}

/** What a person may do: Viewarr's locks, and Dispatcharr's own switches for recording, catch-up and IPTV films & series. */
@Composable
private fun PermsDialog(u: DUser, onCancel: () -> Unit, onSave: (Perms) -> Unit) = DialogBox("What ${u.username} can do", onCancel) { first ->
    var p by remember { mutableStateOf(u.perms) }
    PermsList(p, first) { p = it }
    Spacer(Modifier.height(10.dp))
    MenuItem(text = "Save", selected = false, onClick = { onSave(p) })
}

/** The switches, shared by Add someone and What they can do. */
@Composable
private fun PermsList(p: Perms, first: FocusRequester?, onChange: (Perms) -> Unit) {
    val rows = listOf<Triple<String, Boolean, (Boolean) -> Perms>>(
        Triple("Live TV", p.livetv) { v -> p.copy(livetv = v) },
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
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val invite by vm.invite.collectAsStateWithLifecycle()
    val inviteFor by vm.inviteFor.collectAsStateWithLifecycle()
    invite?.let { c -> InviteShown(inviteFor ?: "them", c, onClose = { vm.clearInvite(); onClose() }); return }
    val details by vm.details.collectAsStateWithLifecycle()
    details?.let { d -> DetailsShown(d, onClose = { vm.clearDetails(); onClose() }); return }
    DialogBox("Add someone", onClose) { first ->
        var name by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var away by remember { mutableStateOf(false) }
        var awayAddress by remember { mutableStateOf("") }
        var needAway by remember { mutableStateOf(false) }
        var options by remember { mutableStateOf<List<SharedService>>(emptyList()) }
        var keep by remember { mutableStateOf<Set<SharedService>>(emptySet()) }
        var perms by remember { mutableStateOf(Perms()) }
        var sent by remember { mutableStateOf(false) }
        // Seerr isn't ticked here: "Request on Seerr" below is its one switch (on: their Seerr connection comes too).
        var hasNd by remember { mutableStateOf(false) }
        var musicMode by remember { mutableIntStateOf(0) }
        var ndUser by remember { mutableStateOf("") }
        var ndPass by remember { mutableStateOf("") }
        var ndAdmin by remember { mutableStateOf("") }
        // Music isn't ticked here either: it has its own choice below (their own Navidrome login).
        var addons by remember { mutableStateOf<List<AddonInfo>>(emptyList()) }
        var addonsOff by remember { mutableStateOf<Set<String>>(emptySet()) }
        // Plex: null = no Plex on this TV. 0 none, 1 a new managed user, 2+ an existing Home user.
        var plexUsers by remember { mutableStateOf<List<PlexService.HomeUser>?>(null) }
        var plexMode by remember { mutableIntStateOf(1) }
        var families by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
        var musicFrom by remember { mutableStateOf<Set<String>>(emptySet()) }
        LaunchedEffect(Unit) {
            families = vm.musicFamilies(); musicFrom = families.keys
            options = vm.sharable().filter { it != SharedService.Seerr && it != SharedService.Music }; keep = options.toSet()
            needAway = vm.dispatcharrAway() == null; hasNd = vm.hasNavidrome()
            addons = vm.myAddons(); addonsOff = addons.filter { !it.enabled }.map { it.baseUrl }.toSet()
            plexUsers = vm.plexHomeUsers()
        }
        // On this TV: done once it's added.
        LaunchedEffect(sent, busy) { if (sent && !busy && !away && message?.contains("ready") == true) onClose() }
        // Where first: on this TV it's a short form (no password to make up, services all given unless opened up);
        // another house adds the password they'll type and the outside address.
        MenuItem(text = "Where", trailing = if (away) "In another house" else "On this TV", selected = false, onClick = { away = !away }, modifier = Modifier.focusRequester(first))
        Spacer(Modifier.height(8.dp))
        WizardField(name, { name = it }, "Name", null, KeyboardType.Text, if (away) ImeAction.Next else ImeAction.Done, {})
        if (away) {
            Spacer(Modifier.height(8.dp))
            WizardField(pass, { pass = it }, "Password (6+ characters, they type it once)", null, KeyboardType.Password, ImeAction.Done, {}, password = true)
        }
        if (away && needAway) {
            Text("Your Dispatcharr's public address, the one they'll type (asked once):", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 12.dp))
            WizardField(awayAddress, { awayAddress = it }, "e.g. tv.example.com", null, KeyboardType.Uri, ImeAction.Done, {})
        }
        var pickServices by remember { mutableStateOf(false) }
        if (options.isNotEmpty() && !away && !pickServices) {
            Spacer(Modifier.height(8.dp))
            MenuItem(text = "They get", trailing = if (keep == options.toSet()) "Everything this TV has" else "Your choice", selected = false, onClick = { pickServices = true })
        }
        if (options.isNotEmpty() && (away || pickServices)) {
            Text("They get (live TV is theirs already)", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 12.dp))
            options.forEach { s ->
                val on = s in keep
                MenuItem(text = s.label, trailing = if (on) "✓" else "–", selected = on, onClick = { keep = if (on) keep - s else keep + s })
                // Each addon on its own, under Addons.
                if (s == SharedService.Addons && on && addons.size > 1) addons.forEach { ad ->
                    val adOn = ad.baseUrl !in addonsOff
                    MenuItem(text = "    ${ad.name}", trailing = if (adOn) "✓" else "–", selected = adOn, onClick = { addonsOff = if (adOn) addonsOff + ad.baseUrl else addonsOff - ad.baseUrl })
                }
            }
        }
        plexUsers?.let { pu ->
            val label = when (plexMode) {
                0 -> "Not given"
                1 -> "New Plex Home user" + (name.trim().takeIf { it.isNotEmpty() }?.let { " \"$it\"" } ?: "")
                else -> pu.getOrNull(plexMode - 2)?.title ?: "Not given"
            }
            Spacer(Modifier.height(8.dp))
            MenuItem(text = "Plex (their own watch history)", trailing = label, selected = plexMode != 0, onClick = { plexMode = (plexMode + 1) % (pu.size + 2) })
        }
        // Music from the servers they'll have: Plex through their Plex user, Jellyfin / Emby when given.
        val musicKinds = families.keys.filter { k -> when (k) { "Plex" -> plexUsers != null && plexMode != 0; "Jellyfin" -> SharedService.Jellyfin in keep; "Emby" -> SharedService.Emby in keep; else -> false } }
        if (hasNd || musicKinds.isNotEmpty()) Spacer(Modifier.height(8.dp))
        if (hasNd) MusicPicker(musicMode, { musicMode = it }, ndUser, { ndUser = it }, ndPass, { ndPass = it }, ndAdmin, { ndAdmin = it }, canCreate = true)
        MusicFrom(musicKinds, musicFrom) { musicFrom = it }
        Text("They can", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 12.dp))
        PermsList(perms, null) { perms = it }
        // Another house: anything they'd get on a home-only address won't reach them. Said plainly, before adding.
        var homeOnly by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(away, keep, addonsOff, musicMode, perms.seerr) { homeOnly = if (away) vm.homeOnly(if (perms.seerr) keep + SharedService.Seerr else keep, addonsOff, musicMode != 0) else emptyList() }
        if (homeOnly.isNotEmpty()) {
            Text("⚠ These are on a home address, so they won't work in another house. Add them in Viewarr with their public address to share them:", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp))
            homeOnly.forEach { Text("• $it", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 24.dp, top = 2.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        MenuItem(text = if (busy) "Adding…" else if (away) "Add and show what to send them" else "Add to this TV", selected = false, onClick = {
            if (!busy) {
                sent = true
                val plex = when (plexMode) {
                    0 -> PlexChoice.None
                    1 -> PlexChoice.New(name.trim().replaceFirstChar { it.uppercase() })
                    else -> plexUsers?.getOrNull(plexMode - 2)?.let { PlexChoice.Existing(it.id, it.title) } ?: PlexChoice.None
                }
                vm.addSomeone(name, pass, away, awayAddress, if (perms.seerr) keep + SharedService.Seerr else keep, perms, musicChoice(musicMode, ndUser, ndPass, ndAdmin), addonsOff, if (plexUsers == null) PlexChoice.None else plex, musicFrom.filter { k -> when (k) { "Plex" -> plex != PlexChoice.None; "Jellyfin" -> SharedService.Jellyfin in keep; "Emby" -> SharedService.Emby in keep; else -> false } }.toSet())
            }
        })
        message?.let { Text(it, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp, start = 12.dp)) }
    }
}

/** [u]'s Plex: a new Plex Home user, one already in your Home, or none. */
@Composable
private fun PlexDialog(u: DUser, vm: ProfilesViewModel, onCancel: () -> Unit, onSave: (PlexChoice) -> Unit) = DialogBox("Plex for ${u.username}", onCancel) { first ->
    var users by remember { mutableStateOf<List<PlexService.HomeUser>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { users = vm.plexHomeUsers(); loaded = true }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val newName = u.username.replaceFirstChar { it.uppercase() }
    Text(
        (u.share?.plexUser?.let { "Now: Plex Home user $it. " } ?: "") + "Their own Plex Home user gives them their own watch history and On Deck on your server (managed users need no Plex account).",
        fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 10.dp),
    )
    when {
        !loaded -> Text("Loading your Plex Home…", fontSize = 13.sp, color = AppColors.TextSecondary)
        users == null -> Text("This TV isn't signed in to Plex.", fontSize = 13.sp, color = AppColors.Accent)
        else -> {
            val existing = users.orEmpty().firstOrNull { it.title.equals(newName, true) }
            if (existing == null) MenuItem(text = "New Plex Home user \"$newName\"", selected = false, onClick = { if (!busy) onSave(PlexChoice.New(newName)) }, modifier = Modifier.focusRequester(first))
            users.orEmpty().forEachIndexed { i, h ->
                MenuItem(text = h.title, trailing = if (h.managed) "managed" else "Plex account", selected = h.title == u.share?.plexUser,
                    onClick = { if (!busy) onSave(PlexChoice.Existing(h.id, h.title)) },
                    modifier = if (existing != null && i == 0) Modifier.focusRequester(first) else Modifier)
            }
            MenuItem(text = "No Plex", selected = false, onClick = { if (!busy) onSave(PlexChoice.None) })
        }
    }
}

/** Backups of this TV's whole setup in the Viewarr plugin (the same view as Settings → Backup & sync has). */
@Composable
private fun BackupsDialog(@Suppress("UNUSED_PARAMETER") vm: ProfilesViewModel, onClose: () -> Unit) = DialogBox("Backups", onClose) { first ->
    io.github.petebutty.viewarr.ui.sync.PluginBackupsContent(first)
}

/** What to send someone in another house, big and plain: the address, their username and password, and the steps. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DetailsShown(d: ProfilesViewModel.Details, onClose: () -> Unit) {
    val done = remember { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    BackHandler { onClose() }
    LaunchedEffect(d) { done.grab { inside } }
    Box(
        Modifier.fillMaxSize().background(AppColors.Background).onFocusChanged { inside = it.hasFocus }
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(620.dp)) {
            Text("${d.who} is ready", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Send ${d.who} these three:", fontSize = 16.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 12.dp))
            listOf("Address" to d.address, "Username" to d.who, "Password" to d.password).forEach { (k, v) ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(k, fontSize = 20.sp, color = AppColors.TextSecondary, modifier = Modifier.width(150.dp), maxLines = 1, softWrap = false)
                    Text(v, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("On their TV: install Viewarr, choose Someone gave me sign-in details, and type these three. Everything else is set up for them.", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 16.dp))
            Spacer(Modifier.height(20.dp))
            MenuItem(text = "Done", selected = false, onClick = onClose, modifier = Modifier.focusRequester(done))
        }
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
        val server = InviteShareServer(who, listOf("Invite" to c))
        shareUrl = server.start()
        onDispose { server.stop() }
    }
    Box(
        Modifier.fillMaxSize().background(AppColors.Background).onFocusChanged { inside = it.hasFocus }
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
            val side = (io.github.petebutty.viewarr.ui.common.LocalCanvas.current.height * 0.82f)
            Box(Modifier.size(side).background(Color.White, RoundedCornerShape(10.dp)).padding(12.dp)) {
                QrImage(shareUrl ?: c, Modifier.fillMaxSize(), dense = shareUrl == null)
            }
            Spacer(Modifier.width(36.dp))
            Column(Modifier.width(380.dp)) {
                Text("Invite for $who", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (shareUrl != null) "1. Scan this with your phone's camera (phone on this Wi-Fi): a page opens with the invite. Tap Copy invite and send it to $who." else "1. Scan this with your phone's camera and send the text to $who.", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 14.dp))
                Text("2. On their TV they install Viewarr, scan its setup code with their phone and choose I have an invite.", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 8.dp))
                Text("3. They paste it and type their password. That's it.", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(20.dp))
                MenuItem(text = "Done", selected = false, onClick = onClose, modifier = Modifier.focusRequester(done))
            }
        }
    }
}

/** How someone gets Plex: not at all, an existing Plex Home user, or a new managed user in your Plex Home. */
sealed interface PlexChoice {
    data object None : PlexChoice
    data class Existing(val id: String, val title: String) : PlexChoice
    data class New(val name: String) : PlexChoice
}

/** How someone gets music: not at all, their existing Navidrome login, or a new Navidrome user made for them. */
sealed interface MusicChoice {
    data object None : MusicChoice
    /** Leave their Navidrome login as it is (changing only the other music). */
    data object Keep : MusicChoice
    data class Existing(val user: String, val password: String) : MusicChoice
    data class Create(val adminPassword: String) : MusicChoice
}

/** The music part of Add someone and Music…: which way, and the logins it needs. */
@Composable
private fun MusicPicker(mode: Int, onMode: (Int) -> Unit, user: String, onUser: (String) -> Unit, pass: String, onPass: (String) -> Unit, admin: String, onAdmin: (String) -> Unit, canCreate: Boolean, firstLabel: String = "Not given") {
    val labels = listOf(firstLabel, "Their existing login", "Make a user for them")
    MenuItem(text = "Music: Navidrome", trailing = labels[mode], selected = false, onClick = { onMode((mode + 1) % (if (canCreate) 3 else 2)) })
    when (mode) {
        1 -> {
            Text("Their Navidrome username and password (checked with Navidrome).", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
            WizardField(user, onUser, "Navidrome username", null, KeyboardType.Text, ImeAction.Next, {})
            Spacer(Modifier.height(6.dp))
            WizardField(pass, onPass, "Navidrome password", null, KeyboardType.Password, ImeAction.Done, {}, password = true)
        }
        2 -> {
            Text("Same name and password as their Viewarr login. Your Navidrome password is used once to make it, not kept.", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
            WizardField(admin, onAdmin, "Your Navidrome password", null, KeyboardType.Password, ImeAction.Done, {}, password = true)
        }
    }
}

/** Music from your other servers, one switch each (only the kinds they can reach are offered). */
@Composable
private fun MusicFrom(available: List<String>, on: Set<String>, onChange: (Set<String>) -> Unit) {
    available.forEach { k ->
        val isOn = k in on
        MenuItem(text = "Music: $k", trailing = if (isOn) "✓" else "–", selected = isOn, onClick = { onChange(if (isOn) on - k else on + k) })
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
    var mode by remember { mutableIntStateOf(0) }
    var user by remember { mutableStateOf(u.username) }
    var pass by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf("") }
    var viewarrPass by remember { mutableStateOf("") }
    var families by remember { mutableStateOf<List<String>>(emptyList()) }
    var from by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { vm.musicKindsFor(u).let { (reach, on) -> families = reach; from = on } }
    Text("Navidrome with their own login (their plays, favourites and playlists), and the music on your Plex (through their Plex user), Jellyfin and Emby.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
    MusicPicker(mode, { mode = it }, user, { user = it }, pass, { pass = it }, admin, { admin = it }, canCreate = true, firstLabel = "Keep as it is")
    if (mode == 2) {
        Spacer(Modifier.height(6.dp))
        WizardField(viewarrPass, { viewarrPass = it }, "Their password for the new Navidrome user", null, KeyboardType.Password, ImeAction.Done, {}, password = true)
    }
    Spacer(Modifier.height(10.dp))
    MusicFrom(families, from) { from = it }
    MenuItem(text = "Save", selected = false, onClick = { vm.setMusic(u, viewarrPass, if (mode == 0) MusicChoice.Keep else musicChoice(mode, user, pass, admin), from); onDone() }, modifier = Modifier.focusRequester(first))
}

