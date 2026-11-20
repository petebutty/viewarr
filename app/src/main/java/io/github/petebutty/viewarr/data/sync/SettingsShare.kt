package io.github.petebutty.viewarr.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.github.petebutty.viewarr.BuildConfig
import io.github.petebutty.viewarr.data.report.ReportSender
import io.github.petebutty.viewarr.data.settings.appearanceStore
import io.github.petebutty.viewarr.data.settings.playbackStore
import io.github.petebutty.viewarr.data.settings.remoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Settings codes (Settings → Troubleshooting → Share my settings / Use someone's settings): this TV's look, playback
 * and remote settings as a small JSON file on the same free host reports use (litterbox, 72 hours; catbox if that's
 * down), and a short code ("VS-ab12cd") to load them on another TV. Nothing secret is in it, so it isn't locked.
 *
 * Shared: the "appearance", "playback" and "remote" stores, in the shape SettingsSync's copies use
 * ({"key": {"t": type, "v": value}}). Never shared, and never overwritten when a code is used:
 * - the whole "row_layout" store (home rows and libraries name your own servers and addons);
 * - sign-ins, Live TV sources and sessions (other stores, never read here);
 * - guide data: favourites, channel numbers, time offsets (GuideRepository's own prefs, never read here);
 * - the box-specific keys SettingsSync.LOCAL_ONLY keeps on each TV (passthrough, downmix, mpv_decoding, frame_rate,
 *   picture_size, deinterlace, buffer, max_res, audio_delay; the remote's buttons and key codes; opt_zap_delay);
 * - remote: last_channel and recent_channels (channel ids on your own Dispatcharr), sports_follow (followed teams,
 *   with logo addresses), live_now_picks and live_now_picks_v1 (your Live now list and its one-off marker);
 * - any value with "http", "://" or an IP address in it, or that the report cleaner would change (a token, an
 *   address, an email); in a set, just those entries. menu_order's "svc:<service>" entries are streaming services'
 *   names (Netflix…), not addon ids, so they're kept.
 */
object SettingsShare {
    private const val LITTER = "https://litterbox.catbox.moe/resources/internals/api.php"
    private const val CATBOX = "https://catbox.moe/user/api.php"
    private const val UNDO_FILE = "settings_code_undo.json"

    private val SHARED = listOf("appearance", "playback", "remote")

    private val NOT_SHARED = mapOf(
        "remote" to setOf("last_channel", "recent_channels", "sports_follow", "live_now_picks", "live_now_picks_v1"),
    )

    private val IP = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b")

    private fun storeOf(context: Context, name: String): DataStore<Preferences> = when (name) {
        "appearance" -> context.appearanceStore
        "playback" -> context.playbackStore
        else -> context.remoteStore
    }

    private fun skipped(name: String) = SettingsSync.LOCAL_ONLY[name].orEmpty() + NOT_SHARED[name].orEmpty()

    private fun looksLikeServer(s: String) = s.contains("http", ignoreCase = true) || "://" in s || IP.containsMatchIn(s)

    /** Safe to hand on: no address, and nothing the report cleaner would take out. */
    private fun safe(s: String) = !looksLikeServer(s) && ReportSender.clean(s) == s

    /** [v] as it may be shared (a set with the unsafe entries out), or null when it stays on this TV. */
    private fun shareable(v: Any): Any? = when (v) {
        is String -> v.takeIf(::safe)
        is Set<*> -> v.filterIsInstance<String>().filter(::safe).toSet()
        else -> v
    }

    /** This TV's shareable settings: {"appearance": {...}, "playback": {...}, "remote": {...}}. */
    suspend fun prefs(context: Context): JsonObject = buildJsonObject {
        SHARED.forEach { name ->
            val skip = skipped(name)
            val p = storeOf(context, name).data.first()
            put(name, JsonObject(p.asMap().entries.filter { it.key.name !in skip }.sortedBy { it.key.name }.mapNotNull { (k, v) ->
                shareable(v)?.let { SettingsSync.encodeValue(it) }?.let { k.name to it }
            }.toMap()))
        }
    }

    /** Uploads this TV's settings; the code ("VS-…") or an error. */
    suspend fun share(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("v", 1)
                put("app", BuildConfig.VERSION_NAME)
                put("made", System.currentTimeMillis() / 1000)
                put("prefs", prefs(context))
            }
            val bytes = body.toString().toByteArray()
            val url = ReportSender.upload(LITTER, mapOf("reqtype" to "fileupload", "time" to "72h"), bytes, "settings.txt")
                ?: ReportSender.upload(CATBOX, mapOf("reqtype" to "fileupload"), bytes, "settings.txt")
                ?: error("Couldn't upload your settings (no internet, or the file host is down).")
            "VS-" + url.substringAfterLast('/').substringBefore('.')
        }
    }

    /** "VS-ab12cd", "vs ab12cd" or "ab12cd" → "ab12cd" (null if there's nothing usable). */
    fun idOf(code: String): String? =
        code.trim().replace(Regex("(?i)^vs[\\s-]*"), "").filter { it.isLetterOrDigit() }.lowercase().takeIf { it.length in 4..16 }

    /** The settings behind [code]: checked (v 1, with prefs), not applied yet. */
    suspend fun fetch(code: String): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val id = idOf(code) ?: error("That doesn't look like a settings code.")
            val text = listOf("https://litter.catbox.moe/$id.txt", "https://files.catbox.moe/$id.txt").firstNotNullOfOrNull { get(it) }
                ?: error("No settings found for that code. Codes last 3 days.")
            val body = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: error("That code isn't a Viewarr settings code.")
            if ((body["v"] as? JsonPrimitive)?.intOrNull != 1) error("Those settings are from a different Viewarr version. Update both TVs and try again.")
            body["prefs"] as? JsonObject ?: error("That code isn't a Viewarr settings code.")
            body
        }
    }

    /** Puts [body]'s settings on this TV, keeping a copy of what was here for [undo]. */
    suspend fun applyCode(context: Context, body: JsonObject) = withContext(Dispatchers.IO) {
        val incoming = body["prefs"] as? JsonObject ?: return@withContext
        undoFile(context).writeText(prefs(context).toString())
        put(context, incoming)
    }

    fun hasUndo(context: Context) = undoFile(context).exists()

    /** Puts back the settings this TV had before the last code was used. */
    suspend fun undo(context: Context): Boolean = withContext(Dispatchers.IO) {
        val f = undoFile(context)
        val saved = runCatching { Json.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull() ?: return@withContext false
        put(context, saved)
        f.delete()
        true
    }

    /**
     * Replaces this TV's shareable settings with [all]'s. What isn't shared stays exactly as it is: the box-specific
     * and personal keys above, and any value of this TV's own that wouldn't be shared (it names a server).
     */
    private suspend fun put(context: Context, all: JsonObject) {
        SHARED.forEach { name ->
            val values = all[name] as? JsonObject ?: return@forEach
            val skip = skipped(name)
            storeOf(context, name).edit { p ->
                p.asMap().filter { (k, v) -> k.name !in skip && shareable(v) != null }.keys.forEach { p.remove(it) }
                values.forEach { (key, v) -> if (key !in skip && !looksLikeServer(v.toString())) SettingsSync.setValue(p, key, v) }
            }
        }
    }

    private fun undoFile(context: Context) = File(context.filesDir, UNDO_FILE)

    /** A small text file's contents, or null (not there, or too big to be settings). */
    private fun get(url: String): String? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "Viewarr-report/1")
        }
        if (c.responseCode !in 200..299) return@runCatching null
        c.inputStream.use { s ->
            val bytes = s.readNBytesCompat(512 * 1024 + 1)
            if (bytes.size > 512 * 1024) null else String(bytes)
        }
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
