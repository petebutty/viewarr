package io.github.petebutty.viewarr.data.profiles

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.media.LibraryViews
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.asArray
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.session.KeystoreCipher
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.data.sync.SettingsSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Profiles: each person is a Dispatcharr user. A TV can hold several ("Who's watching?"); each has its own setup
 * (settings, sign-ins, library sorts…), kept on the TV and on that user's Dispatcharr account, so it follows them
 * to any TV they sign in on.
 */

/** A person on this TV: their Dispatcharr user (the key is theirs, so live TV limits and their saved setup apply). */
@Serializable
data class Profile(
    val serverUrl: String,
    val username: String,
    val apiKey: String,
    /** Picker colour (index into the palette). */
    val color: Int = 0,
    /** Asked for when switching to this profile (set by its owner; stored only on this TV, encrypted). */
    val pin: String? = null,
) {
    val id get() = serverUrl.trimEnd('/') + "|" + username
    val session get() = Session(serverUrl, username, apiKey)
    val initial get() = username.firstOrNull()?.uppercaseChar() ?: '?'
}

@Serializable
internal data class ProfileList(val profiles: List<Profile> = emptyList(), val active: String? = null)

private val Context.profileStore by preferencesDataStore(name = "profiles")

@Singleton
class ProfileStore @Inject constructor(@ApplicationContext private val context: Context, private val json: Json) {
    private val LIST = stringPreferencesKey("list")
    private val ASK = booleanPreferencesKey("ask_on_start")

    private fun decode(s: String?): ProfileList = s?.let(KeystoreCipher::decrypt)?.let { runCatching { json.decodeFromString<ProfileList>(it) }.getOrNull() } ?: ProfileList()

    val profiles: Flow<List<Profile>> = context.profileStore.data.map { decode(it[LIST]).profiles }
    val activeId: Flow<String?> = context.profileStore.data.map { decode(it[LIST]).active }
    /** "Who's watching?" each time Viewarr opens (with more than one profile). */
    /** "Who's watching?" when Viewarr opens: off unless switched on (it opens on the last profile used). */
    val askOnStart: Flow<Boolean> = context.profileStore.data.map { it[ASK] ?: false }

    internal suspend fun list() = decode(context.profileStore.data.first()[LIST])

    private suspend fun write(l: ProfileList) = context.profileStore.edit { it[LIST] = KeystoreCipher.encrypt(json.encodeToString(ProfileList.serializer(), l)) }

    suspend fun upsert(p: Profile) {
        val l = list()
        val existing = l.profiles.firstOrNull { it.id == p.id }
        val updated = if (existing != null) l.profiles.map { if (it.id == p.id) p.copy(color = existing.color, pin = p.pin ?: existing.pin) else it }
        else l.profiles + p.copy(color = l.profiles.size % PROFILE_COLOURS)
        write(l.copy(profiles = updated))
    }
    suspend fun remove(id: String) {
        val l = list()
        write(l.copy(profiles = l.profiles.filter { it.id != id }, active = l.active.takeIf { it != id }))
        context.profileStore.edit { it.remove(stringPreferencesKey("snap_$id")) }
    }
    suspend fun setActive(id: String?) = write(list().copy(active = id))
    /** [pin] null or blank: none. */
    suspend fun setPin(id: String, pin: String?) { val l = list(); write(l.copy(profiles = l.profiles.map { if (it.id == id) it.copy(pin = pin?.takeIf { p -> p.isNotBlank() }) else it })) }
    suspend fun setAskOnStart(on: Boolean) = context.profileStore.edit { it[ASK] = on }

    /** A profile's setup kept on this TV (encrypted). */
    suspend fun saveSnapshot(id: String, snap: JsonObject) = context.profileStore.edit { it[stringPreferencesKey("snap_$id")] = KeystoreCipher.encrypt(snap.toString()) }
    suspend fun snapshot(id: String): JsonObject? =
        context.profileStore.data.first()[stringPreferencesKey("snap_$id")]?.let(KeystoreCipher::decrypt)?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
}

const val PROFILE_COLOURS = 8

/** A Dispatcharr user, as an admin sees it. */
data class DUser(
    val id: Int, val username: String, val level: Int, val streamLimit: Int, val channelProfiles: List<Int>,
    val apiKey: String?, val lastLogin: String?,
    /** What they may do (Viewarr's locks and Dispatcharr's own recording / catch-up / VOD flags). */
    val perms: Perms = Perms(),
) {
    val levelName get() = when (level) { 0 -> "Streamer"; 1 -> "Standard"; 10 -> "Admin"; else -> "Level $level" }
}

/** A Dispatcharr channel profile (which channels a user sees). */
data class DChannelProfile(val id: Int, val name: String)

/** Managing Dispatcharr users from Viewarr (admin accounts only). */
@Singleton
class DispatcharrUsers @Inject constructor(private val http: HttpJson) {
    private fun base(s: Session) = s.serverUrl.trimEnd('/') + "/api/accounts"
    private fun h(s: Session) = mapOf("X-API-Key" to s.apiKey)

    private fun user(j: JsonElement) = DUser(
        id = j.int("id") ?: 0, username = j.str("username").orEmpty(), level = j.int("user_level") ?: 0,
        streamLimit = j.int("stream_limit") ?: 0,
        channelProfiles = j.array("channel_profiles").mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() },
        apiKey = j.str("api_key")?.takeIf { it.isNotBlank() }, lastLogin = j.str("last_login"),
        perms = Perms.from(j.child("custom_properties") as? JsonObject),
    )

    /** The signed-in account's level (10 = admin). */
    suspend fun myLevel(s: Session): Int = runCatching { http.get(base(s) + "/users/me/", h(s)).body.int("user_level") }.getOrNull() ?: 0

    suspend fun users(s: Session): List<DUser> {
        val r = http.get(base(s) + "/users/", h(s))
        if (!r.ok) error("Dispatcharr didn't list its users (error ${r.code}).")
        val arr = (r.body as? JsonArray) ?: r.body.array("results").let { JsonArray(it) }
        return arr.map(::user).sortedBy { it.username.lowercase() }
    }

    suspend fun channelProfiles(s: Session): List<DChannelProfile> = runCatching {
        val r = http.get(s.serverUrl.trimEnd('/') + "/api/channels/profiles/", h(s))
        r.body.asArray().mapNotNull { p -> p.int("id")?.let { DChannelProfile(it, p.str("name") ?: "Profile $it") } }
    }.getOrDefault(emptyList())

    /** A new user (Standard: Viewarr needs at least that). */
    suspend fun create(s: Session, username: String, password: String, streamLimit: Int = 0, channelProfiles: List<Int> = emptyList()): Result<DUser> {
        val body = buildJsonObject {
            put("username", username.trim()); put("password", password); put("user_level", 1); put("stream_limit", streamLimit)
            put("channel_profiles", JsonArray(channelProfiles.map { JsonPrimitive(it) }))
        }
        val r = http.post(base(s) + "/users/", body, h(s))
        return if (r.ok) Result.success(user(r.body!!)) else Result.failure(Exception(errorText(r.body, "create that user")))
    }

    suspend fun update(s: Session, id: Int, password: String? = null, streamLimit: Int? = null, channelProfiles: List<Int>? = null, props: JsonObject? = null): Result<Unit> {
        val body = buildJsonObject {
            props?.let { put("custom_properties", it) }
            password?.let { put("password", it) }
            streamLimit?.let { put("stream_limit", it) }
            channelProfiles?.let { put("channel_profiles", JsonArray(it.map { v -> JsonPrimitive(v) })) }
        }
        val r = http.patch(base(s) + "/users/$id/", body, h(s))
        return if (r.ok) Result.success(Unit) else Result.failure(Exception(errorText(r.body, "change that user")))
    }

    suspend fun delete(s: Session, id: Int): Boolean = http.delete(base(s) + "/users/$id/", h(s)).ok

    /** The user's API key: their existing one (making a new one would break their other apps), or a new one if none. */
    suspend fun apiKeyFor(s: Session, u: DUser): String? {
        u.apiKey?.let { return it }
        val r = http.post(base(s) + "/api-keys/generate/", buildJsonObject { put("user_id", u.id) }, h(s))
        return r.body.str("key")
    }

    private fun errorText(body: JsonElement?, what: String): String {
        val o = body as? JsonObject ?: return "Dispatcharr wouldn't $what."
        val first = o.entries.firstOrNull() ?: return "Dispatcharr wouldn't $what."
        val msg = (first.value as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content } ?: (first.value as? JsonPrimitive)?.content
        return msg?.let { if (first.key == "detail") it else "${first.key}: $it" } ?: "Dispatcharr wouldn't $what."
    }
}

/** Which of this TV's services an admin hands to someone else's profile. */
enum class SharedService(val label: String) {
    Jellyfin("Jellyfin"), Emby("Emby"), Addons("Addons"), ProviderVod("Provider VOD"),
    Music("Music (Navidrome / server music)"), Seerr("Seerr requests"), Tmdb("TMDB (posters and details)"), Mdblist("MDBList ratings");

    /** What [a] has for this service ("" = nothing to share). */
    fun has(a: Accounts) = when (this) {
        Jellyfin -> a.jellyfin != null; Emby -> a.emby != null; Addons -> a.addons.isNotEmpty()
        ProviderVod -> a.vodEnabled; Music -> a.musicOn; Seerr -> a.seerr != null; Tmdb -> a.tmdbKey != null; Mdblist -> a.mdblistKey != null
    }
}

/**
 * [a] with only [keep]. Trakt and Plex are personal, so never shared: Plex is theirs through a Plex Home user or
 * their own invited account, never this account's token. A new device id (it names the TV to Plex and others).
 */
fun Accounts.only(keep: Set<SharedService>): Accounts = Accounts(
    tmdbKey = tmdbKey.takeIf { SharedService.Tmdb in keep },
    away = away,
    jellyfin = jellyfin.takeIf { SharedService.Jellyfin in keep },
    emby = emby.takeIf { SharedService.Emby in keep },
    addons = if (SharedService.Addons in keep) addons else emptyList(),
    vodEnabled = vodEnabled && SharedService.ProviderVod in keep,
    seerr = seerr.takeIf { SharedService.Seerr in keep },
    mdblistKey = mdblistKey.takeIf { SharedService.Mdblist in keep },
    navidrome = navidrome.takeIf { SharedService.Music in keep },
    musicEnabled = musicEnabled.takeIf { SharedService.Music in keep },
    musicOff = if (SharedService.Music in keep) musicOff else emptySet(),
)

/** Switching between the people on this TV. */
@Singleton
class ProfileManager @Inject constructor(
    private val store: ProfileStore,
    private val sessions: SessionStore,
    private val sync: SettingsSync,
    private val libraryViews: LibraryViews,
) {
    private val lock = Mutex()
    val profiles = store.profiles
    val activeId = store.activeId

    /** Keeps the active profile in step with the signed-in Dispatcharr user (adds them the first time). */
    suspend fun adoptCurrent() = lock.withLock {
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return@withLock
        val p = Profile(s.serverUrl, s.username, s.apiKey)
        store.upsert(p)
        // Signed in as someone else (Accounts & sources): they're the one watching now.
        if (store.list().active != p.id) store.setActive(p.id)
        libraryViews.profile = p.id
    }

    /**
     * To [to]: the current person's setup is saved (on the TV and to their Dispatcharr copy), then [to]'s is put
     * back: from the TV if it has one, else from their Dispatcharr copy, else they start with a fresh setup.
     */
    suspend fun switchTo(to: Profile): String? = lock.withLock {
        val l = store.list()
        if (l.active == to.id) return@withLock null
        l.active?.let { cur ->
            sync.saveNow()
            store.saveSnapshot(cur, sync.snapshot())
        }
        sessions.save(to.session)
        val snap = store.snapshot(to.id) ?: sync.savedCopy(to.session)?.let { sync.snapshotOf(it, to.session) }
        if (snap != null) sync.restore(snap) else sync.restore(EMPTY)
        store.setActive(to.id)
        libraryViews.profile = to.id
        null
    }

    /** Adds [p] to this TV (an admin picking a user, or someone signing in) without switching to them. */
    suspend fun add(p: Profile) = store.upsert(p)

    suspend fun remove(id: String) {
        if (store.list().active == id) return
        store.remove(id)
    }

    companion object {
        /** A new person: this TV's look, but none of the previous person's sign-ins. */
        private val EMPTY = buildJsonObject { put("prefs", buildJsonObject { }); put("accounts", "{}"); put("setupComplete", true) }
    }
}
