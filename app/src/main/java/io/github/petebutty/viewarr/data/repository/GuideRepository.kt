package io.github.petebutty.viewarr.data.repository

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import androidx.room.withTransaction
import io.github.petebutty.viewarr.data.local.AppDatabase
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
    private val db: AppDatabase,
    private val apiFactory: ApiFactory,
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("guide", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val dao get() = db.guideDao()

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync = _sync.asStateFlow()

    /** Bumped after each full sync, which wipes any past programmes fetched on demand. */
    private val _generation = MutableStateFlow(0L)
    val generation = _generation.asStateFlow()
    private val pastLimiter = Semaphore(4)

    /** Start of the window covered by the last full sync; anything earlier is fetched on demand. */
    fun syncedFrom(): Long = prefs.getLong(KEY_SYNCED_FROM, System.currentTimeMillis() - LOOKBACK_MS)

    /** Fetches one past chunk for one guide key; only programmes starting inside the chunk and ending before
     *  the synced window are kept, so nothing overlaps what the full sync already stored. */
    suspend fun loadPastChunk(session: Session, tvgId: String, chunkStart: Long, chunkEnd: Long) = pastLimiter.withPermit {
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
                        description = p.description?.takeIf { it.isNotBlank() },
                        startTime = start,
                        endTime = end,
                        season = p.props?.intOrNull("season"),
                        episode = p.props?.intOrNull("episode"),
                        isNew = false,
                        isLive = false,
                        isPremiere = false,
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

    fun observeGroups() = dao.observeGroups()
    fun observeChannels(groupId: Int?) = dao.observeChannels(groupId)
    fun observeProgramBlock(groupId: Int?, offset: Int, count: Int, start: Long, end: Long) =
        dao.observeProgramBlock(groupId, offset, count, start, end)

    suspend fun syncIfStale(session: Session) {
        val age = System.currentTimeMillis() - prefs.getLong(KEY_LAST_SYNC, 0)
        if (age > STALE_MS || dao.programCount() == 0) sync(session)
    }

    suspend fun sync(session: Session) = mutex.withLock {
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
            val channelRows = channels.map {
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
            val groupRows = groups.map { GroupEntity(it.id, it.name) }

            _sync.value = SyncState.Running("Loading TV guide…")
            val now = System.currentTimeMillis()
            val start = Instant.ofEpochMilli(now - LOOKBACK_MS).toString()
            val end = Instant.ofEpochMilli(now + FORWARD_MS).toString()
            api.grid(start, end).use { body ->
                db.withTransaction {
                    dao.clearPrograms()
                    dao.clearChannels()
                    dao.clearGroups()
                    dao.insertGroups(groupRows)
                    dao.insertChannels(channelRows)
                    var total = 0
                    parseGrid(body.byteStream()) { batch ->
                        dao.insertPrograms(batch)
                        total += batch.size
                        _sync.value = SyncState.Running("Loading TV guide… %,d programmes".format(total))
                    }
                    dao.useUuidWhereGuideMissing()
                }
            }
            prefs.edit().putLong(KEY_LAST_SYNC, now).putLong(KEY_SYNCED_FROM, now - LOOKBACK_MS).apply()
            _generation.value++
            _sync.value = SyncState.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _sync.value = SyncState.Failed("Couldn't update the TV guide: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        db.clearAllTables()
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
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (tvgId == null || start == null || end == null || end <= start) return null
        return ProgramEntity(
            tvgId = tvgId,
            title = title.orEmpty(),
            subTitle = subTitle?.takeIf { it.isNotBlank() },
            description = description?.takeIf { it.isNotBlank() },
            startTime = start,
            endTime = end,
            season = season,
            episode = episode,
            isNew = isNew,
            isLive = isLive,
            isPremiere = isPremiere,
        )
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
        const val STALE_MS = 4 * 3_600_000L
        const val LOOKBACK_MS = 3_600_000L
        const val FORWARD_MS = 36 * 3_600_000L
        const val BATCH = 1000
    }
}
