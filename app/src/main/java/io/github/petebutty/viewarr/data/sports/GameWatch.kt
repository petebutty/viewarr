package io.github.petebutty.viewarr.data.sports

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.put
import javax.inject.Singleton

/**
 * Reminders and recordings asked for on games the TV guide doesn't list yet (it reaches a few days ahead), and
 * "record all of a team's games". Kept on this TV; the Sports page turns each into a real reminder / Dispatcharr
 * recording as soon as it finds the game's channel in the guide. Recordings are also made in the background: by the
 * Viewarr plugin on Dispatcharr when it's running, else by this TV while Viewarr is running (TvGameRecorder).
 */
@Singleton
class GameWatch @Inject constructor(
    @ApplicationContext context: Context,
    private val sessions: io.github.petebutty.viewarr.data.session.SessionStore,
    private val http: io.github.petebutty.viewarr.data.remote.services.HttpJson,
) {
    private val prefs = context.getSharedPreferences("game_watch", Context.MODE_PRIVATE)

    // ---- Shared with Dispatcharr (key [SHARED_KEY] in this user's app data): the Viewarr plugin 0.4.1+ makes the
    // recordings itself every few minutes, so a TV needn't be on once the game is listed (2026-11-17). Written
    // after each change here; read on start and every few minutes (the server marks games done and drops them from
    // "record"). Without the plugin it's just a copy: the Sports page still does it, as before.
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var pushJob: kotlinx.coroutines.Job? = null
    private var started = false

    fun startSharing() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                runCatching { pull() }.onFailure { android.util.Log.w(TAG, "couldn't read the shared copy: ${it.message}") }
                kotlinx.coroutines.delay(5 * 60_000L)
            }
        }
    }

    private suspend fun target(): Pair<String, Map<String, String>>? {
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return null
        return (s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/$SHARED_KEY/") to mapOf("X-API-Key" to s.apiKey)
    }

    /** The server's copy → here: its done games, and its record list (games it has recorded are dropped there). */
    private suspend fun pull() {
        val (url, headers) = target() ?: return
        val r = http.get(url, headers)
        if (r.code == 404) { push(); return }
        val o = (r.body as? kotlinx.serialization.json.JsonObject)?.takeIf { r.code in 200..299 } ?: return
        fun strings(k: String) = (o[k] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()
        val serverDone = (o["done"] as? kotlinx.serialization.json.JsonObject).orEmpty()
            .mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()?.let { k to it } }.toMap()
        val m = doneMap()
        var changed = false
        serverDone.forEach { (k, at) -> if (!m.containsKey(k)) { m[k] = at; changed = true } }
        if (changed) prefs.edit().putString(DONE_AT, m.entries.sortedByDescending { it.value }.take(300).joinToString(";") { "${it.key}=${it.value}" }).apply()
        // Games the server recorded: no longer wanted here either. Teams: the server's list stands for this user (any
        // of their TVs may have changed it) unless this TV changed it more recently than the server copy.
        val record = set(RECORD) - serverDone.keys
        val teams = if (prefs.getLong(CHANGED_AT, 0) > (o["at"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L) set(TEAMS) else strings("teams")
        if (record != set(RECORD) || teams != set(TEAMS) || changed) {
            prefs.edit().putStringSet(RECORD, record).putStringSet(TEAMS, teams).apply()
            version.value++
        }
    }

    /** Here → the server: what this user wants recorded, with the done games kept. */
    private suspend fun push() {
        val (url, headers) = target() ?: return
        val body = kotlinx.serialization.json.buildJsonObject {
            put("record", kotlinx.serialization.json.JsonArray(set(RECORD).sorted().map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("teams", kotlinx.serialization.json.JsonArray(set(TEAMS).sorted().map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("done", kotlinx.serialization.json.JsonObject(doneMap().mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            put("at", kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()))
        }
        val w = http.put(url, body, headers)
        if (!w.ok) android.util.Log.w(TAG, "couldn't save the shared copy (${w.code})")
    }

    /** A change here: saved to the server shortly after (several quick changes in one go). */
    private fun pushSoon() {
        prefs.edit().putLong(CHANGED_AT, System.currentTimeMillis()).apply()
        pushJob?.cancel()
        pushJob = scope.launch { kotlinx.coroutines.delay(3_000); runCatching { push() } }
    }

    /** Bumped on every change, for screens to redraw ticks. */
    val version = mutableStateOf(0)

    private fun set(name: String) = prefs.getStringSet(name, emptySet()).orEmpty()
    private fun put(name: String, v: Set<String>) { prefs.edit().putStringSet(name, v).apply(); version.value++; if (name != REMIND) pushSoon() }

    fun wantsReminder(gameId: String) = gameId in set(REMIND)
    fun wantsRecording(gameId: String) = gameId in set(RECORD)
    fun recordsTeam(team: String) = team.lowercase() in set(TEAMS)

    fun setReminder(gameId: String, on: Boolean) = put(REMIND, if (on) set(REMIND) + gameId else set(REMIND) - gameId)
    fun setRecording(gameId: String, on: Boolean) = put(RECORD, if (on) set(RECORD) + gameId else set(RECORD) - gameId)
    fun setTeam(team: String, on: Boolean) = put(TEAMS, if (on) set(TEAMS) + team.lowercase() else set(TEAMS) - team.lowercase())

    /** Done (turned into the real thing): forgotten here so it isn't done twice. */
    fun resolved(gameId: String) {
        val r = set(REMIND); val c = set(RECORD)
        if (gameId in r || gameId in c) prefs.edit().putStringSet(REMIND, r - gameId).putStringSet(RECORD, c - gameId).apply()
        markDone(gameId)
    }

    /** The games asked to be recorded once they're in the guide, and the teams recorded in full (lower case). */
    fun recordIds(): Set<String> = set(RECORD)
    fun teams(): Set<String> = set(TEAMS)

    /**
     * Recorded by this TV in the background (TvGameRecorder): off the record list and remembered as done, but a
     * reminder asked for on the same game stays for the Sports page to set.
     */
    fun recorded(gameId: String) {
        val c = set(RECORD)
        if (gameId in c) put(RECORD, c - gameId)
        markDone(gameId)
    }

    /** Games already recorded (by a team rule or by hand): not recorded again. */
    fun done(gameId: String) = doneMap().containsKey(gameId)
    /** Remembered as recorded, newest kept (the oldest dropped after 300). */
    fun markDone(gameId: String) {
        val m = doneMap(); if (m.containsKey(gameId)) return
        m[gameId] = System.currentTimeMillis()
        val kept = m.entries.sortedByDescending { it.value }.take(300)
        prefs.edit().putString(DONE_AT, kept.joinToString(";") { "${it.key}=${it.value}" }).apply(); version.value++
    }
    private fun doneMap(): HashMap<String, Long> = HashMap(prefs.getString(DONE_AT, "").orEmpty().split(';').mapNotNull {
        val k = it.substringBefore('=', ""); val v = it.substringAfter('=', "").toLongOrNull(); if (k.isEmpty() || v == null) null else k to v
    }.toMap())

    private companion object {
        const val REMIND = "remind"
        const val RECORD = "record"
        const val TEAMS = "teams"
        const val DONE_AT = "done_at"
        const val CHANGED_AT = "changed_at"
        const val SHARED_KEY = "viewarr_game_watch"
        const val TAG = "GameWatch"
    }
}
