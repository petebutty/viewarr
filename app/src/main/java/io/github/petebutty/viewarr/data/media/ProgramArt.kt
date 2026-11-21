package io.github.petebutty.viewarr.data.media

import android.util.LruCache
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.TmdbService
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.str
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) {
    // The answers kept on the box too (Peter, 2026-11-18: the guide's pictures and title logos were looked up again
    // after every start): "guide_art.json" {"p|<title>" or "l|<title>": [url or "" (none), day]}, 14 days (none: 3).
    private val kept = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    @Volatile private var keptLoaded = false
    private val keptScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var keptSave: kotlinx.coroutines.Job? = null
    private val keptFile get() = java.io.File(appContext.filesDir, "guide_art.json")
    private fun today() = System.currentTimeMillis() / 86_400_000L
    private fun keptGet(k: String): String? {
        if (!keptLoaded) {
            keptLoaded = true
            runCatching {
                val o = kotlinx.serialization.json.Json.parseToJsonElement(keptFile.readText()) as? kotlinx.serialization.json.JsonObject
                o?.forEach { (key, v) ->
                    val a = v as? JsonArray ?: return@forEach
                    val url = (a.getOrNull(0) as? JsonPrimitive)?.content ?: return@forEach
                    val day = (a.getOrNull(1) as? JsonPrimitive)?.content?.toLongOrNull() ?: return@forEach
                    if (today() - day <= (if (url.isEmpty()) 3 else 14)) kept.putIfAbsent(key, url to day)
                }
            }
        }
        return kept[k]?.first
    }
    private fun keptPut(k: String, url: String?) {
        kept[k] = url.orEmpty() to today()
        keptSave?.cancel()
        keptSave = keptScope.launch {
            kotlinx.coroutines.delay(5_000)
            runCatching {
                val keep = kept.entries.sortedByDescending { it.value.second }.take(4_000)
                val o = kotlinx.serialization.json.buildJsonObject {
                    keep.forEach { (key, v) -> put(key, kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(v.first)); add(JsonPrimitive(v.second)) }) }
                }
                val tmp = java.io.File(appContext.filesDir, "guide_art.json.tmp")
                tmp.writeText(o.toString()); tmp.renameTo(keptFile)
            }
        }
    }

    private val cache = LruCache<String, Any>(300) // title → Match, or NONE
    private val logos = LruCache<String, Any>(300) // "tv:123" → logo url, or NONE
    private object None
    /** A guide title matched to TMDB: tv or movie, its id, and its backdrop. */
    private data class Match(val type: String, val id: Int, val backdrop: String?, val logoMatch: Match? = null, val poster: String? = null, val name: String? = null)

    /** The programme's show / film on TMDB, by a close name match only (cached, hits and misses). */
    private suspend fun match(rawTitle: String): Match? {
        val title = clean(rawTitle)
        if (title.length < 2) return null
        cache.get(title)?.let { return it as? Match }
        val key = store.current().tmdbKey ?: return null
        val found = withTimeoutOrNull(6_000) {
            runCatching {
                val r = http.get(tmdb.url("/search/multi", key, mapOf("query" to title, "include_adult" to "false", "language" to "en-GB")), tmdb.headers(key))
                if (!r.ok) return@runCatching null
                val want = norm(title)
                val same = r.body.array("results")
                    .filter { it.str("media_type") == "tv" || it.str("media_type") == "movie" }
                    .filter { j -> norm(j.str("name") ?: j.str("title") ?: "") == want }
                fun m(j: JsonElement) = j.int("id")?.let { Match(j.str("media_type")!!, it, j.str("backdrop_path"), poster = j.str("poster_path"), name = j.str("name") ?: j.str("title")) }
                // For the title logo, stricter (a wrong logo is worse than the name): a UK / Irish / US show, or the
                // only film with that name. ("Breakfast": five films and a Philippine show, so no logo.)
                val tvOk = same.firstOrNull { j -> j.str("media_type") == "tv" && (j.child("origin_country") as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.content in setOf("GB", "IE", "US") } }
                val films = same.filter { it.str("media_type") == "movie" }
                val logoPick = tvOk ?: films.singleOrNull()
                // The picture: one with a picture first, else any (as before).
                (same.firstOrNull { it.str("backdrop_path") != null } ?: same.firstOrNull())
                    ?.let(::m)?.copy(logoMatch = logoPick?.let(::m))
            }.getOrNull()
        }
        cache.put(title, found ?: None)
        return found
    }

    /**
     * A recording's (or any programme's) show or film on TMDB, by the same close name match as the guide's pictures:
     * "tv" or "movie", its id, its name there, poster and backdrop. Null: no close match (news, sport, one-offs).
     */
    suspend fun tmdbFor(rawTitle: String): TmdbTag? = match(rawTitle)?.let { m ->
        TmdbTag(m.type, m.id, m.name, m.poster?.let { "https://image.tmdb.org/t/p/w342$it" }, m.backdrop?.let { "https://image.tmdb.org/t/p/w780$it" })
    }

    /** An episode's own name on TMDB (null: unknown). */
    suspend fun episodeName(showId: Int, season: Int, episode: Int): String? {
        val key = store.current().tmdbKey ?: return null
        return withTimeoutOrNull(6_000) {
            runCatching {
                http.get(tmdb.url("/tv/$showId/season/$season/episode/$episode", key, mapOf("language" to "en-GB")), tmdb.headers(key))
                    .takeIf { it.ok }?.body?.str("name")?.takeIf { n -> n.isNotBlank() && !n.matches(Regex("(?i)episode \\d+")) }
            }.getOrNull()
        }
    }

    suspend fun imageFor(rawTitle: String): String? {
        val k = "p|" + norm(clean(rawTitle))
        keptGet(k)?.let { return it.ifEmpty { null } }
        return match(rawTitle)?.backdrop?.let { "https://image.tmdb.org/t/p/w780$it" }.also { keptPut(k, it) }
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

/** A programme matched on TMDB: its kind ("tv" / "movie"), id, name there, and pictures (full addresses). */
data class TmdbTag(val type: String, val id: Int, val name: String?, val poster: String?, val backdrop: String?)
