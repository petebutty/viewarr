package io.github.petebutty.viewarr.data.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Home or away. Setups keep each server's home address (what it is on the home network). When that can't be
 * reached (this TV is in another house), requests and streams for it go to its away address instead (Settings →
 * Users & profiles → Away addresses). Home TVs keep using the home address: most routers can't reach their own
 * public address from inside.
 *
 * Every request through the app's OkHttp client passes [Interceptor]; players and anything else that opens a URL
 * itself go through [map].
 */
object Reach {
    private enum class Where { Unknown, Home, Away }

    /** Home base ("http://192.168.1.50:9191") → away base ("https://tv.example.com"). */
    @Volatile private var pairs: Map<String, HttpUrl> = emptyMap()
    private val where = ConcurrentHashMap<String, Where>()
    private val lastCheck = ConcurrentHashMap<String, Long>()

    /** "scheme://host:port" of [u] (the port always given, so either spelling matches). */
    private fun baseOf(u: HttpUrl) = "${u.scheme}://${u.host}:${u.port}"

    /** A typed address made whole: no scheme means http for a home address, https for an away one. */
    fun normalise(raw: String, away: Boolean): String? {
        val t = raw.trim().trimEnd('/')
        if (t.isEmpty()) return null
        val withScheme = if ("://" in t) t else (if (away) "https://" else "http://") + t
        val u = withScheme.toHttpUrlOrNull() ?: return null
        return baseOf(u)
    }

    /** The away addresses in use (from the setup). Changing them starts the home/away check again. */
    fun setPairs(map: Map<String, String>) {
        val next = map.mapNotNull { (h, a) ->
            val home = normalise(h, away = false) ?: return@mapNotNull null
            val away = normalise(a, away = true)?.toHttpUrlOrNull() ?: return@mapNotNull null
            home to away
        }.toMap()
        if (next.keys == pairs.keys && next.values.map(::baseOf) == pairs.values.map(::baseOf)) return
        pairs = next
        where.clear()
        next.keys.forEach { probe(it) }
    }

    private fun swap(u: HttpUrl, to: HttpUrl): HttpUrl = u.newBuilder().scheme(to.scheme).host(to.host).port(to.port).build()

    // ---- Plex's secure home addresses on networks that won't look them up ----
    // Plex gives a server a name with its home IP in it (192-168-1-50.<id>.plex.direct = 192.168.1.50). Some routers
    // (BT's among them) refuse to look up any name that points into the home network ("DNS rebind protection"), so
    // those names fail outright and everything on that server waited for the slower routes (2026-11-27: Play took
    // 12-19 s on a phone). There, the server's plain home address is used instead: the same server, on the same port.
    // Previewarr Mobile only (phones meet these routers; the TV apps keep using the addresses as they are), and only
    // while that address answers: a failed look-up is trusted for a minute, then checked again.
    private val PLEX_DIRECT = Regex("^(\\d{1,3})-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})\\.[0-9a-f]+\\.plex\\.direct$")
    /** Each such name: when this network last failed to look it up and the plain address answered (ms; absent: fine). */
    private val plexUnresolvable = ConcurrentHashMap<String, Long>()
    private val plexChecked = ConcurrentHashMap<String, Long>()

    /** [u]'s plain home address when it's a Plex home name this network can't look up; else null. */
    private fun plexHome(u: HttpUrl, mayLookUp: Boolean): HttpUrl? {
        if (!io.github.petebutty.viewarr.BuildConfig.MOBILE) return null
        val m = PLEX_DIRECT.matchEntire(u.host.lowercase()) ?: return null
        val ip = m.groupValues.drop(1).joinToString(".")
        if (!io.github.petebutty.viewarr.data.media.isHomeAddress("http://$ip")) return null
        val now = System.currentTimeMillis()
        val fresh = (now - (plexChecked[u.host] ?: 0L)) < 60_000
        if (!fresh) {
            if (!mayLookUp) return plexUnresolvable[u.host]?.let { plain(u, ip) }
            plexChecked[u.host] = now
            val lookedUp = runCatching { java.net.InetAddress.getByName(u.host) }.isSuccess
            // (Only when the plain home address really answers here: not on someone else's network, or offline.)
            val plainOk = !lookedUp && runCatching { Socket().use { it.connect(InetSocketAddress(ip, u.port), 1_200) } }.isSuccess
            if (plainOk) plexUnresolvable[u.host] = now else plexUnresolvable.remove(u.host)
        }
        return plexUnresolvable[u.host]?.let { plain(u, ip) }
    }
    private fun plain(u: HttpUrl, ip: String) = u.newBuilder().scheme("http").host(ip).port(u.port).build()
    private fun offMain() = android.os.Looper.myLooper() != android.os.Looper.getMainLooper()

    /** [url] as it should be opened now: its away address when its home one can't be reached. */
    fun map(url: String): String {
        val u0 = url.toHttpUrlOrNull() ?: return url
        // (Away from home the original address's away pair applies; at home the plain address is used.)
        val mapped = mapPlain(u0, url)
        if (mapped != url) return mapped
        return plexHome(u0, offMain())?.toString() ?: url
    }

    private fun mapPlain(u: HttpUrl, original: String = u.toString()): String {
        val url = original
        val h = baseOf(u)
        val to = pairs[h] ?: return url
        recheck(h)
        return if (where[h] == Where.Away) swap(u, to).toString() else url
    }

    /** Can't connect at all (as opposed to a slow or failed reply, which says nothing about where we are). */
    private fun unreachable(e: IOException) = e is ConnectException || e is NoRouteToHostException || e is UnknownHostException ||
        (e is SocketTimeoutException && e.message.orEmpty().contains("connect", ignoreCase = true))

    /** Quick check of the home address in the background (a TCP connect, nothing sent). */
    private fun probe(h: String) {
        lastCheck[h] = System.currentTimeMillis()
        val u = h.toHttpUrlOrNull() ?: return
        Thread {
            val ok = runCatching { Socket().use { it.connect(InetSocketAddress(u.host, u.port), 1_200) } }.isSuccess
            // A request may have decided first; the probe only fills in what isn't known yet, or brings us home.
            if (ok) where[h] = Where.Home else where.putIfAbsent(h, Where.Away)
        }.apply { isDaemon = true; name = "reach" }.start()
    }

    /** Away: look for home again now and then (the TV may be back on the home network). */
    private fun recheck(h: String) {
        if (where[h] != Where.Away) return
        if (System.currentTimeMillis() - (lastCheck[h] ?: 0) > 120_000) probe(h)
    }

    object Interceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            val req0 = chain.request()
            // (A Plex home name this network won't look up: the server's plain home address. Its away pair, if any, is
            // looked up by the original address below.)
            val plexPlain = if (pairs.containsKey(baseOf(req0.url))) null else plexHome(req0.url, mayLookUp = true)
            val req = plexPlain?.let { req0.newBuilder().url(it).build() } ?: req0
            val h = baseOf(req.url)
            val to = pairs[h] ?: return chain.proceed(req)
            val awayReq = { req.newBuilder().url(swap(req.url, to)).build() }
            return when (where[h]) {
                Where.Away -> { recheck(h); chain.proceed(awayReq()) }
                Where.Home -> try { chain.proceed(req) } catch (e: IOException) {
                    if (!unreachable(e)) throw e
                    where[h] = Where.Away; lastCheck[h] = System.currentTimeMillis()
                    chain.proceed(awayReq())
                }
                // Not known yet: home, briefly, then away.
                else -> try {
                    chain.withConnectTimeout(2, TimeUnit.SECONDS).proceed(req).also { where[h] = Where.Home }
                } catch (e: IOException) {
                    if (!unreachable(e)) throw e
                    where[h] = Where.Away; lastCheck[h] = System.currentTimeMillis()
                    chain.proceed(awayReq())
                }
            }
        }
    }
}
