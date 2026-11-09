package io.github.petebutty.viewarr.data.media

import android.util.LruCache
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.TmdbService
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.str
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Artwork for a TV guide programme: its show's (or film's) backdrop from TMDB, found by title. Only a close name
 * match counts, so news and one-offs keep the channel logo rather than a stranger's picture. Answers are cached.
 */
@Singleton
class ProgramArt @Inject constructor(
    private val http: HttpJson,
    private val tmdb: TmdbService,
    private val store: AccountStore,
) {
    private val cache = LruCache<String, Any>(300) // title → url, or NONE
    private object None

    suspend fun imageFor(rawTitle: String): String? {
        val title = clean(rawTitle)
        if (title.length < 2) return null
        cache.get(title)?.let { return it as? String }
        val key = store.current().tmdbKey ?: return null
        val url = withTimeoutOrNull(6_000) {
            runCatching {
                val r = http.get(tmdb.url("/search/multi", key, mapOf("query" to title, "include_adult" to "false", "language" to "en-GB")), tmdb.headers(key))
                if (!r.ok) return@runCatching null
                val want = norm(title)
                r.body.array("results")
                    .filter { it.str("media_type") == "tv" || it.str("media_type") == "movie" }
                    .firstOrNull { j -> norm(j.str("name") ?: j.str("title") ?: "") == want && j.str("backdrop_path") != null }
                    ?.str("backdrop_path")?.let { "https://image.tmdb.org/t/p/w780$it" }
            }.getOrNull()
        }
        cache.put(title, url ?: None)
        return url
    }

    /** "New: Bridge of Lies", "Film: Heat (1995)", "Arsenal v Chelsea ᴸᶦᵛᵉ" → the name to look up. */
    private fun clean(t: String) = t
        .replace(Regex("^(New|Live|Film|Premiere|Movie)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
        .replace(Regex("[ᴬ-ᵛ]+"), "")
        .replace(Regex("\\.?\\s*S\\d+\\s*E\\d+$", RegexOption.IGNORE_CASE), "")
        .trim().trimEnd('.')

    private fun norm(s: String) = s.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]"), "")
}
