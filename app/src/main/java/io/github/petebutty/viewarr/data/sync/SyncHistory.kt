package io.github.petebutty.viewarr.data.sync

import android.util.Base64
import android.util.Log
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.long
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.session.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One earlier version of the saved setup, as listed in "Restore from Dispatcharr". */
data class SyncVersion(
    val savedAt: Long,
    val device: String,
    val deviceId: String,
    /** Set for versions made on purpose (e.g. "Before restore on Living room TV"); null for routine ones. */
    val name: String?,
    internal val raw: JsonObject,
)

/**
 * Earlier versions of the "Save settings to Dispatcharr" copy, kept in the same user's app-data under
 * [HISTORY_KEY] so a bad change (or another TV's) can be undone:
 *
 *     {"v":1,"items":[{"savedAt","device","deviceId","name"?,"archivedAt","copyGz"}, ...]}   newest first
 *
 * `copyGz` is the whole earlier value, gzipped and base64'd: Dispatcharr allows 128 KiB per key and a copy is
 * around 30 KB as plain JSON (about 9 KB packed), so plain copies would only fit three or four. Sign-ins inside it
 * stay encrypted exactly as they were in the main copy. A plain `copy` object is read too.
 *
 * Kept: the 5 newest, plus the newest from each of the last 7 days that isn't among them (12 at most), trimmed
 * oldest day-entries first if it would go over [MAX_BYTES].
 */
@Singleton
class SyncHistory @Inject constructor(private val http: HttpJson, private val json: Json) {

    private fun url(s: Session) = s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/$HISTORY_KEY/"
    private fun headers(s: Session) = mapOf("X-API-Key" to s.apiKey)

    /** The versions on the server, newest first. Empty when there's none yet; a failure when it can't be read. */
    suspend fun list(s: Session): Result<List<SyncVersion>> = runCatching {
        val r = http.get(url(s), headers(s))
        when {
            r.code in 200..299 -> parse(r.body as? JsonObject)
            r.code == 404 -> emptyList()
            else -> error("Dispatcharr answered ${r.code}.")
        }
    }

    /** The saved copy inside [v] (null if it can't be unpacked). */
    fun copyOf(v: SyncVersion): JsonObject? =
        (v.raw["copy"] as? JsonObject) ?: v.raw.str("copyGz")?.let { gz ->
            runCatching { json.parseToJsonElement(unpack(gz)) as? JsonObject }.onFailure { Log.w(TAG, "version unreadable", it) }.getOrNull()
        }

    /**
     * Adds [copies] (whole saved values, each with an optional name) to the history and writes it back. Copies
     * already in it (same savedAt and device id) are skipped. True when written, or nothing needed writing; false
     * when the history couldn't be read or saved (the caller logs it and carries on: this never blocks a save).
     */
    suspend fun add(s: Session, copies: List<Pair<JsonObject, String?>>): Boolean {
        if (copies.isEmpty()) return true
        // Can't read what's there: leave it alone rather than replace it with a fresh, shorter one.
        val current = list(s).getOrElse { Log.w(TAG, "history not read: ${it.message}"); return false }
        val now = System.currentTimeMillis()
        val fresh = copies.mapNotNull { (copy, name) ->
            val savedAt = copy.long("savedAt") ?: now
            val id = copy.str("deviceId").orEmpty()
            if (current.any { it.savedAt == savedAt && it.deviceId == id }) return@mapNotNull null
            val raw = buildJsonObject {
                put("savedAt", savedAt)
                put("device", copy.str("device") ?: "another TV")
                put("deviceId", id)
                name?.let { put("name", it) }
                put("archivedAt", now)
                put("copyGz", pack(copy.toString()))
            }
            SyncVersion(savedAt, raw.str("device").orEmpty(), id, name, raw)
        }.distinctBy { it.savedAt to it.deviceId }
        if (fresh.isEmpty()) return true
        val kept = keep(fresh + current, ZoneId.systemDefault()) { encode(it).toString().length }
        val r = runCatching { http.put(url(s), encode(kept), headers(s)) }.onFailure { Log.w(TAG, "history not saved", it) }.getOrNull() ?: return false
        if (r.code !in 200..299) Log.w(TAG, "history save answered ${r.code}: ${r.body.str("code") ?: ""}")
        return r.code in 200..299
    }

    private fun parse(body: JsonObject?): List<SyncVersion> =
        (body?.get("items") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o["copy"] !is JsonObject && o.str("copyGz") == null) return@mapNotNull null
            SyncVersion(o.long("savedAt") ?: 0, o.str("device") ?: "another TV", o.str("deviceId").orEmpty(), o.str("name"), o)
        }.sortedByDescending { it.savedAt }

    private fun encode(items: List<SyncVersion>) = buildJsonObject {
        put("v", 1)
        put("items", JsonArray(items.map { it.raw }))
    }

    private fun pack(s: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(s.toByteArray()) }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun unpack(s: String): String =
        GZIPInputStream(Base64.decode(s, Base64.NO_WRAP).inputStream()).use { String(it.readBytes()) }

    internal companion object {
        private const val TAG = "ViewarrSync"
        // (The Viewarr launcher's history apart from Viewarr's, as its settings are: SettingsSync.)
        val HISTORY_KEY = if (io.github.petebutty.viewarr.BuildConfig.LAUNCHER) "io.github.petebutty.viewarr.launcher.history" else "io.github.petebutty.viewarr.history"
        const val RECENT = 5
        const val DAYS = 7
        const val MAX_ITEMS = 12
        /** Under Dispatcharr's 128 KiB a key, with room to spare. */
        const val MAX_BYTES = 120_000

        /**
         * The keep rules: the [RECENT] newest, then the newest of each of the last [DAYS] days not already kept
         * ([MAX_ITEMS] at most). Too big ([size] over [MAX_BYTES]): the oldest day-entries go first, then the oldest
         * of the rest (always leaving the newest).
         */
        fun keep(all: List<SyncVersion>, zone: ZoneId, size: (List<SyncVersion>) -> Int): List<SyncVersion> {
            val sorted = all.sortedByDescending { it.savedAt }
            val recent = sorted.take(RECENT)
            val today = LocalDate.now(zone)
            val days = (0 until DAYS).map { today.minusDays(it.toLong()) }.toSet()
            val daily = sorted.drop(RECENT)
                .filter { Instant.ofEpochMilli(it.savedAt).atZone(zone).toLocalDate() in days }
                .distinctBy { Instant.ofEpochMilli(it.savedAt).atZone(zone).toLocalDate() }
                .toMutableList()
            val kept = (recent + daily).sortedByDescending { it.savedAt }.take(MAX_ITEMS).toMutableList()
            daily.retainAll(kept.toSet())
            while (kept.size > 1 && size(kept) > MAX_BYTES) {
                val drop = daily.lastOrNull() ?: kept.last()
                daily.remove(drop)
                kept.remove(drop)
            }
            return kept
        }
    }
}
