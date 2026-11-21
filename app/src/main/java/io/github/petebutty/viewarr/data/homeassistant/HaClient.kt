package io.github.petebutty.viewarr.data.homeassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Home Assistant's REST API (events, the connection test) and the addresses its WebSocket and pictures use. */
object HaClient {
    /** Every call gives up after 5 s: nothing here is worth waiting longer for, and nothing is retried. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** The WebSocket: open for as long as Viewarr is on screen, with a ping every 30 s to notice a dropped link. */
    val socket: OkHttpClient by lazy {
        http.newBuilder().readTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build()
    }

    private val JSON = "application/json".toMediaType()

    fun url(cfg: HaConfig, path: String) = cfg.address.trimEnd('/') + path

    /** ws:// or wss://, from the address's http:// or https://. */
    fun socketUrl(cfg: HaConfig): String {
        val a = cfg.address.trimEnd('/')
        return when {
            a.startsWith("https://", ignoreCase = true) -> "wss://" + a.substring(8)
            a.startsWith("http://", ignoreCase = true) -> "ws://" + a.substring(7)
            else -> a
        } + "/api/websocket"
    }

    /** A picture's address: Home Assistant's own paths (/api/camera_proxy/…, /local/…) on [cfg]'s address. */
    fun absolute(cfg: HaConfig, url: String): String = if (url.startsWith("/")) cfg.address.trimEnd('/') + url else url

    /** On Home Assistant itself: only these get the token (never another site's pictures). */
    fun isHa(cfg: HaConfig, url: String): Boolean = cfg.address.isNotBlank() && url.startsWith(cfg.address.trimEnd('/') + "/")

    private fun request(cfg: HaConfig, path: String) = Request.Builder().url(url(cfg, path)).header("Authorization", "Bearer ${cfg.token}")

    /** Fires [event] with [data] (one try). True when Home Assistant took it. */
    fun fire(cfg: HaConfig, event: String, data: JsonObject): Boolean =
        http.newCall(request(cfg, "/api/events/$event").post(data.toString().toRequestBody(JSON)).build()).execute().use { it.isSuccessful }

    /** Settings → Home Assistant → Test connection: "Connected to Home Assistant 2026.11.2", or what went wrong. */
    suspend fun test(cfg: HaConfig): Result<String> = withContext(Dispatchers.IO) {
        if (cfg.address.isBlank()) return@withContext Result.failure(IllegalStateException("Add Home Assistant's address first."))
        if (cfg.token.isBlank()) return@withContext Result.failure(IllegalStateException("Add an access token first."))
        runCatching {
            val code = try {
                http.newCall(request(cfg, "/api/").get().build()).execute().use { it.code }
            } catch (e: java.net.UnknownHostException) {
                error("Can't find ${cfg.address}. Check the address.")
            } catch (e: java.io.IOException) {
                error("Couldn't reach ${cfg.address}. Check the address and that Home Assistant is running.")
            }
            when (code) {
                in 200..299 -> Unit
                401, 403 -> error("Home Assistant didn't accept the access token. Make a new one and enter it again.")
                404 -> error("That address answered, but it isn't Home Assistant's API. Check the address and port (usually 8123).")
                else -> error("Home Assistant answered with an error ($code).")
            }
            val version = runCatching {
                http.newCall(request(cfg, "/api/config").get().build()).execute().use { r ->
                    r.body?.string()?.let { Json.parseToJsonElement(it).jsonObject["version"]?.jsonPrimitive?.contentOrNull }
                }
            }.getOrNull()
            if (version.isNullOrBlank()) "Connected to Home Assistant" else "Connected to Home Assistant $version"
        }
    }
}
