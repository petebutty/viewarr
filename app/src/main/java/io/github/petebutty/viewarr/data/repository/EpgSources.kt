package io.github.petebutty.viewarr.data.repository

import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.JsonResponse
import io.github.petebutty.viewarr.data.remote.services.asArray
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One of Dispatcharr's TV guide (EPG) sources, as /api/epg/sources/ lists it.
 * [status]: idle, fetching, parsing, error, success or disabled. [updatedAt]: the last good refresh (epoch ms, 0: never).
 * [refreshHours]: how often Dispatcharr fetches it itself (0: never); [cron]: a custom schedule set on its web page.
 */
data class EpgSource(
    val id: Int,
    val name: String,
    val type: String,
    val url: String?,
    val username: String?,
    val active: Boolean,
    val refreshHours: Int,
    val cron: String,
    val status: String,
    val message: String?,
    val updatedAt: Long,
    val channels: Int,
    /** Filled in by a Dispatcharr plugin (no address or file of its own): the plugin updates it, not "Update now". */
    val fromPlugin: Boolean,
) {
    val busy get() = status == "fetching" || status == "parsing"
    val typeLabel get() = when (type) { XMLTV -> "XMLTV"; SD -> "Schedules Direct"; DUMMY -> "Dummy"; else -> type }
    /** "Update now" means something: a link or Schedules Direct account Dispatcharr fetches itself. */
    val canUpdate get() = type == SD || (type == XMLTV && !fromPlugin)

    companion object {
        const val XMLTV = "xmltv"
        const val SD = "schedules_direct"
        const val DUMMY = "dummy"
    }
}

/** What a change came to: [ok], and the line to show ("Sky switched off", or "Dispatcharr refused: …"). */
class EpgResult(val ok: Boolean, val message: String, val created: EpgSource? = null)

/**
 * Dispatcharr's TV guide sources, for admins (Settings → Live TV → TV guide sources). Everything goes straight to
 * the server with this session's API key: list, add, switch on / off, rename, change the address or how often it's
 * fetched, delete, and refresh now (POST /api/epg/import/ with the source's id).
 */
@Singleton
class EpgSources @Inject constructor(private val http: HttpJson, private val sessions: SessionStore) {

    private suspend fun session(): Session? = sessions.session.first()?.takeIf { !it.isDirect }
    private fun url(s: Session, path: String) = "${s.serverUrl.trimEnd('/')}/api/epg/$path"
    private fun h(s: Session) = mapOf("X-API-Key" to s.apiKey)

    /** Null when not signed in to Dispatcharr; throws [SecurityException] for a user who isn't an admin. */
    suspend fun list(): List<EpgSource>? {
        val s = session() ?: return null
        val r = http.get(url(s, "sources/"), h(s))
        if (r.code == 401 || r.code == 403) throw SecurityException()
        if (!r.ok) throw java.io.IOException("HTTP ${r.code}")
        // (A plain list; paged servers wrap it in "results".)
        val rows = (r.body as? JsonArray)?.toList() ?: r.body.child("results").asArray()
        return rows.mapNotNull { parse(it) }.sortedWith(compareBy({ !it.active }, { it.name.lowercase() }))
    }

    private fun parse(o: JsonElement): EpgSource? {
        val id = o.int("id") ?: return null
        val url = o.str("url")?.takeIf { it.isNotBlank() }
        val file = o.str("file_path")?.takeIf { it.isNotBlank() }
        val type = o.str("source_type") ?: EpgSource.XMLTV
        val props = o.child("custom_properties") as? JsonObject
        return EpgSource(
            id = id,
            name = o.str("name") ?: "Source $id",
            type = type,
            url = url,
            username = o.str("username"),
            active = ((o.child("is_active") as? JsonPrimitive)?.booleanOrNull) ?: true,
            refreshHours = o.int("refresh_interval") ?: 0,
            cron = o.str("cron_expression").orEmpty(),
            status = o.str("status") ?: "idle",
            message = o.str("last_message")?.takeIf { it.isNotBlank() },
            updatedAt = o.str("updated_at")?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L,
            channels = (o.child("epg_data_count") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
            fromPlugin = type == EpgSource.XMLTV && url == null && file == null && !props.isNullOrEmpty(),
        )
    }

    /** Partial change (PATCH): only the fields given. */
    private suspend fun patch(src: EpgSource, done: String, body: JsonObject): EpgResult = call(done) { s -> http.patch(url(s, "sources/${src.id}/"), body, h(s)) }

    suspend fun setActive(src: EpgSource, on: Boolean) =
        patch(src, "${src.name} switched ${if (on) "on" else "off"}", buildJsonObject { put("is_active", on) })

    suspend fun rename(src: EpgSource, name: String) = patch(src, "Renamed to $name", buildJsonObject { put("name", name) })

    suspend fun setUrl(src: EpgSource, address: String) = patch(src, "${src.name}: address changed", buildJsonObject { put("url", address) })

    /** How often Dispatcharr fetches it ([hours], 0: never). A custom schedule (cron) is cleared, or it would win. */
    suspend fun setRefresh(src: EpgSource, hours: Int) = patch(src, "${src.name}: " + refreshLabel(hours).replaceFirstChar { it.lowercase() },
        buildJsonObject { put("refresh_interval", hours); put("cron_expression", "") })

    suspend fun delete(src: EpgSource) = call("${src.name} deleted") { s -> http.delete(url(s, "sources/${src.id}/"), h(s)) }

    /** Starts a refresh on the server (it runs in the background; the list shows "Updating…" meanwhile). */
    suspend fun refresh(src: EpgSource) = call("Updating ${src.name}…") { s -> http.post(url(s, "import/"), buildJsonObject { put("id", src.id) }, h(s)) }

    /** A new source: an XMLTV link ([address]) or a Schedules Direct account ([username] / [password]). */
    suspend fun add(type: String, name: String, address: String?, username: String?, password: String?): EpgResult {
        val body = buildJsonObject {
            put("name", name)
            put("source_type", type)
            put("is_active", true)
            put("refresh_interval", if (type == EpgSource.SD) 24 else 12)
            if (address != null) put("url", address)
            if (username != null) put("username", username)
            if (password != null) put("password", password)
        }
        var made: EpgSource? = null
        val r = call("$name added") { s -> http.post(url(s, "sources/"), body, h(s)).also { if (it.ok) made = it.body?.let(::parse) } }
        return EpgResult(r.ok, r.message, made)
    }

    private suspend fun call(done: String, work: suspend (Session) -> JsonResponse): EpgResult {
        val s = session() ?: return EpgResult(false, "Sign in to a Dispatcharr server first.")
        val r = runCatching { work(s) }.getOrElse { return EpgResult(false, "Couldn't reach Dispatcharr.") }
        return when {
            r.ok -> EpgResult(true, done)
            r.code == 401 || r.code == 403 -> EpgResult(false, "Dispatcharr refused: only admin users can change guide sources.")
            r.code == 404 -> EpgResult(false, "Dispatcharr refused: that source isn't there any more.")
            else -> EpgResult(false, "Dispatcharr refused: " + (reason(r.body) ?: "error ${r.code}"))
        }
    }

    /** The first readable reason in an error reply: {"detail": "…"}, {"error": "…"} or {"url": ["…"]}. */
    private fun reason(body: JsonElement?): String? {
        val o = body as? JsonObject ?: return null
        for (k in listOf("detail", "error", "message")) o.str(k)?.let { return it }
        for ((k, v) in o) {
            val text = when (v) {
                is JsonArray -> v.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> v.contentOrNull
                else -> null
            } ?: continue
            return if (k == "non_field_errors") text else "$k: $text"
        }
        return null
    }

    companion object {
        /** The choices for how often a source is fetched, in hours (0: never). */
        val REFRESH_CHOICES = listOf(6, 12, 24, 48, 0)
        fun refreshLabel(hours: Int) = when (hours) {
            0 -> "Never (only Update now)"
            24 -> "Every day"
            48 -> "Every 2 days"
            else -> "Every $hours hours"
        }
    }
}
