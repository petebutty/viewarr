package io.github.petebutty.viewarr.data.remote.services

import io.github.petebutty.viewarr.data.accounts.SeerrAuth
import io.github.petebutty.viewarr.data.media.MediaType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seerr (Overseerr / Jellyseerr): request a film or show so the server's Radarr / Sonarr fetch it, and see whether it's
 * already been asked for. Authenticated with the Seerr API key (Settings → General in Seerr).
 */
@Singleton
class SeerrService @Inject constructor(private val http: HttpJson) {

    /** Where a title stands on Seerr. */
    enum class Status(val label: String) {
        None("Request on Seerr"),
        Pending("Requested · waiting for approval"),
        Processing("Requested · downloading"),
        Partial("Partly available · request the rest"),
        Available("Already available"),
    }

    private fun h(a: SeerrAuth) = mapOf("X-Api-Key" to a.apiKey)

    /** The address as typed, then with Seerr's usual port: the first one that answers with this key wins. */
    /** Seerr / Overseerr / Jellyseerr on the home network (port 5055, confirmed by its public status endpoint). */
    suspend fun discover(): List<String> = lanScan(5055) { url ->
        val r = kotlinx.coroutines.withTimeoutOrNull(3_000) { runCatching { http.get("$url/api/v1/status") }.getOrNull() }
        url.takeIf { r?.ok == true && !r.body.str("version").isNullOrBlank() }
    }

    suspend fun connect(input: String, apiKey: String): Result<SeerrAuth> {
        val raw = input.trim().trimEnd('/')
        if (raw.isEmpty()) return Result.failure(IllegalStateException("Enter your Seerr address."))
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("Enter your Seerr API key."))
        val withScheme = if (raw.contains("://")) listOf(raw) else listOf("http://$raw", "https://$raw")
        val hostOnly = !raw.substringAfter("://").contains(':') && !raw.substringAfter("://").contains('/')
        val candidates = withScheme + if (hostOnly) withScheme.map { "$it:5055" } else emptyList()
        var refused = false
        for (url in candidates) {
            val r = runCatching { http.get("$url/api/v1/auth/me", mapOf("X-Api-Key" to apiKey.trim())) }.getOrNull() ?: continue
            when {
                r.code in 200..299 -> {
                    val who = r.body.str("displayName") ?: r.body.str("username") ?: r.body.str("email")
                    return Result.success(SeerrAuth(url, apiKey.trim(), who))
                }
                r.code == 401 || r.code == 403 -> refused = true
            }
        }
        return Result.failure(IllegalStateException(if (refused) "Seerr didn't accept that API key. Copy it from Seerr → Settings → General." else "Couldn't reach Seerr at that address. Check it (Seerr usually uses port 5055)."))
    }

    suspend fun status(a: SeerrAuth, type: MediaType, tmdbId: Int): Status {
        val path = if (type == MediaType.Movie) "movie" else "tv"
        val r = runCatching { http.get("${a.url}/api/v1/$path/$tmdbId", h(a)) }.getOrNull() ?: return Status.None
        if (r.code !in 200..299) return Status.None
        val info = r.body.child("mediaInfo") ?: return Status.None
        // Seerr media status: 1 unknown, 2 pending, 3 processing, 4 partly available, 5 available.
        return when (info.int("status")) {
            5 -> Status.Available
            4 -> Status.Partial
            3 -> Status.Processing
            2 -> Status.Pending
            else -> if ((info.child("requests") as? JsonArray)?.isNotEmpty() == true) Status.Pending else Status.None
        }
    }

    /** Asks Seerr for the title (shows: every season). Returns a message to show. */
    suspend fun request(a: SeerrAuth, type: MediaType, tmdbId: Int): Pair<Boolean, String> {
        val body = buildJsonObject {
            put("mediaType", if (type == MediaType.Movie) "movie" else "tv")
            put("mediaId", tmdbId)
            if (type == MediaType.Show) put("seasons", JsonPrimitive("all"))
        }
        val r = runCatching { http.post("${a.url}/api/v1/request", body, h(a)) }.getOrNull()
            ?: return false to "Couldn't reach Seerr."
        return when {
            r.code in 200..299 -> true to "Requested on Seerr."
            r.code == 409 -> true to "Already requested on Seerr."
            r.code == 403 -> false to "Seerr says this user can't make requests."
            else -> false to (r.body.str("message") ?: "Seerr couldn't make the request (${r.code}).")
        }
    }
}
