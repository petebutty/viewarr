package io.github.petebutty.viewarr.data.homeassistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** A pop-up Home Assistant sent (event "viewarr_notify"). [id] is new for every one, so the same text twice shows twice. */
data class HaCard(
    val id: Long,
    val title: String?,
    val message: String?,
    /** A picture's full address (Home Assistant's own paths already put on its address). */
    val image: String?,
    /** A camera entity (camera.front_door): its picture, fetched again about once a second. */
    val camera: String?,
    val seconds: Int,
    val position: Position,
) {
    enum class Position { TopRight, TopLeft, BottomRight, BottomLeft, Centre }
}

/**
 * Settings → Home Assistant → Pop-ups from Home Assistant: one WebSocket to Home Assistant while Viewarr is on screen
 * (the host runs [run] while the app is started, and cancels it when it goes to the background). It signs in with the
 * token, subscribes to "viewarr_notify" events, and turns each into a [HaCard]. A dropped link is tried again after
 * 5 s, 15 s, 60 s, then every 5 minutes; a refused token waits 5 minutes too.
 */
object HaEvents {
    const val EVENT = "viewarr_notify"
    /** Home Assistant controlling Viewarr (scripts, dashboard buttons, voice assistants): see [command]. */
    const val COMMAND = "viewarr_command"

    private val _card = MutableStateFlow<HaCard?>(null)
    /** The pop-up showing (null: none). The host clears it when its time is up. */
    val card: StateFlow<HaCard?> = _card.asStateFlow()
    private var next = 0L

    fun dismiss(id: Long) { if (_card.value?.id == id) _card.value = null }

    private class Refused : Exception()

    /** Connected and listening, until cancelled. */
    suspend fun run(cfg: HaConfig, tv: String) {
        var failures = 0
        while (true) {
            var signedIn = false
            val refused = try {
                session(cfg, tv) { signedIn = true }
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Refused) {
                android.util.Log.w(HaStore.TAG, "pop-ups: the access token was refused")
                true
            } catch (e: Exception) {
                if (failures == 0) android.util.Log.w(HaStore.TAG, "pop-ups: link lost (${e.javaClass.simpleName})")
                false
            }
            if (signedIn) failures = 0
            val wait = if (refused) 300 else when (failures) { 0 -> 5; 1 -> 15; 2 -> 60; else -> 300 }
            failures++
            delay(wait * 1000L)
        }
    }

    /** One connection: returns when Home Assistant closes it, throws when it fails. */
    private suspend fun session(cfg: HaConfig, tv: String, onSignedIn: () -> Unit) {
        val inbox = Channel<String>(Channel.UNLIMITED)
        val ws = HaClient.socket.newWebSocket(Request.Builder().url(HaClient.socketUrl(cfg)).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { inbox.trySend(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); inbox.close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { inbox.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { inbox.close(t) }
        })
        try {
            for (text in inbox) {
                val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: continue
                when (o.str("type")) {
                    "auth_required" -> ws.send(buildJsonObject { put("type", "auth"); put("access_token", cfg.token) }.toString())
                    "auth_ok" -> {
                        onSignedIn()
                        ws.send(buildJsonObject { put("id", 1); put("type", "subscribe_events"); put("event_type", EVENT) }.toString())
                        ws.send(buildJsonObject { put("id", 2); put("type", "subscribe_events"); put("event_type", COMMAND) }.toString())
                    }
                    "auth_invalid" -> throw Refused()
                    "event" -> {
                        val ev = o["event"] as? JsonObject
                        val data = ev?.get("data") as? JsonObject
                        if (data != null) { if (ev.str("event_type") == COMMAND) command(tv, data) else show(cfg, tv, data) }
                    }
                }
            }
        } finally {
            ws.close(1000, null)
        }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A "viewarr_command" event: run as the matching viewarr:// link (docs/AUTOMATION.md), so it does exactly what that
     * link does. Data: command = channel (number / uuid), channel_up, channel_down, stop, guide, page (name), record
     * (number / uuid), search (query), pause, play, play_pause, refresh, or link (any viewarr:// link); tv = only that TV.
     */
    private fun command(tv: String, d: JsonObject) {
        d.str("tv")?.let { if (!it.equals(tv, ignoreCase = true)) return }
        fun q(k: String) = android.net.Uri.encode(d.str(k).orEmpty())
        val link = when (d.str("command")?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
            "channel", "tune" -> d.str("uuid")?.let { "viewarr://channel?uuid=${q("uuid")}" } ?: d.str("number")?.let { "viewarr://channel?number=${q("number")}" }
            "channel_up" -> "viewarr://channel?step=up"
            "channel_down" -> "viewarr://channel?step=down"
            "stop" -> "viewarr://stop"
            "guide" -> "viewarr://guide"
            "page" -> d.str("name")?.let { "viewarr://page?name=${q("name")}" }
            "record" -> d.str("uuid")?.let { "viewarr://record?uuid=${q("uuid")}" } ?: d.str("number")?.let { "viewarr://record?number=${q("number")}" } ?: "viewarr://record"
            "search" -> d.str("query")?.let { "viewarr://search?q=${q("query")}" }
            "pause" -> "viewarr://pause"
            "play", "resume" -> "viewarr://play"
            "play_pause", "playpause" -> "viewarr://playpause"
            "refresh" -> "viewarr://refresh"
            "link" -> d.str("link")?.takeIf { it.startsWith("viewarr://") }
            else -> null
        } ?: return
        android.util.Log.i("HomeAssistant", "command: ${link.substringBefore('?')}")
        io.github.petebutty.viewarr.data.media.DeepLink.handle(android.net.Uri.parse(link))
    }

    private fun show(cfg: HaConfig, tv: String, d: JsonObject) {
        // Meant for another TV.
        d.str("tv")?.let { if (!it.equals(tv, ignoreCase = true)) return }
        val title = d.str("title")
        val message = d.str("message")
        val camera = d.str("camera")?.takeIf { it.startsWith("camera.") }
        val image = d.str("image")?.let { HaClient.absolute(cfg, it) }
        if (title == null && message == null && camera == null && image == null) return
        val seconds = d.str("duration")?.toDoubleOrNull()?.toInt()?.coerceIn(1, 120) ?: 10
        val position = when (d.str("position")?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
            "top_left" -> HaCard.Position.TopLeft
            "bottom_right" -> HaCard.Position.BottomRight
            "bottom_left" -> HaCard.Position.BottomLeft
            "centre", "center", "middle" -> HaCard.Position.Centre
            else -> HaCard.Position.TopRight
        }
        _card.value = HaCard(++next, title, message, image, camera, seconds, position)
    }
}
