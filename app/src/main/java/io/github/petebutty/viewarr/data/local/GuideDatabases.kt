package io.github.petebutty.viewarr.data.local

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One guide database per Live TV source, so switching between saved sources is instant: the three used most recently
 * are kept (each is mostly guide data, a few tens to a couple of hundred MB); older ones are deleted.
 */
@Singleton
class GuideDatabases @Inject constructor(@ApplicationContext private val context: Context) {
    private val open = HashMap<String, AppDatabase>()
    private val prefs = context.getSharedPreferences("guide_databases", Context.MODE_PRIVATE)

    init {
        // The single database from before sources were cached: its guide is re-fetched into the new one.
        runCatching { if (context.getDatabasePath("viewarr.db").exists()) context.deleteDatabase("viewarr.db") }
    }

    private fun nameFor(sourceId: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(sourceId.toByteArray()).joinToString("") { "%02x".format(it) }
        return "guide-${hash.take(12)}.db"
    }

    /** The database for [sourceId] (opened, and marked most recently used; the fourth-most-recent is deleted). */
    @Synchronized
    fun get(sourceId: String): AppDatabase {
        val name = nameFor(sourceId)
        val recent = (listOf(name) + prefs.getString(KEY_RECENT, "").orEmpty().split(',').filter { it.isNotBlank() && it != name })
        recent.drop(KEEP).forEach { old ->
            open.remove(old)?.close()
            runCatching { context.deleteDatabase(old) }
        }
        prefs.edit().putString(KEY_RECENT, recent.take(KEEP).joinToString(",")).apply()
        return open.getOrPut(name) {
            Room.databaseBuilder(context, AppDatabase::class.java, name).fallbackToDestructiveMigration().build()
        }
    }

    /** Signing out everywhere: every source's guide goes. */
    @Synchronized
    fun deleteAll() {
        open.values.forEach { runCatching { it.close() } }
        open.clear()
        prefs.getString(KEY_RECENT, "").orEmpty().split(',').filter { it.isNotBlank() }.forEach { runCatching { context.deleteDatabase(it) } }
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEEP = 3
        const val KEY_RECENT = "recent"
    }
}
