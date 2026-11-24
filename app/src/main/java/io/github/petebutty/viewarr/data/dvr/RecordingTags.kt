package io.github.petebutty.viewarr.data.dvr

import android.content.Context
import io.github.petebutty.viewarr.data.media.ProgramArt
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * Recordings tagged from TMDB (2026-11-21: "use TMDB to tag recordings and organise them"): each recording's
 * show or film there, its poster and backdrop, and an episode's own name, by the same close name match as the guide's
 * pictures. Kept on the box by recording id (looked up once); a recording with no match is a "Sport & other" one.
 */
object RecordingTags {
    data class Tag(val kind: String, val tmdbId: Int?, val name: String?, val poster: String?, val backdrop: String?, val episodeName: String?)

    private const val FILE = "recording_tags"
    val version = MutableStateFlow(0)
    @Volatile private var cache: MutableMap<Int, Tag>? = null

    private fun load(context: Context): MutableMap<Int, Tag> = cache ?: HashMap<Int, Tag>().also { m ->
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val id = k.toIntOrNull() ?: return@forEach
            runCatching {
                val o = JSONObject(v as String)
                m[id] = Tag(o.optString("k", "other"), o.optInt("id").takeIf { it > 0 }, o.optString("n").ifEmpty { null },
                    o.optString("p").ifEmpty { null }, o.optString("b").ifEmpty { null }, o.optString("e").ifEmpty { null })
            }
        }
        cache = m
    }

    fun of(context: Context, id: Int): Tag? = load(context)[id]

    /** The player's details for a recording (Info, or paused): its show's poster and backdrop, the episode and description. */
    fun art(context: Context, rec: DvrRecording): io.github.petebutty.viewarr.data.media.PlayerArt {
        val t = of(context, rec.id)
        val ep = listOfNotNull(
            if (rec.season != null && rec.episode != null) "S${rec.season} E${rec.episode}" else null,
            t?.episodeName ?: rec.subTitle?.takeIf { it.isNotBlank() && !it.equals(rec.title, ignoreCase = true) },
        ).joinToString(" · ").ifEmpty { null }
        return io.github.petebutty.viewarr.data.media.PlayerArt(
            backdrop = t?.backdrop, logo = null, poster = t?.poster, episodeLine = ep,
            facts = "Recorded " + io.github.petebutty.viewarr.ui.theme.Dates.dayTime(rec.start), overview = rec.description?.takeIf { it.isNotBlank() },
        )
    }

    /** Looks up every recording not tagged yet (once each; a miss is kept as "other"). */
    suspend fun tagAll(context: Context, art: ProgramArt, recs: List<DvrRecording>) {
        val m = load(context)
        var changed = false
        for (rec in recs) {
            if (m.containsKey(rec.id)) continue
            val t = runCatching { art.tmdbFor(rec.title) }.getOrNull()
            val ep = if (t?.type == "tv" && rec.season != null && rec.episode != null) runCatching { art.episodeName(t.id, rec.season, rec.episode) }.getOrNull() else null
            val tag = when (t?.type) {
                "tv" -> Tag("show", t.id, t.name, t.poster, t.backdrop, ep)
                "movie" -> Tag("film", t.id, t.name, t.poster, t.backdrop, null)
                else -> Tag("other", null, null, null, null, null)
            }
            m[rec.id] = tag
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(rec.id.toString(), JSONObject().apply {
                put("k", tag.kind); tag.tmdbId?.let { put("id", it) }; put("n", tag.name ?: ""); put("p", tag.poster ?: ""); put("b", tag.backdrop ?: ""); put("e", tag.episodeName ?: "")
            }.toString()).apply()
            changed = true
        }
        if (changed) version.value++
    }
}
