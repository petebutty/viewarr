package io.github.petebutty.viewarr.data.repository

import android.content.Context
import io.github.petebutty.viewarr.data.settings.appearanceStore
import android.util.JsonReader
import android.util.JsonToken
import androidx.room.withTransaction
import io.github.petebutty.viewarr.data.local.ChannelEntity
import io.github.petebutty.viewarr.data.local.GroupEntity
import io.github.petebutty.viewarr.data.local.ProgramEntity
import io.github.petebutty.viewarr.data.remote.ApiFactory
import io.github.petebutty.viewarr.data.session.Session
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val message: String) : SyncState
    data class Failed(val message: String) : SyncState
}

@Singleton
class GuideRepository @Inject constructor(
    private val dbs: io.github.petebutty.viewarr.data.local.GuideDatabases,
    private val apiFactory: ApiFactory,
    private val xtream: io.github.petebutty.viewarr.data.xtream.XtreamClient,
    private val m3u: io.github.petebutty.viewarr.data.m3u.M3uClient,
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("guide", Context.MODE_PRIVATE)
    /** How far ahead to fetch (Settings → Live TV → TV guide days ahead), read from the saved setting itself
     *  so a sync at start-up doesn't use the default before the appearance settings have loaded. */
    private suspend fun forwardMs(): Long = io.github.petebutty.viewarr.ui.theme.UiOpts.guideForwardMs(
        runCatching { context.appearanceStore.data.first()[androidx.datastore.preferences.core.intPreferencesKey("opt_" + io.github.petebutty.viewarr.ui.theme.UiOpts.GUIDE_DAYS)] }.getOrNull() ?: 0)
    private val mutex = Mutex()
    /** The active source's guide database (each saved source keeps its own; see GuideDatabases). */
    private val _db = MutableStateFlow(dbs.get(prefs.getString(KEY_SOURCE, null) ?: "none"))
    private val db get() = _db.value
    private val dao get() = db.guideDao()
    /** Which source the guide is showing (its sourceId). */
    private val activeSource get() = prefs.getString(KEY_SOURCE, null) ?: "none"
    /** This source's own record (last sync, how far back): each cached source has its own. */
    private fun k(key: String) = "$key|$activeSource"

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync = _sync.asStateFlow()

    /** Bumped after each full sync, which wipes any past programmes fetched on demand. */
    private val _generation = MutableStateFlow(0L)
    val generation = _generation.asStateFlow()
    private val pastLimiter = Semaphore(4)

    /** Start of the window covered by the last full sync; anything earlier is fetched on demand. */
    fun syncedFrom(): Long = prefs.getLong(k(KEY_SYNCED_FROM), System.currentTimeMillis() - LOOKBACK_MS)

    /** Fetches one past chunk for one guide key; only programmes starting inside the chunk and ending before
     *  the synced window are kept, so nothing overlaps what the full sync already stored. */
    suspend fun loadPastChunk(session: Session, tvgId: String, chunkStart: Long, chunkEnd: Long) = pastLimiter.withPermit {
        // IPTV providers: their guide file already includes the past days, loaded in the full sync.
        if (session.isDirect) return@withPermit Result.success(Unit)
        runCatching {
            val api = apiFactory.create(session.serverUrl, session.apiKey)
            val syncedFrom = syncedFrom()
            val rows = ArrayList<ProgramEntity>()
            var page = api.searchPrograms(
                tvgId = tvgId,
                startAfter = Instant.ofEpochMilli(chunkStart).toString(),
                startBefore = Instant.ofEpochMilli(chunkEnd).toString(),
            )
            var pages = 0
            while (true) {
                page.results.forEach { p ->
                    val start = parseTime(p.start) ?: return@forEach
                    val end = parseTime(p.end) ?: return@forEach
                    if (start < chunkStart || start >= chunkEnd || end > syncedFrom || end <= start) return@forEach
                    rows += ProgramEntity(
                        tvgId = tvgId,
                        title = p.title.orEmpty(),
                        subTitle = p.subTitle?.takeIf { it.isNotBlank() },
                        description = io.github.petebutty.viewarr.data.local.Packed.of(p.description),
                        startTime = start,
                        endTime = end,
                        season = p.props?.intOrNull("season"),
                        episode = p.props?.intOrNull("episode"),
                        isNew = false,
                        isLive = false,
                        isPremiere = false,
                        image = programImage(p.props),
                    )
                }
                val next = page.next ?: break
                if (++pages >= 4) break
                page = api.searchPage(next)
            }
            // Replace rather than append: chunks persist across restarts but the "already fetched" set doesn't.
            dao.replacePastChunk(tvgId, chunkStart, chunkEnd, syncedFrom, rows)
        }
    }

    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    // Follow the active source's database: switching sources switches what these show, at once.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeGroups() = _db.flatMapLatest { it.guideDao().observeGroups() }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeChannels(groupId: Int?) = _db.flatMapLatest { it.guideDao().observeChannels(groupId) }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeChannelsIn(uuids: List<String>) = _db.flatMapLatest { it.guideDao().observeChannelsIn(uuids) }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeProgramBlockIn(uuids: List<String>, offset: Int, count: Int, start: Long, end: Long) =
        _db.flatMapLatest { it.guideDao().observeProgramBlockIn(uuids, offset, count, start, end) }

    /** The channel groups' order as you've set it (hold OK on a group → move), by name; per Live TV source. */
    private val _groupOrder = MutableStateFlow(loadGroupOrder())
    val groupOrder = _groupOrder.asStateFlow()
    private fun loadGroupOrder(): List<String> = prefs.getString(k("group_order"), null)?.split('\u001F')?.filter { it.isNotEmpty() }.orEmpty()
    fun setGroupOrder(names: List<String>) {
        prefs.edit().putString(k("group_order"), names.joinToString("\u001F")).apply()
        _groupOrder.value = names
    }

    /** Favourite channels (their uuids), kept per Live TV source on this box; shared by every profile. */
    private val _favourites = MutableStateFlow(loadFavourites())
    val favourites = _favourites.asStateFlow()
    private fun loadFavourites(): Set<String> = prefs.getStringSet(k("favourites"), emptySet()).orEmpty().toSet()
    /** Adds or removes [uuid]; true if it's now a favourite. */
    fun toggleFavourite(uuid: String): Boolean {
        val now = _favourites.value.let { if (uuid in it) it - uuid else it + uuid }
        prefs.edit().putStringSet(k("favourites"), now).apply()
        _favourites.value = now
        return uuid in now
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeProgramBlock(groupId: Int?, offset: Int, count: Int, start: Long, end: Long) =
        _db.flatMapLatest { it.guideDao().observeProgramBlock(groupId, offset, count, start, end) }

    /**
     * [session]'s channels once it's the active source: at once when its guide is cached, else when its sync has put
     * them in (null if that takes over [timeoutMs]).
     */
    suspend fun channelsFor(session: Session, timeoutMs: Long = 180_000): List<io.github.petebutty.viewarr.data.local.ChannelListItem>? {
        activate(session)
        val dao = dbs.get(sourceId(session)).guideDao()
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { dao.observeChannels(null).first { it.isNotEmpty() } }
    }

    // ---- Groups you've hidden (per source): their channels and guide aren't stored at all.

    /** Hidden groups of the active source, by name (group ids can change between syncs; names don't). */
    fun hiddenGroups(): Set<String> = prefs.getStringSet(k("hidden_groups"), emptySet()).orEmpty()
    fun setHiddenGroups(names: Set<String>) = prefs.edit().putStringSet(k("hidden_groups"), names).apply()

    /** Every group the active source has (shown or hidden), with its channel count, from the last sync. */
    fun allGroups(): List<Pair<String, Int>> = runCatching {
        val a = org.json.JSONArray(prefs.getString(k("all_groups"), "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { it.getString("n") to it.getInt("c") } }
    }.getOrDefault(emptyList())

    /** Records every group (for Choose groups), then leaves out the hidden ones and their channels. */
    private fun onlyShown(groups: List<GroupEntity>, channels: List<ChannelEntity>): Pair<List<GroupEntity>, List<ChannelEntity>> {
        val counts = channels.groupingBy { it.groupId }.eachCount()
        prefs.edit().putString(k("all_groups"), org.json.JSONArray().apply {
            groups.forEach { g -> put(org.json.JSONObject().put("n", g.name).put("c", counts[g.id] ?: 0)) }
        }.toString()).apply()
        val hidden = hiddenGroups()
        if (hidden.isEmpty()) return groups to channels
        val hiddenIds = groups.filter { it.name in hidden }.mapTo(HashSet()) { it.id }
        return groups.filter { it.id !in hiddenIds } to channels.filter { it.groupId !in hiddenIds }
    }

    /** The last channel watched on the active source (switching back to a source carries on there). */
    fun lastChannel(): Int? = prefs.getInt(k("last_channel"), -1).takeIf { it >= 0 }
    fun rememberChannel(id: Int) = prefs.edit().putInt(k("last_channel"), id).apply()

    /**
     * Makes [session]'s guide the one shown: its own database (kept for the three most recent sources), so a source
     * switched back to shows straight away and is only refreshed when its guide is old.
     */
    @Synchronized
    private fun activate(session: Session) {
        val id = sourceId(session)
        if (prefs.getString(KEY_SOURCE, null) == id) return
        android.util.Log.i("Guide", "switching to source ${id.hashCode()}")
        prefs.edit().putString(KEY_SOURCE, id).apply()
        _db.value = dbs.get(id)
        _favourites.value = loadFavourites()
        _groupOrder.value = loadGroupOrder()
        _generation.value++
    }

    suspend fun searchPrograms(q: String, from: Long, limit: Int) = dao.searchPrograms(q, from, limit)

    /**
     * Catch-up search: programmes that have ended since [from] whose title matches [q], newest first, as
     * (guide key, programme without a channel yet). Dispatcharr searches its own guide (it keeps the last few days);
     * an IPTV login or playlist searches this TV's guide.
     */
    suspend fun searchPast(session: Session, q: String, from: Long, now: Long): List<Pair<String?, io.github.petebutty.viewarr.data.local.GuideProgram>> {
        if (session.isDirect) return dao.searchPast(q, from, now, 60).map { null to it }
        return runCatching {
            val api = apiFactory.create(session.serverUrl, session.apiKey)
            api.searchPast(title = q, startAfter = Instant.ofEpochMilli(from).toString(), endBefore = Instant.ofEpochMilli(now).toString())
                .results.mapNotNull { p ->
                    val start = parseTime(p.start) ?: return@mapNotNull null
                    val end = parseTime(p.end) ?: return@mapNotNull null
                    p.tvgId to io.github.petebutty.viewarr.data.local.GuideProgram(
                        channelId = 0,
                        // Not a row of this TV's guide: a stand-in key, unique per showing.
                        rowId = -start,
                        title = p.title.orEmpty(),
                        subTitle = p.subTitle?.takeIf { it.isNotBlank() },
                        packedDescription = io.github.petebutty.viewarr.data.local.Packed.of(p.description),
                        startTime = start,
                        endTime = end,
                        season = p.props?.intOrNull("season"),
                        episode = p.props?.intOrNull("episode"),
                        isNew = false, isLive = false, isPremiere = false,
                        image = programImage(p.props),
                    )
                }
                .sortedByDescending { it.second.startTime }
        }.getOrDefault(emptyList())
    }
    suspend fun programsFor(channelId: Int, guideKey: String, from: Long, to: Long) = dao.programsFor(channelId, guideKey, from, to)
    suspend fun programsForChannels(channelIds: List<Int>, from: Long, to: Long) =
        // (SQLite takes up to 999 values in one IN list.)
        channelIds.chunked(900).flatMap { dao.programsForChannels(it, from, to) }
    suspend fun searchProgramsEnding(q: String, endFrom: Long, endTo: Long) = dao.searchProgramsEnding(q, endFrom, endTo)

    /** [maxAgeMs]: how old the guide may be (Settings → Live TV → TV guide refresh); null: only if it's empty. */
    suspend fun syncIfStale(session: Session, maxAgeMs: Long? = STALE_MS) {
        activate(session)
        val age = System.currentTimeMillis() - prefs.getLong(k(KEY_LAST_SYNC), 0)
        val count = dao.programCount()
        android.util.Log.i("Guide", "source ${activeSource.hashCode()}: guide ${age / 60_000} min old, $count programmes")
        if ((maxAgeMs != null && age > maxAgeMs) || count == 0) sync(session)
    }

    private fun sourceId(s: Session) = "${s.serverUrl}|${s.username}|${s.isXc}|${s.isM3u}|${s.m3uEpg}"

    suspend fun sync(session: Session) = mutex.withLock {
        // Each source fills its own database (another source's stays as it is, for switching back).
        activate(session)
        if (session.isXc) return@withLock syncXc(session)
        if (session.isM3u) return@withLock syncM3u(session)
        try {
            _sync.value = SyncState.Running("Updating channels…")
            val api = apiFactory.create(session.serverUrl, session.apiKey)
            val (channels, groups) = coroutineScope {
                val c = async { api.channels() }
                val g = async { api.groups() }
                c.await() to g.await()
            }
            val referenced = channels.mapNotNull { it.epgDataId }.toSet()
            val epgTvgIds = withContext(Dispatchers.IO) {
                api.epgData().use { parseEpgData(it.byteStream(), referenced) }
            }
            val allChannelRows = channels.map {
                ChannelEntity(
                    id = it.id,
                    uuid = it.uuid,
                    name = it.name,
                    number = it.number,
                    groupId = it.groupId,
                    logoId = it.logoId,
                    guideKey = it.epgDataId?.let(epgTvgIds::get) ?: it.uuid,
                    catchupDays = if (it.isCatchup) it.catchupDays else 0,
                )
            }
            val (groupRows, channelRows) = onlyShown(groups.map { GroupEntity(it.id, it.name) }, allChannelRows)
            // With groups hidden, only the shown channels' programmes are kept.
            val keep = if (hiddenGroups().isEmpty()) null else channelRows.flatMapTo(HashSet()) { listOf(it.guideKey, it.uuid) }

            _sync.value = SyncState.Running("Loading TV guide…")
            val now = System.currentTimeMillis()
            val start = Instant.ofEpochMilli(now - LOOKBACK_MS).toString()
            val end = Instant.ofEpochMilli(now + forwardMs()).toString()
            api.grid(start, end).use { body ->
                db.withTransaction {
                    dao.clearPrograms()
                    dao.clearChannels()
                    dao.clearGroups()
                    dao.insertGroups(groupRows)
                    dao.insertChannels(channelRows)
                    var total = 0
                    parseGrid(body.byteStream()) { all ->
                        val batch = if (keep == null) all else all.filter { it.tvgId in keep }
                        dao.insertPrograms(batch)
                        total += batch.size
                        _sync.value = SyncState.Running("Loading TV guide… %,d programmes".format(total))
                    }
                    dao.useUuidWhereGuideMissing()
                }
            }
            prefs.edit().putLong(k(KEY_LAST_SYNC), now).putLong(k(KEY_SYNCED_FROM), now - LOOKBACK_MS).apply()
            _generation.value++
            _sync.value = SyncState.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _sync.value = SyncState.Failed("Couldn't update the TV guide: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Just the channel list again, not the guide (quick): a channel added on Dispatcharr since the last fetch shows up
     * at once, its programmes with the next guide fetch. (An IPTV login or playlist: the whole fetch, as there the
     * channels and guide come together.) At most once every 20 seconds, so outside links can't hammer the server.
     */
    suspend fun syncChannels(session: Session) {
        val now = System.currentTimeMillis()
        if (now - lastChannelSync < 20_000) return
        lastChannelSync = now
        if (session.isXc || session.isM3u) { sync(session); return }
        mutex.withLock {
            activate(session)
            try {
                _sync.value = SyncState.Running("Updating channels…")
                val api = apiFactory.create(session.serverUrl, session.apiKey)
                val (channels, groups) = coroutineScope {
                    val c = async { api.channels() }
                    val g = async { api.groups() }
                    c.await() to g.await()
                }
                val referenced = channels.mapNotNull { it.epgDataId }.toSet()
                val epgTvgIds = withContext(Dispatchers.IO) { api.epgData().use { parseEpgData(it.byteStream(), referenced) } }
                val allChannelRows = channels.map {
                    ChannelEntity(
                        id = it.id, uuid = it.uuid, name = it.name, number = it.number, groupId = it.groupId, logoId = it.logoId,
                        guideKey = it.epgDataId?.let(epgTvgIds::get) ?: it.uuid,
                        catchupDays = if (it.isCatchup) it.catchupDays else 0,
                    )
                }
                val (groupRows, channelRows) = onlyShown(groups.map { GroupEntity(it.id, it.name) }, allChannelRows)
                db.withTransaction {
                    dao.clearChannels()
                    dao.clearGroups()
                    dao.insertGroups(groupRows)
                    dao.insertChannels(channelRows)
                    dao.useUuidWhereGuideMissing()
                }
                _generation.value++
                _sync.value = SyncState.Idle
            } catch (e: CancellationException) {
                // (Stopped part-way: the "Updating channels…" note mustn't stay up.)
                _sync.value = SyncState.Idle
                throw e
            } catch (e: Exception) {
                _sync.value = SyncState.Failed("Couldn't update the channels: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
    private var lastChannelSync = 0L

    /** IPTV provider (Xtream Codes): channels and groups from player_api.php, the guide from xmltv.php. */
    private suspend fun syncXc(session: Session) {
        try {
            _sync.value = SyncState.Running("Updating channels…")
            val (channels, groups) = coroutineScope {
                val c = async { xtream.liveStreams(session) }
                val g = async { xtream.liveCategories(session) }
                c.await() to g.await()
            }
            val groupIds = HashMap<String, Int>()
            fun groupId(id: String?) = id?.let { groupIds.getOrPut(it) { it.toIntOrNull() ?: (it.hashCode() and 0x3fffffff) } }
            val allGroupRows = groups.map { GroupEntity(groupId(it.id)!!, it.name) }.distinctBy { it.id }
            val allChannelRows = channels.distinctBy { it.streamId }.map {
                ChannelEntity(
                    id = it.streamId,
                    uuid = it.streamId.toString(),
                    name = it.name,
                    number = it.number,
                    groupId = groupId(it.categoryId),
                    logoId = null,
                    logoUrl = it.logo,
                    guideKey = it.epgId ?: it.streamId.toString(),
                    catchupDays = it.archiveDays,
                )
            }
            val (groupRows, channelRows) = onlyShown(allGroupRows, allChannelRows)
            _sync.value = SyncState.Running("Loading TV guide…")
            val now = System.currentTimeMillis()
            // Catch-up channels can go back days; keep up to two days of the past (the box has little room).
            val back = if (channelRows.any { it.catchupDays > 0 }) XC_LOOKBACK_MS else LOOKBACK_MS
            val wanted = channelRows.mapTo(HashSet()) { it.guideKey }
            db.withTransaction {
                dao.clearPrograms()
                dao.clearChannels()
                dao.clearGroups()
                dao.insertGroups(groupRows)
                dao.insertChannels(channelRows)
            }
            var total = 0
            // Providers without a guide still work: channels just show their names.
            runCatching {
                xtream.guide(session, wanted, now - back, now + forwardMs()) { batch ->
                    dao.insertPrograms(batch)
                    total += batch.size
                    _sync.value = SyncState.Running("Loading TV guide… %,d programmes".format(total))
                }
            }.onFailure { if (it is CancellationException) throw it }
            dao.useUuidWhereGuideMissing()
            prefs.edit().putLong(k(KEY_LAST_SYNC), now).putLong(k(KEY_SYNCED_FROM), now - back).apply()
            _generation.value++
            _sync.value = SyncState.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _sync.value = SyncState.Failed("Couldn't update channels from your provider: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * An M3U playlist: its live channels (numbered by tvg-chno, else in playlist order), groups from group-title,
     * and the guide from the XMLTV link given at sign-in or the playlist's own. The stream link is kept in the
     * channel's uuid (what plays). No catch-up: plain playlists don't describe it reliably.
     */
    private suspend fun syncM3u(session: Session) {
        try {
            _sync.value = SyncState.Running("Updating channels…")
            val playlist = m3u.load(session.serverUrl)
            val groupIds = LinkedHashMap<String, Int>()
            playlist.channels.forEach { c -> c.group?.let { g -> groupIds.getOrPut(g) { groupIds.size + 1 } } }
            val allGroupRows = groupIds.map { (name, id) -> GroupEntity(id, name) }
            val allChannelRows = playlist.channels.distinctBy { it.url }.mapIndexed { i, c ->
                ChannelEntity(
                    id = i + 1,
                    uuid = c.url,
                    name = c.name,
                    number = c.number ?: (i + 1).toDouble(),
                    groupId = c.group?.let { groupIds[it] },
                    logoId = null,
                    logoUrl = c.logo,
                    guideKey = c.tvgId ?: c.name,
                    catchupDays = 0,
                )
            }
            val (groupRows, channelRows) = onlyShown(allGroupRows, allChannelRows)
            val now = System.currentTimeMillis()
            db.withTransaction {
                dao.clearPrograms()
                dao.clearChannels()
                dao.clearGroups()
                dao.insertGroups(groupRows)
                dao.insertChannels(channelRows)
            }
            val guide = session.m3uEpg ?: playlist.guideUrl
            var total = 0
            if (guide != null) {
                _sync.value = SyncState.Running("Loading TV guide…")
                val wanted = channelRows.mapTo(HashSet()) { it.guideKey }
                runCatching {
                    xtream.guideFromUrl(guide, wanted, now - LOOKBACK_MS, now + forwardMs()) { batch ->
                        dao.insertPrograms(batch)
                        total += batch.size
                        _sync.value = SyncState.Running("Loading TV guide… %,d programmes".format(total))
                    }
                }.onFailure { if (it is CancellationException) throw it }
            }
            prefs.edit().putLong(k(KEY_LAST_SYNC), now).putLong(k(KEY_SYNCED_FROM), now - LOOKBACK_MS).apply()
            _generation.value++
            _sync.value = SyncState.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _sync.value = SyncState.Failed("Couldn't load your playlist: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dbs.deleteAll()
        _db.value = dbs.get("none")
        prefs.edit().clear().apply()
    }

    private fun parseEpgData(stream: InputStream, wanted: Set<Int>): Map<Int, String> {
        val result = HashMap<Int, String>(wanted.size)
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                var id: Int? = null
                var tvgId: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "id" -> id = r.nextInt()
                        "tvg_id" -> tvgId = r.nullableString()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id != null && tvgId != null && id in wanted) result[id] = tvgId
            }
            r.endArray()
        }
        return result
    }

    private suspend fun parseGrid(stream: InputStream, onBatch: suspend (List<ProgramEntity>) -> Unit) {
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "data") {
                    r.skipValue()
                    continue
                }
                val batch = ArrayList<ProgramEntity>(BATCH)
                r.beginArray()
                while (r.hasNext()) {
                    readProgram(r)?.let(batch::add)
                    if (batch.size == BATCH) {
                        onBatch(batch.toList())
                        batch.clear()
                    }
                }
                r.endArray()
                if (batch.isNotEmpty()) onBatch(batch.toList())
            }
            r.endObject()
        }
    }

    private fun readProgram(r: JsonReader): ProgramEntity? {
        var tvgId: String? = null
        var title: String? = null
        var subTitle: String? = null
        var description: String? = null
        var start: Long? = null
        var end: Long? = null
        var season: Int? = null
        var episode: Int? = null
        var isNew = false
        var isLive = false
        var isPremiere = false
        var image: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "tvg_id" -> tvgId = r.nullableString()
                "title" -> title = r.nullableString()
                "sub_title" -> subTitle = r.nullableString()
                "description" -> description = r.nullableString()
                "start_time" -> start = r.nullableString()?.let(::parseTime)
                "end_time" -> end = r.nullableString()?.let(::parseTime)
                "season" -> season = r.nullableInt()
                "episode" -> episode = r.nullableInt()
                "is_new" -> isNew = r.nullableBoolean()
                "is_live" -> isLive = r.nullableBoolean()
                "is_premiere" -> isPremiere = r.nullableBoolean()
                "custom_properties" -> image = readImage(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (tvgId == null || start == null || end == null || end <= start) return null
        return ProgramEntity(
            tvgId = tvgId,
            title = title.orEmpty(),
            subTitle = subTitle?.takeIf { it.isNotBlank() },
            description = io.github.petebutty.viewarr.data.local.Packed.of(description),
            startTime = start,
            endTime = end,
            season = season,
            episode = episode,
            isNew = isNew,
            isLive = isLive,
            isPremiere = isPremiere,
            image = image,
        )
    }

    /** custom_properties as it streams past: the programme's picture ("icon", or the first of "images"). */
    private fun readImage(r: JsonReader): String? {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
        var icon: String? = null
        var first: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "icon" -> icon = if (r.peek() == JsonToken.STRING) r.nextString() else { r.skipValue(); null }
                "images" -> if (r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray()
                    while (r.hasNext()) {
                        if (r.peek() == JsonToken.BEGIN_OBJECT) {
                            r.beginObject()
                            while (r.hasNext()) {
                                if (r.nextName() == "url" && r.peek() == JsonToken.STRING) { val u = r.nextString(); if (first == null) first = u } else r.skipValue()
                            }
                            r.endObject()
                        } else r.skipValue()
                    }
                    r.endArray()
                } else r.skipValue()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return usableImage(icon) ?: usableImage(first)
    }

    private fun programImage(props: JsonObject?): String? {
        props ?: return null
        val icon = (props["icon"] as? JsonPrimitive)?.contentOrNull
        val first = (props["images"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
            ?.let { (it as? JsonObject)?.get("url") as? JsonPrimitive }?.contentOrNull
        return usableImage(icon) ?: usableImage(first)
    }

    /** Web addresses only, and not ones inside the server's own Docker network (the TV can't reach those). */
    private fun usableImage(url: String?): String? {
        val u = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        val host = u.substringAfter("://").substringBefore('/').substringBefore(':')
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        val dockerNet = octets.size == 4 && octets[0] == 172 && octets[1] in 16..31
        if (dockerNet || host == "localhost" || host.startsWith("127.")) return null
        return u
    }

    private fun parseTime(s: String): Long? = runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

    private fun JsonReader.nullableString(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()

    private fun JsonReader.nullableInt(): Int? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.NUMBER -> nextInt()
        JsonToken.STRING -> nextString().toIntOrNull()
        else -> { skipValue(); null }
    }

    private fun JsonReader.nullableBoolean(): Boolean = when (peek()) {
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.NULL -> { nextNull(); false }
        else -> { skipValue(); false }
    }

    private companion object {
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_SYNCED_FROM = "synced_from"
        const val KEY_SOURCE = "synced_source"
        const val STALE_MS = 4 * 3_600_000L
        const val LOOKBACK_MS = 3_600_000L
        const val XC_LOOKBACK_MS = 48 * 3_600_000L
        const val BATCH = 1000
    }
}
