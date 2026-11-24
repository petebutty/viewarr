package io.github.petebutty.viewarr.data.media

import android.content.Context
import android.util.Log
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * "Just added" without the Viewarr plugin (2026-11-21: "all features should be available to people not using
 * the plugin"): the TV asks JustWatch itself, as the plugin does for the house (justadded.py), once a day, and keeps
 * the answer on the box in the plugin's own shape ({"v":1,"services":[{"name","movies","shows"}]}), so the rows are
 * made the same way. Light: one request per day looked back over the last week, a page of up to 100 each, once a
 * day; a failure waits for the next day.
 */
object JustAddedLocal {
    private const val TAG = "JustAdded"
    private const val DAYS = 7
    private const val PER_SERVICE = 20
    private val lock = Mutex()

    private val SERVICES = listOf(
        "Netflix" to listOf("nfx", "nfa"), "Prime Video" to listOf("amp", "pva"), "Disney+" to listOf("dnp"), "Apple TV+" to listOf("atp"),
        "HBO Max" to listOf("mxx"), "NOW" to listOf("ntv", "ntc"), "BBC iPlayer" to listOf("bbc"), "ITVX" to listOf("itv", "itp"),
        "Paramount+" to listOf("pmp", "ppp"), "Sky Go" to listOf("skg"), "UKTV Play" to listOf("ukt"), "My5" to listOf("myf"),
        "Discovery+" to listOf("dpe"), "Crunchyroll" to listOf("cru"), "Shudder" to listOf("shd"),
    )
    private val BY_SHORT = SERVICES.flatMap { (n, s) -> s.map { it to n } }.toMap()

    private const val QUERY = """query GetNewTitles(${'$'}country: Country!, ${'$'}date: Date!, ${'$'}language: Language!, ${'$'}filter: TitleFilter, ${'$'}first: Int!, ${'$'}after: String) {
  newTitles(country: ${'$'}country, date: ${'$'}date, filter: ${'$'}filter, first: ${'$'}first, pageType: NEW, after: ${'$'}after) {
    pageInfo { hasNextPage endCursor }
    edges {
      newOffer(platform: WEB) { package { shortName } }
      node {
        objectType
        ... on MovieOrShowOrSeason { content(country: ${'$'}country, language: ${'$'}language) { title originalReleaseYear posterUrl externalIds { imdbId tmdbId } } }
        ... on Season {
          content(country: ${'$'}country, language: ${'$'}language) { seasonNumber }
          show { content(country: ${'$'}country, language: ${'$'}language) { title originalReleaseYear posterUrl externalIds { imdbId tmdbId } } }
        }
      }
    }
  }
}"""

    private fun file(context: Context) = java.io.File(context.filesDir, "just_added.json")

    /** The lists kept on the box (null: none yet), fetching today's first if they're older than a day. */
    suspend fun lists(context: Context, http: HttpJson): JsonObject? = lock.withLock {
        val f = file(context)
        val kept = runCatching { Json.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull()
        val tried = context.getSharedPreferences("just_added", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (tried.getString("day", null) != today) {
            tried.edit().putString("day", today).apply() // (tried for today, whatever happens: no retries)
            runCatching { build(http) }.onSuccess { v -> f.writeText(v.toString()); return@withLock v }
                .onFailure { Log.w(TAG, "JustWatch didn't answer (${it.message}); trying again tomorrow") }
        }
        kept
    }

    private suspend fun build(http: HttpJson): JsonObject {
        val lists = SERVICES.associate { it.first to ArrayList<JsonObject>() }
        val seen = HashSet<String>()
        val packages = SERVICES.flatMap { it.second }
        for (back in 0 until DAYS) {
            val day = LocalDate.now().minusDays(back.toLong()).toString()
            var after: String? = null
            for (page in 0 until 2) {
                val body = buildJsonObject {
                    put("operationName", "GetNewTitles"); put("query", QUERY)
                    put("variables", buildJsonObject {
                        put("country", "GB"); put("language", "en"); put("date", day); put("first", 100)
                        after?.let { put("after", it) }
                        put("filter", buildJsonObject { put("packages", buildJsonArray { packages.forEach { add(JsonPrimitive(it)) } }) })
                    })
                }
                val r = http.post("https://apis.justwatch.com/graphql", body, mapOf("User-Agent" to "Mozilla/5.0 (Viewarr)"))
                if (!r.ok) error("HTTP ${r.code}")
                val nt = (r.body as? JsonObject)?.get("data")?.jsonObject?.get("newTitles")?.jsonObject ?: error("no data")
                for (e in nt["edges"]?.jsonArray.orEmpty()) {
                    val short = e.obj("newOffer")?.obj("package")?.str("shortName") ?: continue
                    val name = BY_SHORT[short] ?: continue
                    val it = item(e, day) ?: continue
                    val k = "$name|${it.str("t")}|${it.int("tmdb")}"
                    val list = lists.getValue(name)
                    if (k in seen || list.count { x -> x.str("t") == it.str("t") } >= PER_SERVICE) continue
                    seen += k; list += it
                }
                val info = nt.obj("pageInfo")
                if (info?.get("hasNextPage")?.let { (it as? JsonPrimitive)?.contentOrNull } != "true") break
                after = info.str("endCursor")
            }
            kotlinx.coroutines.delay(800) // gentle
        }
        return buildJsonObject {
            put("v", 1); put("at", System.currentTimeMillis() / 1000)
            put("services", buildJsonArray {
                lists.filterValues { it.isNotEmpty() }.forEach { (name, items) ->
                    add(buildJsonObject {
                        put("name", name)
                        put("movies", JsonArray(items.filter { it.str("t") == "m" }))
                        put("shows", JsonArray(items.filter { it.str("t") == "s" }))
                    })
                }
            })
        }
    }

    private fun item(e: JsonElement, day: String): JsonObject? {
        val n = e.obj("node") ?: return null
        val kind = n.str("objectType")
        val c = n.obj("content")
        val (base, season) = when (kind) {
            "SHOW_SEASON" -> n.obj("show")?.obj("content") to c?.int("seasonNumber")
            "MOVIE", "SHOW" -> c to null
            else -> return null
        }
        base ?: return null
        val ids = base.obj("externalIds")
        val tmdb = ids?.str("tmdbId")?.substringBefore(':')?.toIntOrNull() ?: return null
        val title = base.str("title") ?: return null
        return buildJsonObject {
            put("t", if (kind == "MOVIE") "m" else "s"); put("tmdb", tmdb); ids.str("imdbId")?.let { put("imdb", it) }
            put("title", title); base.int("originalReleaseYear")?.let { put("year", it) }; season?.let { put("season", it) }
            put("added", day)
            base.str("posterUrl")?.let { put("poster", "https://images.justwatch.com" + it.replace("{profile}", "s332").replace("{format}", "jpg")) }
        }
    }

    private fun JsonElement.obj(k: String) = (this as? JsonObject)?.get(k) as? JsonObject
    private fun JsonElement.str(k: String) = ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.int(k: String) = ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.intOrNull
}
