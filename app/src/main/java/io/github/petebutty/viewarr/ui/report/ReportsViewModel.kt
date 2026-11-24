package io.github.petebutty.viewarr.ui.report

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.report.ChannelReport
import io.github.petebutty.viewarr.data.report.ChannelReports
import io.github.petebutty.viewarr.data.report.HouseSync
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/** One guide (EPGData row) on Dispatcharr, as the picker lists it. */
data class EpgRow(val id: Int, val name: String, val tvgId: String, val source: String)

/**
 * Report a problem (hold OK on a channel) and Settings → Live TV → Reports: the reports kept on this box
 * (data/report/ChannelReports), the plugin's house data (HouseSync), the guide search for "Wrong programme guide", and
 * an admin's direct fixes on Dispatcharr. (2026-11-19.)
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val sessions: SessionStore,
    private val http: HttpJson,
    val reports: ChannelReports,
    val house: HouseSync,
    private val guide: io.github.petebutty.viewarr.data.repository.GuideRepository,
) : ViewModel() {
    private suspend fun session(): Session? = sessions.session.first()?.takeIf { !it.isDirect }
    private fun h(s: Session) = mapOf("X-API-Key" to s.apiKey)
    private fun api(s: Session, path: String) = s.serverUrl.trimEnd('/') + "/api/" + path

    /** EPG source names by id, read once. */
    private var sourceNames: Map<Int, String>? = null

    /**
     * Dispatcharr's guides matching [q] (every word of it in the name or tvg id, any order), with their source's name:
     * filtered on the box from the whole list (GuideRepository.allGuides: the server's list endpoint has no search).
     * Empty when it can't be asked. Shown best first: names starting with the words, then the rest, 60 at most.
     */
    suspend fun searchEpg(q: String): List<EpgRow> {
        val s = session() ?: return emptyList()
        val names = sourceNames ?: runCatching {
            http.get(api(s, "epg/sources/"), h(s)).body.let { b -> (b as? JsonArray)?.toList() ?: b.array("results") }
                .mapNotNull { e -> e.int("id")?.let { it to (e.str("name") ?: "Source $it") } }.toMap()
        }.getOrDefault(emptyMap()).also { sourceNames = it }
        val words = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val all = guide.allGuides(s)
        val hits = all.filter { g -> val t = (g.name + " " + g.tvgId).lowercase(); words.all { it in t } }
        return hits.sortedBy { g -> if (g.name.lowercase().startsWith(words.first())) 0 else 1 }.take(60)
            .map { g -> EpgRow(g.id, g.name, g.tvgId, g.sourceId?.let { names[it] } ?: "") }
    }

    /**
     * Sends [r] (kept here, pushed to the plugin). A wrong-guide report with the right guide picked by an admin is
     * also applied straight away on Dispatcharr (PATCH the channel's epg_data_id); the report still goes, so the plugin
     * logs it with Undo. [onDone] gets whether it was applied directly.
     */
    fun submit(r: ChannelReport, onDone: (applied: Boolean) -> Unit = {}) {
        reports.add(r)
        viewModelScope.launch {
            var applied = false
            if (r.kind == ChannelReport.GUIDE_WRONG && r.epgDataId != null && Access.admin) {
                val s = session()
                if (s != null) {
                    val w = runCatching { http.patch(api(s, "channels/channels/${r.channelId}/"), buildJsonObject { put("epg_data_id", r.epgDataId) }, h(s)) }.getOrNull()
                    applied = w?.ok == true
                    Log.i(TAG, "guide for channel ${r.channelId} set to ${r.epgDataId}: ${w?.code}")
                }
            }
            onDone(applied)
            // (The plugin acts within a minute or so: the house data read again shortly after.)
            kotlinx.coroutines.delay(45_000); house.refresh()
        }
    }

    fun undo(id: String) = house.undo(id)

    private companion object { const val TAG = "Reports" }
}

/** "3 min ago", "2 h ago", "4 days ago" from seconds since the epoch. */
fun agoLabel(atSec: Long): String {
    val d = System.currentTimeMillis() / 1000 - atSec
    return when {
        atSec <= 0 -> ""
        d < 60 -> "just now"
        d < 3_600 -> "${d / 60} min ago"
        d < 86_400 -> "${d / 3_600} h ago"
        d < 2 * 86_400 -> "yesterday"
        else -> "${d / 86_400} days ago"
    }
}
