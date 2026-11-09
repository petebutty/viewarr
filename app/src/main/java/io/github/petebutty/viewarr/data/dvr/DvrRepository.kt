package io.github.petebutty.viewarr.data.dvr

import android.util.Log
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.JsonResponse
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.asArray
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

enum class DvrState { Scheduled, Recording, Recorded, Failed }

data class DvrRecording(
    val id: Int,
    val channelId: Int,
    val start: Long,
    val end: Long,
    val title: String,
    val subTitle: String?,
    val description: String?,
    val tvgId: String?,
    val season: Int?,
    val episode: Int?,
    val state: DvrState,
    val hasFile: Boolean,
    val posterLogoId: Int?,
)

data class SeriesRule(val tvgId: String, val title: String, val mode: String, val titleMode: String, val epgSourceId: Int?) {
    val label get() = title.trim('"', '^', '$')
}

/** Result of a DVR action: null message = success. */
data class DvrResult(val ok: Boolean, val message: String)

/**
 * Server-side recording through Dispatcharr's DVR: the app only schedules, lists and plays back.
 * Everything here shows up in Dispatcharr's own DVR page too.
 */
@Singleton
class DvrRepository @Inject constructor(
    private val http: HttpJson,
    private val client: OkHttpClient,
    private val sessions: SessionStore,
) {
    private val _recordings = MutableStateFlow<List<DvrRecording>>(emptyList())
    val recordings = _recordings.asStateFlow()
    private val _rules = MutableStateFlow<List<SeriesRule>>(emptyList())
    val rules = _rules.asStateFlow()
    /** null until the first refresh; false when the account isn't allowed to use the DVR. */
    private val _allowed = MutableStateFlow<Boolean?>(null)
    val allowed = _allowed.asStateFlow()

    private suspend fun session(): Session? = sessions.session.first()
    private fun headers(s: Session) = mapOf("X-API-Key" to s.apiKey)

    /** True when live TV comes from an IPTV provider: recordings need a Dispatcharr server. */
    private val _xc = MutableStateFlow(false)
    val xc = _xc.asStateFlow()

    suspend fun refresh() {
        val s = session() ?: return
        _xc.value = s.isDirect
        if (s.isDirect) { _allowed.value = false; _recordings.value = emptyList(); return }
        val r = runCatching { http.get("${s.serverUrl}/api/channels/recordings/", headers(s)) }.getOrNull() ?: return
        if (r.code == 403 || r.code == 401) {
            _allowed.value = false
            return
        }
        if (!r.ok) return
        _allowed.value = true
        val list = (r.body as? JsonObject)?.let { it.array("results") } ?: r.body.asArray()
        val now = System.currentTimeMillis()
        _recordings.value = list.mapNotNull { parse(it, now) }.sortedBy { it.start }
        val rr = runCatching { http.get("${s.serverUrl}/api/channels/series-rules/", headers(s)) }.getOrNull()
        if (rr?.ok == true) {
            _rules.value = rr.body.array("rules").map {
                SeriesRule(it.str("tvg_id").orEmpty(), it.str("title").orEmpty(), it.str("mode") ?: "all", it.str("title_mode") ?: "exact", it.int("epg_source_id"))
            }
        }
    }

    private fun parse(j: JsonElement, now: Long): DvrRecording? {
        val id = j.int("id") ?: return null
        val start = j.str("start_time")?.let(::millis) ?: return null
        val end = j.str("end_time")?.let(::millis) ?: return null
        val cp = j.child("custom_properties")
        val program = cp.child("program")
        val status = cp.str("status")
        val hasFile = cp.str("file_url") != null || cp.str("output_file_url") != null
        val state = when {
            status == "failed" || status == "error" -> if (hasFile) DvrState.Recorded else DvrState.Failed
            status == "recording" || (start <= now && end > now && status != "completed" && status != "stopped") -> DvrState.Recording
            start > now -> DvrState.Scheduled
            else -> if (hasFile || status == "completed" || status == "stopped") DvrState.Recorded else DvrState.Failed
        }
        return DvrRecording(
            id = id,
            channelId = j.int("channel") ?: return null,
            start = start,
            end = end,
            title = program.str("title") ?: "Recording",
            subTitle = program.str("sub_title"),
            description = program.str("description"),
            tvgId = program.str("tvg_id"),
            season = program.int("season"),
            episode = program.int("episode"),
            state = state,
            hasFile = hasFile,
            posterLogoId = cp.int("poster_logo_id"),
        )
    }

    private fun millis(s: String) = runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .getOrElse { runCatching { Instant.parse(s).toEpochMilli() }.getOrNull() } ?: 0L

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    /** The scheduled/active recording that covers this programme, if any. */
    fun recordingFor(channelId: Int, p: GuideProgram): DvrRecording? = _recordings.value.firstOrNull {
        it.channelId == channelId && it.state != DvrState.Recorded && it.state != DvrState.Failed &&
            minOf(it.end, p.endTime) - maxOf(it.start, p.startTime) > (p.endTime - p.startTime) / 2
    }

    /** The series rule that would record this programme, if any (exact or simple-regex title match on the same EPG id). */
    fun ruleFor(channel: ChannelListItem, p: GuideProgram): SeriesRule? = _rules.value.firstOrNull { r ->
        (r.tvgId.isEmpty() || r.tvgId == channel.guideKey) && when (r.titleMode) {
            "exact" -> r.title.equals(p.title, ignoreCase = true)
            "contains" -> p.title.contains(r.title, ignoreCase = true)
            "regex" -> runCatching { Regex(r.title, RegexOption.IGNORE_CASE).containsMatchIn(p.title) }.getOrDefault(false)
            else -> p.title.contains(r.title, ignoreCase = true)
        }
    }

    private fun programJson(channel: ChannelListItem, p: GuideProgram) = buildJsonObject {
        put("title", p.title)
        p.subTitle?.let { put("sub_title", it) }
        p.description?.let { put("description", it) }
        put("tvg_id", channel.guideKey)
        put("start_time", iso(p.startTime))
        put("end_time", iso(p.endTime))
        p.season?.let { put("season", it) }
        p.episode?.let { put("episode", it) }
    }

    suspend fun record(channel: ChannelListItem, p: GuideProgram): DvrResult {
        val s = session() ?: return DvrResult(false, "Live TV isn't set up.")
        val body = buildJsonObject {
            put("channel", channel.id.toString())
            put("start_time", iso(p.startTime))
            put("end_time", iso(p.endTime))
            putJsonObject("custom_properties") { put("program", programJson(channel, p)) }
        }
        val r = call { http.post("${s.serverUrl}/api/channels/recordings/", body, headers(s)) }
        return result(r, "Recording scheduled: ${p.title}").also { refresh() }
    }

    suspend fun recordSeries(channel: ChannelListItem, p: GuideProgram, newOnly: Boolean): DvrResult {
        val s = session() ?: return DvrResult(false, "Live TV isn't set up.")
        val body = buildJsonObject {
            put("tvg_id", channel.guideKey)
            put("mode", if (newOnly) "new" else "all")
            put("title", p.title)
            put("title_mode", "exact")
            put("channel_id", channel.id)
        }
        val r = call { http.post("${s.serverUrl}/api/channels/series-rules/", body, headers(s)) }
        if (r?.ok == true) {
            call { http.post("${s.serverUrl}/api/channels/series-rules/evaluate/", buildJsonObject { put("tvg_id", channel.guideKey) }, headers(s)) }
        }
        val what = if (newOnly) "new episodes of" else "every episode of"
        return result(r, "Recording $what ${p.title}").also { refresh() }
    }

    suspend fun cancel(rec: DvrRecording): DvrResult {
        val s = session() ?: return DvrResult(false, "Live TV isn't set up.")
        val r = delete("${s.serverUrl}/api/channels/recordings/${rec.id}/", s)
        return result(r, if (rec.state == DvrState.Recorded) "Recording deleted" else "Recording cancelled").also { refresh() }
    }

    suspend fun stop(rec: DvrRecording): DvrResult {
        val s = session() ?: return DvrResult(false, "Live TV isn't set up.")
        val r = call { http.post("${s.serverUrl}/api/channels/recordings/${rec.id}/stop/", null, headers(s)) }
        return result(r, "Recording stopped. What was recorded is kept.").also { refresh() }
    }

    suspend fun removeRule(rule: SeriesRule): DvrResult {
        val s = session() ?: return DvrResult(false, "Live TV isn't set up.")
        val q = buildString {
            append("tvg_id=").append(enc(rule.tvgId)).append("&title=").append(enc(rule.title))
            rule.epgSourceId?.let { append("&epg_source_id=").append(it) }
        }
        val r = delete("${s.serverUrl}/api/channels/series-rules/?$q", s)
        return result(r, "Series recording stopped for ${rule.label}").also { refresh() }
    }

    /** Playback URL and headers for a finished recording's file. */
    suspend fun playback(rec: DvrRecording): Pair<String, Map<String, String>>? {
        val s = session() ?: return null
        return "${s.serverUrl}/api/channels/recordings/${rec.id}/file/" to headers(s)
    }

    private suspend fun call(block: suspend () -> JsonResponse): JsonResponse? =
        runCatching { block() }.onFailure { Log.w("Dvr", "call failed: ${it.message}") }.getOrNull()

    private suspend fun delete(url: String, s: Session): JsonResponse? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).delete().header("X-API-Key", s.apiKey).build()).execute().use {
                JsonResponse(it.code, null)
            }
        }.getOrNull()
    }

    private fun result(r: JsonResponse?, okMessage: String) = when {
        r == null -> DvrResult(false, "Couldn't reach Dispatcharr.")
        r.ok -> DvrResult(true, okMessage)
        r.code == 401 || r.code == 403 -> DvrResult(false, "Your Dispatcharr account isn't allowed to manage recordings (DVR access).")
        else -> DvrResult(false, r.body.str("error") ?: r.body.str("detail") ?: "Dispatcharr refused that (error ${r.code}).")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
