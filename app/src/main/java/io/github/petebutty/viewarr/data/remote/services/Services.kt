package io.github.petebutty.viewarr.data.remote.services

import io.github.petebutty.viewarr.data.accounts.AddonInfo
import io.github.petebutty.viewarr.data.accounts.MediaServerAuth
import io.github.petebutty.viewarr.data.accounts.PlexAuth
import io.github.petebutty.viewarr.data.accounts.TraktAuth
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

@Singleton
class TmdbService @Inject constructor(private val http: HttpJson) {
    /** v3 keys go in the query string; v4 read-access tokens (JWTs) go in a Bearer header. */
    fun headers(key: String) = if (key.contains('.')) mapOf("Authorization" to "Bearer $key") else emptyMap()

    fun url(path: String, key: String, params: Map<String, String> = emptyMap()): String {
        val all = if (key.contains('.')) params else params + ("api_key" to key)
        val query = all.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return "https://api.themoviedb.org/3$path" + if (query.isEmpty()) "" else "?$query"
    }

    suspend fun validate(key: String): Boolean = runCatching { http.get(url("/configuration", key), headers(key)).ok }.getOrDefault(false)
}

data class TraktDeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresAt: Long, val intervalSec: Int)

sealed interface TraktPoll {
    data object Pending : TraktPoll
    data object SlowDown : TraktPoll
    data class Done(val auth: TraktAuth) : TraktPoll
    data class Failed(val message: String) : TraktPoll
}

@Singleton
class TraktService @Inject constructor(private val http: HttpJson) {
    private val base = "https://api.trakt.tv"

    fun headers(token: String? = null) = buildMap {
        put("trakt-api-version", "2")
        put("trakt-api-key", CLIENT_ID)
        put("Content-Type", "application/json")
        token?.let { put("Authorization", "Bearer $it") }
    }

    suspend fun deviceCode(): TraktDeviceCode? = runCatching {
        val r = http.post("$base/oauth/device/code", buildJsonObject { put("client_id", CLIENT_ID) }, headers())
        if (!r.ok) return null
        TraktDeviceCode(
            deviceCode = r.body.str("device_code")!!,
            userCode = r.body.str("user_code")!!,
            verificationUrl = r.body.str("verification_url") ?: "https://trakt.tv/activate",
            expiresAt = System.currentTimeMillis() + (r.body.int("expires_in") ?: 600) * 1000L,
            intervalSec = r.body.int("interval") ?: 5,
        )
    }.getOrNull()

    suspend fun poll(deviceCode: String): TraktPoll = runCatching {
        val r = http.post("$base/oauth/device/token", buildJsonObject {
            put("code", deviceCode)
            put("client_id", CLIENT_ID)
        }, headers())
        when (r.code) {
            200 -> {
                val access = r.body.str("access_token")!!
                val created = r.body.long("created_at") ?: (System.currentTimeMillis() / 1000)
                val expires = r.body.long("expires_in") ?: 86_400
                TraktPoll.Done(TraktAuth(access, r.body.str("refresh_token")!!, (created + expires) * 1000, username(access) ?: "Trakt user"))
            }
            400 -> TraktPoll.Pending
            429 -> TraktPoll.SlowDown
            404 -> TraktPoll.Failed("That code wasn't recognised. Try again.")
            409 -> TraktPoll.Failed("That code was already used. Try again.")
            410 -> TraktPoll.Failed("The code expired. Try again.")
            418 -> TraktPoll.Failed("Access was denied on Trakt.")
            else -> TraktPoll.Failed("Trakt error ${r.code}.")
        }
    }.getOrElse { TraktPoll.Pending }

    private suspend fun username(access: String): String? =
        runCatching { http.get("$base/users/settings", headers(access)).body.child("user").str("username") }.getOrNull()

    companion object {
        /** Trakt app client id, from secrets.properties at build time (device login needs no secret). Blank = Trakt not offered. */
        val CLIENT_ID: String = io.github.petebutty.viewarr.BuildConfig.TRAKT_CLIENT_ID
    }
}

data class PlexPin(val id: Long, val code: String)

data class PlexServerChoice(val name: String, val machineId: String, val token: String, val url: String)

@Singleton
class PlexService @Inject constructor(private val http: HttpJson) {
    fun headers(deviceId: String, token: String? = null) = buildMap {
        put("X-Plex-Product", "Viewarr")
        put("X-Plex-Version", "0.4")
        put("X-Plex-Client-Identifier", deviceId)
        put("X-Plex-Device", "Android TV")
        put("X-Plex-Platform", "Android")
        token?.let { put("X-Plex-Token", it) }
    }

    /** strong = true gives a long code for the app.plex.tv sign-in page ([authUrl]); false a 4-letter code for plex.tv/link. */
    suspend fun createPin(deviceId: String, strong: Boolean = false): PlexPin? = runCatching {
        val r = http.post("https://plex.tv/api/v2/pins?strong=$strong", null, headers(deviceId))
        PlexPin(r.body.long("id")!!, r.body.str("code")!!)
    }.getOrNull()

    /** Plex's own sign-in page for a strong PIN: sign in there and the PIN is approved, no code to type. */
    fun authUrl(deviceId: String, code: String) = "https://app.plex.tv/auth#?clientID=${enc(deviceId)}&code=${enc(code)}" +
        "&context%5Bdevice%5D%5Bproduct%5D=Viewarr&context%5Bdevice%5D%5Bplatform%5D=Android%20TV"

    /** Returns the account token once the code has been entered at plex.tv/link. */
    suspend fun checkPin(deviceId: String, pinId: Long): String? = runCatching {
        http.get("https://plex.tv/api/v2/pins/$pinId", headers(deviceId)).body.str("authToken")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Servers on the account, each with the first connection that actually answers (local first). */
    suspend fun servers(deviceId: String, accountToken: String): List<PlexServerChoice> {
        val r = runCatching { http.get("https://plex.tv/api/v2/resources?includeHttps=1", headers(deviceId, accountToken)) }.getOrNull() ?: return emptyList()
        return r.body.asArray()
            .filter { it.str("provides")?.contains("server") == true }
            .mapNotNull { res ->
                val token = res.str("accessToken") ?: accountToken
                val connections = res.array("connections")
                val candidates = buildList {
                    connections.filter { it.str("local") == "true" && it.str("relay") != "true" }.forEach { c ->
                        val address = c.str("address")
                        val port = c.str("port")
                        if (address != null && port != null) add("http://$address:$port")
                        c.str("uri")?.let(::add)
                    }
                    connections.filter { it.str("local") != "true" && it.str("relay") != "true" }.mapNotNull { it.str("uri") }.forEach(::add)
                }.distinct()
                val url = candidates.firstOrNull { reachable(it, deviceId, token) } ?: return@mapNotNull null
                PlexServerChoice(res.str("name") ?: "Plex", res.str("clientIdentifier") ?: "", token, url)
            }
    }

    private suspend fun reachable(url: String, deviceId: String, token: String) = withTimeoutOrNull(4_000) {
        runCatching { http.get("$url/identity", headers(deviceId, token)).ok }.getOrDefault(false)
    } == true

    fun toAuth(choice: PlexServerChoice) = PlexAuth(choice.token, choice.name, choice.url, choice.machineId)
}

data class ServerInfo(val url: String, val name: String, val product: String)

data class QuickConnectCode(val secret: String, val code: String)

/** Jellyfin and Emby share the API; they differ in the auth header name and Quick Connect (Jellyfin only). */
@Singleton
class MediaServerService @Inject constructor(private val http: HttpJson) {

    fun headers(emby: Boolean, deviceId: String, token: String? = null): Map<String, String> {
        val value = "MediaBrowser Client=\"Viewarr\", Device=\"Android TV\", DeviceId=\"$deviceId\", Version=\"0.4.0\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")
        return mapOf((if (emby) "X-Emby-Authorization" else "Authorization") to value, "Content-Type" to "application/json")
    }

    /**
     * Finds Jellyfin or Emby servers on the home network: both answer a UDP broadcast on port 7359
     * ("who is JellyfinServer?" / "who is EmbyServer?"). In Docker the reply's address can be the container's own,
     * so the address the reply came from is tried too. [alsoTry] are hosts worth trying on port 8096 when nothing
     * answers (e.g. the box Dispatcharr runs on, which often runs Jellyfin as well).
     */
    suspend fun discover(emby: Boolean, alsoTry: List<String> = emptyList()): List<ServerInfo> {
        val candidates = LinkedHashSet<String>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                java.net.DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.soTimeout = 500
                    val ask = (if (emby) "who is EmbyServer?" else "who is JellyfinServer?").toByteArray()
                    sock.send(java.net.DatagramPacket(ask, ask.size, java.net.InetAddress.getByName("255.255.255.255"), 7359))
                    val end = System.currentTimeMillis() + 2_000
                    val buf = ByteArray(4096)
                    while (System.currentTimeMillis() < end) {
                        val p = java.net.DatagramPacket(buf, buf.size)
                        try { sock.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                        val j = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(String(p.data, 0, p.length)) }.getOrNull() ?: continue
                        val address = j.str("Address")?.trimEnd('/') ?: continue
                        val port = Regex(":(\\d+)$").find(address)?.groupValues?.get(1) ?: "8096"
                        candidates += address
                        p.address?.hostAddress?.let { candidates += "http://$it:$port" }
                    }
                }
            }
        }
        alsoTry.forEach { candidates += "http://$it:8096" }
        val found = LinkedHashMap<String, ServerInfo>()
        for (c in candidates) {
            val s = findServer(c, emby) ?: continue
            if (s.product.isBlank() || s.product.contains("Emby", ignoreCase = true) == emby) found.putIfAbsent(s.name, s)
        }
        // Nothing answered the broadcast (e.g. Docker without UDP 7359 mapped): look for port 8096 on the network.
        if (found.isEmpty()) lanScan(8096) { url -> findServer(url, emby) }.forEach { s ->
            if (s.product.isBlank() || s.product.contains("Emby", ignoreCase = true) == emby) found.putIfAbsent(s.name, s)
        }
        return found.values.toList()
    }

    /** Accepts host, host:port or a full URL; tries the default port 8096 when none is given. */
    suspend fun findServer(input: String, emby: Boolean): ServerInfo? {
        val raw = input.trim().trimEnd('/')
        if (raw.isEmpty()) return null
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        val hasPort = Regex("^[a-zA-Z]+://[^/]+:\\d+").containsMatchIn(withScheme)
        val candidates = if (hasPort || withScheme.startsWith("https")) listOf(withScheme)
        else listOf(withScheme, withScheme.replaceFirst(Regex("^(https?://[^/]+)"), "$1:8096"))
        for (url in candidates) {
            val info = withTimeoutOrNull(6_000) { runCatching { http.get("$url/System/Info/Public") }.getOrNull() }
            val product = info?.body.str("ProductName").orEmpty()
            if (info?.ok == true) {
                val isEmby = product.contains("Emby", ignoreCase = true)
                if (emby == isEmby || product.isBlank()) return ServerInfo(url, info.body.str("ServerName") ?: url, product)
            }
        }
        return null
    }

    suspend fun signIn(server: ServerInfo, emby: Boolean, deviceId: String, user: String, password: String): MediaServerAuth? = runCatching {
        val r = http.post("${server.url}/Users/AuthenticateByName", buildJsonObject {
            put("Username", user.trim())
            put("Pw", password)
        }, headers(emby, deviceId))
        if (!r.ok) return null
        toAuth(server, r)
    }.getOrNull()

    suspend fun startQuickConnect(server: ServerInfo, deviceId: String): QuickConnectCode? = runCatching {
        val r = http.post("${server.url}/QuickConnect/Initiate", null, headers(false, deviceId))
        if (!r.ok) return null
        QuickConnectCode(r.body.str("Secret")!!, r.body.str("Code")!!)
    }.getOrNull()

    /** Polls Quick Connect; returns the signed-in account once approved in Jellyfin. */
    suspend fun finishQuickConnect(server: ServerInfo, deviceId: String, code: QuickConnectCode): MediaServerAuth? = runCatching {
        val status = http.get("${server.url}/QuickConnect/Connect?Secret=${enc(code.secret)}", headers(false, deviceId))
        if (status.body.child("Authenticated") != JsonPrimitive(true)) return null
        val r = http.post("${server.url}/Users/AuthenticateWithQuickConnect", buildJsonObject { put("Secret", code.secret) }, headers(false, deviceId))
        if (!r.ok) return null
        toAuth(server, r)
    }.getOrNull()

    private fun toAuth(server: ServerInfo, r: JsonResponse) = MediaServerAuth(
        serverUrl = server.url,
        serverName = server.name,
        userId = r.body.child("User").str("Id")!!,
        userName = r.body.child("User").str("Name") ?: "",
        token = r.body.str("AccessToken")!!,
    )
}

@Singleton
class StremioService @Inject constructor(private val http: HttpJson) {
    /** Accepts the install link or manifest URL; returns the addon if its manifest loads. */
    suspend fun fetchAddon(input: String): AddonInfo? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val url = trimmed.replaceFirst(Regex("^stremio://"), "https://")
        val base = url.removeSuffix("/manifest.json").trimEnd('/')
        val r = runCatching { http.get("$base/manifest.json") }.getOrNull() ?: return null
        if (!r.ok) return null
        val id = r.body.str("id") ?: return null
        return AddonInfo(baseUrl = base, id = id, name = r.body.str("name") ?: id)
    }
}
