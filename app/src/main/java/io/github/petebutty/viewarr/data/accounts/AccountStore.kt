package io.github.petebutty.viewarr.data.accounts

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.music.NavidromeAuth
import io.github.petebutty.viewarr.data.session.KeystoreCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class TraktAuth(val accessToken: String, val refreshToken: String, val expiresAt: Long, val username: String)

@Serializable
data class PlexAuth(
    val token: String, val serverName: String, val serverUrl: String, val machineId: String,
    /** A Plex Home user's own plex.tv token (their [token] is the server's): used to find the server's addresses. */
    val accountToken: String? = null,
)

/** Seerr (Overseerr / Jellyseerr): where to send requests. */
@Serializable
data class SeerrAuth(val url: String, val apiKey: String, val userName: String? = null)

@Serializable
data class MediaServerAuth(val serverUrl: String, val serverName: String, val userId: String, val userName: String, val token: String)

@Serializable
data class AddonInfo(
    val baseUrl: String,
    val id: String,
    val name: String,
    /** Off = kept but unused: no rows, search results or streams from it (Settings → Addons). */
    val enabled: Boolean = true,
)

/** Every optional source. Stored as one Keystore-encrypted JSON blob; none of it is ever logged. */
@Serializable
data class Accounts(
    val deviceId: String = UUID.randomUUID().toString(),
    val tmdbKey: String? = null,
    val trakt: TraktAuth? = null,
    val plex: PlexAuth? = null,
    val jellyfin: MediaServerAuth? = null,
    val emby: MediaServerAuth? = null,
    val addons: List<AddonInfo> = emptyList(),
    val vodEnabled: Boolean = false,
    val seerr: SeerrAuth? = null,
    /** Your other Plex servers (found on the same Plex account); [plex] stays the main one. */
    val plexMore: List<PlexAuth> = emptyList(),
    /** MDBList API key (optional): IMDb, Rotten Tomatoes, Metacritic, Letterboxd and Trakt ratings for every title. */
    val mdblistKey: String? = null,
    /** Plex servers switched off in Settings (by machine id): left out of rows, search, libraries and playback. */
    val plexOff: Set<String> = emptySet(),
    /** Mark on Plex what Trakt says you've watched (Settings → Trakt). Off unless switched on: it changes Plex. */
    val traktToPlex: Boolean = false,
    /** Navidrome (Subsonic) for Music: address, user and a salted token (never the password). */
    val navidrome: NavidromeAuth? = null,
    /** Music sources switched off (MusicSource ids, e.g. "nd", "px…"): left out of the Music pages and search. */
    val musicOff: Set<String> = emptySet(),
    /** Music turned on in setup (Settings → Accounts & sources → Music). Off: no Music in the menu or search. */
    val musicEnabled: Boolean? = null,
    /**
     * Away addresses: a home address (scheme://host:port, e.g. http://192.168.1.50:9191) → the address that reaches
     * it from outside (https://tv.example.com). Away from home, requests to the home address go there instead.
     */
    val away: Map<String, String> = emptyMap(),
    /** Which watchlist Add / Remove and the "Your watchlist" row use: trakt, plex, jellyfin or emby (null: the first connected). */
    val watchlist: String? = null,
) {
    /** Music on: only once you choose it in setup (off by default). */
    val musicOn: Boolean get() = musicEnabled == true

    /** The addons switched on in Settings → Addons. */
    val activeAddons: List<AddonInfo> get() = addons.filter { it.enabled }
}

private val Context.accountsStore by preferencesDataStore(name = "accounts")

@Singleton
class AccountStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private fun decode(blob: String?): Accounts? =
        blob?.let(KeystoreCipher::decrypt)?.let { runCatching { json.decodeFromString<Accounts>(it) }.getOrNull() }

    // Nothing saved yet: one device id for this run (a new one on every read signed Plex / Jellyfin in under an id
    // that was never used again).
    // (Each read also tells WatchSource what's signed in: Watch progress and Send plays to Trakt need it.
    // Only a real read: a key store blip's blank copy would look like signed out.)
    val accounts: Flow<Accounts> = context.accountsStore.data.map { prefs ->
        val blob = prefs[BLOB]
        val a = decode(blob)
        if (a != null || blob == null) io.github.petebutty.viewarr.data.sync.WatchSource.note(a ?: Accounts())
        a ?: Accounts(deviceId = firstRunDeviceId)
    }

    /**
     * The sign-ins, or null while they're saved but can't be read (the box's key store failing for a moment). [accounts]
     * shows a blank copy then, which looks just like signed out: anything that would send or replace the sign-ins
     * (settings sync) reads this instead, so a passing key store blip never spreads a blank copy.
     */
    suspend fun currentReadable(): Accounts? {
        val blob = context.accountsStore.data.first()[BLOB]
        return if (blob == null) Accounts(deviceId = firstRunDeviceId) else decode(blob)
    }

    /** null = never recorded (installs from before the wizard had optional steps). */
    val setupComplete: Flow<Boolean?> = context.accountsStore.data.map { it[SETUP_COMPLETE] }

    suspend fun current(): Accounts = accounts.first()

    /**
     * Read, change and write in one step (two at once, e.g. a Trakt refresh and a Plex server refresh, lost one of them).
     * Saved sign-ins that can't be read right now (the box's key store failing for a moment) are never written over
     * with a blank copy: the change is skipped instead.
     */
    suspend fun update(transform: (Accounts) -> Accounts) {
        context.accountsStore.edit { prefs ->
            val blob = prefs[BLOB]
            val now = decode(blob)
            if (blob != null && now == null) { Log.w("AccountStore", "saved accounts unreadable; change skipped"); return@edit }
            prefs[BLOB] = KeystoreCipher.encrypt(json.encodeToString(Accounts.serializer(), transform(now ?: Accounts(deviceId = firstRunDeviceId))))
        }
    }

    suspend fun setSetupComplete(done: Boolean) {
        context.accountsStore.edit { it[SETUP_COMPLETE] = done }
    }

    suspend fun clear() {
        context.accountsStore.edit { it.clear() }
    }

    private companion object {
        val BLOB = stringPreferencesKey("accounts_enc")
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
    }
}

/** The device id used before anything is saved (kept for the whole run, then saved with the first sign-in). */
private val firstRunDeviceId: String by lazy { UUID.randomUUID().toString() }
