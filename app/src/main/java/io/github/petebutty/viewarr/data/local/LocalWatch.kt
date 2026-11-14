package io.github.petebutty.viewarr.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.media.MediaType
import io.github.petebutty.viewarr.data.media.PlexEpisode
import io.github.petebutty.viewarr.data.profiles.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * One film or episode watched from IPTV (Dispatcharr / Xtream VOD), kept on this box because the provider keeps
 * nothing: where you stopped, and whether you finished it. [id] ties it to the title whatever row it was opened
 * from (TMDB id when known, else the title's own key); episodes carry [season] / [episode].
 */
@Serializable
data class LocalWatch(
    val id: String,
    val type: String,
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val key: String,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watched: Boolean = false,
    /** Taken out of Continue watching by hand (comes back when it's played again). */
    val hidden: Boolean = false,
    /** Episodes, once finished: the next one from the show's real episode list (into the next season at a season's
     *  end); null with [lastOfShow]: there's none (the show is done, for now). Unknown: both unset (S·E+1 is assumed). */
    val nextSeason: Int? = null,
    val nextEpisode: Int? = null,
    val lastOfShow: Boolean = false,
    val updatedAt: Long = 0,
) {
    val mediaType get() = if (type == "Show") MediaType.Show else MediaType.Movie
    val progress get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

private val Context.localWatchStore by preferencesDataStore(name = "local_watch")

/**
 * IPTV watch progress, per profile (each Dispatcharr user on the box has their own). Saved when a film or episode
 * from IPTV stops or pauses; read for Resume, the ticks on episodes, Continue watching (IPTV) and the rows under the
 * info bars. Past [WATCHED] of the way through counts as watched.
 */
@Singleton
class LocalWatchStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val profiles: ProfileStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serializer = ListSerializer(LocalWatch.serializer())
    private fun keyFor(profile: String?) = stringPreferencesKey("watch_" + (profile ?: "default").hashCode().toUInt().toString(16))
    private fun decode(s: String?): List<LocalWatch> = s?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /** The active profile's entries, newest first. */
    val entries: StateFlow<List<LocalWatch>> = combine(context.localWatchStore.data, profiles.activeId) { prefs, active ->
        decode(prefs[keyFor(active)]).sortedByDescending { it.updatedAt }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * [entries] changing only in ways rows show (what's there, watched, about where): a save every 10 s while playing
     * redrew every page and row before. Positions count in 2% steps.
     */
    val rowEntries: StateFlow<List<LocalWatch>> = entries.distinctUntilChanged { a, b ->
        a.size == b.size && a.indices.all { i -> val x = a[i]; val y = b[i]
            x.id == y.id && x.season == y.season && x.episode == y.episode && x.watched == y.watched && x.hidden == y.hidden &&
                (x.progress * 50).toInt() == (y.progress * 50).toInt() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private suspend fun edit(change: (List<LocalWatch>) -> List<LocalWatch>) {
        val k = keyFor(profiles.activeId.first())
        context.localWatchStore.edit { p -> p[k] = json.encodeToString(serializer, change(decode(p[k])).sortedByDescending { it.updatedAt }.take(MAX)) }
    }

    /** Where you are in a film or episode (playing stopped or paused). */
    /**
     * [creditsMs]: where the credits start, when known (then that counts as the end; else [WATCHED] of the way).
     * [episodes]: the show's episodes as (season, episode), when known, to find the real next one.
     */
    suspend fun record(
        item: MediaItem, season: Int?, episode: Int?, positionMs: Long, durationMs: Long,
        creditsMs: Long? = null, episodes: Collection<Pair<Int, Int>> = emptyList(),
    ) {
        if (durationMs <= 0 || positionMs < 0) return
        val credits = creditsMs?.takeIf { it > durationMs / 2 && it < durationMs }
        val done = if (credits != null) positionMs >= credits - 5_000 else positionMs >= durationMs * WATCHED
        // The next episode from the real list (the first after this one, so a season's end goes on to the next).
        val after = if (season != null && episode != null && episodes.isNotEmpty())
            episodes.filter { it.first > 0 }.sortedWith(compareBy({ it.first }, { it.second }))
                .firstOrNull { it.first > season || (it.first == season && it.second > episode) }
        else null
        val id = idOf(item)
        edit { list ->
            val old = list.firstOrNull { it.id == id && it.season == season && it.episode == episode }
            // A few seconds in and stopped: not worth remembering (unless it was already being watched).
            if (old == null && positionMs < 60_000 && !done) return@edit list
            val entry = LocalWatch(
                id = id, type = item.type.name, title = item.title, year = item.year, poster = item.poster, backdrop = item.backdrop,
                tmdbId = item.tmdbId, imdbId = item.imdbId, key = baseKey(item.key), season = season, episode = episode,
                positionMs = if (done) 0 else positionMs, durationMs = durationMs, watched = done || (old?.watched == true && positionMs < 60_000),
                hidden = false, updatedAt = System.currentTimeMillis(),
                nextSeason = after?.first, nextEpisode = after?.second, lastOfShow = season != null && episodes.isNotEmpty() && after == null,
            )
            Log.i(TAG, "saved ${item.title}${season?.let { " S${it}E$episode" } ?: ""}: ${positionMs / 1000}s of ${durationMs / 1000}s" + if (entry.watched) " (watched)" else "")
            listOf(entry) + list.filterNot { it.id == id && it.season == season && it.episode == episode }
        }
    }

    /** Mark watched / not watched by hand: the film, or one episode (a whole show: every episode it has here). */
    suspend fun setWatched(item: MediaItem, watched: Boolean, season: Int? = null, episode: Int? = null) {
        val id = idOf(item)
        Log.i(TAG, "marked ${item.title}${season?.let { " S${it}E$episode" } ?: ""} " + if (watched) "watched" else "not watched")
        edit { list ->
            if (item.type == MediaType.Show && season == null) {
                if (watched) list.map { if (it.id == id) it.copy(watched = true, positionMs = 0) else it }
                else list.filterNot { it.id == id }
            } else {
                val old = list.firstOrNull { it.id == id && it.season == season && it.episode == episode }
                val rest = list.filterNot { it.id == id && it.season == season && it.episode == episode }
                // A mark by hand keeps the entry's place in time (a new one goes to the back): ticking an old episode
                // mustn't make it "the last one played", which moved Continue watching and Play back to it.
                if (watched) listOf(LocalWatch(id = id, type = item.type.name, title = item.title, year = item.year, poster = item.poster,
                    backdrop = item.backdrop, tmdbId = item.tmdbId, imdbId = item.imdbId, key = baseKey(item.key), season = season, episode = episode,
                    watched = true, updatedAt = old?.updatedAt ?: 0L, nextSeason = old?.nextSeason, nextEpisode = old?.nextEpisode, lastOfShow = old?.lastOfShow == true)) + rest
                else rest
            }
        }
    }

    /** Out of Continue watching (IPTV), without forgetting what's been watched. */
    suspend fun hide(item: MediaItem) {
        val id = idOf(item)
        Log.i(TAG, "removed ${item.title} from Continue watching")
        edit { list -> list.map { if (it.id == id) it.copy(hidden = true) else it } }
    }

    /** Where to carry on from (0: from the start). */
    fun resumeMs(item: MediaItem, season: Int? = null, episode: Int? = null): Long =
        entries.value.firstOrNull { it.id == idOf(item) && it.season == season && it.episode == episode && !it.watched }?.positionMs ?: 0

    fun isWatched(item: MediaItem): Boolean {
        val id = idOf(item)
        return entries.value.any { it.id == id && it.season == null && it.watched }
    }

    /** A show's episodes watched or started here, as the title page's ticks and resume points. */
    fun episodesOf(item: MediaItem): Map<Pair<Int, Int>, PlexEpisode> {
        val id = idOf(item)
        return entries.value.filter { it.id == id && it.season != null && it.episode != null }
            .associate { (it.season!! to it.episode!!) to PlexEpisode("local", it.positionMs, it.watched) }
    }

    /** Where the show carries on (season, episode, part way through) from what's been watched here; null: nothing yet,
     *  or the last episode there is has been finished. */
    fun nextUp(item: MediaItem): Triple<Int, Int, Boolean>? {
        val id = idOf(item)
        val last = entries.value.firstOrNull { it.id == id && it.season != null && it.episode != null } ?: return null
        if (!last.watched) return Triple(last.season!!, last.episode!!, last.positionMs > 0)
        if (last.lastOfShow) return null
        return if (last.nextSeason != null && last.nextEpisode != null) Triple(last.nextSeason, last.nextEpisode, false)
        else Triple(last.season!!, last.episode!! + 1, false)
    }

    /**
     * Continue watching (IPTV) for [type]: films started and not finished; shows at the episode you're part way
     * through, or the one after the last you finished. Newest first.
     */
    fun continueItems(type: MediaType, list: List<LocalWatch> = entries.value): List<MediaItem> =
        list.filter { it.mediaType == type && !it.hidden }.groupBy { it.id }.mapNotNull { (_, all) ->
            val last = all.maxBy { it.updatedAt }
            if (type == MediaType.Movie) {
                if (last.watched || last.positionMs <= 0) null else last.toItem(progress = last.progress)
            } else {
                val s = last.season ?: return@mapNotNull null
                val e = last.episode ?: return@mapNotNull null
                // Finished a long time ago: the show has probably been left (or is waiting for a new season).
                if (last.watched && System.currentTimeMillis() - last.updatedAt > 60L * 24 * 60 * 60_000) return@mapNotNull null
                // Finished the last episode there is: nothing to carry on with.
                if (last.watched && last.lastOfShow) return@mapNotNull null
                val (ns, ne) = when {
                    !last.watched -> s to e
                    last.nextSeason != null && last.nextEpisode != null -> last.nextSeason to last.nextEpisode
                    else -> s to e + 1
                }
                // (The title's own key: a changed one stopped Xtream logins finding the show to play.)
                last.toItem(progress = if (last.watched) null else last.progress).copy(nextSeason = ns, nextEpisode = ne, subtitle = "S$ns · E$ne")
            }
        }.take(30)

    private fun LocalWatch.toItem(progress: Float?) = MediaItem(
        key = key, type = mediaType, title = title, year = year, poster = poster, backdrop = backdrop,
        tmdbId = tmdbId, imdbId = imdbId, origin = "IPTV", progress = progress?.takeIf { it > 0f },
        durationMs = durationMs.takeIf { it > 0 },
    )

    companion object {
        const val WATCHED = 0.9
        private const val TAG = "LocalWatch"
        private const val MAX = 400

        /** The same title whichever row or source it came from: its TMDB id, else IMDb, else its own key. */
        /** A title's key without a row's marks (Plex's Continue watching adds ":deck", Trakt's ":trakt-next"). */
        fun baseKey(key: String) = key.substringBefore(":iptv-next").substringBefore(":deck").substringBefore(":trakt-next")

        fun idOf(item: MediaItem) = item.tmdbId?.let { "tmdb:${item.type.name}:$it" } ?: item.imdbId?.let { "imdb:$it" }
            ?: baseKey(item.key)
    }
}
