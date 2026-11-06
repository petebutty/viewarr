package io.github.petebutty.viewarr.data.dvr

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.reminderStore by preferencesDataStore(name = "reminders")

/** "Remind me": a programme to pop up about when it starts (while Viewarr is open). */
data class Reminder(val channelId: Int, val start: Long, val end: Long, val title: String, val channelName: String) {
    val id get() = "$channelId@$start"

    internal fun encode() = listOf(channelId, start, end, title.replace(SEP, " "), channelName.replace(SEP, " ")).joinToString(SEP)

    companion object {
        private const val SEP = "\u001F"
        internal fun decode(s: String): Reminder? = runCatching {
            val p = s.split(SEP)
            Reminder(p[0].toInt(), p[1].toLong(), p[2].toLong(), p[3], p[4])
        }.getOrNull()
    }
}

@Singleton
class ReminderStore @Inject constructor(@ApplicationContext private val context: Context) {

    /** Reminders still to come (or on now), soonest first. */
    val reminders: Flow<List<Reminder>> = context.reminderStore.data.map { p ->
        p[KEY].orEmpty().mapNotNull { Reminder.decode(it) }.sortedBy { it.start }
    }

    suspend fun add(r: Reminder) {
        context.reminderStore.edit { p -> p[KEY] = p[KEY].orEmpty().filterNot { Reminder.decode(it)?.id == r.id }.toSet() + r.encode() }
    }

    suspend fun remove(id: String) {
        context.reminderStore.edit { p -> p[KEY] = p[KEY].orEmpty().filterNot { Reminder.decode(it)?.id == id }.toSet() }
    }

    /** Drops reminders for programmes that have finished. */
    suspend fun prune(now: Long) {
        context.reminderStore.edit { p -> p[KEY] = p[KEY].orEmpty().filter { (Reminder.decode(it)?.end ?: 0) > now }.toSet() }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("reminders")
    }
}
