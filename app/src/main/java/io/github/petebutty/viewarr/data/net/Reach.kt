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

    /** Away from home right now (any server on its away address). */
    val away: Boolean get() = where.values.any { it == Where.Away }

    private fun swap(u: HttpUrl, to: HttpUrl): HttpUrl = u.newBuilder().scheme(to.scheme).host(to.host).port(to.port).build()

    /** [url] as it should be opened now: its away address when its home one can't be reached. */
    fun map(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
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
            val req = chain.request()
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
