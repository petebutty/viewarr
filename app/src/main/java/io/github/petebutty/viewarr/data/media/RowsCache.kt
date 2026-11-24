package io.github.petebutty.viewarr.data.media

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * The Movies / Shows rows as last loaded, kept on the box (2026-11-18: the first opening asked every addon for
 * every catalogue again, which weaker boxes feel). Shown straight away on the next opening while the fresh rows load
 * behind them. One file per page and per set of sign-ins ([profile]), so another profile's rows never show. Lists only
 * (names, ids, picture addresses; descriptions cut short), about 1–2 MB in all; the pictures stay in the image cache.
 */
object RowsCache {
    private const val MAX_DAYS = 14

    private fun file(context: Context, type: MediaType, profile: String) =
        File(context.filesDir, "rows_${type.name.lowercase()}_$profile.json")

    /** How old the saved rows are (ms), or null when there aren't any. */
    fun ageMs(context: Context, type: MediaType, profile: String): Long? =
        file(context, type, profile).takeIf { it.exists() }?.let { System.currentTimeMillis() - it.lastModified() }

    fun read(context: Context, type: MediaType, profile: String): List<MediaRow>? = runCatching {
        val f = file(context, type, profile)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_DAYS * 86_400_000L) return null
        val rows = (Json.parseToJsonElement(f.readText()) as? JsonArray ?: return null).mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            MediaRow(
                id = o.s("id") ?: return@mapNotNull null, title = o.s("t").orEmpty(),
                items = (o["i"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { j -> item(j, type) } },
                group = o.s("g"), groupName = o.s("gn"),
            )
        }
        rows.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun write(context: Context, type: MediaType, profile: String, rows: List<MediaRow>) {
        runCatching {
            val a = buildJsonArray {
                for (r in rows) add(buildJsonObject {
                    put("id", r.id); put("t", r.title); r.group?.let { put("g", it) }; r.groupName?.let { put("gn", it) }
                    put("i", buildJsonArray { for (m in r.items) add(json(m)) })
                })
            }
            val f = file(context, type, profile)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(f)
        }
    }

    private fun json(m: MediaItem) = buildJsonObject {
        put("k", m.key); if (m.type != MediaType.Movie) put("ty", m.type.name); put("t", m.title)
        m.year?.let { put("y", it) }; m.poster?.let { put("p", it) }; m.backdrop?.let { put("b", it) }
        m.overview?.let { put("o", it.take(240)) }; m.tmdbId?.let { put("tm", it) }; m.imdbId?.let { put("im", it) }
        m.rating?.let { put("r", it) }; put("or", m.origin); m.subtitle?.let { put("s", it) }
        m.progress?.let { put("pr", it.toDouble()) }; m.nextSeason?.let { put("ns", it) }; m.nextEpisode?.let { put("ne", it) }
        m.durationMs?.let { put("d", it) }; if (m.owned) put("ow", true); m.ownedFrom?.let { put("of", it) }
        m.addonType?.let { put("at", it) }
    }

    private fun item(j: JsonObject, page: MediaType): MediaItem? {
        val key = j.s("k") ?: return null
        return MediaItem(
        key = key,
        // (Films are saved without a type; anything else says which.)
        type = j.s("ty")?.let { runCatching { MediaType.valueOf(it) }.getOrNull() } ?: if (j.containsKey("ty")) page else MediaType.Movie,
        title = j.s("t").orEmpty(), year = j.i("y"), poster = j.s("p"), backdrop = j.s("b"), overview = j.s("o"),
        tmdbId = j.i("tm"), imdbId = j.s("im"), rating = (j["r"] as? JsonPrimitive)?.doubleOrNull, origin = j.s("or").orEmpty(),
        subtitle = j.s("s"), progress = (j["pr"] as? JsonPrimitive)?.doubleOrNull?.toFloat(), nextSeason = j.i("ns"), nextEpisode = j.i("ne"),
        durationMs = (j["d"] as? JsonPrimitive)?.longOrNull, owned = (j["ow"] as? JsonPrimitive)?.contentOrNull == "true",
        ownedFrom = j.s("of"), addonType = j.s("at"),
        )
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
}
