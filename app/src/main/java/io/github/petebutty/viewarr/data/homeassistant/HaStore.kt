package io.github.petebutty.viewarr.data.homeassistant

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.petebutty.viewarr.data.session.KeystoreCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Settings → This TV → Home Assistant: where it is and the long-lived access token. [ready] once both are set; nothing
 * Home Assistant does runs before that. [sendPlaying] and [popups] start On, so it works as soon as it's connected.
 */
data class HaConfig(
    val address: String = "",
    val token: String = "",
    val sendPlaying: Boolean = true,
    val popups: Boolean = true,
) {
    val ready: Boolean get() = address.isNotBlank() && token.isNotBlank()
}

/**
 * Kept in a store of its own ("home_assistant"), on this TV only, with the token encrypted by the box's key store (as
 * the other sign-ins are). Settings codes, Backup & sync and reports only ever read their own stores (appearance,
 * playback, remote, row_layout and the accounts), so none of this is ever copied, uploaded or sent anywhere.
 */
private val Context.haStore by preferencesDataStore(name = "home_assistant")

object HaStore {
    private val ADDRESS = stringPreferencesKey("address")
    private val TOKEN = stringPreferencesKey("token_enc")
    private val SEND = booleanPreferencesKey("send_playing")
    private val POPUPS = booleanPreferencesKey("popups")

    /** Background work for Home Assistant (sending events, reading the store): never on the main thread. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _config = MutableStateFlow(HaConfig())
    /** As saved now (blank until [start] has read it). */
    val config: StateFlow<HaConfig> = _config.asStateFlow()

    private val started = AtomicBoolean(false)
    private var app: Context? = null

    /** Reads the store and follows it (once; later calls do nothing). */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val c = context.applicationContext
        app = c
        scope.launch {
            c.haStore.data.collect { p ->
                _config.value = HaConfig(
                    address = p[ADDRESS].orEmpty(),
                    token = p[TOKEN]?.let(KeystoreCipher::decrypt).orEmpty(),
                    sendPlaying = p[SEND] ?: true,
                    popups = p[POPUPS] ?: true,
                )
            }
        }
    }

    fun setAddress(raw: String) = edit { p -> normalise(raw).let { if (it.isEmpty()) p.remove(ADDRESS) else p[ADDRESS] = it } }
    fun setToken(raw: String) = edit { p -> raw.trim().let { if (it.isEmpty()) p.remove(TOKEN) else p[TOKEN] = KeystoreCipher.encrypt(it) } }
    fun setSendPlaying(on: Boolean) = edit { it[SEND] = on }
    fun setPopups(on: Boolean) = edit { it[POPUPS] = on }
    /** Forget Home Assistant on this TV: address, token and both switches. */
    fun clear() = edit { it.clear() }

    private fun edit(change: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val c = app ?: return
        scope.launch { runCatching { c.haStore.edit(change) }.onFailure { android.util.Log.w(TAG, "not saved: ${it.javaClass.simpleName}") } }
    }

    /** "192.168.1.10:8123/" → "http://192.168.1.10:8123" (no slash or /api on the end; http:// when no scheme is given). */
    fun normalise(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (s.endsWith("/api")) s = s.removeSuffix("/api").trimEnd('/')
        if (!s.contains("://")) s = "http://$s"
        return s
    }

    @Volatile private var name: String? = null

    /**
     * This TV's name in events (so automations can tell TVs apart): the name set in Android TV's settings, else its
     * maker and model. The same name Backup & sync shows for this TV.
     */
    fun tvName(context: Context): String = name ?: run {
        val cr = context.applicationContext.contentResolver
        (listOf("device_name", "bluetooth_name").firstNotNullOfOrNull { k ->
            runCatching { Settings.Global.getString(cr, k) }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { Settings.Secure.getString(cr, k) }.getOrNull()?.takeIf { it.isNotBlank() }
        } ?: (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL)).also { name = it }
    }

    /** [tvName] once [start] has run ("" before). */
    fun tvName(): String = name ?: app?.let(::tvName).orEmpty()

    internal const val TAG = "HomeAssistant"
}
