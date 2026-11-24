package io.github.petebutty.viewarr.data.sync

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.playback.SourceRank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This box's record of how each Live TV stream has behaved (playback/SourceRank: fails this week, last good play),
 * shared with the Viewarr plugin on Dispatcharr through this user's own app data, key [KEY], so the plugin can put
 * the house's streams in a better order for everyone (2026-11-19):
 *
 *   {"updated_at": 1794800000, "server": "http://192.168.0.10:9191",
 *    "streams": {"412": {"f": [1794790000, 1794795000], "ok": 0}, "413": {"f": [], "ok": 1794799000}}}
 *
 * Times in seconds. Sent at most every 10 minutes, and only when the record has changed since the last send (a hash of
 * it is kept). Told of changes by SourceRank ([changed]); runs on its own scope with the application context, so it
 * never holds a ViewModel. Any user can write their own app data: admin or not.
 */
@Singleton
class HealthSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val http: HttpJson,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val prefs by lazy { context.getSharedPreferences("health_sync", Context.MODE_PRIVATE) }
    @Volatile private var pending = false
    @Volatile private var lastPush = 0L

    private var pushJob: kotlinx.coroutines.Job? = null

    /**
     * A fail or a good play was saved: a push follows, once 10 minutes have passed since the last one. A fail
     * ([soon]) goes within 30 s instead, so the plugin's next round already knows a source didn't start
     * (2026-11-19: failed channels reported automatically).
     */
    fun changed(soon: Boolean = false) {
        val gap = if (soon) SOON_GAP else MIN_GAP
        if (pending && !soon) return
        pending = true
        pushJob?.cancel()
        pushJob = scope.launch {
            val wait = gap - (System.currentTimeMillis() - lastPush)
            if (wait > 0) delay(wait)
            pending = false
            runCatching { push() }.onFailure { Log.w(TAG, "push failed: ${it.message}") }
        }
    }

    private suspend fun push() = lock.withLock {
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return@withLock
        val server = s.serverUrl.trimEnd('/')
        val streams = SourceRank(context).export(server)
        val streamsJson = buildJsonObject {
            for ((id, rec) in streams) put(id.toString(), buildJsonObject {
                put("f", JsonArray(rec.first.map { JsonPrimitive(it) }))
                put("ok", rec.second)
            })
        }
        // Only the record itself is hashed (not updated_at), so an unchanged record isn't sent again.
        val hash = streamsJson.toString().hashCode().toString()
        val hashKey = "hash|$server|${s.username}"
        if (prefs.getString(hashKey, null) == hash) return@withLock
        val body = buildJsonObject {
            put("updated_at", System.currentTimeMillis() / 1000)
            put("server", server)
            put("streams", streamsJson)
        }
        val w = http.put("$server/api/accounts/users/me/app-data/$KEY/", body, mapOf("X-API-Key" to s.apiKey))
        lastPush = System.currentTimeMillis()
        if (!w.ok) { Log.w(TAG, "couldn't save stream health (${w.code})"); return@withLock }
        prefs.edit().putString(hashKey, hash).apply()
        Log.i(TAG, "stream health shared: ${streams.size} streams")
    }

    companion object {
        const val KEY = "viewarr_stream_health"
        private const val TAG = "HealthSync"
        private const val MIN_GAP = 10 * 60_000L
        private const val SOON_GAP = 30_000L
    }
}
