package io.github.petebutty.viewarr.data.sync

import android.util.Log
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "A film is coming in over the internet": while a film or episode plays from outside the house (an addon's stream,
 * a Plex or Jellyfin server elsewhere), this box says so at most once a minute in this user's Dispatcharr app data,
 * key [KEY]: {"at": <seconds>, "box": "AM9 PRO", "source": "addon" | "server", "box_kbps": <this box's measured speed>}.
 * Off unless switched on (Settings → Playback → Tell the server about internet films): it only means something with a
 * server script that acts on it. The box only reports (2026-11-24: "Viewarr app should only relay information"): what to do about it (pause or
 * slow downloads) is decided on the server. Nothing is sent for your own servers, IPTV or recordings, and nothing
 * about what's playing.
 */
@Singleton
class StreamSignal @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val sessions: SessionStore,
    private val http: HttpJson,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var last = 0L

    /** Called while such a film plays (every few seconds): sent once a minute. */
    fun playing(source: String) {
        val now = System.currentTimeMillis()
        if (now - last < 60_000) return
        last = now
        scope.launch {
            runCatching {
                val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return@launch
                val w = http.put(s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/$KEY/",
                    buildJsonObject {
                        put("at", now / 1000)
                        put("box", boxName())
                        put("source", source)
                        io.github.petebutty.viewarr.data.net.BoxSpeed.measured(context)?.let { put("box_kbps", it) }
                    }, mapOf("X-API-Key" to s.apiKey))
                if (!w.ok) Log.w(TAG, "couldn't send (${w.code})")
            }.onFailure { Log.w(TAG, "couldn't send: ${it.message}") }
        }
    }

    /** The box's own name (Android's device name), else its model. */
    private fun boxName(): String = listOf("device_name", "bluetooth_name").firstNotNullOfOrNull { k ->
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, k) }.getOrNull()?.takeIf { it.isNotBlank() }
    } ?: android.os.Build.MODEL

    companion object {
        const val KEY = "viewarr_internet_stream"
        private const val TAG = "StreamSignal"
    }
}
