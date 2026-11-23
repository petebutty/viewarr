package io.github.petebutty.viewarr.data.sync

import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.session.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** One job of the Viewarr plugin and what its last run did (plugin activity.py). */
data class ActivityItem(val id: String, val name: String, val at: Long, val text: String, val state: String) {
    val problem get() = state == "problem"
    val off get() = state == "off"
}

/**
 * What the Viewarr plugin on Dispatcharr did last, one line per job, for Settings → Plugins → Viewarr. The plugin
 * writes it to admins' app data (key [KEY]) only; everyone else, and a server without the plugin, gets nothing.
 */
@Singleton
class PluginActivity @Inject constructor(
    private val sessions: SessionStore,
    private val http: HttpJson,
) {
    /** The lines, newest plugin round first in time order of the jobs; empty when there's nothing (no plugin yet). */
    suspend fun load(): List<ActivityItem> {
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return emptyList()
        val r = runCatching { http.get(s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/$KEY/", mapOf("X-API-Key" to s.apiKey)) }.getOrNull()
            ?: return emptyList()
        val o = (r.body as? JsonObject)?.takeIf { r.code in 200..299 } ?: return emptyList()
        if ((o["v"] as? JsonPrimitive)?.intOrNull != 1) return emptyList()
        return (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val i = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (i[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            ActivityItem(str("id"), str("name"), (i["at"] as? JsonPrimitive)?.longOrNull ?: 0L, str("text"), str("state"))
                .takeIf { it.name.isNotEmpty() }
        }
    }

    companion object {
        const val KEY = "viewarr_activity"
    }
}
