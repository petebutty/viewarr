package io.github.petebutty.viewarr.data.repository

import io.github.petebutty.viewarr.data.remote.ApiFactory
import io.github.petebutty.viewarr.data.remote.TokenRequest
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ProbeResult {
    data class Found(val url: String, val version: String) : ProbeResult
    data class Failed(val message: String) : ProbeResult
}

@Singleton
class AuthRepository @Inject constructor(
    private val apiFactory: ApiFactory,
    private val sessions: SessionStore,
) {
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

    suspend fun signOut() = sessions.clear()

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
