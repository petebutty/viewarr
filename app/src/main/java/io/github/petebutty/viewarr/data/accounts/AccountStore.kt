package io.github.petebutty.viewarr.data.accounts

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.petebutty.viewarr.data.session.KeystoreCipher
import dagger.hilt.android.qualifiers.ApplicationContext
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
data class PlexAuth(val token: String, val serverName: String, val serverUrl: String, val machineId: String)

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
) {
    /** The addons switched on in Settings → Addons. */
    val activeAddons: List<AddonInfo> get() = addons.filter { it.enabled }
}

private val Context.accountsStore by preferencesDataStore(name = "accounts")

@Singleton
class AccountStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    val accounts: Flow<Accounts> = context.accountsStore.data.map { prefs ->
        prefs[BLOB]?.let(KeystoreCipher::decrypt)?.let { runCatching { json.decodeFromString<Accounts>(it) }.getOrNull() }
            ?: Accounts()
    }

    /** null = never recorded (installs from before the wizard had optional steps). */
    val setupComplete: Flow<Boolean?> = context.accountsStore.data.map { it[SETUP_COMPLETE] }

    suspend fun current(): Accounts = accounts.first()

    suspend fun update(transform: (Accounts) -> Accounts) {
        val next = transform(current())
        context.accountsStore.edit { it[BLOB] = KeystoreCipher.encrypt(json.encodeToString(Accounts.serializer(), next)) }
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
