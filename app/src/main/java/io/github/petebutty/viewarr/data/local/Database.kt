package io.github.petebutty.viewarr.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayOutputStream

@Entity(tableName = "channels", indices = [Index("guideKey"), Index("groupId")])
data class ChannelEntity(
    @PrimaryKey val id: Int,
    val uuid: String,
    val name: String,
    val number: Double?,
    val groupId: Int?,
    val logoId: Int?,
    /** XC providers give a full logo address instead of a Dispatcharr logo id. */
    val logoUrl: String? = null,
    // Programmes are keyed by the EPG source row's tvg_id (not the channel's own), or the channel uuid for dummy/no EPG.
    val guideKey: String,
    val catchupDays: Int,
)

@Entity(tableName = "channel_groups")
data class GroupEntity(
    @PrimaryKey val id: Int,
    val name: String,
)

/**
 * An event channel kept out of the lists for now (Settings → Live TV → Event channels: only with a listing): one of
 * the Viewarr plugin's quiet channels with nothing in the guide on now or in the next 24 hours. Written by
 * GuideRepository as the guide and the plugin's data change; the list, group and guide-row queries leave these out,
 * so every screen agrees. Looking a channel up by id (links, reminders, recordings, Sports) doesn't. (2026-11-19.)
 */
@Entity(tableName = "hidden_channels")
data class HiddenChannelEntity(@PrimaryKey val id: Int)

@Entity(tableName = "programs", indices = [Index(value = ["tvgId", "startTime"]), Index("endTime")])
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val tvgId: String,
    val title: String,
    val subTitle: String?,
    /** The synopsis, stored compressed (see [Packed]). */
    val description: Packed?,
    val startTime: Long,
    val endTime: Long,
    val season: Int?,
    val episode: Int?,
    val isNew: Boolean,
    val isLive: Boolean,
    val isPremiere: Boolean,
    /** The programme's own picture from the guide source (XMLTV icon), if it has one. */
    val image: String? = null,
)

/**
 * A programme's synopsis, compressed: synopses are most of the guide's size, and only the highlighted programme's is
 * ever shown, so it's unpacked then (once), not for every programme in the grid.
 */
class Packed(val bytes: ByteArray) {
    val text: String by lazy {
        runCatching {
            val inf = java.util.zip.Inflater(true)
            inf.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 3)
            val buf = ByteArray(1024)
            while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) }
            inf.end()
            out.toString(Charsets.UTF_8.name())
        }.getOrDefault("")
    }

    companion object {
        /** Packs [text] (null for none). */
        fun of(text: String?): Packed? {
            if (text.isNullOrBlank()) return null
            val def = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
            def.setInput(text.toByteArray(Charsets.UTF_8)); def.finish()
            val out = ByteArrayOutputStream(text.length)
            val buf = ByteArray(1024)
            while (!def.finished()) out.write(buf, 0, def.deflate(buf))
            def.end()
            return Packed(out.toByteArray())
        }
    }
}

class PackedConverters {
    @TypeConverter fun toBytes(p: Packed?): ByteArray? = p?.bytes
    @TypeConverter fun toPacked(b: ByteArray?): Packed? = b?.let(::Packed)
}

data class ChannelListItem(
    val id: Int,
    val uuid: String,
    val name: String,
    val number: Double?,
    val logoId: Int?,
    val logoUrl: String?,
    val groupName: String?,
    val guideKey: String,
    val catchupDays: Int,
)

data class GroupWithCount(
    val id: Int,
    val name: String,
    val channelCount: Int,
)

data class GuideProgram(
    val channelId: Int,
    val rowId: Long,
    val title: String,
    val subTitle: String?,
    @androidx.room.ColumnInfo(name = "description") val packedDescription: Packed?,
    val startTime: Long,
    val endTime: Long,
    val season: Int?,
    val episode: Int?,
    val isNew: Boolean,
    val isLive: Boolean,
    val isPremiere: Boolean,
    val image: String? = null,
) {
    /** The synopsis (unpacked the first time it's read). */
    val description: String? get() = packedDescription?.text?.takeIf { it.isNotEmpty() }

    /** A stand-in for a channel with nothing listed (its hold-OK menu still opens: favourites, EPG offset, Report). */
    val noGuide get() = rowId == NO_GUIDE

    companion object {
        const val NO_GUIDE = -1L
        fun noGuide(channelId: Int, now: Long = System.currentTimeMillis()) = GuideProgram(
            channelId, NO_GUIDE, "No guide information", null, null, now, now + 3_600_000L, null, null, false, false, false,
        )
    }
}

@Dao
interface GuideDao {
    // (The list, group and guide-row queries leave out hidden_channels, the event channels with nothing listed: see
    // HiddenChannelEntity. observeAllChannels is the one that doesn't, for looking a channel up by id.)
    @Query(
        """
        SELECT c.id, c.uuid, c.name, c.number, c.logoId, c.logoUrl, g.name AS groupName, c.guideKey, c.catchupDays
        FROM channels c LEFT JOIN channel_groups g ON g.id = c.groupId
        WHERE (:groupId IS NULL OR c.groupId = :groupId) AND c.id NOT IN (SELECT id FROM hidden_channels)
        ORDER BY c.number IS NULL, c.number, c.name, c.id
        """
    )
    fun observeChannels(groupId: Int?): Flow<List<ChannelListItem>>

    /** Every channel, hidden event channels too: for finding one by id (links, reminders, recordings, Sports). */
    @Query(
        """
        SELECT c.id, c.uuid, c.name, c.number, c.logoId, c.logoUrl, g.name AS groupName, c.guideKey, c.catchupDays
        FROM channels c LEFT JOIN channel_groups g ON g.id = c.groupId
        ORDER BY c.number IS NULL, c.number, c.name, c.id
        """
    )
    fun observeAllChannels(): Flow<List<ChannelListItem>>

    /**
     * A list-made group's channels (by uuid): in [order]'s order when given ("|uuid|uuid|…|": Live now, live first then
     * starting soon), else channel order (favourites). [everyChannel]: hidden event channels too (Live now).
     */
    @Query(
        """
        SELECT c.id, c.uuid, c.name, c.number, c.logoId, c.logoUrl, g.name AS groupName, c.guideKey, c.catchupDays
        FROM channels c LEFT JOIN channel_groups g ON g.id = c.groupId
        WHERE c.uuid IN (:uuids) AND (:everyChannel OR c.id NOT IN (SELECT id FROM hidden_channels))
        ORDER BY instr(:order, '|' || c.uuid || '|') = 0, instr(:order, '|' || c.uuid || '|'), c.number IS NULL, c.number, c.name, c.id
        """
    )
    fun observeChannelsIn(uuids: List<String>, order: String, everyChannel: Boolean): Flow<List<ChannelListItem>>

    @Query(
        """
        SELECT g.id, g.name, COUNT(c.id) AS channelCount
        FROM channel_groups g JOIN channels c ON c.groupId = g.id
        WHERE c.id NOT IN (SELECT id FROM hidden_channels)
        GROUP BY g.id
        ORDER BY MIN(c.number IS NULL), MIN(c.number), g.name
        """
    )
    fun observeGroups(): Flow<List<GroupWithCount>>

    @Query(
        """
        SELECT c.id AS channelId, p.rowId, p.title, p.subTitle, p.description, p.startTime, p.endTime,
               p.season, p.episode, p.isNew, p.isLive, p.isPremiere, p.image
        FROM (
            SELECT id, guideKey FROM channels
            WHERE (:groupId IS NULL OR groupId = :groupId) AND id NOT IN (SELECT id FROM hidden_channels)
            ORDER BY number IS NULL, number, name, id
            LIMIT :count OFFSET :offset
        ) c
        JOIN programs p ON p.tvgId = c.guideKey
        WHERE p.endTime > :start AND p.startTime < :end
        ORDER BY c.id, p.startTime
        """
    )
    fun observeProgramBlock(groupId: Int?, offset: Int, count: Int, start: Long, end: Long): Flow<List<GuideProgram>>

    @Query(
        """
        SELECT c.id AS channelId, p.rowId, p.title, p.subTitle, p.description, p.startTime, p.endTime,
               p.season, p.episode, p.isNew, p.isLive, p.isPremiere, p.image
        FROM (
            SELECT id, guideKey FROM channels
            WHERE uuid IN (:uuids) AND (:everyChannel OR id NOT IN (SELECT id FROM hidden_channels))
            ORDER BY instr(:order, '|' || uuid || '|') = 0, instr(:order, '|' || uuid || '|'), number IS NULL, number, name, id
            LIMIT :count OFFSET :offset
        ) c
        JOIN programs p ON p.tvgId = c.guideKey
        WHERE p.endTime > :start AND p.startTime < :end
        ORDER BY c.id, p.startTime
        """
    )
    fun observeProgramBlockIn(uuids: List<String>, order: String, everyChannel: Boolean, offset: Int, count: Int, start: Long, end: Long): Flow<List<GuideProgram>>

    @Query("SELECT COUNT(*) FROM programs")
    suspend fun programCount(): Int

    /** One channel's programmes between [from] and [to] (the channel list's programme panel). */
    @Query(
        """
        SELECT :channelId AS channelId, p.rowId, p.title, p.subTitle, p.description, p.startTime, p.endTime,
               p.season, p.episode, p.isNew, p.isLive, p.isPremiere, p.image
        FROM programs p
        WHERE p.tvgId = :guideKey AND p.endTime > :from AND p.startTime < :to
        ORDER BY p.startTime
        """
    )
    suspend fun programsFor(channelId: Int, guideKey: String, from: Long, to: Long): List<GuideProgram>

    /** Several channels' programmes between [from] and [to] in one go (Live now: every event channel at once). */
    @Query(
        """
        SELECT c.id AS channelId, p.rowId, p.title, p.subTitle, p.description, p.startTime, p.endTime,
               p.season, p.episode, p.isNew, p.isLive, p.isPremiere, p.image
        FROM channels c JOIN programs p ON p.tvgId = c.guideKey
        WHERE c.id IN (:channelIds) AND p.endTime > :from AND p.startTime < :to
        ORDER BY p.startTime
        """
    )
    suspend fun programsForChannels(channelIds: List<Int>, from: Long, to: Long): List<GuideProgram>

    /**
     * Programmes whose title or episode title contains [q] and that end between [endFrom] and [endTo] (a game's
     * kick-off: the shows around it), read through the end-time index rather than the whole guide. (Schedules Direct
     * lists a game as "Live: NHL Hockey" with the teams in the episode title.)
     */
    @Query(
        """
        SELECT c.id AS channelId, p.rowId, p.title, p.subTitle, p.description, p.startTime, p.endTime,
               p.season, p.episode, p.isNew, p.isLive, p.isPremiere, p.image
        FROM programs p JOIN channels c ON c.guideKey = p.tvgId
        WHERE p.endTime BETWEEN :endFrom AND :endTo
          AND (p.title LIKE '%' || :q || '%' OR p.subTitle LIKE '%' || :q || '%')
        ORDER BY p.startTime, c.number IS NULL, c.number
        LIMIT 60
        """
    )
    suspend fun searchProgramsEnding(q: String, endFrom: Long, endTo: Long): List<GuideProgram>

    /** Search: programmes whose title contains [q], on now or still to come, soonest first. */
    @Query(
        """
        SELECT c.id AS channelId, p.rowId, p.title, p.subTitle, p.description, p.startTime, p.endTime,
               p.season, p.episode, p.isNew, p.isLive, p.isPremiere, p.image
        FROM programs p JOIN channels c ON c.guideKey = p.tvgId
        WHERE p.endTime > :from AND p.title LIKE '%' || :q || '%'
        ORDER BY p.startTime, c.number IS NULL, c.number
        LIMIT :limit
        """
    )
    suspend fun searchPrograms(q: String, from: Long, limit: Int): List<GuideProgram>

    /** Catch-up search on this TV's own guide (IPTV logins / playlists): ended between [from] and [to], newest first. */
    @Query(
        """
        SELECT c.id AS channelId, p.rowId, p.title, p.subTitle, p.description, p.startTime, p.endTime,
               p.season, p.episode, p.isNew, p.isLive, p.isPremiere, p.image
        FROM programs p JOIN channels c ON c.guideKey = p.tvgId
        WHERE p.endTime <= :to AND p.startTime >= :from AND p.title LIKE '%' || :q || '%'
        ORDER BY p.startTime DESC
        LIMIT :limit
        """
    )
    suspend fun searchPast(q: String, from: Long, to: Long, limit: Int): List<GuideProgram>

    @Query(
        """
        UPDATE channels SET guideKey = uuid
        WHERE guideKey != uuid
          AND NOT EXISTS (SELECT 1 FROM programs WHERE tvgId = channels.guideKey)
          AND EXISTS (SELECT 1 FROM programs WHERE tvgId = channels.uuid)
        """
    )
    suspend fun useUuidWhereGuideMissing()

    @Insert
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Insert
    suspend fun insertGroups(groups: List<GroupEntity>)

    @Insert
    suspend fun insertPrograms(programs: List<ProgramEntity>)

    /** Which guide (tvg id) a programme row belongs to: the guide grid's programmes don't carry it. */
    @Query("SELECT tvgId FROM programs WHERE rowId = :rowId")
    suspend fun tvgIdOf(rowId: Long): String?

    /** EPG offset changed: a guide's saved programmes slid later (+) / earlier (−) in place, nothing fetched again. */
    @Query("UPDATE programs SET startTime = startTime + :by, endTime = endTime + :by WHERE tvgId = :tvgId")
    suspend fun shiftPrograms(tvgId: String, by: Long): Int

    /** The whole source's EPG offset changed: every saved programme slid. */
    @Query("UPDATE programs SET startTime = startTime + :by, endTime = endTime + :by")
    suspend fun shiftAllPrograms(by: Long): Int

    @Query("DELETE FROM programs WHERE tvgId = :tvgId AND startTime >= :from AND startTime < :to AND endTime <= :endLimit")
    suspend fun deletePastChunk(tvgId: String, from: Long, to: Long, endLimit: Long)

    @Transaction
    suspend fun replacePastChunk(tvgId: String, from: Long, to: Long, endLimit: Long, programs: List<ProgramEntity>) {
        deletePastChunk(tvgId, from, to, endLimit)
        insertPrograms(programs)
    }

    /**
     * The channels that stay listed whatever the plugin calls them: in a "Club TV" group, or with a programme on now
     * or starting before [until] (the guide's own emptiness is what hides an event channel). Re-read as the guide,
     * channels and groups change.
     */
    @Query(
        """
        SELECT c.id FROM channels c LEFT JOIN channel_groups g ON g.id = c.groupId
        WHERE g.name = 'Club TV' COLLATE NOCASE
           OR EXISTS (SELECT 1 FROM programs p WHERE p.tvgId = c.guideKey AND p.endTime > :now AND p.startTime < :until)
        """
    )
    fun observeListedOrClub(now: Long, until: Long): Flow<List<Int>>

    @Query("DELETE FROM hidden_channels")
    suspend fun clearHidden()

    @Insert
    suspend fun insertHidden(rows: List<HiddenChannelEntity>)

    /** The hidden event channels, replaced in one go (the lists change once). */
    @Transaction
    suspend fun replaceHidden(ids: Collection<Int>) {
        clearHidden()
        if (ids.isNotEmpty()) insertHidden(ids.map(::HiddenChannelEntity))
    }

    @Query("DELETE FROM channels")
    suspend fun clearChannels()

    @Query("DELETE FROM channel_groups")
    suspend fun clearGroups()

    @Query("DELETE FROM programs")
    suspend fun clearPrograms()

    /** How many saved programmes have a synopsis (for the log: a guide source with none shows in a tester's report). */
    @Query("SELECT COUNT(*) FROM programs WHERE description IS NOT NULL")
    suspend fun programsWithDescription(): Int

    /** Everything still running at [from] or starting after it (a guide top-up brings these again). */
    @Query("DELETE FROM programs WHERE endTime > :from")
    suspend fun deleteProgramsAfter(from: Long)

    /** Programmes over before [before] (the guide's lookback; catch-up loads its own past when asked). */
    @Query("DELETE FROM programs WHERE endTime < :before")
    suspend fun deleteProgramsBefore(before: Long)
}

@Database(
    entities = [ChannelEntity::class, GroupEntity::class, ProgramEntity::class, HiddenChannelEntity::class],
    version = 7,
    exportSchema = false,
)
@androidx.room.TypeConverters(PackedConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun guideDao(): GuideDao
}
