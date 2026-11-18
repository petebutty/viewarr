package io.github.petebutty.viewarr.ui.music

import android.content.Context
import io.github.petebutty.viewarr.data.music.MusicLibrary
import io.github.petebutty.viewarr.data.music.Song
import io.github.petebutty.viewarr.playback.MusicPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * Keeps a radio queue endless. The player (MusicPlayer.radio) says when three songs or fewer are left; this asks the
 * server for songs like the last one and adds those not heard yet this session. Nothing is shown: the queue just
 * carries on. When the server has nothing new (twice running) the radio ends quietly, as a normal queue would.
 */
internal class RadioFeeder(private val library: MusicLibrary, private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** One look-up at a time; a new radio makes the old one's answer stale. */
    private var busy = false
    private var generation = 0
    private var empties = 0
    /** What's been queued this session (artist and title as [MusicLibrary.norm] has them), so nothing repeats. */
    private val heard = HashSet<String>()

    private fun key(s: Song) = MusicLibrary.norm(s.artist) + "|" + MusicLibrary.norm(s.title)

    /** A new radio began: forget the last one's songs. */
    fun started(songs: List<Song>) { generation++; empties = 0; heard.clear(); songs.forEach { heard += key(it) } }

    /** The queue is running low ([last] is its final song): one refill at a time. */
    fun low(last: Song) {
        if (busy) return
        busy = true
        val gen = generation
        scope.launch {
            try { refill(last, gen) } finally { busy = false }
        }
    }

    private suspend fun refill(last: Song, gen: Int) {
        MusicPlayer.queue.forEach { heard += key(it) }
        var fresh = like(last)
        // Nothing new like the last song: one more try from another song of the queue.
        if (fresh.isEmpty()) MusicPlayer.queue.randomOrNull()?.let { fresh = like(it) }
        // (The radio may have stopped, or another begun, while the server answered.)
        if (gen != generation || !MusicPlayer.radio) return
        if (fresh.isEmpty()) { if (++empties >= 2) MusicPlayer.endRadio(); return }
        empties = 0
        fresh.forEach { heard += key(it) }
        MusicPlayer.addToQueue(context, fresh)
    }

    /** Up to 15 songs like [song], none heard this session. */
    private suspend fun like(song: Song): List<Song> =
        runCatching { library.similarSongs(song, 30) }.getOrDefault(emptyList()).filter { key(it) !in heard }.distinctBy { key(it) }.take(15)
}
