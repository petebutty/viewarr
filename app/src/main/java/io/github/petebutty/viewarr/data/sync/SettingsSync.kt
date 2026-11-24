package io.github.petebutty.viewarr.data.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.long
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.data.settings.appearanceStore
import io.github.petebutty.viewarr.data.settings.playbackStore
import io.github.petebutty.viewarr.data.settings.remoteStore
import io.github.petebutty.viewarr.data.settings.rowLayoutStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncStore by preferencesDataStore(name = "settings_sync")

/** Whether this TV keeps its settings on Dispatcharr. Unset: not decided yet (nothing is sent until it is). */
enum class SyncMode { Unset, On, Off }

/** A copy saved on Dispatcharr by another TV, offered when this one signs in. */
data class SyncOffer(val device: String, val savedAt: Long, val hasSignIns: Boolean)

data class SyncStatus(val mode: SyncMode = SyncMode.Unset, val signIns: Boolean = true, val lastSaved: Long = 0, val message: String? = null)

/**
 * Viewarr's setup kept on the Dispatcharr server (per user, in Dispatcharr's app-data store): appearance, home rows
 * and libraries, playback and remote settings, and (encrypted) the Plex / Jellyfin / Emby / Seerr / Trakt / TMDB
 * sign-ins. A new TV that signs in to the same Dispatcharr user is offered the lot; TVs that have it switched on
 * pick up each other's changes when they start.
 *
 * Sign-ins are encrypted on the TV with a key made from the user's Dispatcharr API key (the same on every TV that
 * signs in as that user), so the server only ever stores ciphertext for them. Settings that depend on the box
 * itself (surround passthrough, decoding, frame rate, picture size, buffer…) stay on each TV.
 */
@Singleton
class SettingsSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val accounts: AccountStore,
    private val http: HttpJson,
    private val json: Json,
    private val history: SyncHistory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var started = false
    /** The copy last read from the server, while an offer is showing. */
    @Volatile private var pending: JsonObject? = null

    private val _offer = MutableStateFlow<SyncOffer?>(null)
    val offer = _offer.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    val status: Flow<SyncStatus> = combine(context.syncStore.data, _message) { p, m ->
        SyncStatus(
            mode = p[MODE]?.let { runCatching { SyncMode.valueOf(it) }.getOrNull() } ?: SyncMode.Unset,
            signIns = p[SIGN_INS] ?: true,
            lastSaved = p[APPLIED_AT] ?: 0,
            message = m,
        )
    }

    private val stores: Map<String, DataStore<Preferences>> by lazy {
        mapOf(
            "appearance" to context.appearanceStore,
            "row_layout" to context.rowLayoutStore,
            "playback" to context.playbackStore,
            "remote" to context.remoteStore,
        )
    }

    /** Starts watching: sign-ins (to offer / pick up a saved copy) and changes (to save them). Safe to call again. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // A check that couldn't finish (Dispatcharr not answering, sign-ins unreadable for a moment) is tried again a
            // few times, further apart each time, then left for the next start. A new sign-in replaces the waiting one.
            sessions.session.filterNotNull().distinctUntilChanged { a, b -> a.serverUrl == b.serverUrl && a.username == b.username && a.apiKey == b.apiKey }
                .collectLatest { s ->
                    if (s.isDirect) return@collectLatest
                    for (wait in CHECK_RETRY_WAITS) {
                        delay(wait)
                        val done = runCatching { onSignedIn(s) }.onFailure { Log.w(TAG, "sync check failed", it) }.getOrDefault(false)
                        if (done) break
                    }
                }
        }
        scope.launch {
            val changes: List<Flow<Any?>> = stores.values.map { it.data } + listOf(accounts.accounts, context.syncStore.data, accounts.setupComplete)
            combine(changes) { it.size }
                .debounce(4_000)
                .collect { runCatching { pushIfChanged() }.onFailure { Log.w(TAG, "sync save failed", it) } }
        }
    }

    // ---------------------------------------------------------------- choices

    /** "Restore": takes the offered copy (settings, and sign-ins if it has them) and switches syncing on. */
    suspend fun acceptOffer(): Boolean = lock.withLock {
        val copy = pending ?: return@withLock false
        val s = sessions.session.first() ?: return@withLock false
        val restored = restoreLocked(copy, s) ?: return@withLock false
        _offer.value = null
        pending = null
        if (restored) accounts.setSetupComplete(true)
        _message.value = if (restored) "Restored from ${copy.str("device") ?: "another TV"}." else "Settings restored; sign in to your services again."
        true
    }

    /** "Not now": this TV keeps its own settings and doesn't save them (Settings can switch it on later). */
    suspend fun declineOffer() {
        setMode(SyncMode.Off)
        _offer.value = null
        pending = null
    }

    suspend fun setEnabled(on: Boolean) {
        setMode(if (on) SyncMode.On else SyncMode.Off)
        if (on) { forgetLastPush(); scope.launch { runCatching { pushIfChanged(force = true) } } }
    }

    suspend fun setSignIns(on: Boolean) {
        context.syncStore.edit { it[SIGN_INS] = on }
    }

    /** Settings → "Restore from Dispatcharr": reads the saved copy now and offers it. */
    suspend fun checkNow(): String {
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return "Sign in to Dispatcharr first."
        val copy = copyResult(s).getOrElse { return "Couldn't read the saved copy from Dispatcharr. Try again in a moment." }
            ?: return "Nothing saved on Dispatcharr yet."
        pending = copy
        _offer.value = offerOf(copy)
        return ""
    }

    /** Signing out: forget the decision, so signing in again offers the saved copy instead of overwriting it. */
    suspend fun reset() {
        context.syncStore.edit { it.clear() }
        _offer.value = null
        pending = null
    }

    /**
     * Restoring [copy] (lock held): this TV's setup and the copy on the server go into the history first, so the
     * restore can be undone; then the copy is applied and saving is on. Null when nothing was done (this TV's
     * sign-ins can't be read just now: restoring over them waits); else whether its sign-ins came back too.
     */
    private suspend fun restoreLocked(copy: JsonObject, s: Session): Boolean? {
        val mine = accounts.currentReadable() ?: run {
            _message.value = "This TV's sign-ins can't be read just now. Try again in a moment."
            return null
        }
        keepBeforeRestore(s, mine)
        val restored = apply(copy, s)
        setMode(SyncMode.On)
        if (restored) accounts.setSetupComplete(true)
        return restored
    }

    /** The server's copy and (for a TV that's set up) this TV's own setup, as "Before restore on <TV>", into the history. */
    private suspend fun keepBeforeRestore(s: Session, mine: Accounts) {
        if (s.isDirect) return
        val copies = mutableListOf<Pair<JsonObject, String?>>()
        copyResult(s).getOrNull()?.let { copies += it to null }
        if (accounts.setupComplete.first() != false) runCatching {
            val signIns = context.syncStore.data.first()[SIGN_INS] ?: true
            copies += buildJsonObject {
                put("v", 1)
                put("savedAt", System.currentTimeMillis())
                put("device", deviceName())
                put("deviceId", mine.deviceId)
                put("prefs", exportPrefs())
                if (signIns) put("signIns", encrypt(accountsJson(mine), s))
            } to "Before restore on ${deviceName()}"
        }.onFailure { Log.w(TAG, "this TV's setup not kept before restoring", it) }
        archive(s, copies, routine = false, mineId = mine.deviceId)
    }

    /** What "Restore from Dispatcharr" lists: the main copy (null: nothing saved) and the earlier versions. */
    data class RestoreChoices(val latest: JsonObject?, val versions: List<SyncVersion>, val historyUnread: Boolean)

    suspend fun restoreChoices(): Result<RestoreChoices> {
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return Result.failure(IllegalStateException("Sign in to Dispatcharr first."))
        val latest = copyResult(s).getOrElse { return Result.failure(IllegalStateException("Couldn't read the saved copy from Dispatcharr. Try again in a moment.")) }
        val hist = history.list(s).onFailure { Log.w(TAG, "history not read: ${it.message}") }
        // A version that's the same as the main copy (just saved by hand) is shown once, as Latest.
        val versions = hist.getOrDefault(emptyList()).filterNot { v ->
            latest != null && v.savedAt == latest.long("savedAt") && v.deviceId == latest.str("deviceId").orEmpty()
        }
        return Result.success(RestoreChoices(latest, versions, hist.isFailure))
    }

    /** The saved copy inside an earlier version (null: it can't be unpacked). */
    fun copyOf(v: SyncVersion): JsonObject? = history.copyOf(v)

    /** How [copy] is described before restoring it (sign-ins only if they can be read with this login's key). */
    suspend fun offerFor(copy: JsonObject): SyncOffer {
        val s = sessions.session.first()
        return offerOf(copy).copy(hasSignIns = s != null && !s.isDirect && signInsOf(copy, s).getOrNull() != null)
    }

    /**
     * Settings → Restore from Dispatcharr: puts [copy] on this TV (see [restoreLocked]). An earlier [version] is
     * then saved as the main copy, so other TVs and the next start get it too. A message for the screen.
     */
    suspend fun restoreFrom(copy: JsonObject, version: Boolean): String {
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return "Sign in to Dispatcharr first."
        val restored = lock.withLock {
            restoreLocked(copy, s)?.also {
                // Newer than the main copy from now on: a failed save below can't let the next start undo the restore.
                if (version) context.syncStore.edit { it.remove(LAST_HASH); it[APPLIED_AT] = System.currentTimeMillis() }
            }
        } ?: return _message.value ?: "That couldn't be restored just now. Try again in a moment."
        if (version) scope.launch { runCatching { pushIfChanged(force = true) }.onFailure { Log.w(TAG, "save after restore failed", it) } }
        val from = copy.str("device") ?: "another TV"
        return when {
            restored -> "Restored from $from."
            copy.str("signIns") != null -> "Settings restored; its sign-ins couldn't be read."
            else -> "Settings restored."
        }
    }

    /** "Save a copy now": the server's copy into the history, and this TV's setup saved straight away (kept as a version too). */
    suspend fun saveCopyNow(): String {
        if (currentMode() != SyncMode.On) return "Switch on Save settings to Dispatcharr first."
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return "Sign in to Dispatcharr first."
        forgetLastPush()
        val body = runCatching { pushIfChanged(force = true) }.onFailure { Log.w(TAG, "save a copy failed", it) }.getOrNull()
            ?: return _message.value ?: "Not saved. Try again in a moment."
        // In the history straight away, so the next routine saves can't replace it.
        lock.withLock {
            val ok = runCatching { history.add(s, listOf(body to "Copy saved on ${deviceName()}")) }.onFailure { Log.w(TAG, "history not updated", it) }.getOrDefault(false)
            if (ok) context.syncStore.edit { it[LAST_ARCHIVE] = System.currentTimeMillis() }
        }
        return "Saved. Restore from Dispatcharr lists it."
    }

    // ---------------------------------------------------------------- profiles

    /** This TV's setup as it is now (settings and sign-ins), for keeping a profile's copy on the TV. */
    suspend fun snapshot(): JsonObject = buildJsonObject {
        put("prefs", exportPrefs())
        put("accounts", accountsJson())
        put("setupComplete", accounts.setupComplete.first() ?: true)
    }

    /** Puts a profile's setup back (from [snapshot]). Sign-ins keep this TV's own device id. */
    suspend fun restore(snap: JsonObject) = lock.withLock {
        (snap["prefs"] as? JsonObject)?.let { importPrefs(it) }
        snap.str("accounts")?.let { a ->
            runCatching { json.decodeFromString(Accounts.serializer(), a) }.getOrNull()?.let { incoming ->
                val me = accounts.current().deviceId
                // Away addresses stay if the copy has none (it was saved before they were set).
                accounts.update { cur -> incoming.copy(deviceId = me, away = incoming.away.ifEmpty { cur.away }) }
            }
        }
        accounts.setSetupComplete((snap["setupComplete"] as? JsonPrimitive)?.booleanOrNull ?: true)
        // The restored setup is this profile's; saving starts from here.
        context.syncStore.edit { it.remove(LAST_HASH); it[MODE] = SyncMode.On.name }
        _offer.value = null
        pending = null
    }

    /**
     * The saved copy for [s]'s user, telling apart "nothing saved" (success null: Dispatcharr says there's none) from
     * "couldn't find out" (failure: no answer, or an error). Changes to someone's setup must stop on a failure, or a
     * blip would replace their setup with a fresh one.
     */
    suspend fun copyResult(s: Session): Result<JsonObject?> = runCatching {
        val r = http.get(url(s), headers(s))
        when {
            r.code in 200..299 -> r.body as? JsonObject ?: error("Dispatcharr sent an unreadable copy.")
            r.code == 404 -> null
            else -> error("Dispatcharr answered ${r.code}.")
        }
    }

    /** A copy's sign-ins: null when it has none; a failure when they're there but can't be read (another key). */
    fun signInsOf(copy: JsonObject, s: Session): Result<Accounts?> {
        val enc = copy.str("signIns") ?: return Result.success(null)
        val plain = runCatching { decrypt(enc, s) }.getOrNull() ?: return Result.failure(IllegalStateException("their saved sign-ins can't be read"))
        return runCatching { json.decodeFromString(Accounts.serializer(), plain) }
    }

    /** The saved copy on Dispatcharr for [s]'s user, or null. */
    suspend fun savedCopy(s: Session): JsonObject? = copyResult(s).getOrNull()

    /** A saved copy as a snapshot (sign-ins decrypted with that user's key); null if there's nothing usable. */
    fun snapshotOf(copy: JsonObject, s: Session): JsonObject? {
        val prefs = copy["prefs"] as? JsonObject ?: return null
        val acct = copy.str("signIns")?.let { runCatching { decrypt(it, s) }.getOrNull() }
        // No readable sign-ins: start with none (never the previous person's).
        return buildJsonObject { put("prefs", prefs); put("accounts", acct ?: "{}"); put("setupComplete", true) }
    }

    /** Saves this TV's setup to the signed-in user's copy now (before switching profile). */
    suspend fun saveNow() { runCatching { pushIfChanged(force = true) } }

    /** This TV's name, as saved copies show it. */
    fun thisDevice(): String = deviceName()

    /**
     * An admin preparing another user's Viewarr: [prefs] and [signIns] (Accounts JSON) written into [user]'s copy
     * on Dispatcharr, the sign-ins encrypted with that user's own key, so their TV restores them when they sign in.
     */
    suspend fun writeFor(admin: Session, userId: Int, user: Session, prefs: JsonObject, signIns: String?): Boolean {
        val body = buildJsonObject {
            put("v", 1)
            put("savedAt", System.currentTimeMillis())
            put("device", "Set up by ${admin.username}")
            put("deviceId", "admin-" + admin.username)
            put("prefs", prefs)
            signIns?.let { put("signIns", encrypt(it, user)) }
        }
        val r = http.put(admin.serverUrl.trimEnd('/') + "/api/accounts/users/$userId/app-data/$APP_KEY/", body, headers(admin))
        return r.code in 200..299
    }

    /** This TV's whole setup as a backup: settings and sign-ins (encrypted with [s]'s key), as a saved copy is. */
    suspend fun backupBody(s: Session): JsonObject = buildJsonObject {
        put("v", 1)
        put("savedAt", System.currentTimeMillis())
        put("device", deviceName())
        put("deviceId", accounts.current().deviceId)
        put("prefs", exportPrefs())
        put("signIns", encrypt(accountsJson(), s))
    }

    /** Puts a backup ([backupBody]) back on this TV. True when its sign-ins came back too. */
    suspend fun restoreBackup(copy: JsonObject, s: Session): Boolean {
        val restored = lock.withLock { apply(copy, s) }
        if (restored) accounts.setSetupComplete(true)
        // Saved again from here, so the live copy on Dispatcharr matches what's now on this TV.
        forgetLastPush(); scope.launch { runCatching { pushIfChanged(force = true) } }
        return restored
    }

    /** This TV's settings and sign-ins as they'd be saved (for an admin to hand a copy to someone else). */
    suspend fun currentPrefs(): JsonObject = exportPrefs()
    suspend fun currentAccounts(): Accounts = accounts.current()

    // ---------------------------------------------------------------- signing in

    /**
     * Offers / picks up the saved copy. True when that's settled; false when it couldn't be found out (try later).
     * Only Dispatcharr saying there's no copy (404) counts as "nothing saved": any other answer leaves the choice
     * unmade, or a 500 or a proxy's 502 switched saving on and overwrote the copy that should have been offered.
     */
    private suspend fun onSignedIn(s: Session): Boolean {
        val mode = currentMode()
        if (mode == SyncMode.Off) return true
        // Sign-ins unreadable for a moment: the blank stand-in's device id isn't this TV's, and anything applied now
        // couldn't be checked against what's here. Wait for the retry.
        val mine = accounts.currentReadable() ?: run { Log.w(TAG, "sign-ins unreadable; sync check put off"); return false }
        val copy = copyResult(s).getOrElse { Log.w(TAG, "saved copy unavailable: ${it.message}"); return false }
        val me = mine.deviceId
        when (mode) {
            SyncMode.Unset -> when {
                copy == null || copy.str("deviceId") == me -> setMode(SyncMode.On)
                // A setup an admin prepared for this person, on a TV not set up yet: on it goes, no question.
                copy.str("deviceId")?.startsWith("admin-") == true && accounts.setupComplete.first() != true -> {
                    val restored = lock.withLock { apply(copy, s) }
                    // Sign-ins that couldn't be read: don't start saving (it would overwrite the prepared copy).
                    if (restored) { setMode(SyncMode.On); accounts.setSetupComplete(true) }
                    _message.value = if (restored) "Your setup from ${copy.str("device")?.removePrefix("Set up by ") ?: "your admin"} is ready." else null
                }
                else -> { pending = copy; _offer.value = offerOf(copy) }
            }
            SyncMode.On -> {
                // Another TV saved since this one last did: pick its changes up.
                val savedAt = copy?.long("savedAt") ?: 0
                if (copy != null && copy.str("deviceId") != me && savedAt > (context.syncStore.data.first()[APPLIED_AT] ?: 0)) {
                    lock.withLock { apply(copy, s) }
                    _message.value = "Settings updated from ${copy.str("device") ?: "another TV"}."
                }
            }
            SyncMode.Off -> Unit
        }
        return true
    }

    private fun offerOf(copy: JsonObject) = SyncOffer(
        device = copy.str("device") ?: "another TV",
        savedAt = copy.long("savedAt") ?: 0,
        hasSignIns = copy.str("signIns") != null,
    )

    // ---------------------------------------------------------------- saving

    /** Saves this TV's setup as the main copy if it changed ([force]: whatever). The copy saved, or null if none was. */
    private suspend fun pushIfChanged(force: Boolean = false): JsonObject? = lock.withLock {
        if (currentMode() != SyncMode.On || _offer.value != null) return@withLock null
        // Never save a half-set-up TV over a good copy (e.g. right after signing out and back in).
        if (accounts.setupComplete.first() == false) return@withLock null
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return@withLock null
        // Sign-ins saved but unreadable for a moment: what we'd see is a blank copy, and sending it would sign every
        // other TV out. Nothing is saved until they read again (the hash isn't updated, so the next change sends it).
        val mine = accounts.currentReadable() ?: run { Log.w(TAG, "sign-ins unreadable; save skipped"); return@withLock null }
        val signIns = context.syncStore.data.first()[SIGN_INS] ?: true
        val prefs = exportPrefs()
        val acct = if (signIns) accountsJson(mine) else null
        val hash = sha256(prefs.toString() + "|" + (acct ?: "-"))
        if (!force && hash == context.syncStore.data.first()[LAST_HASH]) return@withLock null
        // Never a blind overwrite: if another TV (or an admin) saved after this one last picked the copy up, theirs
        // would be lost. That's left alone and picked up at the next start instead. Asked for outright (switching
        // saving on, restoring a backup: [force]), this TV's copy wins. Can't read the copy at all: try next change.
        val server = copyResult(s).getOrElse { Log.w(TAG, "save put off: ${it.message}"); return@withLock null }
        if (!force && server != null && server.str("deviceId") != mine.deviceId &&
            (server.long("savedAt") ?: 0) > (context.syncStore.data.first()[APPLIED_AT] ?: 0)) {
            Log.w(TAG, "save skipped: ${server.str("device") ?: "another TV"} saved since this TV last picked up the copy")
            _message.value = "Not saved: ${server.str("device") ?: "another TV"} changed the settings since. They'll be picked up when Viewarr next starts."
            return@withLock null
        }
        // The copy about to be replaced goes into the history first (see [archive]); never in the way of the save.
        if (server != null) archive(s, listOf(server to null), routine = !force, mineId = mine.deviceId)
        val now = System.currentTimeMillis()
        val body = buildJsonObject {
            put("v", 1)
            put("savedAt", now)
            put("device", deviceName())
            put("deviceId", mine.deviceId)
            put("prefs", prefs)
            acct?.let { put("signIns", encrypt(it, s)) }
        }
        val r = http.put(url(s), body, headers(s))
        when {
            r.code in 200..299 -> {
                context.syncStore.edit { it[LAST_HASH] = hash; it[APPLIED_AT] = now }
                _message.value = null
                return@withLock body
            }
            r.code == 413 -> _message.value = "Too much to save on Dispatcharr (over 128 KB)."
            r.code == 404 -> _message.value = "This Dispatcharr version can't store app settings (needs 0.33 or newer)."
            else -> Log.w(TAG, "save answered ${r.code}: ${r.body.str("code") ?: ""}")
        }
        null
    }

    /**
     * Puts [copies] into the version history. A [routine] save (this TV's own small changes) only archives once
     * every 30 minutes, so a run of small saves can't push older versions out; another TV's copy, and anything
     * asked for outright, always goes in. Failing here is logged and never stops the save that follows.
     */
    private suspend fun archive(s: Session, copies: List<Pair<JsonObject, String?>>, routine: Boolean, mineId: String) {
        val now = System.currentTimeMillis()
        val last = context.syncStore.data.first()[LAST_ARCHIVE] ?: 0
        if (routine && copies.all { it.first.str("deviceId") == mineId } && now - last in 0 until ARCHIVE_EVERY) return
        val ok = runCatching { history.add(s, copies) }.onFailure { Log.w(TAG, "history not updated", it) }.getOrDefault(false)
        if (ok) context.syncStore.edit { it[LAST_ARCHIVE] = now }
    }

    private suspend fun forgetLastPush() = context.syncStore.edit { it.remove(LAST_HASH) }

    // ---------------------------------------------------------------- reading / applying

    /** Applies a saved copy. True when its sign-ins were restored too. */
    private suspend fun apply(copy: JsonObject, s: Session): Boolean {
        // This TV's sign-ins unreadable for a moment: nothing is applied (the stamp below would be measured against a
        // blank copy, and the next save would then send it). The callers' checks normally stop it before here.
        val mine = accounts.currentReadable() ?: run { Log.w(TAG, "sign-ins unreadable; copy not applied"); return false }
        (copy["prefs"] as? JsonObject)?.let { importPrefs(it) }
        var restored = false
        copy.str("signIns")?.let { enc ->
            val plain = runCatching { decrypt(enc, s) }.getOrNull()
            if (plain == null) {
                _message.value = "The saved sign-ins couldn't be read (the Dispatcharr API key has changed)."
            } else {
                val incoming = runCatching { json.decodeFromString(Accounts.serializer(), plain) }.getOrNull()
                if (incoming != null) {
                    // Away addresses stay if the copy has none (it was saved before they were set). Trakt: whichever login
                    // is newer (its refresh token works once: an older copy from another TV signed Trakt out here).
                    accounts.update { cur ->
                        val trakt = listOfNotNull(cur.trakt, incoming.trakt).maxByOrNull { it.expiresAt }
                        incoming.copy(deviceId = cur.deviceId, away = incoming.away.ifEmpty { cur.away }, trakt = if (incoming.trakt == null) null else trakt)
                    }
                    restored = true
                }
            }
        }
        // What this TV now has is what the server has: don't send it straight back.
        val signIns = context.syncStore.data.first()[SIGN_INS] ?: true
        val now = accounts.currentReadable() ?: mine
        val hash = sha256(exportPrefs().toString() + "|" + (if (signIns) accountsJson(now) else "-"))
        context.syncStore.edit { it[LAST_HASH] = hash; it[APPLIED_AT] = copy.long("savedAt") ?: System.currentTimeMillis() }
        return restored
    }

    /**
     * The sign-ins as JSON, without this TV's own device id. Unreadable for a moment: a failure, never a blank copy
     * (a backup or profile copy made from that would quietly lose every sign-in).
     */
    private suspend fun accountsJson(a: Accounts? = null): String {
        val acct = a ?: accounts.currentReadable() ?: error("This TV's sign-ins can't be read just now. Try again in a moment.")
        val full = json.encodeToJsonElement(Accounts.serializer(), acct).jsonObject
        return JsonObject(full.filterKeys { it != "deviceId" }).toString()
    }

    private suspend fun exportPrefs(): JsonObject = buildJsonObject {
        stores.forEach { (name, store) ->
            val skip = LOCAL_ONLY[name].orEmpty()
            val p = store.data.first()
            put(name, JsonObject(p.asMap().filterKeys { it.name !in skip }.entries.sortedBy { it.key.name }.mapNotNull { (k, v) ->
                encodeValue(v)?.let { k.name to it }
            }.toMap()))
        }
    }

    private suspend fun importPrefs(all: JsonObject) {
        stores.forEach { (name, store) ->
            val values = all[name] as? JsonObject ?: return@forEach
            val skip = LOCAL_ONLY[name].orEmpty()
            store.edit { p ->
                p.asMap().keys.filter { it.name !in skip }.forEach { p.remove(it) }
                values.forEach { (key, v) -> if (key !in skip) setValue(p, key, v) }
            }
        }
    }

    // ---------------------------------------------------------------- encryption

    private fun keyFor(s: Session): SecretKeySpec {
        val spec = PBEKeySpec(s.apiKey.toCharArray(), ("viewarr-sync:" + s.username).toByteArray(), 20_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun encrypt(plain: String, s: Session): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keyFor(s), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(enc: String, s: Session): String {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keyFor(s), GCMParameterSpec(128, all, 0, 12))
        return String(c.doFinal(all, 12, all.size - 12))
    }

    // ---------------------------------------------------------------- bits

    private suspend fun currentMode(): SyncMode =
        context.syncStore.data.first()[MODE]?.let { runCatching { SyncMode.valueOf(it) }.getOrNull() } ?: SyncMode.Unset

    private suspend fun setMode(m: SyncMode) = context.syncStore.edit { it[MODE] = m.name }

    private fun url(s: Session) = s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/$APP_KEY/"
    private fun headers(s: Session) = mapOf("X-API-Key" to s.apiKey)

    /** The TV's name as set in Android TV's settings (e.g. "Living room TV"), else its maker and model. */
    private fun deviceName(): String =
        listOf("device_name", "bluetooth_name").firstNotNullOfOrNull { k ->
            runCatching { Settings.Global.getString(context.contentResolver, k) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { Settings.Secure.getString(context.contentResolver, k) }.getOrNull()?.takeIf { it.isNotBlank() }
        } ?: (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL)

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    internal companion object {
        private const val TAG = "ViewarrSync"
        private const val APP_KEY = "io.github.petebutty.viewarr"
        val MODE = stringPreferencesKey("mode")
        val SIGN_INS = booleanPreferencesKey("sign_ins")
        val LAST_HASH = stringPreferencesKey("last_hash")
        val LAST_ARCHIVE = longPreferencesKey("last_archive")
        /** Routine saves archive the copy they replace at most this often. */
        const val ARCHIVE_EVERY = 30 * 60_000L
        val APPLIED_AT = longPreferencesKey("applied_at")
        /** The sign-in check: at once, then again after 30 s, 2 min, 10 min and 30 min; after that, next start. */
        val CHECK_RETRY_WAITS = listOf(0L, 30_000L, 120_000L, 600_000L, 1_800_000L)

        /**
         * Settings that belong to the box itself, never copied between TVs. The remote's buttons too (Settings →
         * Remote buttons): every TV can have a different remote, with different buttons and codes. Where to go
         * (Navigation: the hub, shortcuts, sports, the menu) is yours on every TV, so that's still copied.
         */
        val LOCAL_ONLY = mapOf(
            "playback" to setOf("passthrough", "downmix", "mpv_decoding", "frame_rate", "picture_size", "deinterlace", "buffer", "max_res", "audio_delay"),
            "remote" to setOf(
                "guide_left_v2", "guide_back_v2", "channel_keys", "zap_v2", "seek", "player_ok", "row_left", "media_back_v2",
                "menu_back", "preset_base", "live_sides", "search_button", "search_keycode", "buttons_v1", "bmap_v1",
            ),
            // (Internet copies: each box's own connection. 2026-11-24.)
            "appearance" to setOf("opt_zap_delay", "opt_away_max_mbps"),
        )

        /** {"t": type, "v": value}: Preferences keep their type, which plain JSON would lose. */
        internal fun encodeValue(v: Any): JsonObject? = when (v) {
            is String -> buildJsonObject { put("t", "s"); put("v", v) }
            is Boolean -> buildJsonObject { put("t", "b"); put("v", v) }
            is Int -> buildJsonObject { put("t", "i"); put("v", v) }
            is Long -> buildJsonObject { put("t", "l"); put("v", v) }
            is Float -> buildJsonObject { put("t", "f"); put("v", v) }
            is Double -> buildJsonObject { put("t", "d"); put("v", v) }
            is Set<*> -> buildJsonObject { put("t", "ss"); put("v", JsonArray(v.filterIsInstance<String>().sorted().map(::JsonPrimitive))) }
            else -> null
        }

        internal fun setValue(p: MutablePreferences, key: String, e: JsonElement) {
            val o = e as? JsonObject ?: return
            val v = o["v"] ?: return
            when (o.str("t")) {
                "s" -> (v as? JsonPrimitive)?.contentOrNull?.let { p[stringPreferencesKey(key)] = it }
                "b" -> (v as? JsonPrimitive)?.booleanOrNull?.let { p[booleanPreferencesKey(key)] = it }
                "i" -> (v as? JsonPrimitive)?.intOrNull?.let { p[intPreferencesKey(key)] = it }
                "l" -> (v as? JsonPrimitive)?.longOrNull?.let { p[longPreferencesKey(key)] = it }
                "f" -> (v as? JsonPrimitive)?.doubleOrNull?.let { p[floatPreferencesKey(key)] = it.toFloat() }
                "d" -> (v as? JsonPrimitive)?.doubleOrNull?.let { p[doublePreferencesKey(key)] = it }
                "ss" -> (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()?.let { p[stringSetPreferencesKey(key)] = it }
            }
        }
    }
}
