package io.github.petebutty.viewarr.data.report

import android.content.Context
import android.util.Log
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.dvr.PluginsRepository
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.data.profiles.House
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.repository.GuideRepository
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.ui.common.Notify
import io.github.petebutty.viewarr.ui.report.HouseNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** A change the Viewarr plugin made on Dispatcharr from someone's report. */
data class HouseChange(val id: String, val at: Long, val by: String, val channelId: Int, val channelName: String, val what: String, val undone: Boolean)

/** A wrong-guide report the plugin couldn't settle itself: someone has to pick the right guide. */
data class NeedsPick(val reportId: String, val at: Long, val channelId: Int, val channelName: String, val by: String)

/** What the plugin left for every TV in [HouseSync.KEY]. */
data class HouseData(
    val updatedAt: Long, val offsets: Map<Int, Int>, val changes: List<HouseChange>, val needsPick: List<NeedsPick>,
    /** Channels in the plugin's quiet groups (club TV, event channels): off air by nature, never reported as dead by a TV. */
    val quiet: Set<Int> = emptySet(),
)

/**
 * What the Viewarr plugin on Dispatcharr worked out from everyone's reports, read by every TV from this user's app
 * data, key [KEY] (the plugin writes it; the TV only reads):
 *
 *   {"updated_at": 1794800000, "offsets": {"101": 60},
 *    "changes": [{"id": "c1", "at": 1794799000, "by": "alex", "channel_id": 101, "channel_name": "Sky Max",
 *                 "what": "Guide changed to Sky Max HD (XMLTV UK)", "undone": false}],
 *    "needs_pick": [{"report_id": "6f1c…", "at": 1794799000, "channel_id": 102, "channel_name": "Dave", "by": "sam"}],
 *    "order": {"reordered": 3}}
 *
 * Read on start and every 10 minutes (a 404: no plugin, which is fine). "offsets" (minutes) are the house's guide
 * offsets per channel, used by a box with no offset of its own for that channel (GuideRepository.setHouseShifts);
 * "changes" is the Undo list in Settings → Live TV → Reports, and a new one shows admins a quiet card (HouseNotice);
 * "needs_pick" lists channels where someone reported a wrong guide and the plugin couldn't find a match: the reporter
 * and admins are asked to pick one. The last copy is kept, so the offsets are there before the first read.
 * (Peter, 2026-11-19.)
 */
@Singleton
class HouseSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val http: HttpJson,
    private val guide: GuideRepository,
    private val plugins: PluginsRepository,
    private val reports: ChannelReports,
) {
    private val prefs by lazy { context.getSharedPreferences("house_sync", Context.MODE_PRIVATE) }
    private val _data = MutableStateFlow<HouseData?>(null)
    /** The plugin's latest (null: never read, or no plugin). */
    val data = _data.asStateFlow()
    /** A channel the plugin lists as quiet (club TV, event channels): being off air isn't a fault, so no automatic report or fail. */
    fun quiet(channelId: Int): Boolean = _data.value?.quiet?.contains(channelId) == true
    /** Every quiet channel id (empty: no plugin, or nothing read yet). */
    fun quietIds(): Set<Int> = _data.value?.quiet.orEmpty()

    /** The plugin answered at least once (false: nothing fetched yet, or no plugin). */
    @Volatile var fetched = false
        private set
    private var started = false
    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        this.scope = scope
        reports.retryPending()
        scope.launch(Dispatchers.IO) {
            sessions.session.distinctUntilChanged { a, b -> a?.id == b?.id }.collectLatest { s ->
                _data.value = null; fetched = false
                // (No Dispatcharr: no quiet channels, so no event channel is hidden; see GuideRepository.)
                if (s == null || s.isDirect) { guide.setQuietChannels(emptySet()); return@collectLatest }
                // The last copy first (the offsets are wanted before the guide draws), then a fresh read every 10 minutes.
                prefs.getString(dataKey(s), null)?.let { runCatching { parse(http.json.parseToJsonElement(it) as JsonObject) }.getOrNull() }
                    ?.let { _data.value = it; guide.setQuietChannels(it.quiet); runCatching { applyOffsets(s, it) } }
                while (true) {
                    runCatching { fetch(s) }.onFailure { Log.w(TAG, "read failed: ${it.message}") }
                    delay(10 * 60_000L)
                }
            }
        }
    }

    /** Again now (after a report, or Undo). */
    fun refresh() {
        scope?.launch(Dispatchers.IO) {
            val s = sessions.session.first()?.takeIf { !it.isDirect } ?: return@launch
            runCatching { fetch(s) }.onFailure { Log.w(TAG, "read failed: ${it.message}") }
        }
    }

    private fun dataKey(s: Session) = "data|" + s.id
    private fun seenKey(s: Session) = "seen|" + s.id

    private suspend fun fetch(s: Session) {
        val r = http.get(s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/$KEY/", mapOf("X-API-Key" to s.apiKey))
        if (r.code == 404) { Log.i(TAG, "no house data (no plugin)"); guide.setQuietChannels(emptySet()); return }
        val o = (r.body as? JsonObject)?.takeIf { r.code in 200..299 } ?: run { Log.w(TAG, "couldn't read the house data (${r.code})"); return }
        val d = parse(o)
        fetched = true
        _data.value = d
        prefs.edit().putString(dataKey(s), o.toString()).apply()
        // The event channels (quiet, bar club TV) the guide lists nothing for are kept out of the lists from here.
        guide.setQuietChannels(d.quiet)
        runCatching { applyOffsets(s, d) }.onFailure { Log.w(TAG, "offsets: ${it.message}") }
        notice(s, d)
    }

    /** The house's offsets (by channel id) as this box's guide keys, for channels with no offset of their own here. */
    private suspend fun applyOffsets(s: Session, d: HouseData) {
        val channels = guide.observeAllChannels().first()
        val byKey = HashMap<String, Int>()
        for (ch in channels) d.offsets[ch.id]?.takeIf { it != 0 }?.let { byKey[ch.guideKey] = it }
        guide.setHouseShifts(byKey)
    }

    /**
     * A change or a "needs your pick" not seen before: a quiet card (Settings → Notifications → Reports from the
     * house; off by default). Changes: admins (OK: Undo). Picks: admins and whoever reported it. The first read on
     * this box only learns what's there.
     */
    private suspend fun notice(s: Session, d: HouseData) {
        val key = seenKey(s)
        val seen = prefs.getStringSet(key, null)
        val ids = (d.changes.map { "c:" + it.id } + d.needsPick.map { "p:" + it.reportId }).toSet()
        prefs.edit().putStringSet(key, ids).apply()
        // A guide, name or logo changed on the server (applied or undone) since this box last looked: the channel list
        // and guide are read again now, so the change shows without waiting for the next scheduled sync (Fox News's
        // new guide looked empty on the box until it was refreshed; Peter, 2026-11-19).
        val guideChanged = seen != null && d.changes.any { c -> ("c:" + c.id) !in seen && (c.what.startsWith("Guide changed") || c.what.startsWith("Renamed") || c.what.startsWith("Logo")) }
        val undoneChanged = seen != null && d.changes.any { it.undone && ("c:" + it.id + ":undone") !in seen }
        if (guideChanged || undoneChanged) {
            prefs.edit().putStringSet(key, ids + d.changes.filter { it.undone }.map { "c:" + it.id + ":undone" }).apply()
            runCatching { guide.syncChannels(s); guide.sync(s) }.onFailure { Log.w(TAG, "refresh after a house change: ${it.message}") }
        }
        if (seen == null || !Notify.reports) return
        val c = if (Access.admin) d.changes.firstOrNull { !it.undone && ("c:" + it.id) !in seen } else null
        if (c != null) {
            withContext(Dispatchers.Main) {
                HouseNotice.show(HouseNotice.Notice("${c.what} · ${c.channelName} (by ${c.by})", "OK: undo  ·  Back: dismiss", action = { undo(c.id) }))
            }
            return
        }
        d.needsPick.firstOrNull { ("p:" + it.reportId) !in seen && (Access.admin || it.by.equals(s.username, true)) }?.let { p ->
            withContext(Dispatchers.Main) {
                HouseNotice.show(
                    HouseNotice.Notice("Couldn't find a guide for ${p.channelName} — choose one", "OK: choose  ·  Back: dismiss",
                        action = { io.github.petebutty.viewarr.ui.report.ReportFlow.openPicker(p.channelId, p.channelName) }),
                )
            }
        }
    }

    /** Undo one of the plugin's changes (admins: plugin actions are admin-only). The result as a message. */
    fun undo(id: String) {
        scope?.launch(Dispatchers.IO) {
            val r = plugins.run(House.KEY, "undo_change", buildJsonObject { put("id", id) })
            withContext(Dispatchers.Main) { Toast.makeText(context, if (r.ok) "Undone" else "Couldn't undo: ${r.message}", Toast.LENGTH_SHORT).show() }
            if (r.ok) { delay(1_500); refresh() }
        }
    }

    private fun parse(o: JsonObject): HouseData {
        val offsets = (o["offsets"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> k.toIntOrNull()?.let { id -> (v as? JsonPrimitive)?.intOrNull?.let { id to it } } }.toMap()
        val changes = (o["changes"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            HouseChange(
                id = c.text("id") ?: return@mapNotNull null, at = c.long("at") ?: 0, by = c.text("by") ?: "", channelId = c.int("channel_id") ?: 0,
                channelName = c.text("channel_name") ?: "", what = c.text("what") ?: "Changed", undone = (c["undone"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }.sortedByDescending { it.at }
        val picks = (o["needs_pick"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            NeedsPick(p.text("report_id") ?: return@mapNotNull null, p.long("at") ?: 0, p.int("channel_id") ?: return@mapNotNull null, p.text("channel_name") ?: "", p.text("by") ?: "")
        }.sortedByDescending { it.at }
        val quiet = (o["quiet_channels"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }.toSet()
        return HouseData(o.long("updated_at") ?: 0, offsets, changes, picks, quiet)
    }

    private fun JsonObject.text(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull

    companion object {
        const val KEY = "viewarr_house"
        private const val TAG = "HouseSync"
    }
}
