package io.github.petebutty.viewarr.data.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
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
    /** An M3U playlist (serverUrl is the playlist link): no Dispatcharr, no Xtream login. */
    val isM3u: Boolean = false,
    /** M3U: the XMLTV guide link, if one was given (else the playlist's own url-tvg / x-tvg-url). */
    val m3uEpg: String? = null,
    /** Your own name for it ("Living room Dispatcharr"), else one made from its address. */
    val name: String? = null,
) {
    val isXc get() = xcPassword != null
    /** Straight to an IPTV provider (Xtream Codes or an M3U playlist), not through Dispatcharr. */
    val isDirect get() = isXc || isM3u
    /** Identifies a saved source (the same server and login is the same source). */
    val id get() = "${serverUrl.trimEnd('/')}|$username|${if (isM3u) "m3u" else if (isXc) "xc" else "d"}"
    /** How it's listed: "Dispatcharr · 192.168.1.50 (pete)", "IPTV · provider.tv (user)", "Playlist · host". */
    val label: String get() {
        name?.takeIf { it.isNotBlank() }?.let { return it }
        val host = runCatching { java.net.URI(if ("://" in serverUrl) serverUrl else "http://$serverUrl").host }.getOrNull() ?: serverUrl
        return when {
            isM3u -> "Playlist · $host"
            isXc -> "IPTV · $host ($username)"
            else -> "Dispatcharr · $host ($username)"
        }
    }
}

private val Context.sessionDataStore by preferencesDataStore(name = "session")

@Singleton
class SessionStore @Inject constructor(@ApplicationContext private val context: Context) {

    val session: Flow<Session?> = context.sessionDataStore.data.map { prefs ->
        val url = prefs[SERVER_URL] ?: return@map null
        val user = prefs[USERNAME] ?: return@map null
        val key = prefs[API_KEY]?.let(KeystoreCipher::decrypt) ?: return@map null
        Session(url, user, key, prefs[XC_PASSWORD]?.let(KeystoreCipher::decrypt), prefs[XC_TZ], prefs[M3U] == true, prefs[M3U_EPG], prefs[NAME])
    }

    /**
     * Every Live TV source signed in to on this TV (Dispatcharr instances, IPTV logins, playlists), one of them
     * active ([session]). Kept as one encrypted list: switching is choosing another; only the active one's channels
     * and guide are stored.
     */
    val saved: Flow<List<Session>> = context.sessionDataStore.data.map { prefs ->
        val list = prefs[SAVED]?.let(KeystoreCipher::decrypt)?.let { decode(it) }.orEmpty()
        // Sessions from before the list existed: the active one is the list.
        val active = prefs[SERVER_URL]?.let { url ->
            val user = prefs[USERNAME] ?: return@let null
            val key = prefs[API_KEY]?.let(KeystoreCipher::decrypt) ?: return@let null
            Session(url, user, key, prefs[XC_PASSWORD]?.let(KeystoreCipher::decrypt), prefs[XC_TZ], prefs[M3U] == true, prefs[M3U_EPG], prefs[NAME])
        }
        if (active != null && list.none { it.id == active.id }) list + active else list
    }

    /** Removes a saved source (not the active one: sign out of that, or switch first). */
    suspend fun forget(id: String) {
        context.sessionDataStore.edit { prefs ->
            val list = prefs[SAVED]?.let(KeystoreCipher::decrypt)?.let { decode(it) }.orEmpty().filter { it.id != id }
            prefs[SAVED] = KeystoreCipher.encrypt(encode(list))
        }
    }

    private fun encode(list: List<Session>): String = org.json.JSONArray().apply {
        list.forEach { s ->
            put(org.json.JSONObject().apply {
                put("url", s.serverUrl); put("user", s.username); put("key", s.apiKey)
                s.xcPassword?.let { put("xc", it) }; s.xcTimezone?.let { put("tz", it) }
                put("m3u", s.isM3u); s.m3uEpg?.let { put("epg", it) }; s.name?.let { put("name", it) }
            })
        }
    }.toString()

    private fun decode(json: String): List<Session> = runCatching {
        val a = org.json.JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Session(
                o.getString("url"), o.getString("user"), o.optString("key", ""),
                o.optString("xc").takeIf { o.has("xc") }, o.optString("tz").takeIf { o.has("tz") },
                o.optBoolean("m3u", false), o.optString("epg").takeIf { o.has("epg") }, o.optString("name").takeIf { o.has("name") },
            )
        }
    }.getOrDefault(emptyList())

    suspend fun save(session: Session) {
        context.sessionDataStore.edit {
            // Also into the saved list (replacing the same source's old details), so it can be switched back to.
            // The source active until now goes in the list first, if it isn't there yet (signed in before the list
            // existed): adding another mustn't lose it.
            val previous = it[SERVER_URL]?.let { url ->
                val user = it[USERNAME] ?: return@let null
                val key = it[API_KEY]?.let(KeystoreCipher::decrypt) ?: return@let null
                Session(url, user, key, it[XC_PASSWORD]?.let(KeystoreCipher::decrypt), it[XC_TZ], it[M3U] == true, it[M3U_EPG], it[NAME])
            }
            val stored = it[SAVED]?.let(KeystoreCipher::decrypt)?.let { j -> decode(j) }.orEmpty()
            val old = if (previous != null && stored.none { s -> s.id == previous.id }) stored + previous else stored
            val named = if (session.name == null) session.copy(name = old.firstOrNull { s -> s.id == session.id }?.name) else session
            val list = if (old.any { s -> s.id == session.id }) old.map { s -> if (s.id == session.id) named else s } else old + named
            it[SAVED] = KeystoreCipher.encrypt(encode(list))
            it[SERVER_URL] = session.serverUrl
            it[USERNAME] = session.username
            it[API_KEY] = KeystoreCipher.encrypt(session.apiKey)
            if (session.xcPassword != null) it[XC_PASSWORD] = KeystoreCipher.encrypt(session.xcPassword) else it.remove(XC_PASSWORD)
            if (session.xcTimezone != null) it[XC_TZ] = session.xcTimezone else it.remove(XC_TZ)
            if (session.isM3u) it[M3U] = true else it.remove(M3U)
            if (session.m3uEpg != null) it[M3U_EPG] = session.m3uEpg else it.remove(M3U_EPG)
            if (named.name != null) it[NAME] = named.name else it.remove(NAME)
        }
    }

    /** Gives a saved source your own name (null: back to the one from its address). */
    suspend fun rename(id: String, name: String?) {
        val active = session.first()
        context.sessionDataStore.edit { prefs ->
            val list = prefs[SAVED]?.let(KeystoreCipher::decrypt)?.let { decode(it) }.orEmpty()
                .map { if (it.id == id) it.copy(name = name) else it }
            prefs[SAVED] = KeystoreCipher.encrypt(encode(list))
            if (active?.id == id) { if (name != null) prefs[NAME] = name else prefs.remove(NAME) }
        }
    }

    /** Removes a saved source; removing the active one switches to another saved one (if there is one). */
    suspend fun remove(id: String) {
        if (session.first()?.id == id) clear() else forget(id)
    }

    /** Forgets everything: the active source and every saved one (the app's own Sign out). */
    suspend fun clearAll() {
        context.sessionDataStore.edit { it.clear() }
    }

    /** Signs out of the active source: it leaves the saved list; another saved one (if any) becomes active. */
    suspend fun clear() {
        val active = session.first()
        val rest = saved.first().filter { it.id != active?.id }
        context.sessionDataStore.edit { it.clear() }
        if (rest.isNotEmpty()) {
            save(rest.last())
            context.sessionDataStore.edit { it[SAVED] = KeystoreCipher.encrypt(encode(rest)) }
        }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val API_KEY = stringPreferencesKey("api_key_enc")
        val XC_PASSWORD = stringPreferencesKey("xc_password_enc")
        val XC_TZ = stringPreferencesKey("xc_timezone")
        val M3U = androidx.datastore.preferences.core.booleanPreferencesKey("m3u")
        val M3U_EPG = stringPreferencesKey("m3u_epg")
        val SAVED = stringPreferencesKey("saved_sources_enc")
        val NAME = stringPreferencesKey("source_name")
    }
}
