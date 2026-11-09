package io.github.petebutty.viewarr.data.dvr

import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.obj
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** A plugin installed on the Dispatcharr server. Plugins run there; the app lists, switches and drives them. */
data class InstalledPlugin(
    val key: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val latestVersion: String?,
    val updateAvailable: Boolean,
    val enabled: Boolean,
    val actions: List<PluginAction>,
    val fields: List<PluginField>,
    val settings: JsonObject,
)

data class PluginAction(val id: String, val label: String, val description: String, val button: String, val confirm: String?)

data class PluginField(
    val id: String,
    val label: String,
    val type: String,
    val description: String,
    val default: JsonElement?,
    val options: List<Pair<String, String>>,
)

/** A plugin offered by one of the server's plugin repositories (the store). */
data class StorePlugin(
    val slug: String,
    val name: String,
    val description: String,
    val author: String,
    val repoId: Int,
    val repoName: String,
    val official: Boolean,
    val verified: Boolean,
    val version: String,
    val downloadUrl: String,
    val sha256: String?,
    val minVersion: String?,
    val maxVersion: String?,
    val downloads: Int?,
    val status: String,
    val installedVersion: String?,
)

class PluginResult(val ok: Boolean, val message: String)

@Singleton
class PluginsRepository @Inject constructor(private val http: HttpJson, private val sessions: SessionStore) {

    private suspend fun session(): Session? = sessions.session.first()?.takeIf { !it.isDirect }
    private fun url(s: Session, path: String) = "${s.serverUrl.trimEnd('/')}/api/plugins/$path"
    private fun h(s: Session) = mapOf("X-API-Key" to s.apiKey)

    /** Null when not signed in to Dispatcharr; throws [SecurityException] for a non-admin user. */
    suspend fun installed(): List<InstalledPlugin>? {
        val s = session() ?: return null
        val r = http.get(url(s, "plugins/"), h(s))
        if (r.code == 401 || r.code == 403) throw SecurityException()
        return r.body.array("plugins").map { p ->
            InstalledPlugin(
                key = p.str("key").orEmpty(),
                name = p.str("name") ?: p.str("key").orEmpty(),
                description = p.str("description").orEmpty(),
                author = p.str("author").orEmpty(),
                version = p.str("version").orEmpty(),
                latestVersion = p.str("latest_version"),
                updateAvailable = p.bool("update_available"),
                enabled = p.bool("enabled"),
                // Actions tied to server events (e.g. "recording_end") are internal: not buttons.
                actions = p.array("actions").filter { it.child("events") == null }.map { a ->
                    PluginAction(
                        id = a.str("id").orEmpty(),
                        label = a.str("label") ?: a.str("id").orEmpty(),
                        description = a.str("description").orEmpty(),
                        button = a.str("button_label") ?: "Run",
                        confirm = a.child("confirm")?.let { c -> c.str("message") ?: c.str("title") ?: "Are you sure?" },
                    )
                },
                fields = p.array("fields").map { f ->
                    PluginField(
                        id = f.str("id").orEmpty(),
                        label = f.str("label") ?: f.str("id").orEmpty(),
                        type = f.str("type") ?: "string",
                        description = f.str("description") ?: f.str("help_text").orEmpty(),
                        default = f.child("default"),
                        options = f.array("options").mapNotNull { o -> o.str("value")?.let { it to (o.str("label") ?: it) } },
                    )
                },
                settings = p.child("settings").obj() ?: JsonObject(emptyMap()),
            )
        }
    }

    suspend fun store(): List<StorePlugin> {
        val s = session() ?: return emptyList()
        val r = http.get(url(s, "repos/available/"), h(s))
        if (r.code == 401 || r.code == 403) throw SecurityException()
        return r.body.array("plugins").mapNotNull { p ->
            StorePlugin(
                slug = p.str("slug") ?: return@mapNotNull null,
                name = p.str("name") ?: p.str("slug").orEmpty(),
                description = p.str("description").orEmpty(),
                author = p.str("author").orEmpty(),
                repoId = p.int("repo_id") ?: return@mapNotNull null,
                repoName = p.str("repo_name").orEmpty(),
                official = p.bool("is_official_repo"),
                verified = p.bool("signature_verified"),
                version = p.str("latest_version") ?: return@mapNotNull null,
                downloadUrl = p.str("latest_url") ?: return@mapNotNull null,
                sha256 = p.str("latest_sha256"),
                minVersion = p.str("min_dispatcharr_version"),
                maxVersion = p.str("max_dispatcharr_version"),
                downloads = p.int("download_count"),
                status = p.str("install_status") ?: "not_installed",
                installedVersion = p.str("installed_version"),
            )
        }
    }

    suspend fun setEnabled(key: String, on: Boolean): PluginResult = post("plugins/$key/enabled/", buildJsonObject { put("enabled", on) })

    suspend fun run(key: String, action: String): PluginResult {
        val s = session() ?: return PluginResult(false, "Not signed in to Dispatcharr.")
        val r = runCatching { http.post(url(s, "plugins/$key/run/"), buildJsonObject { put("action", action); put("params", JsonObject(emptyMap())) }, h(s)) }.getOrNull()
            ?: return PluginResult(false, "No answer from Dispatcharr.")
        if (r.code !in 200..299 || !r.body.bool("success")) return PluginResult(false, r.body.str("error") ?: "Failed (${r.code}).")
        // Plugins usually answer {status, message}; show the message.
        val res = r.body.child("result")
        val msg = res.str("message") ?: (res as? JsonPrimitive)?.content ?: "Done."
        return PluginResult(res.str("status") != "error", msg)
    }

    /** Saves the whole settings object (Dispatcharr replaces it), with [id] changed to [value]. */
    suspend fun saveSetting(p: InstalledPlugin, id: String, value: JsonElement): PluginResult =
        post("plugins/${p.key}/settings/", buildJsonObject { put("settings", JsonObject(p.settings + (id to value))) })

    suspend fun install(p: StorePlugin): PluginResult = post("repos/install/", buildJsonObject {
        put("repo_id", p.repoId)
        put("slug", p.slug)
        put("version", p.version)
        put("download_url", p.downloadUrl)
        p.sha256?.let { put("sha256", it) }
        p.minVersion?.let { put("min_dispatcharr_version", it) }
        p.maxVersion?.let { put("max_dispatcharr_version", it) }
    })

    private suspend fun post(path: String, body: JsonObject): PluginResult {
        val s = session() ?: return PluginResult(false, "Not signed in to Dispatcharr.")
        val r = runCatching { http.post(url(s, path), body, h(s)) }.getOrNull() ?: return PluginResult(false, "No answer from Dispatcharr.")
        return if (r.code in 200..299 && r.body.child("success")?.let { (it as? JsonPrimitive)?.booleanOrNull } != false) PluginResult(true, "Done.")
        else PluginResult(false, r.body.str("error") ?: "Failed (${r.code}).")
    }

    private fun JsonElement?.bool(k: String) = (this.child(k) as? JsonPrimitive)?.booleanOrNull == true
}
