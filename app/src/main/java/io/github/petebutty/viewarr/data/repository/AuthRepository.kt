package io.github.petebutty.viewarr.data.repository

import io.github.petebutty.viewarr.data.remote.ApiFactory
import io.github.petebutty.viewarr.data.remote.TokenRequest
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import retrofit2.HttpException
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Singleton

sealed interface ProbeResult {
    data class Found(val url: String, val version: String) : ProbeResult
    data class Failed(val message: String) : ProbeResult
}

@Singleton
class AuthRepository @Inject constructor(
    private val apiFactory: ApiFactory,
    private val sessions: SessionStore,
    private val xtream: io.github.petebutty.viewarr.data.xtream.XtreamClient,
    private val m3u: io.github.petebutty.viewarr.data.m3u.M3uClient,
) {
    /**
     * IPTV provider (Xtream Codes) sign-in: the username and password from the provider. Tries the address as typed,
     * then with http:// added. The password is kept (encrypted) because every stream address contains it.
     */
    suspend fun signInXc(server: String, username: String, password: String): Result<Session> {
        val raw = server.trim().trimEnd('/')
        if (raw.isEmpty()) return Result.failure(IllegalStateException("Enter your provider's server address."))
        if (username.isBlank() || password.isEmpty()) return Result.failure(IllegalStateException("Enter the username and password from your provider."))
        val candidates = if (raw.contains("://")) listOf(raw) else listOf("http://$raw", "https://$raw")
        var last: Throwable = IllegalStateException("Couldn't reach that server. Check the address with your provider.")
        for (url in candidates) {
            try {
                val account = xtream.signIn(url, username.trim(), password)
                val session = Session(url, account.username, apiKey = "", xcPassword = password, xcTimezone = account.timezone)
                sessions.save(session)
                return Result.success(session)
            } catch (e: io.github.petebutty.viewarr.data.xtream.XtreamException) {
                return Result.failure(IllegalStateException(e.message))
            } catch (e: Exception) {
                last = IllegalStateException("Couldn't reach that server. Check the address with your provider.")
            }
        }
        return Result.failure(last)
    }

    /**
     * An M3U playlist link (with an optional XMLTV guide link). A provider's get.php link carries an Xtream Codes
     * login, which gives more (catch-up, films and series): that signs in as Xtream instead.
     */
    suspend fun signInM3u(playlist: String, guide: String?): Result<Session> {
        val url = playlist.trim().let { if (it.contains("://")) it else "http://$it" }
        if (playlist.isBlank()) return Result.failure(IllegalStateException("Enter your playlist link."))
        val parsed = url.toHttpUrlOrNull() ?: return Result.failure(IllegalStateException("That doesn't look like a link."))
        val user = parsed.queryParameter("username")
        val pass = parsed.queryParameter("password")
        if (parsed.encodedPath.endsWith("get.php") && !user.isNullOrBlank() && !pass.isNullOrEmpty()) {
            val server = parsed.newBuilder().encodedPath("/").query(null).build().toString().trimEnd('/')
            signInXc(server, user, pass).onSuccess { return Result.success(it) }
        }
        return try {
            val list = m3u.load(url)
            val epg = guide?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.contains("://")) it else "http://$it" }
            val session = Session(url, parsed.host, apiKey = "", isM3u = true, m3uEpg = epg)
            sessions.save(session)
            android.util.Log.i("Auth", "m3u: ${list.channels.size} channels, guide ${if (epg != null || list.guideUrl != null) "yes" else "no"}")
            Result.success(session)
        } catch (e: io.github.petebutty.viewarr.data.m3u.M3uException) {
            Result.failure(IllegalStateException(e.message))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Couldn't load that playlist. Check the link with your provider."))
        }
    }

    /** An address that's a playlist link rather than a server (setup takes either). */
    fun looksLikePlaylist(input: String): Boolean {
        val l = input.trim().lowercase()
        // ".m3u" / ".m3u8" files, paths like /output/m3u or /playlist/m3u, and providers' get.php links.
        return "m3u" in l || "get.php" in l || "output=ts" in l
    }

    /**
     * Looks for Dispatcharr on this TV's own network: every address in the local /24 with port 9191 open, confirmed
     * by Dispatcharr's version endpoint. Takes a few seconds; nothing outside the home network is contacted.
     */
    suspend fun discover(): List<ProbeResult.Found> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val prefix = localPrefix() ?: return@withContext emptyList()
        val gate = kotlinx.coroutines.sync.Semaphore(48)
        kotlinx.coroutines.coroutineScope {
            (1..254).map { host ->
                async {
                    gate.withPermit {
                        val ip = "$prefix.$host"
                        val open = runCatching {
                            java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, 9191), 300); true }
                        }.getOrDefault(false)
                        if (!open) return@withPermit null
                        val url = "http://$ip:9191"
                        runCatching { apiFactory.create(url, quick = true).version().version }.getOrNull()
                            ?.takeIf { it.isNotBlank() }?.let { ProbeResult.Found(url, it) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** "192.168.1" for a TV at 192.168.1.50: the first private IPv4 address on an active interface. */
    private fun localPrefix(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress?.substringBeforeLast('.')
    }.getOrNull()

    suspend fun probe(input: String): ProbeResult {
        val candidates = candidateUrls(input)
        if (candidates.isEmpty()) return ProbeResult.Failed("Enter your Dispatcharr server address.")
        for (url in candidates) {
            val version = runCatching { apiFactory.create(url, quick = true).version().version }.getOrNull()
            if (!version.isNullOrBlank()) return ProbeResult.Found(url, version)
        }
        return ProbeResult.Failed("No Dispatcharr server found at that address. Check it and try again.")
    }

    suspend fun signIn(serverUrl: String, username: String, password: String): Result<Session> = try {
        val api = apiFactory.create(serverUrl)
        val name = username.trim()
        // Dispatcharr usernames are case-sensitive, and phone or TV keyboards like to capitalise or autocorrect.
        // If the name as typed is refused, try it in lower case once (most usernames are).
        val token = try {
            api.token(TokenRequest(name, password))
        } catch (e: HttpException) {
            if (e.code() !in listOf(400, 401) || name == name.lowercase()) throw LoginRefused(e.code())
            try { api.token(TokenRequest(name.lowercase(), password)) } catch (e2: HttpException) { throw LoginRefused(e2.code()) }
        }
        val bearer = "Bearer " + token.access
        // generate replaces the user's existing key and would break other apps using it, so reuse first.
        val key = try {
            api.apiKey(bearer).key?.takeIf { it.isNotBlank() } ?: api.generateApiKey(bearer).key
        } catch (e: HttpException) {
            error("Your username and password were accepted, but Dispatcharr wouldn't give Viewarr an API key (error ${e.code()}). " +
                "Use the API key option instead: in Dispatcharr open Users, edit your account and copy the API key.")
        } ?: error("Your username and password were accepted, but Dispatcharr didn't return an API key. Use the API key option instead.")
        val me = apiFactory.create(serverUrl, apiKey = key).me()
        check(me.userLevel >= STANDARD_LEVEL) {
            "\"${me.username}\" is a Streamer account, which only works in player apps. " +
                "Sign in with a Standard or Admin account, or change this account's level in Dispatcharr under Users."
        }
        val session = Session(serverUrl, me.username, key)
        sessions.save(session)
        Result.success(session)
    } catch (e: LoginRefused) {
        Result.failure(
            IllegalStateException(
                when (e.code) {
                    400, 401 -> "Dispatcharr didn't accept that username or password. Usernames are case-sensitive, so check capital letters " +
                        "and that the keyboard didn't autocorrect it. The API key option always works."
                    403 -> "This Dispatcharr server doesn't allow sign-in from this network (Settings → Network access in Dispatcharr)."
                    429 -> "Too many attempts. Wait a minute and try again."
                    else -> "Sign-in failed (server error ${e.code})."
                }
            )
        )
    } catch (e: HttpException) {
        Result.failure(
            IllegalStateException(
                when (e.code()) {
                    400, 401 -> "Dispatcharr refused the sign-in (error ${e.code()}). Try the API key option instead."
                    403 -> "This server doesn't allow sign-in from this network."
                    429 -> "Too many attempts. Wait a minute and try again."
                    else -> "Sign-in failed (server error ${e.code()})."
                }
            )
        )
    } catch (e: IOException) {
        Result.failure(IllegalStateException("Can't reach the server. Check your connection."))
    } catch (e: IllegalStateException) {
        Result.failure(e)
    }

    /** Signs in with an API key copied from Dispatcharr (the user's edit form → API Key → copy). */
    suspend fun signInWithKey(serverUrl: String, apiKey: String): Result<Session> = try {
        val key = apiKey.trim()
        val me = apiFactory.create(serverUrl, apiKey = key).me()
        check(me.userLevel >= STANDARD_LEVEL) {
            "That key belongs to \"${me.username}\", a Streamer account, which only works in player apps. " +
                "Use a Standard or Admin account's key."
        }
        val session = Session(serverUrl, me.username, key)
        sessions.save(session)
        Result.success(session)
    } catch (e: HttpException) {
        Result.failure(IllegalStateException(if (e.code() in listOf(401, 403)) "Dispatcharr didn't accept that API key." else "Sign-in failed (server error ${e.code()})."))
    } catch (e: IOException) {
        Result.failure(IllegalStateException("Can't reach the server. Check your connection."))
    } catch (e: IllegalStateException) {
        Result.failure(e)
    }

    /** Signs out of the active Live TV source; another saved one, if any, takes over. */
    suspend fun signOut() = sessions.clear()

    /** Signs out of every Live TV source (the app's own Sign out, which starts setup again). */
    suspend fun signOutEverywhere() = sessions.clearAll()

    private class LoginRefused(val code: Int) : Exception()

    private companion object {
        const val STANDARD_LEVEL = 1
    }

    private fun candidateUrls(input: String): List<String> {
        val raw = input.trim().trimEnd('/')
        if (raw.isEmpty()) return emptyList()
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        val hasPort = Regex("^[a-zA-Z]+://[^/]+:\\d+").containsMatchIn(withScheme)
        return if (hasPort) listOf(withScheme) else listOf(withScheme, insertPort(withScheme, 9191))
    }

    private fun insertPort(url: String, port: Int): String {
        val schemeEnd = url.indexOf("://") + 3
        val pathStart = url.indexOf('/', schemeEnd).let { if (it == -1) url.length else it }
        return url.substring(0, pathStart) + ":$port" + url.substring(pathStart)
    }
}
