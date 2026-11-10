package io.github.petebutty.viewarr.data.profiles

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** What an admin can lock for a person (kept on their Dispatcharr user, custom_properties.viewarr_locks). */
object Lock {
    const val SEERR = "seerr"
    const val ACCOUNTS = "accounts"
    const val SOURCES = "sources"
    const val SETTINGS = "settings"
    const val SIGNOUT = "signout"
    /** A Standard user until an admin says otherwise: no requests, can't change sign-ins or sources, or sign out. */
    val DEFAULT = setOf(SEERR, ACCOUNTS, SOURCES, SIGNOUT)
}

/**
 * What the person signed in may do here. Admins (and IPTV logins / playlists, which have no Dispatcharr users)
 * may do everything. Recording, catch-up and IPTV films & series are also enforced by Dispatcharr itself.
 */
object Access {
    var admin by mutableStateOf(true)
    var locks by mutableStateOf<Set<String>>(emptySet())
    /** Dispatcharr dvr_access "manage" (admins always): record, cancel, series rules. */
    var record by mutableStateOf(true)
    fun can(lock: String) = admin || lock !in locks
}

/** One person's settings as an admin sets them (what they may do; Dispatcharr's own flags included). */
data class Perms(
    val seerr: Boolean = false,
    val record: Boolean = false,
    val catchup: Boolean = true,
    val vod: Boolean = true,
    val accounts: Boolean = false,
    val sources: Boolean = false,
    val settings: Boolean = true,
    val signout: Boolean = false,
) {
    val locks get() = buildSet {
        if (!seerr) add(Lock.SEERR); if (!accounts) add(Lock.ACCOUNTS); if (!sources) add(Lock.SOURCES)
        if (!settings) add(Lock.SETTINGS); if (!signout) add(Lock.SIGNOUT)
    }

    /** As Dispatcharr custom_properties (merged into what's there). */
    fun toProps() = buildJsonObject {
        put("viewarr_locks", JsonArray(locks.sorted().map { JsonPrimitive(it) }))
        put("dvr_access", if (record) "manage" else "view")
        put("catchup_enabled", catchup)
        put("vod_movies_enabled", vod)
        put("vod_series_enabled", vod)
    }

    companion object {
        /** From a user's custom_properties. None set yet (a user made elsewhere): the Standard defaults ([Lock.DEFAULT]). */
        fun from(props: JsonObject?): Perms {
            val locks = (props?.get("viewarr_locks") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: Lock.DEFAULT
            fun flag(k: String) = (props?.get(k) as? JsonPrimitive)?.booleanOrNull != false
            return Perms(
                seerr = Lock.SEERR !in locks, record = (props?.get("dvr_access") as? JsonPrimitive)?.contentOrNull == "manage",
                catchup = flag("catchup_enabled"), vod = flag("vod_movies_enabled") && flag("vod_series_enabled"),
                accounts = Lock.ACCOUNTS !in locks, sources = Lock.SOURCES !in locks,
                settings = Lock.SETTINGS !in locks, signout = Lock.SIGNOUT !in locks,
            )
        }
    }
}

/** Keeps [Access] in step with whoever is signed in (and remembers it, for when Dispatcharr can't be reached). */
@Singleton
class AccessLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val http: HttpJson,
) {
    private val prefs by lazy { context.getSharedPreferences("access", Context.MODE_PRIVATE) }

    fun start(scope: CoroutineScope) = scope.launch {
        sessions.session.distinctUntilChanged().collect { s -> load(s) }
    }

    /** Again now (after an admin changes someone's settings). */
    fun refresh(scope: CoroutineScope) = scope.launch { load(sessionsNow()) }

    private suspend fun sessionsNow(): Session? = runCatching { sessions.session.first() }.getOrNull()

    private suspend fun load(s: Session?) {
        if (s == null || s.isDirect) { apply(true, emptySet(), true); return }
        val key = "u:" + s.id
        // Last known first, so a locked-down profile doesn't flash everything while Dispatcharr answers.
        prefs.getString(key, null)?.split('|')?.let { p ->
            if (p.size == 3) apply(p[0] == "1", p[1].split(',').filter { it.isNotBlank() }.toSet(), p[2] == "1")
        }
        val me = runCatching { http.get(s.serverUrl.trimEnd('/') + "/api/accounts/users/me/", mapOf("X-API-Key" to s.apiKey)) }.getOrNull()
        if (me == null || !me.ok) return
        val admin = (me.body.int("user_level") ?: 0) >= 10
        val props = me.body.child("custom_properties") as? JsonObject
        val perms = Perms.from(props)
        val locks = if (admin) emptySet() else perms.locks
        val record = admin || perms.record
        apply(admin, locks, record)
        prefs.edit().putString(key, (if (admin) "1" else "0") + "|" + locks.joinToString(",") + "|" + (if (record) "1" else "0")).apply()
    }

    private fun apply(admin: Boolean, locks: Set<String>, record: Boolean) {
        Access.admin = admin; Access.locks = locks; Access.record = record
    }
}
