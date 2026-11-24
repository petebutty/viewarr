package io.github.petebutty.viewarr.data.media

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.settings.ThemeStore
import io.github.petebutty.viewarr.ui.common.Notify
import io.github.petebutty.viewarr.ui.report.HouseNotice
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Followed shows, and a card when a new episode of one is in your library (2026-11-24: "notifications for shows
 * you're watching when a new episode is in your library … could we follow shows for this?").
 *
 * Followed: the shows you follow yourself (Follow on a show's page; kept with your settings, so every box has them,
 * as "follow:<key>|<tmdb id>|<name>"), and, unless switched off, the shows you're watching (in Continue watching in
 * the last 60 days; kept on this box). A show unfollowed that you're still watching stays off ("unfollow:<key>").
 *
 * New: once Viewarr has opened and every 3 hours while it's open, each followed show's newest episode on your own
 * servers (Plex, Jellyfin, Emby) is compared with the newest this box had seen: a later one is new. The first look at
 * a show only remembers it. Settings → Notifications → New episodes (off to start, as every notification): the card
 * (OK opens the show), and the episode on the Android home screen's Play next row for a week (WatchNext).
 */
@Singleton
class FollowedShows @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: CatalogRepository,
    private val themeStore: ThemeStore,
    private val watchNext: dagger.Lazy<WatchNext>,
) {
    private val prefs = context.getSharedPreferences("followed_shows", Context.MODE_PRIVATE)
    private var started = false

    /** One followed show: its item key, TMDB id and name. */
    data class Show(val key: String, val tmdbId: Int?, val title: String) {
        fun item() = MediaItem(key = key, type = MediaType.Show, title = title, tmdbId = tmdbId, origin = "Followed")
    }

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        loadAuto()
        scope.launch(Dispatchers.IO) {
            // Every 3 hours while New episodes is on; looked at every 10 minutes, so switching it on starts within 10.
            var last = 0L
            while (true) {
                if (Notify.newEpisodes && System.currentTimeMillis() - last >= 3 * 3_600_000L) {
                    last = System.currentTimeMillis()
                    runCatching { check() }.onFailure { Log.w(TAG, "check failed: ${it.message}") }
                }
                delay(10 * 60_000L)
            }
        }
    }

    // ---- Following -------------------------------------------------------------------------------------------

    suspend fun follow(item: MediaItem, on: Boolean) {
        val key = item.key
        if (on) {
            themeStore.saveTitlePart(part(Show(key, item.tmdbId, item.title)), false)
            themeStore.saveTitlePart(UNFOLLOW + key, true)
            // (Unfollowed while you were watching it, under Continue watching's own key: followed again too.)
            auto.value.filter { it.tmdbId != null && it.tmdbId == item.tmdbId }.forEach { themeStore.saveTitlePart(UNFOLLOW + it.key, true) }
        } else unfollowParts(match(item)?.key ?: key).forEach { (p, on) -> themeStore.saveTitlePart(p, on) }
    }

    /** What the shows you're watching were, by when this box last saw them in Continue watching. */
    private fun loadAuto() {
        val now = System.currentTimeMillis()
        auto.value = prefs.getStringSet(AUTO, emptySet()).orEmpty().mapNotNull { line ->
            val p = line.split('|', limit = 4)
            if (p.size < 4) return@mapNotNull null
            val at = p[0].toLongOrNull() ?: return@mapNotNull null
            if (now - at > AUTO_KEEP_MS) null else Show(p[1], p[2].toIntOrNull(), p[3]) to at
        }.map { it.first }
    }

    private suspend fun updateAuto() {
        val now = System.currentTimeMillis()
        val old = prefs.getStringSet(AUTO, emptySet()).orEmpty().mapNotNull { line ->
            val p = line.split('|', limit = 4)
            if (p.size < 4) null else p[1] to line
        }.toMap().toMutableMap()
        catalog.continueWatching(MediaType.Show).filter { it.type == MediaType.Show }.forEach { i ->
            old[i.key] = "$now|${i.key}|${i.tmdbId ?: ""}|${i.title.replace('|', ' ')}"
        }
        val kept = old.values.filter { (it.substringBefore('|').toLongOrNull() ?: 0L) > now - AUTO_KEEP_MS }.toSet()
        prefs.edit().putStringSet(AUTO, kept).apply()
        loadAuto()
    }

    // ---- New episodes ----------------------------------------------------------------------------------------

    /** A new episode found: for the card and the Play next row. */
    data class NewEpisode(val show: Show, val season: Int, val episode: Int, val at: Long)

    private suspend fun check() {
        if (!Notify.newEpisodes) return
        if (autoOn()) runCatching { updateAuto() }
        val news = ArrayList<NewEpisode>()
        for (s in followed()) {
            val latest = runCatching { latestEpisode(s) }.getOrNull() ?: continue
            val code = latest.first * 10_000 + latest.second
            val seenKey = SEEN + s.key
            val seen = prefs.getInt(seenKey, -1)
            prefs.edit().putInt(seenKey, maxOf(code, seen)).apply()
            // (The first look only remembers where it's up to.)
            if (seen in 0 until code) news += NewEpisode(s, latest.first, latest.second, System.currentTimeMillis())
        }
        if (news.isEmpty()) return
        Log.i(TAG, "new: " + news.joinToString { "${it.show.title} S${it.season} E${it.episode}" })
        saveCards(news)
        runCatching { watchNext.get().refresh() }
        withContext(Dispatchers.Main) {
            val first = news.first()
            val text = if (news.size == 1) "New episode · ${first.show.title} S${first.season} E${first.episode} is in your library"
                else "${news.size} new episodes: " + news.joinToString(", ") { it.show.title }
            HouseNotice.show(HouseNotice.Notice(text, "OK: open ${first.show.title}  ·  Back: dismiss", action = { DeepLink.pending.value = first.show.item() }))
        }
    }

    /** The newest episode (season, number) of [s] on any of your servers; null when it isn't on one. */
    private suspend fun latestEpisode(s: Show): Pair<Int, Int>? {
        val copies = catalog.copiesOf(s.item()).filter { it.ref != null && it.source != PROVIDER }
        val eps = copies.flatMap { c ->
            runCatching { if (c.source == "Plex") catalog.plexEpisodes(c.ref!!).keys else catalog.serverEpisodes(c.source, c.ref!!).keys }.getOrDefault(emptySet())
        }
        return eps.filter { it.first > 0 }.maxWithOrNull(compareBy({ it.first }, { it.second }))
    }

    private fun saveCards(news: List<NewEpisode>) {
        val now = System.currentTimeMillis()
        val keep = cardLines().filter { (it.substringBefore('|').toLongOrNull() ?: 0L) > now - CARD_KEEP_MS }
            .filter { line -> news.none { line.split('|').getOrNull(1) == it.show.key } }
        val add = news.map { "${it.at}|${it.show.key}|${it.show.tmdbId ?: ""}|${it.season}|${it.episode}|${it.show.title.replace('|', ' ')}" }
        prefs.edit().putStringSet(CARDS, (keep + add).toSet()).apply()
    }

    private fun cardLines() = prefs.getStringSet(CARDS, emptySet()).orEmpty()

    /** The new episodes of the last week, newest first, as Play next cards (WatchNext). */
    fun playNextCards(): List<MediaItem> {
        if (!Notify.newEpisodes) return emptyList()
        val now = System.currentTimeMillis()
        return cardLines().mapNotNull { line ->
            val p = line.split('|', limit = 6)
            if (p.size < 6) return@mapNotNull null
            val at = p[0].toLongOrNull() ?: return@mapNotNull null
            if (now - at > CARD_KEEP_MS) return@mapNotNull null
            at to MediaItem(key = p[1], type = MediaType.Show, title = p[5], tmdbId = p[2].toIntOrNull(), origin = "Followed",
                nextSeason = p[3].toIntOrNull(), nextEpisode = p[4].toIntOrNull(), subtitle = "New episode  S${p[3]} E${p[4]}")
        }.sortedByDescending { it.first }.map { it.second }
    }

    companion object {
        private const val TAG = "FollowedShows"
        private const val FOLLOW = "follow:"
        private const val UNFOLLOW = "unfollow:"
        /** Present: the shows you're watching aren't followed by themselves. */
        const val AUTO_OFF = "follow:auto-off"
        private const val AUTO = "auto"
        private const val CARDS = "cards"
        private const val SEEN = "seen_"
        private const val AUTO_KEEP_MS = 60 * 86_400_000L
        private const val CARD_KEEP_MS = 7 * 86_400_000L

        /** The shows you're watching (this box's list), for the page and Settings to read. */
        val auto = mutableStateOf<List<Show>>(emptyList())

        private fun part(s: Show) = FOLLOW + listOf(s.key, s.tmdbId?.toString().orEmpty(), s.title.replace('|', ' ')).joinToString("|")

        fun autoOn() = AppColors.palette.shows(AUTO_OFF)

        /** The shows you follow yourself. */
        fun manual(): List<Show> = AppColors.palette.titleHidden.filter { it.startsWith(FOLLOW) && it != AUTO_OFF }.mapNotNull { p ->
            val v = p.removePrefix(FOLLOW).split('|', limit = 3)
            if (v.size < 3) null else Show(v[0], v[1].toIntOrNull(), v[2])
        }

        private fun unfollowed(key: String) = !AppColors.palette.shows(UNFOLLOW + key)

        /** Every followed show: yours, and the ones you're watching (unless switched off, or unfollowed). */
        fun followed(): List<Show> {
            val mine = manual()
            val watching = if (autoOn()) auto.value.filter { a -> mine.none { it.key == a.key } && !unfollowed(a.key) } else emptyList()
            return (mine + watching).sortedBy { it.title.lowercase() }
        }

        /** The followed show that is [item] (by its key, or its TMDB id: Continue watching's key can be a server's). */
        fun match(item: MediaItem) = followed().firstOrNull { it.key == item.key || (item.tmdbId != null && it.tmdbId == item.tmdbId) }
        fun isFollowed(item: MediaItem) = match(item) != null

        /** Stopping following [key], as settings parts (part, on): yours taken off, one you're watching marked unfollowed. */
        fun unfollowParts(key: String): List<Pair<String, Boolean>> =
            manual().filter { it.key == key }.map { part(it) to true } +
                listOfNotNull((UNFOLLOW + key to false).takeIf { auto.value.any { it.key == key } })
    }
}
