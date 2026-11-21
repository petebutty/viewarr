package io.github.petebutty.viewarr.data.news

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** How a feed's ticker looks: the label's colours and the strip's. */
enum class TickerStyle(val label: String) { Glass("Viewarr glass"), SkySports("Sky Sports yellow"), Bbc("BBC red"), Espn("ESPN red and black"), SkyNews("Sky News red") }

/** A news feed for the ticker: one of Viewarr's or one you added (an RSS / Atom address). */
data class NewsFeed(val id: String, val name: String, val url: String, val builtIn: Boolean, val defaultStyle: TickerStyle)

data class Headline(val feed: NewsFeed, val text: String)

/**
 * The news ticker's feeds (Settings → Appearance → News ticker): a few built in (BBC, Sky, ESPN, the Guardian) and any
 * RSS / Atom feed you add. Headlines are fetched straight from each feed (nothing else is sent) and kept 10 minutes.
 */
@Singleton
class NewsFeeds @Inject constructor(@ApplicationContext context: Context, private val client: OkHttpClient) {
    private val prefs = context.getSharedPreferences("news_feeds", Context.MODE_PRIVATE)

    val builtIn = listOf(
        // Your teams' live scores and goals (the Viewarr plugin on Dispatcharr): only while one is playing.
        NewsFeed(LIVE_SCORES, "Live scores", "viewarr://scores", true, TickerStyle.SkySports),
        NewsFeed("bbc-news", "BBC News", "https://feeds.bbci.co.uk/news/rss.xml", true, TickerStyle.Bbc),
        NewsFeed("bbc-sport", "BBC Sport", "https://feeds.bbci.co.uk/sport/rss.xml", true, TickerStyle.Bbc),
        NewsFeed("bbc-scot-fb", "BBC Scottish Football", "https://feeds.bbci.co.uk/sport/football/scottish/rss.xml", true, TickerStyle.Bbc),
        NewsFeed("sky-news", "Sky News", "https://feeds.skynews.com/feeds/rss/home.xml", true, TickerStyle.SkyNews),
        NewsFeed("sky-sports", "Sky Sports News", "https://www.skysports.com/rss/12040", true, TickerStyle.SkySports),
        NewsFeed("espn", "ESPN", "https://www.espn.com/espn/rss/news", true, TickerStyle.Espn),
        NewsFeed("espn-fc", "ESPN FC", "https://www.espn.co.uk/espn/rss/football/news", true, TickerStyle.Espn),
        NewsFeed("guardian-fb", "Guardian Football", "https://www.theguardian.com/football/rss", true, TickerStyle.Glass),
    )

    /** Bumped on any change (feeds added / removed), for settings to redraw. */
    val version = mutableStateOf(0)

    /** Feeds you added. */
    fun custom(): List<NewsFeed> = runCatching {
        val a = JSONArray(prefs.getString(CUSTOM, "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { NewsFeed(it.getString("id"), it.getString("name"), it.getString("url"), false, TickerStyle.Glass) } }
    }.getOrDefault(emptyList())

    fun all() = builtIn + custom()

    private fun saveCustom(list: List<NewsFeed>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
        prefs.edit().putString(CUSTOM, a.toString()).apply(); version.value++
    }

    /** Adds your own feed after checking it gives headlines; an error message, or null when added. */
    suspend fun add(name: String, rawUrl: String): String? {
        val url = rawUrl.trim().let { if ("://" in it) it else "https://$it" }
        val got = fetch(url)
        if (got.isEmpty()) return "No headlines found at that address (it needs to be an RSS or Atom feed)."
        val id = "custom-" + url.hashCode().toUInt().toString(16)
        saveCustom(custom().filter { it.url != url } + NewsFeed(id, name.trim().ifEmpty { got.first().take(0).ifEmpty { url.substringAfter("://").substringBefore('/') } }, url, false, TickerStyle.Glass))
        return null
    }

    fun remove(id: String) = saveCustom(custom().filter { it.id != id })

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()
    /** Each feed fetch gives up after 10 s (a stuck feed mustn't hold the others). */
    private val quick by lazy { client.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build() }

    /** A feed's headlines (newest first), from the last 10 minutes' copy if there is one. */
    suspend fun headlines(f: NewsFeed): List<String> {
        // Not a feed on the web: the live scores the plugin shares (data/sync/ServerSports), fresh each time; without
        // the plugin, the ones this TV checks itself (data/sports/TvScoreAlerts).
        if (f.id == LIVE_SCORES) return io.github.petebutty.viewarr.data.sync.ServerSports.scoreLines
            .ifEmpty { io.github.petebutty.viewarr.data.sports.TvScoreAlerts.scoreLines }
        cache[f.url]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second }
        val list = fetch(f.url)
        if (list.isNotEmpty()) cache[f.url] = System.currentTimeMillis() to list
        return list.ifEmpty { cache[f.url]?.second.orEmpty() }
    }

    /** RSS <item><title> or Atom <entry><title>, tidied (CDATA, entities), up to 25. */
    private suspend fun fetch(url: String): List<String> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(10_000) {
            runCatching {
                quick.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Viewarr)").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use emptyList()
                    // (At most 2 MB read: a custom address pointing at something huge mustn't fill a small box's memory.)
                    val xml = r.body?.source()?.let { src -> src.request(2_000_000); src.buffer.readUtf8(minOf(src.buffer.size, 2_000_000)) }.orEmpty()
                    Regex("<(item|entry)\\b[\\s\\S]*?</(item|entry)>").findAll(xml).mapNotNull { m ->
                        Regex("<title[^>]*>([\\s\\S]*?)</title>").find(m.value)?.groupValues?.get(1)?.let(::tidy)
                    }.filter { it.isNotBlank() }.distinct().take(25).toList()
                }
            }.getOrDefault(emptyList())
        }.orEmpty()
    }

    private fun tidy(s: String) = s.replace("<![CDATA[", "").replace("]]>", "")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { it.groupValues[1].toIntOrNull(16)?.let { c -> runCatching { String(Character.toChars(c)) }.getOrNull() } ?: "" }
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { c -> runCatching { String(Character.toChars(c)) }.getOrNull() } ?: "" }
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val CUSTOM = "custom"
        /** The built-in Live scores feed (goals and scores from the plugin while your teams play). */
        const val LIVE_SCORES = "live-scores"
    }
}
