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

    // ---------------------------------------------------------------- The TV side

    @Volatile private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    /** This TV's own [keyOf] (set as its sign-in changes); null: requests are refused. */
    @Volatile var ownKey: String? = null
    /** What to do with an accepted link, on the main thread (the launcher clears its own pages first). */
    @Volatile var onLink: (Uri) -> Unit = { DeepLink.handle(it) }

    /** Starts listening and announcing this TV as [name] (once; again after [stop]). */
    fun start(context: Context, name: String) {
        if (server != null) return
        val ss = runCatching { ServerSocket(0) }.getOrElse { Log.w(TAG, "no socket: ${it.message}"); return }
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
            out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: text/plain\r\nContent-Length: ${text.length}\r\nConnection: close\r\n\r\n$text".toByteArray())
            out.flush()
        }
        if (first.substringBefore(" HTTP/") != "POST /play") return reply(404, "not found")
        if (mine == null || key == null || !MessageDigest.isEqual(key.toByteArray(), mine.toByteArray())) return reply(403, "not allowed")
        val buf = ByteArray(length)
        var got = 0
        while (got < length && System.currentTimeMillis() < deadline) { val n = input.read(buf, got, length - got); if (n < 0) break; got += n }
        val uri = Uri.parse(String(buf, 0, got, Charsets.UTF_8).trim())
        if (uri.scheme != "viewarr" || uri.host !in setOf("channel", "recording", "title")) return reply(400, "bad link")
        Handler(Looper.getMainLooper()).post { onLink(uri) }
        reply(200, "ok")
    }

    // ---------------------------------------------------------------- The phone side

    /** A TV found on the network. */
    data class Tv(val name: String, val host: String, val port: Int)

    private val _tvs = MutableStateFlow<List<Tv>>(emptyList())
    val tvs = _tvs.asStateFlow()

    /** Looks for TVs while it runs (the TV list is open); the returned function stops looking. */
    fun discover(context: Context): () -> Unit {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        _tvs.value = emptyList()
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
