package io.github.petebutty.viewarr.data.sync

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
            sessions.session.filterNotNull().map { it }.distinctUntilChanged { a, b -> a.serverUrl == b.serverUrl && a.username == b.username && a.apiKey == b.apiKey }
                .collect { s -> if (!s.isXc) runCatching { onSignedIn(s) }.onFailure { Log.w(TAG, "sync check failed", it) } }
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
        val restored = apply(copy, s)
        setMode(SyncMode.On)
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
        val s = sessions.session.first()?.takeIf { !it.isXc } ?: return "Sign in to Dispatcharr first."
        val copy = runCatching { fetch(s) }.getOrNull() ?: return "Nothing saved on Dispatcharr yet."
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

    // ---------------------------------------------------------------- signing in

    private suspend fun onSignedIn(s: Session) {
        val mode = currentMode()
        if (mode == SyncMode.Off) return
        val copy = fetch(s)
        val me = accounts.current().deviceId
        when (mode) {
            SyncMode.Unset -> when {
                copy == null || copy.str("deviceId") == me -> setMode(SyncMode.On)
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
    }

    private fun offerOf(copy: JsonObject) = SyncOffer(
        device = copy.str("device") ?: "another TV",
        savedAt = copy.long("savedAt") ?: 0,
        hasSignIns = copy.str("signIns") != null,
    )

    // ---------------------------------------------------------------- saving

    private suspend fun pushIfChanged(force: Boolean = false) = lock.withLock {
        if (currentMode() != SyncMode.On || _offer.value != null) return@withLock
        // Never save a half-set-up TV over a good copy (e.g. right after signing out and back in).
        if (accounts.setupComplete.first() == false) return@withLock
        val s = sessions.session.first()?.takeIf { !it.isXc } ?: return@withLock
        val signIns = context.syncStore.data.first()[SIGN_INS] ?: true
        val prefs = exportPrefs()
        val acct = if (signIns) accountsJson() else null
        val hash = sha256(prefs.toString() + "|" + (acct ?: "-"))
        if (!force && hash == context.syncStore.data.first()[LAST_HASH]) return@withLock
        val now = System.currentTimeMillis()
        val body = buildJsonObject {
            put("v", 1)
            put("savedAt", now)
            put("device", deviceName())
            put("deviceId", accounts.current().deviceId)
            put("prefs", prefs)
            acct?.let { put("signIns", encrypt(it, s)) }
        }
        val r = http.put(url(s), body, headers(s))
        when {
            r.code in 200..299 -> {
                context.syncStore.edit { it[LAST_HASH] = hash; it[APPLIED_AT] = now }
                _message.value = null
            }
            r.code == 413 -> _message.value = "Too much to save on Dispatcharr (over 128 KB)."
            r.code == 404 -> _message.value = "This Dispatcharr version can't store app settings (needs 0.33 or newer)."
            else -> Log.w(TAG, "save answered ${r.code}: ${r.body.str("code") ?: ""}")
        }
    }

    private suspend fun forgetLastPush() = context.syncStore.edit { it.remove(LAST_HASH) }

    // ---------------------------------------------------------------- reading / applying

    private suspend fun fetch(s: Session): JsonObject? {
        val r = http.get(url(s), headers(s))
        return if (r.code in 200..299) r.body as? JsonObject else null
    }

    /** Applies a saved copy. True when its sign-ins were restored too. */
    private suspend fun apply(copy: JsonObject, s: Session): Boolean {
        (copy["prefs"] as? JsonObject)?.let { importPrefs(it) }
        var restored = false
        copy.str("signIns")?.let { enc ->
            val plain = runCatching { decrypt(enc, s) }.getOrNull()
            if (plain == null) {
                _message.value = "The saved sign-ins couldn't be read (the Dispatcharr API key has changed)."
            } else {
                val incoming = runCatching { json.decodeFromString(Accounts.serializer(), plain) }.getOrNull()
                if (incoming != null) {
                    val me = accounts.current().deviceId
                    accounts.update { incoming.copy(deviceId = me) }
                    restored = true
                }
            }
        }
        // What this TV now has is what the server has: don't send it straight back.
        val signIns = context.syncStore.data.first()[SIGN_INS] ?: true
        val hash = sha256(exportPrefs().toString() + "|" + (if (signIns) accountsJson() else "-"))
        context.syncStore.edit { it[LAST_HASH] = hash; it[APPLIED_AT] = copy.long("savedAt") ?: System.currentTimeMillis() }
        return restored
    }

    /** The sign-ins as JSON, without this TV's own device id. */
    private suspend fun accountsJson(): String {
        val full = json.encodeToJsonElement(Accounts.serializer(), accounts.current()).jsonObject
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

    /** {"t": type, "v": value}: Preferences keep their type, which plain JSON would lose. */
    private fun encodeValue(v: Any): JsonObject? = when (v) {
        is String -> buildJsonObject { put("t", "s"); put("v", v) }
        is Boolean -> buildJsonObject { put("t", "b"); put("v", v) }
        is Int -> buildJsonObject { put("t", "i"); put("v", v) }
        is Long -> buildJsonObject { put("t", "l"); put("v", v) }
        is Float -> buildJsonObject { put("t", "f"); put("v", v) }
        is Double -> buildJsonObject { put("t", "d"); put("v", v) }
        is Set<*> -> buildJsonObject { put("t", "ss"); put("v", JsonArray(v.filterIsInstance<String>().sorted().map(::JsonPrimitive))) }
        else -> null
    }

    private fun setValue(p: androidx.datastore.preferences.core.MutablePreferences, key: String, e: kotlinx.serialization.json.JsonElement) {
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
            runCatching { android.provider.Settings.Global.getString(context.contentResolver, k) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { android.provider.Settings.Secure.getString(context.contentResolver, k) }.getOrNull()?.takeIf { it.isNotBlank() }
        } ?: (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL)

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "ViewarrSync"
        const val APP_KEY = "io.github.petebutty.viewarr"
        val MODE = stringPreferencesKey("mode")
        val SIGN_INS = booleanPreferencesKey("sign_ins")
        val LAST_HASH = stringPreferencesKey("last_hash")
        val APPLIED_AT = longPreferencesKey("applied_at")

        /** Settings that belong to the box itself, never copied between TVs. */
        val LOCAL_ONLY = mapOf(
            "playback" to setOf("passthrough", "downmix", "mpv_decoding", "frame_rate", "picture_size", "deinterlace", "buffer", "max_res", "audio_delay"),
        )
    }
}
