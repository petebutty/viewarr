package io.github.petebutty.viewarr.data.xtream

import android.util.Xml
import io.github.petebutty.viewarr.data.local.ProgramEntity
import io.github.petebutty.viewarr.data.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** What a provider says about the account when signing in. */
data class XtreamAccount(val username: String, val timezone: String?, val expires: Long?, val maxConnections: Int?)

class XtreamException(message: String) : IOException(message)

data class XtreamCategory(val id: String, val name: String)
data class XtreamChannel(
    val streamId: Int,
    val name: String,
    val number: Double?,
    val logo: String?,
    val epgId: String?,
    val categoryId: String?,
    val archiveDays: Int,
)
data class XtreamVod(
    val streamId: Int,
    val name: String,
    val poster: String?,
    val rating: Double?,
    val year: Int?,
    val tmdbId: Int?,
    val extension: String,
    val added: Long,
    val categoryId: String?,
)
data class XtreamSeries(
    val seriesId: Int,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val rating: Double?,
    val year: Int?,
    val tmdbId: Int?,
    val lastModified: Long,
    val categoryId: String?,
)
data class XtreamEpisode(val id: String, val season: Int, val episode: Int, val title: String, val extension: String)

/**
 * IPTV providers using the Xtream Codes API (player_api.php, xmltv.php and /live, /movie, /series, /timeshift
 * stream paths). Used when live TV is signed in to a provider rather than Dispatcharr.
 */
@Singleton
class XtreamClient @Inject constructor(private val client: OkHttpClient, private val json: Json) {

    /** Checks the login; the provider's own answer, not just "a server responded". */
    suspend fun signIn(server: String, username: String, password: String): XtreamAccount {
        val root = get(api(server, username, password, null)) as? JsonObject
            ?: throw XtreamException("That address didn't answer like an IPTV provider (Xtream Codes). Check it with your provider.")
        val user = root["user_info"] as? JsonObject
            ?: throw XtreamException("That address didn't answer like an IPTV provider (Xtream Codes). Check it with your provider.")
        if (user.str("auth") != "1") throw XtreamException("Your provider didn't accept that username and password. Use the ones from your provider's welcome email.")
        val status = user.str("status")
        val expires = user.str("exp_date")?.toLongOrNull()?.times(1000)
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw XtreamException("Your provider says this account is \"$status\"" +
                (expires?.let { " (it expired on ${java.text.DateFormat.getDateInstance().format(java.util.Date(it))})" } ?: "") + ".")
        }
        val tz = (root["server_info"] as? JsonObject)?.str("timezone")
        return XtreamAccount(user.str("username") ?: username, tz, expires, user.str("max_connections")?.toIntOrNull())
    }

    suspend fun liveCategories(s: Session) = categories(s, "get_live_categories")
    suspend fun vodCategories(s: Session) = categories(s, "get_vod_categories")
    suspend fun seriesCategories(s: Session) = categories(s, "get_series_categories")

    suspend fun liveStreams(s: Session): List<XtreamChannel> = list(s, "get_live_streams").mapNotNull { j ->
        val o = j as? JsonObject ?: return@mapNotNull null
        XtreamChannel(
            streamId = o.str("stream_id")?.toIntOrNull() ?: return@mapNotNull null,
            name = o.str("name").orEmpty(),
            number = o.str("num")?.toDoubleOrNull(),
            logo = o.str("stream_icon")?.takeIf { it.startsWith("http") },
            epgId = o.str("epg_channel_id")?.takeIf { it.isNotBlank() },
            categoryId = o.str("category_id"),
            archiveDays = if (o.str("tv_archive") == "1") o.str("tv_archive_duration")?.toIntOrNull() ?: 0 else 0,
        )
    }

    suspend fun vodStreams(s: Session): List<XtreamVod> = list(s, "get_vod_streams").mapNotNull { j ->
        val o = j as? JsonObject ?: return@mapNotNull null
        val name = o.str("name").orEmpty()
        XtreamVod(
            streamId = o.str("stream_id")?.toIntOrNull() ?: return@mapNotNull null,
            name = cleanTitle(name),
            poster = o.str("stream_icon")?.takeIf { it.startsWith("http") },
            rating = o.str("rating")?.toDoubleOrNull()?.takeIf { it > 0 },
            year = o.str("year")?.take(4)?.toIntOrNull() ?: yearIn(name),
            tmdbId = o.str("tmdb")?.toIntOrNull() ?: o.str("tmdb_id")?.toIntOrNull(),
            extension = o.str("container_extension") ?: "mp4",
            added = o.str("added")?.toLongOrNull()?.times(1000) ?: 0,
            categoryId = o.str("category_id"),
        )
    }

    suspend fun series(s: Session): List<XtreamSeries> = list(s, "get_series").mapNotNull { j ->
        val o = j as? JsonObject ?: return@mapNotNull null
        val name = o.str("name").orEmpty()
        XtreamSeries(
            seriesId = o.str("series_id")?.toIntOrNull() ?: return@mapNotNull null,
            name = cleanTitle(name),
            poster = o.str("cover")?.takeIf { it.startsWith("http") },
            backdrop = (o["backdrop_path"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.startsWith("http") },
            plot = o.str("plot")?.takeIf { it.isNotBlank() },
            rating = o.str("rating")?.toDoubleOrNull()?.takeIf { it > 0 },
            year = (o.str("releaseDate") ?: o.str("release_date"))?.take(4)?.toIntOrNull() ?: yearIn(name),
            tmdbId = o.str("tmdb")?.toIntOrNull() ?: o.str("tmdb_id")?.toIntOrNull(),
            lastModified = o.str("last_modified")?.toLongOrNull()?.times(1000) ?: 0,
            categoryId = o.str("category_id"),
        )
    }

    /** Episodes of one series, by season. */
    suspend fun episodes(s: Session, seriesId: Int): List<XtreamEpisode> {
        val root = get(api(s.serverUrl, s.username, s.xcPassword.orEmpty(), "get_series_info") + "&series_id=$seriesId") as? JsonObject ?: return emptyList()
        val eps = root["episodes"] as? JsonObject ?: return emptyList()
        return eps.entries.flatMap { (season, arr) ->
            (arr as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                XtreamEpisode(
                    id = o.str("id") ?: return@mapNotNull null,
                    season = o.str("season")?.toIntOrNull() ?: season.toIntOrNull() ?: 1,
                    episode = o.str("episode_num")?.toIntOrNull() ?: 0,
                    title = o.str("title").orEmpty(),
                    extension = o.str("container_extension") ?: "mp4",
                )
            }
        }
    }

    // ---- Stream addresses

    fun liveUrl(s: Session, streamId: Int) = "${base(s)}/live/${enc(s.username)}/${enc(s.xcPassword.orEmpty())}/$streamId.ts"
    fun movieUrl(s: Session, v: XtreamVod) = movieUrl(s, v.streamId, v.extension)
    fun movieUrl(s: Session, streamId: Int, ext: String) = "${base(s)}/movie/${enc(s.username)}/${enc(s.xcPassword.orEmpty())}/$streamId.$ext"
    fun episodeUrl(s: Session, e: XtreamEpisode) = "${base(s)}/series/${enc(s.username)}/${enc(s.xcPassword.orEmpty())}/${e.id}.${e.extension}"

    /** Catch-up: the start time is written in the provider's own time zone. */
    fun catchupUrl(s: Session, streamId: Int, startMs: Long, minutes: Long): String {
        val zone = runCatching { ZoneId.of(s.xcTimezone ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
        val start = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startMs), zone).format(TIMESHIFT)
        return "${base(s)}/timeshift/${enc(s.username)}/${enc(s.xcPassword.orEmpty())}/$minutes/$start/$streamId.ts"
    }

    // ---- Guide (XMLTV)

    /**
     * Streams the provider's XMLTV guide, keeping only programmes for [wanted] channel ids that overlap [from]..[to].
     * The file can be tens of MB, so it's read as it downloads and handed over in batches.
     */
    suspend fun guide(s: Session, wanted: Set<String>, from: Long, to: Long, onBatch: suspend (List<ProgramEntity>) -> Unit) = withContext(Dispatchers.IO) {
        val url = "${base(s)}/xmltv.php?username=${enc(s.username)}&password=${enc(s.xcPassword.orEmpty())}"
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw XtreamException("The provider's TV guide didn't load (error ${r.code}).")
            parseXmltv(r.body!!.byteStream(), wanted, from, to, onBatch)
        }
    }

    private suspend fun parseXmltv(stream: InputStream, wanted: Set<String>, from: Long, to: Long, onBatch: suspend (List<ProgramEntity>) -> Unit) {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(stream, null)
        val batch = ArrayList<ProgramEntity>(BATCH)
        var channel: String? = null
        var start = 0L
        var end = 0L
        var title: String? = null
        var sub: String? = null
        var desc: String? = null
        var icon: String? = null
        var season: Int? = null
        var episode: Int? = null
        var field: String? = null
        var keep = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "programme" -> {
                        channel = p.getAttributeValue(null, "channel")
                        start = parseXmltvTime(p.getAttributeValue(null, "start")) ?: 0
                        end = parseXmltvTime(p.getAttributeValue(null, "stop")) ?: 0
                        keep = channel != null && channel in wanted && end > from && start < to && end > start
                        title = null; sub = null; desc = null; season = null; episode = null; icon = null
                    }
                    "icon" -> if (keep && icon == null) icon = p.getAttributeValue(null, "src")?.takeIf { it.startsWith("http") }
                    "title", "sub-title", "desc" -> field = if (keep) p.name else null
                    "episode-num" -> field = if (keep && p.getAttributeValue(null, "system") == "xmltv_ns") "ep" else null
                }
                XmlPullParser.TEXT -> when (field) {
                    "title" -> title = (title.orEmpty() + p.text)
                    "sub-title" -> sub = (sub.orEmpty() + p.text)
                    "desc" -> desc = (desc.orEmpty() + p.text)
                    "ep" -> {
                        // xmltv_ns: "season.episode.part", zero-based.
                        val parts = p.text.split('.')
                        season = parts.getOrNull(0)?.trim()?.substringBefore('/')?.toIntOrNull()?.plus(1)
                        episode = parts.getOrNull(1)?.trim()?.substringBefore('/')?.toIntOrNull()?.plus(1)
                    }
                }
                XmlPullParser.END_TAG -> {
                    field = null
                    if (p.name == "programme" && keep) {
                        batch += ProgramEntity(
                            tvgId = channel!!, title = title?.trim().orEmpty(), subTitle = sub?.trim()?.takeIf { it.isNotEmpty() },
                            description = desc?.trim()?.takeIf { it.isNotEmpty() }, startTime = start, endTime = end,
                            season = season, episode = episode, isNew = false, isLive = false, isPremiere = false, image = icon,
                        )
                        if (batch.size >= BATCH) { onBatch(batch.toList()); batch.clear() }
                    }
                }
            }
            ev = p.next()
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
    }

    // ---- plumbing

    private suspend fun categories(s: Session, action: String): List<XtreamCategory> = list(s, action).mapNotNull { j ->
        val o = j as? JsonObject ?: return@mapNotNull null
        XtreamCategory(o.str("category_id") ?: return@mapNotNull null, o.str("category_name").orEmpty())
    }

    private suspend fun list(s: Session, action: String): List<JsonElement> =
        (get(api(s.serverUrl, s.username, s.xcPassword.orEmpty(), action)) as? JsonArray).orEmpty()

    private suspend fun get(url: String): JsonElement? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw XtreamException("Your provider didn't accept that username and password.")
            if (!r.isSuccessful) throw XtreamException("The provider answered with an error (${r.code}).")
            val text = r.body?.string().orEmpty()
            runCatching { json.parseToJsonElement(text) }.getOrNull()
        }
    }

    private fun api(server: String, user: String, pass: String, action: String?) =
        "${server.trimEnd('/')}/player_api.php?username=${enc(user)}&password=${enc(pass)}" + (action?.let { "&action=$it" } ?: "")

    private fun base(s: Session) = s.serverUrl.trimEnd('/')

    private companion object {
        const val BATCH = 1000
        const val UA = "Viewarr"
        val TIMESHIFT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")
        val XMLTV: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
        fun parseXmltvTime(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            val t = s.trim()
            return runCatching { OffsetDateTime.parse(if (t.length == 14) "$t +0000" else t, XMLTV).toInstant().toEpochMilli() }.getOrNull()
        }
        /** "Name (2021)" or "EN - Name" style provider titles, tidied for display. */
        fun cleanTitle(name: String): String = name
            .replace(Regex("^[A-Z]{2,3}\\s*[|:-]\\s*"), "")
            .replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
            .trim()
        fun yearIn(name: String): Int? = Regex("\\((\\d{4})\\)").find(name)?.groupValues?.get(1)?.toIntOrNull()
    }
}
