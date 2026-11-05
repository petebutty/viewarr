package io.github.petebutty.viewarr.data.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live TV sign-in. Dispatcharr: [apiKey]. IPTV provider (Xtream Codes): [xcPassword] is set and [apiKey] is empty;
 * [xcTimezone] is the provider's clock zone, needed to ask for catch-up.
 */
data class Session(
    val serverUrl: String,
    val username: String,
    val apiKey: String,
    val xcPassword: String? = null,
    val xcTimezone: String? = null,
) {
    val isXc get() = xcPassword != null
}

private val Context.sessionDataStore by preferencesDataStore(name = "session")

@Singleton
class SessionStore @Inject constructor(@ApplicationContext private val context: Context) {

    val session: Flow<Session?> = context.sessionDataStore.data.map { prefs ->
        val url = prefs[SERVER_URL] ?: return@map null
        val user = prefs[USERNAME] ?: return@map null
        val key = prefs[API_KEY]?.let(KeystoreCipher::decrypt) ?: return@map null
        Session(url, user, key, prefs[XC_PASSWORD]?.let(KeystoreCipher::decrypt), prefs[XC_TZ])
    }

    suspend fun save(session: Session) {
        context.sessionDataStore.edit {
            it[SERVER_URL] = session.serverUrl
            it[USERNAME] = session.username
            it[API_KEY] = KeystoreCipher.encrypt(session.apiKey)
            if (session.xcPassword != null) it[XC_PASSWORD] = KeystoreCipher.encrypt(session.xcPassword) else it.remove(XC_PASSWORD)
            if (session.xcTimezone != null) it[XC_TZ] = session.xcTimezone else it.remove(XC_TZ)
        }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val API_KEY = stringPreferencesKey("api_key_enc")
        val XC_PASSWORD = stringPreferencesKey("xc_password_enc")
        val XC_TZ = stringPreferencesKey("xc_timezone")
    }
}
