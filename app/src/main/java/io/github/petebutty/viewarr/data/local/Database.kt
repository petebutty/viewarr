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
    val description: String?,
    val startTime: Long,
    val endTime: Long,
    val season: Int?,
    val episode: Int?,
    val isNew: Boolean,
    val isLive: Boolean,
    val isPremiere: Boolean,
)

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
    val description: String?,
    val startTime: Long,
    val endTime: Long,
    val season: Int?,
    val episode: Int?,
    val isNew: Boolean,
    val isLive: Boolean,
    val isPremiere: Boolean,
)

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
               p.season, p.episode, p.isNew, p.isLive, p.isPremiere
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

    @Query("SELECT COUNT(*) FROM programs")
    suspend fun programCount(): Int

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
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun guideDao(): GuideDao
}
