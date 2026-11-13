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
import kotlinx.coroutines.flow.Flow

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
            val out = java.io.ByteArrayOutputStream(bytes.size * 3)
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
            val out = java.io.ByteArrayOutputStream(text.length)
            val buf = ByteArray(1024)
            while (!def.finished()) out.write(buf, 0, def.deflate(buf))
            def.end()
            return Packed(out.toByteArray())
        }
    }
}

class PackedConverters {
    @androidx.room.TypeConverter fun toBytes(p: Packed?): ByteArray? = p?.bytes
    @androidx.room.TypeConverter fun toPacked(b: ByteArray?): Packed? = b?.let(::Packed)
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
}

@Dao
interface GuideDao {
    @Query(
        """
        SELECT c.id, c.uuid, c.name, c.number, c.logoId, c.logoUrl, g.name AS groupName, c.guideKey, c.catchupDays
        FROM channels c LEFT JOIN channel_groups g ON g.id = c.groupId
        WHERE (:groupId IS NULL OR c.groupId = :groupId)
        ORDER BY c.number IS NULL, c.number, c.name, c.id
        """
    )
    fun observeChannels(groupId: Int?): Flow<List<ChannelListItem>>

    /** The favourites group: these channels (by uuid), in channel order. */
    @Query(
        """
        SELECT c.id, c.uuid, c.name, c.number, c.logoId, c.logoUrl, g.name AS groupName, c.guideKey, c.catchupDays
        FROM channels c LEFT JOIN channel_groups g ON g.id = c.groupId
        WHERE c.uuid IN (:uuids)
        ORDER BY c.number IS NULL, c.number, c.name, c.id
        """
    )
    fun observeChannelsIn(uuids: List<String>): Flow<List<ChannelListItem>>

    @Query(
        """
        SELECT g.id, g.name, COUNT(c.id) AS channelCount
        FROM channel_groups g JOIN channels c ON c.groupId = g.id
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
            WHERE (:groupId IS NULL OR groupId = :groupId)
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
            WHERE uuid IN (:uuids)
            ORDER BY number IS NULL, number, name, id
            LIMIT :count OFFSET :offset
        ) c
        JOIN programs p ON p.tvgId = c.guideKey
        WHERE p.endTime > :start AND p.startTime < :end
        ORDER BY c.id, p.startTime
        """
    )
    fun observeProgramBlockIn(uuids: List<String>, offset: Int, count: Int, start: Long, end: Long): Flow<List<GuideProgram>>

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
     * Programmes whose title contains [q] and that end between [endFrom] and [endTo] (a game's kick-off: the shows
     * around it), read through the end-time index rather than the whole guide.
     */
    @Query(
        """
        SELECT c.id AS channelId, p.rowId, p.title, p.subTitle, p.description, p.startTime, p.endTime,
               p.season, p.episode, p.isNew, p.isLive, p.isPremiere, p.image
        FROM programs p JOIN channels c ON c.guideKey = p.tvgId
        WHERE p.endTime BETWEEN :endFrom AND :endTo AND p.title LIKE '%' || :q || '%'
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

    @Query("DELETE FROM programs WHERE tvgId = :tvgId AND startTime >= :from AND startTime < :to AND endTime <= :endLimit")
    suspend fun deletePastChunk(tvgId: String, from: Long, to: Long, endLimit: Long)

    @Transaction
    suspend fun replacePastChunk(tvgId: String, from: Long, to: Long, endLimit: Long, programs: List<ProgramEntity>) {
        deletePastChunk(tvgId, from, to, endLimit)
        insertPrograms(programs)
    }

    @Query("DELETE FROM channels")
    suspend fun clearChannels()

    @Query("DELETE FROM channel_groups")
    suspend fun clearGroups()

    @Query("DELETE FROM programs")
    suspend fun clearPrograms()
}

@Database(
    entities = [ChannelEntity::class, GroupEntity::class, ProgramEntity::class],
    version = 6,
    exportSchema = false,
)
@androidx.room.TypeConverters(PackedConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun guideDao(): GuideDao
}
