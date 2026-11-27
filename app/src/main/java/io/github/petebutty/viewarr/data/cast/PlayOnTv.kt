package io.github.petebutty.viewarr.data.cast

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.petebutty.viewarr.data.media.DeepLink
import io.github.petebutty.viewarr.data.media.MediaItem
import io.github.petebutty.viewarr.data.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Play on TV: Previewarr Mobile sends what you're watching (a channel, a recording, a film or episode) to Previewarr on
 * a TV on the same network, which plays it itself. The TV announces itself on the network ([TYPE]) and takes "play
 * this" requests; the phone finds the TVs and sends a viewarr:// link. A TV only obeys a request carrying [keyOf] its
 * own sign-in: the same Dispatcharr account (username and API key), so nobody else on the network can drive it.
 */
object PlayOnTv {
    const val TYPE = "_previewarr._tcp."
    private const val PORT = 47870
    /** This user's app data on Dispatcharr: the TVs and where they listen (some boxes don't answer network searches). */
    private const val APP_DATA = "previewarr_tvs"
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun appDataUrl(s: Session) = s.serverUrl.trimEnd('/') + "/api/accounts/users/me/app-data/$APP_DATA/"
    private fun call(method: String, url: String, s: Session, body: String? = null): String? = runCatching {
        val c = URL(io.github.petebutty.viewarr.data.net.Reach.map(url)).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 6_000; c.readTimeout = 10_000
            c.setRequestProperty("X-API-Key", s.apiKey); c.setRequestProperty("Accept", "application/json")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        } finally { c.disconnect() }
    }.getOrNull()

    /** This TV's address on the home network (Wi-Fi or Ethernet), or null. */
    private fun localAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>()
            .map { it.hostAddress.orEmpty() }.firstOrNull { io.github.petebutty.viewarr.data.media.isHomeAddress("http://$it") && !it.startsWith("172.") }
    }.getOrNull()

    /** The TV side: this TV's name and address kept in the user's app data, beside the other TVs'. */
    fun register(s: Session?, name: String) {
        if (s == null || s.isDirect || s.apiKey.isBlank()) return
        val port = server?.localPort ?: return
        bg.launch {
            val host = localAddress() ?: return@launch
            val now = System.currentTimeMillis() / 1000
            val old = call("GET", appDataUrl(s), s)?.let { runCatching { json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
            val tvs = ((old?.get("tvs") as? kotlinx.serialization.json.JsonObject)?.toMutableMap() ?: mutableMapOf())
            tvs[name] = kotlinx.serialization.json.buildJsonObject { put("host", kotlinx.serialization.json.JsonPrimitive(host)); put("port", kotlinx.serialization.json.JsonPrimitive(port)); put("at", kotlinx.serialization.json.JsonPrimitive(now)) }
            val body = kotlinx.serialization.json.JsonObject(mapOf("tvs" to kotlinx.serialization.json.JsonObject(tvs)))
            if (call("PUT", appDataUrl(s), s, body.toString()) == null) Log.w(TAG, "couldn't save this TV's address")
        }
    }

    /** The phone side: the TVs kept in the user's app data (seen in the last two weeks). */
    private fun savedTvs(s: Session?, add: (Tv) -> Unit) {
        if (s == null || s.isDirect || s.apiKey.isBlank()) return
        bg.launch {
            val o = call("GET", appDataUrl(s), s)?.let { runCatching { json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() } ?: return@launch
            val now = System.currentTimeMillis() / 1000
            (o["tvs"] as? kotlinx.serialization.json.JsonObject)?.forEach { (name, v) ->
                val t = v as? kotlinx.serialization.json.JsonObject ?: return@forEach
                fun str(k: String) = (t[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val host = str("host") ?: return@forEach
                val port = str("port")?.toIntOrNull() ?: return@forEach
                if (now - (str("at")?.toLongOrNull() ?: 0) > 14 * 86_400) return@forEach
                add(Tv(name, host, port))
            }
        }
    }
    private const val TAG = "PlayOnTv"

    /** The shared secret for [s]'s account (null: no Dispatcharr sign-in, so no Play on TV). Never the key itself. */
    fun keyOf(s: Session?): String? {
        val k = s?.takeIf { !it.isDirect }?.apiKey?.takeIf { it.isNotBlank() } ?: return null
        val bytes = MessageDigest.getInstance("SHA-256").digest("previewarr-play|${s.username.lowercase()}|$k".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- Links (what a TV is asked to play)

    fun channelLink(uuid: String) = "viewarr://channel?uuid=" + Uri.encode(uuid)
    fun recordingLink(id: Int) = "viewarr://recording?id=$id"
    /** A film, or a show (its next episode, or [season] / [episode]): opened on the TV and started. */
    fun titleLink(item: MediaItem, season: Int? = null, episode: Int? = null): String =
        DeepLink.uriFor(item).let { u ->
            // (An episode asked for replaces the title's own next episode in the link: the first of a name is the one read.)
            val keep = u.queryParameterNames.filter { season == null || (it != "season" && it != "episode") }
            u.buildUpon().scheme("viewarr").clearQuery().apply {
                keep.forEach { n -> u.getQueryParameters(n).forEach { v -> appendQueryParameter(n, v) } }
                appendQueryParameter("play", "1")
                if (season != null && episode != null) { appendQueryParameter("season", "$season"); appendQueryParameter("episode", "$episode") }
            }.build().toString()
        }
    /**
     * Music: a queue saved on the music server ([src] the source, [q] its reference there), or, from a server that
     * can't hold queues, the songs' ids from the one playing on; with how far into that song.
     */
    fun musicLink(src: String, q: String, positionMs: Long) =
        "viewarr://music?src=${Uri.encode(src)}&q=${Uri.encode(q)}&t=${positionMs.coerceAtLeast(0)}"
    fun musicIdsLink(ids: List<String>, positionMs: Long): String? {
        if (ids.isEmpty()) return null
        val keep = ArrayList<String>(); var len = 0
        for (id in ids) { val e = Uri.encode(id).length + 3; if (len + e > 3_200 || keep.size >= 100) break; keep += id; len += e }
        return "viewarr://music?t=${positionMs.coerceAtLeast(0)}&ids=" + keep.joinToString(",") { Uri.encode(it) }
    }
    /** What a music link asks for. */
    class MusicAsk(val src: String?, val q: String?, val ids: List<String>, val positionMs: Long)
    fun musicOf(uri: Uri): MusicAsk? {
        if (uri.host != "music") return null
        val t = uri.getQueryParameter("t")?.toLongOrNull() ?: 0L
        val src = uri.getQueryParameter("src"); val q = uri.getQueryParameter("q")
        if (!src.isNullOrBlank() && !q.isNullOrBlank()) return MusicAsk(src, q, emptyList(), t)
        val ids = uri.getQueryParameter("ids")?.split(',')?.map { Uri.decode(it) }?.filter { it.contains(':') }.orEmpty()
        return if (ids.isEmpty()) null else MusicAsk(null, null, ids, t)
    }

    // ---------------------------------------------------------------- The TV side

    @Volatile private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    /** This TV's own [keyOf] (set as its sign-in changes); null: requests are refused. */
    @Volatile var ownKey: String? = null
    /** This device's name, as announced (the Devices page shows it). */
    @Volatile var ownName: String = android.os.Build.MODEL
    /** An accepted link while the app is on screen: its own pages carry it out (PlayOnTvHost / the phone screens). */
    @Volatile var onScreen: ((Uri) -> Unit)? = null
    /** Off screen: the app comes forward (PlayOnTvService), then carries out [pending] once it's on screen. */
    @Volatile var offScreen: (Uri) -> Unit = {}
    @Volatile var pending: Uri? = null
    /** Take over: what's playing here as a link (PlayOnTvService: music's queue saved on its server). */
    @Volatile var onHandoff: (suspend () -> String?)? = null
    /** Music sent here: played by the service straight away, on screen or not (PlayOnTvService). */
    @Volatile var onMusic: ((Uri) -> Unit)? = null
    private fun deliver(uri: Uri) {
        if (uri.host == "music") { onMusic?.let { it(uri); return } }
        val here = onScreen
        if (here != null && io.github.petebutty.viewarr.AppVisible.visible.value) here(uri) else { pending = uri; offScreen(uri) }
    }
    /** The app on screen again: the link that brought it forward, if any (once). */
    fun takePending(): Uri? = pending.also { pending = null }

    /** Starts listening and announcing this TV as [name] (once; again after [stop]). */
    fun start(context: Context, name: String) {
        ownName = name
        if (server != null) return
        // (The same port each time where it's free, so the address kept on the server stays right.)
        val ss = runCatching { ServerSocket(PORT) }.recoverCatching { ServerSocket(0) }.getOrElse { Log.w(TAG, "no socket: ${it.message}"); return }
        server = ss
        // (Two requests at a time at most, each with its own time limit: a device on the network that opens connections
        // and never finishes them can't tie the TV up.)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r, "PlayOnTv-req").apply { isDaemon = true } }
        val busy = java.util.concurrent.Semaphore(2)
        thread(isDaemon = true, name = "PlayOnTv") {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                if (!busy.tryAcquire()) { runCatching { c.close() }; continue }
                workers.execute {
                    try { runCatching { serve(c) }.onFailure { Log.w(TAG, "request: ${it.message}") } } finally { busy.release() }
                }
            }
            workers.shutdownNow()
        }
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply { serviceName = name; serviceType = TYPE; port = ss.localPort }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "announced as ${i.serviceName} on ${ss.localPort}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, error: Int) { Log.w(TAG, "announce failed: $error") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, error: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); registration = listener }
    }

    fun stop(context: Context) {
        registration?.let { l -> runCatching { (context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } }
        registration = null
        runCatching { server?.close() }
        server = null
    }

    /**
     * One request: POST /play with the key in a header and the link as the body. Read as bytes with limits (the
     * request line and headers 4 KB in all, the body 4 KB) and an overall deadline; the key is checked before the
     * body is read; only a viewarr:// channel, recording or title link is acted on.
     */
    private fun serve(c: Socket) = c.use { s ->
        s.soTimeout = 2_000
        val deadline = System.currentTimeMillis() + 5_000
        val input = s.getInputStream()
        val head = java.io.ByteArrayOutputStream()
        // Up to the blank line that ends the headers.
        while (true) {
            if (System.currentTimeMillis() > deadline || head.size() > 4_096) return
            val b = input.read()
            if (b < 0) return
            head.write(b)
            val a = head.toByteArray()
            if (a.size >= 4 && a[a.size - 4] == '\r'.code.toByte() && a[a.size - 3] == '\n'.code.toByte() && a[a.size - 2] == '\r'.code.toByte() && a[a.size - 1] == '\n'.code.toByte()) break
        }
        val lines = head.toString(Charsets.UTF_8.name()).split("\r\n")
        val first = lines.firstOrNull().orEmpty()
        var length = 0
        var key: String? = null
        for (h in lines.drop(1)) {
            val n = h.substringBefore(':').trim().lowercase()
            val v = h.substringAfter(':', "").trim()
            if (n == "content-length") length = v.toIntOrNull()?.coerceIn(0, 4_096) ?: 0
            if (n == "x-previewarr-key") key = v
        }
        val mine = ownKey
        fun reply(code: Int, text: String) {
            val out = s.getOutputStream()
            // (Content-Length in bytes: counted in letters, a "·" or "é" in a title cut the answer short and the phone
            // couldn't read it: "Not answering" while music played, 2026-11-27.)
            val body = text.toByteArray(Charsets.UTF_8)
            out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }
        val req = first.substringBefore(" HTTP/")
        if (req !in setOf("POST /play", "POST /control", "GET /status")) return reply(404, "not found")
        if (mine == null || key == null || !MessageDigest.isEqual(key.toByteArray(), mine.toByteArray())) return reply(403, "not allowed")
        // What's playing here (the Devices page asks every few seconds).
        if (req == "GET /status") return reply(200, NowPlaying.json(ownName))
        val buf = ByteArray(length)
        var got = 0
        while (got < length && System.currentTimeMillis() < deadline) { val n = input.read(buf, got, length - got); if (n < 0) break; got += n }
        val text = String(buf, 0, got, Charsets.UTF_8).trim()
        // Play / pause / stop / skip / channel up and down.
        if (req == "POST /control") {
            // Take over: what's playing here handed to the phone as a link (music: its queue saved on the server first).
            if (text == "handoff") {
                val link = runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(6_000) { onHandoff?.invoke() } } }.getOrNull()
                return reply(200, link.orEmpty())
            }
            if (text !in setOf("playpause", "play", "pause", "stop", "next", "previous", "forward", "back")) return reply(400, "bad command")
            Handler(Looper.getMainLooper()).post { runCatching { PlaybackControl.run(text) } }
            return reply(200, "ok")
        }
        val uri = Uri.parse(text)
        if (uri.scheme != "viewarr" || uri.host !in setOf("channel", "recording", "title", "music")) return reply(400, "bad link")
        Handler(Looper.getMainLooper()).post { deliver(uri) }
        reply(200, "ok")
    }

    // ---------------------------------------------------------------- The phone side

    /** A TV found on the network. */
    data class Tv(val name: String, val host: String, val port: Int)

    private val _tvs = MutableStateFlow<List<Tv>>(emptyList())
    val tvs = _tvs.asStateFlow()

    /** Looks for TVs while it runs (the TV list is open); the returned function stops looking. */
    fun discover(context: Context, session: Session? = null): () -> Unit {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        _tvs.value = emptyList()
        // The TVs kept on the server too: found by name, whether or not they answer the search (one found both ways
        // is listed once, with the address the search gave).
        savedTvs(session) { tv -> synchronized(_tvs) { if (_tvs.value.none { it.name == tv.name }) _tvs.value = (_tvs.value + tv).sortedBy { it.name } } }
        // Android resolves one at a time: the ones found wait their turn.
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        lateinit var next: () -> Unit
        next = {
            synchronized(queue) {
                if (resolving) return@synchronized
                val info = queue.removeFirstOrNull() ?: return@synchronized
                resolving = true
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            @Suppress("DEPRECATION") val host = i.host?.hostAddress
                            if (host != null) _tvs.value = (_tvs.value.filter { it.name != i.serviceName } + Tv(i.serviceName, host, i.port)).sortedBy { it.name }
                            synchronized(queue) { resolving = false }; next()
                        }
                        override fun onResolveFailed(i: NsdServiceInfo, error: Int) { synchronized(queue) { resolving = false }; next() }
                    })
                }.onFailure { resolving = false }
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { synchronized(queue) { queue.addLast(info) }; next() }
            override fun onServiceLost(info: NsdServiceInfo) { _tvs.value = _tvs.value.filter { it.name != info.serviceName } }
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { Log.w(TAG, "discovery failed: $error") }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
        }
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        return { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    /** What [tv] is playing (null: it didn't answer). */
    suspend fun status(tv: Tv, key: String): NowPlaying.State? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("http://${tv.host}:${tv.port}/status").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 2_500; c.readTimeout = 3_000
                c.setRequestProperty("X-Previewarr-Key", key)
                if (c.responseCode != 200) return@runCatching null
                val o = json.parseToJsonElement(c.inputStream.bufferedReader().readText()) as kotlinx.serialization.json.JsonObject
                fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val kind = str("kind") ?: return@runCatching Idle
                NowPlaying.State(kind, str("title").orEmpty(), str("sub"), str("image"), str("position")?.toLongOrNull() ?: 0,
                    str("duration")?.toLongOrNull() ?: 0, str("paused") == "true", str("link"))
            } finally { c.disconnect() }
        }.getOrNull()
    }
    /** "Nothing playing" (as opposed to null: no answer). */
    val Idle = NowPlaying.State("idle", "", null, null, 0, 0, false, null)

    /** Play / pause / stop / skip on [tv]. */
    suspend fun control(tv: Tv, cmd: String, key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("http://${tv.host}:${tv.port}/control").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 3_000; c.readTimeout = 4_000
                c.setRequestProperty("X-Previewarr-Key", key)
                c.outputStream.use { it.write(cmd.toByteArray()) }
                c.responseCode == 200
            } finally { c.disconnect() }
        }.getOrDefault(false)
    }

    /** Take over: [tv]'s link for what it's playing (null: nothing, or it didn't answer). */
    suspend fun handoff(tv: Tv, key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("http://${tv.host}:${tv.port}/control").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 3_000; c.readTimeout = 8_000
                c.setRequestProperty("X-Previewarr-Key", key)
                c.outputStream.use { it.write("handoff".toByteArray()) }
                if (c.responseCode != 200) null else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8).trim() }.takeIf { it.startsWith("viewarr://") }
            } finally { c.disconnect() }
        }.getOrNull()
    }

    /** Sends [link] to [tv]. A message for people when it didn't work. */
    suspend fun send(tv: Tv, link: String, key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("http://${tv.host}:${tv.port}/play").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 4_000; c.readTimeout = 6_000
                c.setRequestProperty("X-Previewarr-Key", key)
                c.setRequestProperty("Content-Type", "text/plain")
                c.outputStream.use { it.write(link.toByteArray()) }
                when (c.responseCode) {
                    200 -> Unit
                    403 -> error("${tv.name} is signed in to a different Dispatcharr account.")
                    else -> error("${tv.name} didn't take it (${c.responseCode}).")
                }
            } finally { c.disconnect() }
        }.recoverCatching { e -> throw if (e is IllegalStateException) e else IllegalStateException("Couldn't reach ${tv.name}. Is Previewarr open on it?") }
    }
}
