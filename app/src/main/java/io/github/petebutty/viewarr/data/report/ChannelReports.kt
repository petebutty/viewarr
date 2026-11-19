package io.github.petebutty.viewarr.data.report

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One problem reported from this TV (hold OK on a channel → Report a problem). */
data class ChannelReport(
    val id: String,
    /** When, in seconds. */
    val at: Long,
    val channelId: Int,
    /** guide_wrong, guide_times, dead, logo, name or other. */
    val kind: String,
    val epgDataId: Int? = null,
    val offsetMin: Int? = null,
    /** "name": the right name. */
    val name: String? = null,
    val note: String? = null,
    val streamId: Int? = null,
    /** The channel's name as this TV knows it (kept here for the list in Settings; not sent). */
    val channelName: String = "",
) {
    /** How the kind reads in the list ("Wrong programme guide"). */
    val kindLabel get() = labels[kind] ?: kind

    companion object {
        const val GUIDE_WRONG = "guide_wrong"
        const val GUIDE_TIMES = "guide_times"
        const val DEAD = "dead"
        const val LOGO = "logo"
        const val NAME = "name"
        const val OTHER = "other"
        val labels = mapOf(
            GUIDE_WRONG to "Wrong programme guide", GUIDE_TIMES to "Guide times are out", DEAD to "No picture / dead stream",
            LOGO to "Wrong logo", NAME to "Wrong name", OTHER to "Something else",
        )
        fun new(channelId: Int, channelName: String, kind: String, epgDataId: Int? = null, offsetMin: Int? = null, name: String? = null, note: String? = null, streamId: Int? = null) =
            ChannelReport(UUID.randomUUID().toString(), System.currentTimeMillis() / 1000, channelId, kind, epgDataId, offsetMin, name, note, streamId, channelName)
    }
}

/**
 * The problems reported from this TV, kept here (the "channel_reports" preferences, as JSON) and shared with the Viewarr
 * plugin on Dispatcharr through this user's app data, key [KEY], the whole value replaced after each new report:
 *
 *   {"updated_at": 1794800000, "items": [
 *     {"id": "6f1c…", "at": 1794799000, "channel_id": 101, "kind": "guide_wrong", "epg_data_id": 5531,
 *      "offset_min": null, "name": null, "note": null, "stream_id": null} ]}
 *
 * The last 50 are kept, and nothing older than 14 days. Without the plugin the reports stay here (Settings → Live TV →
 * Reports lists them) and a push that failed is tried again on the next start. (Peter, 2026-11-19.)
 */
@Singleton
class ChannelReports @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val http: HttpJson,
) {
    private val prefs by lazy { context.getSharedPreferences("channel_reports", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _items = MutableStateFlow(load())
    /** Newest first. */
    val items = _items.asStateFlow()

    /** A new report: kept, then sent to the plugin (the whole list). */
    fun add(r: ChannelReport) {
        val cut = System.currentTimeMillis() / 1000 - KEEP_DAYS * 86_400L
        val list = (listOf(r) + _items.value).filter { it.at >= cut }.take(MAX)
        _items.value = list
        prefs.edit().putString(ITEMS, JsonArray(list.map { toJson(it, local = true) }).toString()).putBoolean(PENDING, true).apply()
        scope.launch { runCatching { push() }.onFailure { Log.w(TAG, "push failed: ${it.message}") } }
    }

    /** On start: a push that failed last time, again. */
    fun retryPending() {
        if (!prefs.getBoolean(PENDING, false)) return
        scope.launch { runCatching { push() }.onFailure { Log.w(TAG, "retry failed: ${it.message}") } }
    }

    private suspend fun push() = lock.withLock {
        val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return@withLock
        val body = buildJsonObject {
            put("updated_at", System.currentTimeMillis() / 1000)
            put("items", JsonArray(_items.value.map { toJson(it, local = false) }))
        }
        val w = http.put(s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/$KEY/", body, mapOf("X-API-Key" to s.apiKey))
        if (!w.ok) { Log.w(TAG, "couldn't send the reports (${w.code})"); return@withLock }
        prefs.edit().putBoolean(PENDING, false).apply()
        Log.i(TAG, "reports sent: ${_items.value.size}")
    }

    /** As the plugin reads it; [local]: the copy kept here, with the channel's name (for the list in Settings). */
    private fun toJson(r: ChannelReport, local: Boolean): JsonObject = buildJsonObject {
        put("id", r.id); put("at", r.at); put("channel_id", r.channelId); put("kind", r.kind)
        put("epg_data_id", r.epgDataId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("offset_min", r.offsetMin?.let { JsonPrimitive(it) } ?: JsonNull)
        put("name", r.name?.let { JsonPrimitive(it) } ?: JsonNull)
        put("note", r.note?.let { JsonPrimitive(it) } ?: JsonNull)
        put("stream_id", r.streamId?.let { JsonPrimitive(it) } ?: JsonNull)
        if (local && r.channelName.isNotBlank()) put("_channel", r.channelName)
    }

    private fun load(): List<ChannelReport> = runCatching {
        val arr = http.json.parseToJsonElement(prefs.getString(ITEMS, null) ?: return emptyList()) as? JsonArray ?: return emptyList()
        arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            ChannelReport(
                id = o.text("id") ?: return@mapNotNull null, at = o.long("at") ?: 0, channelId = o.int("channel_id") ?: return@mapNotNull null,
                kind = o.text("kind") ?: ChannelReport.OTHER, epgDataId = o.int("epg_data_id"), offsetMin = o.int("offset_min"),
                name = o.text("name"), note = o.text("note"), streamId = o.int("stream_id"), channelName = o.text("_channel").orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    private fun JsonObject.text(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull

    companion object {
        const val KEY = "viewarr_reports"
        private const val TAG = "ChannelReports"
        private const val ITEMS = "items"
        private const val PENDING = "pending"
        private const val MAX = 50
        private const val KEEP_DAYS = 14
    }
}
