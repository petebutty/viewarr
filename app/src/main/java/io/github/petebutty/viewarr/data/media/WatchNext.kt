package io.github.petebutty.viewarr.data.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The TV home screen's "Watch Next" row: Viewarr's Continue watching (Plex On Deck, Jellyfin / Emby resume) as cards
 * with progress. Picking a card opens that title in Viewarr ([DeepLink]). Refreshed when the app starts and after
 * something is watched; cards for things no longer in progress are removed.
 */
@Singleton
class WatchNext @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: CatalogRepository,
) {
    private val lock = Mutex()

    suspend fun refresh() = lock.withLock {
        runCatching {
            val items = MediaType.entries.flatMap { t -> runCatching { catalog.continueWatching(t) }.getOrDefault(emptyList()) }
            publish(items)
        }.onFailure { Log.w(TAG, "watch next refresh failed: $it") }
    }

    private suspend fun publish(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        // Only this app's cards come back from the query.
        val existing = HashMap<String, Long>()
        cr.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) c.getString(1)?.let { existing[it] = c.getLong(0) }
        }
        val now = System.currentTimeMillis()
        val wanted = items.distinctBy { it.key }.take(MAX).withIndex().associate { (i, item) -> item.key to (i to item) }
        // Replace this app's cards outright: an update keeps old values for anything not set this time.
        existing.values.forEach { id -> runCatching { cr.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) } }
        for ((key, pair) in wanted) {
            val (i, item) = pair
            runCatching { cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program(item, key, now - i * 1_000L).toContentValues()) }
                .onFailure { Log.w(TAG, "watch next card '${item.title}': $it") }
        }
        Log.i(TAG, "watch next: ${wanted.size} cards; " + wanted.values.take(4).joinToString { (_, it) -> "${it.title} ${it.durationMs?.div(60_000)}min ${((it.progress ?: 0f) * 100).toInt()}%" })
    }

    private fun program(item: MediaItem, key: String, engaged: Long): WatchNextProgram {
        val episode = item.type == MediaType.Show
        val wide = item.backdrop != null
        val b = WatchNextProgram.Builder()
            .setType(if (episode) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
            .setWatchNextType(
                if (item.progress != null && item.progress > 0f) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT,
            )
            .setLastEngagementTimeUtcMillis(engaged)
            .setInternalProviderId(key)
            .setIntentUri(DeepLink.uriFor(item))
        b.setTitle(item.title)
        // The home screen's own line is the full length; the description says how far through you are.
        val length = item.durationMs?.takeIf { it > 0 }
        val at = item.progress?.takeIf { it > 0f }?.let { p -> length?.let { (p * it).toLong() } }
        val where = when {
            at != null && length != null -> "Stopped at ${clock(at)} of ${clock(length)}"
            item.progress != null && item.progress > 0f -> "${(item.progress * 100).toInt()}% watched"
            else -> null
        }
        listOfNotNull(where, item.overview).joinToString("\n").takeIf { it.isNotBlank() }?.let { b.setDescription(it) }
        (item.backdrop ?: item.poster)?.let { b.setPosterArtUri(Uri.parse(it)) }
        b.setPosterArtAspectRatio(if (wide) TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9 else TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
        if (episode) {
            item.nextSeason?.let { b.setSeasonNumber(it) }
            item.nextEpisode?.let { b.setEpisodeNumber(it) }
            item.subtitle?.substringAfter("  ", "")?.takeIf { it.isNotBlank() }?.let { b.setEpisodeTitle(it) }
        }
        // The real length when the server gave it (the home screen shows it, and the bar is measured against it).
        if (length != null) {
            b.setDurationMillis(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            item.progress?.let { p -> b.setLastPlaybackPositionMillis((p.coerceIn(0f, 1f) * length).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        }
        return b.build()
    }

    /** 45 min, 1h 05m. */
    private fun clock(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "%dh %02dm".format(m / 60, m % 60) else "$m min"
    }

    private companion object {
        const val TAG = "WatchNext"
        const val MAX = 20
    }
}

/** Opening a title from outside the app (a Watch Next card): viewarr://title?…, picked up by the home screen. */
object DeepLink {
    val pending = MutableStateFlow<MediaItem?>(null)

    fun uriFor(item: MediaItem): Uri = Uri.Builder().scheme("viewarr").authority("title").apply {
        appendQueryParameter("key", item.key.removeSuffix(":deck"))
        appendQueryParameter("type", item.type.name)
        appendQueryParameter("title", item.title)
        item.tmdbId?.let { appendQueryParameter("tmdb", it.toString()) }
        item.imdbId?.let { appendQueryParameter("imdb", it) }
        item.year?.let { appendQueryParameter("year", it.toString()) }
        item.poster?.let { appendQueryParameter("poster", it) }
        item.backdrop?.let { appendQueryParameter("backdrop", it) }
        item.nextSeason?.let { appendQueryParameter("season", it.toString()) }
        item.nextEpisode?.let { appendQueryParameter("episode", it.toString()) }
        appendQueryParameter("origin", item.origin)
    }.build()

    /** From an incoming intent's data, or null if it isn't one of ours. */
    fun parse(uri: Uri?): MediaItem? {
        if (uri == null || uri.scheme != "viewarr" || uri.host != "title") return null
        val key = uri.getQueryParameter("key") ?: return null
        val type = runCatching { MediaType.valueOf(uri.getQueryParameter("type").orEmpty()) }.getOrDefault(MediaType.Movie)
        return MediaItem(
            key = key,
            type = type,
            title = uri.getQueryParameter("title").orEmpty(),
            year = uri.getQueryParameter("year")?.toIntOrNull(),
            poster = uri.getQueryParameter("poster"),
            backdrop = uri.getQueryParameter("backdrop"),
            tmdbId = uri.getQueryParameter("tmdb")?.toIntOrNull(),
            imdbId = uri.getQueryParameter("imdb"),
            origin = uri.getQueryParameter("origin") ?: "Viewarr",
            nextSeason = uri.getQueryParameter("season")?.toIntOrNull(),
            nextEpisode = uri.getQueryParameter("episode")?.toIntOrNull(),
        )
    }
}
