package io.github.petebutty.viewarr.data.profiles

import android.util.Log
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.net.Reach
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * House settings kept in the Viewarr plugin for Dispatcharr (github.com/petebutty/viewarr-plugin): the outside
 * addresses of the house's servers. Every Viewarr signed in to that Dispatcharr reads them on start (any signed-in
 * user can read plugin settings), so one change reaches every TV. Admins save them from Away addresses. Without the
 * plugin nothing changes: the addresses stay in each setup as before. Never anything secret: everyone can read it.
 */
@Singleton
class House @Inject constructor(
    private val sessions: SessionStore,
    private val accounts: AccountStore,
    private val http: HttpJson,
) {
    private fun base(s: Session) = s.serverUrl.trimEnd('/') + "/api/plugins/plugins"
    private fun h(s: Session) = mapOf("X-API-Key" to s.apiKey)

    /** The plugin's entry in Dispatcharr's plugin list (null: not installed, or Dispatcharr didn't answer). */
    private suspend fun entry(s: Session): JsonObject? {
        val r = runCatching { http.get(base(s) + "/", h(s)) }.getOrNull()?.takeIf { it.ok } ?: return null
        val list: List<JsonElement> = (r.body as? JsonArray) ?: (r.body.child("plugins") as? JsonArray) ?: return null
        return list.firstOrNull { it.str("key") == KEY } as? JsonObject
    }

    private fun enabled(e: JsonObject) = (e["enabled"] as? JsonPrimitive)?.booleanOrNull == true

    /** The outside addresses in the plugin (home base → outside base); null: no plugin (or switched off). */
    suspend fun away(s: Session): Map<String, String>? {
        val e = entry(s)?.takeIf(::enabled) ?: return null
        return parse(e.child("settings").str("away").orEmpty())
    }

    /** Whether this Dispatcharr has the plugin on (Away addresses then saves there). */
    suspend fun installed(s: Session) = entry(s)?.let(::enabled) == true

    /** The plugin's Backups switch (its "backups" setting; not there, as in older plugins: on). */
    suspend fun backupsOn(s: Session) = (entry(s)?.child("settings")?.child("backups") as? JsonPrimitive)?.booleanOrNull != false

    /**
     * Saves [away] (home → outside) to the plugin (admins only: Dispatcharr refuses anyone else). The plugin's other
     * settings are kept (Dispatcharr replaces them all on save). False: no plugin, or it wasn't saved.
     */
    suspend fun saveAway(s: Session, away: Map<String, String>): Boolean {
        val e = entry(s)?.takeIf(::enabled) ?: return false
        val current = (e["settings"] as? JsonObject) ?: JsonObject(emptyMap())
        val lines = away.entries.joinToString("\n") { (home, out) -> "$home = $out" }
        val settings = buildJsonObject { current.forEach { (k, v) -> put(k, v) }; put("away", JsonPrimitive(lines)) }
        val r = runCatching { http.post(base(s) + "/$KEY/settings/", buildJsonObject { put("settings", settings) }, h(s)) }.getOrNull()
        return r?.ok == true
    }

    // ---------------------------------------------------------------- backups (admins: plugin actions are admin-only)

    data class Backup(val id: String, val name: String, val savedAt: Long, val device: String, val size: Long = 0)

    /** Runs one of the plugin's actions; its result (null: no plugin, not allowed, or it failed). */
    private suspend fun run(s: Session, action: String, params: JsonObject = JsonObject(emptyMap())): JsonObject? {
        val r = runCatching { http.post(base(s) + "/$KEY/run/", buildJsonObject { put("action", JsonPrimitive(action)); put("params", params) }, h(s)) }.getOrNull()
        val result = r?.takeIf { it.ok }?.body.child("result") as? JsonObject ?: return null
        return result.takeIf { it.str("status") == "success" }
    }

    suspend fun backups(s: Session): List<Backup>? = run(s, "backup_list")?.let { res ->
        (res["backups"] as? JsonArray).orEmpty().mapNotNull { b ->
            val id = b.str("id") ?: return@mapNotNull null
            Backup(id, b.str("name") ?: "Backup", b.str("savedAt")?.toLongOrNull() ?: 0, b.str("device").orEmpty(), b.str("size")?.toLongOrNull() ?: 0)
        }
    }

    suspend fun saveBackup(s: Session, name: String, data: JsonObject): Boolean =
        run(s, "backup_save", buildJsonObject { put("name", JsonPrimitive(name)); put("data", data) }) != null

    /** The saved setup inside a backup. */
    suspend fun backup(s: Session, id: String): JsonObject? =
        run(s, "backup_get", buildJsonObject { put("id", JsonPrimitive(id)) })?.child("backup")?.child("data") as? JsonObject

    suspend fun deleteBackup(s: Session, id: String): Boolean = run(s, "backup_delete", buildJsonObject { put("id", JsonPrimitive(id)) }) != null

    /** On each sign-in / start: the plugin's addresses into this TV's setup (they win over older copies). */
    fun start(scope: CoroutineScope) = scope.launch {
        sessions.session.distinctUntilChanged { a, b -> a?.serverUrl == b?.serverUrl && a?.apiKey == b?.apiKey }.collect { s ->
            if (s == null || s.isDirect) return@collect
            val away = runCatching { away(s) }.getOrNull() ?: return@collect
            if (away.isEmpty()) return@collect
            accounts.update { a ->
                // Same home address in both: the plugin's outside address. Others in the setup stay.
                val keep = a.away.filterKeys { k -> away.keys.none { Reach.normalise(it, false) == Reach.normalise(k, false) } }
                a.copy(away = keep + away)
            }
            Log.i(TAG, "house: ${away.size} outside address(es) from the Viewarr plugin")
        }
    }

    companion object {
        const val KEY = "viewarr"
        private const val TAG = "House"

        /** "home = outside" lines (blank and # lines skipped; no scheme on the outside one means https). */
        fun parse(text: String): Map<String, String> = text.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@mapNotNull null
            val home = Reach.normalise(line.substringBefore('=').trim(), away = false) ?: return@mapNotNull null
            val out = Reach.normalise(line.substringAfter('=').trim(), away = true) ?: return@mapNotNull null
            home to out
        }.toMap()
    }
}
