package io.github.petebutty.viewarr.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.BuildConfig
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Updates"
// (Previewarr's downloads have their own repository, petebutty/previewarr: Viewarr 0.29.9.4 and older took the first
// file of a release, alphabetically, so Previewarr's files can't sit in Viewarr's releases. 2026-11-25.)
private val RELEASES_URL = if (BuildConfig.LAUNCHER) "https://api.github.com/repos/petebutty/previewarr/releases?per_page=30"
    else "https://api.github.com/repos/petebutty/viewarr/releases?per_page=30"
/** Automatic checks: on start and when the app comes back to the front, at most this often (GitHub allows 60 an hour). */
private const val AUTO_INTERVAL_MS = 30 * 60_000L

data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val prerelease: Boolean,
    /** The APK's SHA-256 (hex) when GitHub gives one (the asset's "digest": "sha256:…"). */
    val sha256: String? = null,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** Downloaded, waiting for "Install unknown apps" to be allowed for Viewarr. */
    data class NeedsPermission(val release: Release) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

data class UpdatePrefs(val auto: Boolean = true, val beta: Boolean = true)

private val Context.updateStore by preferencesDataStore(name = "updates")

/**
 * Checks GitHub for a newer Viewarr release, downloads its APK and hands it to Android's installer.
 * Android always asks for confirmation the first time; once Viewarr has installed itself, Android 12+
 * lets later updates go through without asking.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    /** The pop-up shows only when this is true: set by a check that found something, cleared by "Later". */
    private val _prompt = MutableStateFlow(false)
    val prompt = _prompt.asStateFlow()

    val prefs: Flow<UpdatePrefs> = context.updateStore.data.map { p ->
        UpdatePrefs(auto = p[AUTO] ?: true, beta = p[BETA] ?: true)
    }

    // (Previewarr's version name carries "-launcher": the release's own number is what's compared.)
    val currentVersion: String = BuildConfig.VERSION_NAME.substringBefore("-launcher")

    suspend fun setAuto(on: Boolean) { context.updateStore.edit { it[AUTO] = on } }
    suspend fun setBeta(on: Boolean) { context.updateStore.edit { it[BETA] = on } }

    /** On app start: check if automatic checks are on and the last one was a while ago. */
    suspend fun autoCheck() {
        val p = context.updateStore.data.first()
        if (p[AUTO] == false) return
        val last = p[LAST_CHECK] ?: 0L
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return
        check(fromUser = false)
    }

    suspend fun check(fromUser: Boolean = true) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        val beta = prefs.first().beta
        val found = runCatching { fetchNewest(beta) }
        context.updateStore.edit { it[LAST_CHECK] = System.currentTimeMillis() }
        found.onSuccess { r ->
            val failed = context.updateStore.data.first()[FAILED]
            if (r == null) {
                _state.value = UpdateState.UpToDate
            } else if (!fromUser && r.version == failed) {
                // This version already failed to install here: don't keep popping up about it. "Check now" still offers it.
                _state.value = UpdateState.Available(r)
            } else {
                _state.value = UpdateState.Available(r)
                _prompt.value = true
            }
        }.onFailure {
            Log.w(TAG, "check failed: ${it.message}")
            // Quietly try again next start if this was automatic; tell the user if they asked.
            _state.value = if (fromUser) UpdateState.Failed("Couldn't reach GitHub. Check the internet connection and try again.") else UpdateState.Idle
        }
    }

    fun later() { _prompt.value = false }

    private suspend fun fetchNewest(beta: Boolean): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Viewarr/$currentVersion")
            .build()
        val body = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("GitHub ${r.code}")
            r.body!!.string()
        }
        val arr = JSONArray(body)
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && (beta || !it.optBoolean("prerelease")) }
            .mapNotNull { j ->
                val version = j.optString("tag_name").removePrefix("v").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val assets = j.optJSONArray("assets") ?: return@mapNotNull null
                // Each release carries both apps: Viewarr's viewarr-<version>.apk and Previewarr's previewarr-<version>.apk
                // (2026-11-25). This app's own, the numbered one first; an older release with one plain .apk: Viewarr's.
                val all = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
                val mine = if (BuildConfig.LAUNCHER) all.filter { it.optString("name").startsWith("previewarr", ignoreCase = true) }
                    else all.filter { !it.optString("name").startsWith("previewarr", ignoreCase = true) }
                val apk = mine.firstOrNull { it.optString("name").contains('-') } ?: mine.firstOrNull() ?: return@mapNotNull null
                val sha = apk.optString("digest").takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substringAfter(':')?.lowercase()
                Release(version, cleanNotes(j.optString("body")), apk.optString("browser_download_url"), apk.optLong("size"), j.optBoolean("prerelease"), sha)
            }
            .filter { isNewer(it.version, currentVersion) }
            .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
    }

    /** Downloads the release, then installs it. */
    suspend fun download(release: Release) {
        _state.value = UpdateState.Downloading(release, 0f)
        val file = apkFile(release)
        val ok = runCatching {
            withContext(Dispatchers.IO) {
                file.parentFile?.listFiles()?.forEach { if (it != file) it.delete() }
                // Only from GitHub (the release page's link and the places it hands the file out from), redirects included.
                if (!trustedHost(release.apkUrl.toHttpUrlOrNull())) error("not a GitHub download: ${release.apkUrl.toHttpUrlOrNull()?.host}")
                val req = Request.Builder().url(release.apkUrl).header("User-Agent", "Viewarr/$currentVersion").build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("download ${r.code}")
                    if (!trustedHost(r.request.url)) error("download redirected to ${r.request.url.host}")
                    val total = r.body!!.contentLength().takeIf { it > 0 } ?: release.apkSize
                    val tmp = File(file.path + ".part")
                    r.body!!.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastShown = 0f
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                ensureActive()
                                out.write(buf, 0, n)
                                done += n
                                val p = if (total > 0) done.toFloat() / total else 0f
                                if (p - lastShown >= 0.01f) { lastShown = p; _state.value = UpdateState.Downloading(release, p) }
                            }
                        }
                    }
                    if (total > 0 && tmp.length() != total) error("incomplete download")
                    file.delete()
                    if (!tmp.renameTo(file)) error("couldn't keep the download")
                }
            }
        }
        if (ok.isFailure) {
            Log.w(TAG, "download failed: ${ok.exceptionOrNull()?.message}")
            file.delete(); File(file.path + ".part").delete()
            _state.value = UpdateState.Failed("The download didn't finish. Try again.", release)
            return
        }
        val problem = withContext(Dispatchers.IO) { runCatching { verify(file, release) }.getOrElse { "it couldn't be checked (${it.message})" } }
        if (problem != null) {
            Log.w(TAG, "downloaded APK rejected: $problem")
            file.delete()
            _state.value = UpdateState.Failed("The download wasn't a genuine Viewarr update: $problem.", release)
            return
        }
        install(release)
    }

    /**
     * Checks the downloaded APK before it goes near the installer: it must be this app, a newer build, signed with
     * the same certificate as the installed copy, and (when GitHub gave one) match the release's SHA-256. Android
     * refuses a different signature too, but only after the user has been asked; a tampered or wrong file is
     * stopped here instead. Null when it's fine, else what's wrong.
     */
    private fun verify(file: File, release: Release): String? {
        release.sha256?.let { want ->
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            val got = md.digest().hex()
            if (!got.equals(want, ignoreCase = true)) return "its checksum doesn't match the release"
        }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return "it isn't a readable app"
        if (archive.packageName != context.packageName) return "it's a different app (${archive.packageName})"
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.longVersionCodeCompat() <= installed.longVersionCodeCompat()) return "it isn't newer than this one"
        val theirs = signersOf(archive)
        val mine = signersOf(installed)
        // Only refused when both can be read and differ: some Android 9–12 boxes can't read a downloaded file's
        // signature, and Android's installer refuses an update signed by anyone else anyway (this is a second check).
        if (theirs.isNotEmpty() && mine.isNotEmpty() && theirs != mine) return "it isn't signed like this one"
        return null
    }

    /** The certificates the APK itself is signed with, as SHA-256 hex (a rotated key's history isn't needed here). */
    private fun signersOf(info: PackageInfo): Set<String> {
        val sigs: Array<Signature>? =
            if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") info.signatures
        return sigs.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).hex() }.toSet()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    /** GitHub's own addresses for release files: the release link, and the hosts it redirects to. */
    private fun trustedHost(url: HttpUrl?): Boolean =
        url != null && url.scheme == "https" && url.host.lowercase() in TRUSTED_HOSTS

    /** Installs the downloaded APK, or asks for permission to install apps first. */
    fun install(release: Release) {
        val file = apkFile(release)
        if (!file.exists()) { _state.value = UpdateState.Available(release); return }
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        _state.value = UpdateState.Installing(release)
        // Copying the APK into the installer is done off the main thread (on a slow box a big APK risked "not responding"
        // right at install time); a session that fails part-way is let go.
        thread(name = "viewarr-install") { installNow(release, file) }
    }

    private fun installNow(release: Release, file: File) {
        var sessionId = -1
        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                // Once Viewarr installed itself, Android 12+ can update it without asking.
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            sessionId = id
            installer.openSession(id).use { session ->
                session.openWrite("viewarr.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        }.onFailure {
            if (sessionId >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            Log.w(TAG, "install failed: ${it.message}")
            _state.value = UpdateState.Failed("Android couldn't start the install (${it.message}).", release)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called by [InstallReceiver] when Android reports back. Success never arrives: the app is restarted. */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = (state.value as? UpdateState.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.UpToDate
            else -> {
                _state.value = UpdateState.Failed("The update wasn't installed" + (message?.let { ": $it" } ?: "."), release)
                release?.let { r -> scope.launch { context.updateStore.edit { it[FAILED] = r.version } } }
            }
        }
    }

    /** Opens Android's "Install unknown apps" switch for Viewarr. False if this box hides that screen. */
    fun openInstallPermission(): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        for (i in intents) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }

    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    private fun apkFile(r: Release) = File(File(context.cacheDir, "updates").apply { mkdirs() }, "Viewarr-${r.version}.apk")

    private companion object {
        val AUTO = booleanPreferencesKey("auto")
        val BETA = booleanPreferencesKey("beta")
        val LAST_CHECK = longPreferencesKey("last_check")
        val FAILED = stringPreferencesKey("failed_version")
        val TRUSTED_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    }
}

/** Numbers first (any count of parts); with the same numbers, a pre-release ("0.29.0-beta.1") comes before the release. */
internal fun compareVersions(a: String, b: String): Int {
    val ca = a.removePrefix("v").substringBefore('-'); val cb = b.removePrefix("v").substringBefore('-')
    val pa = ca.split('.').map { it.toIntOrNull() ?: 0 }
    val pb = cb.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    val preA = a.contains('-'); val preB = b.contains('-')
    return when {
        preA == preB -> 0
        preA -> -1
        else -> 1
    }
}

internal fun isNewer(candidate: String, current: String) = compareVersions(candidate, current) > 0

/** GitHub release notes as plain text for the TV: no bold markers, links reduced to their text. */
private fun cleanNotes(md: String): String = md
    .lines().filterNot { it.trimStart().startsWith("**Install:**") || it.trimStart().startsWith("Install:") }.joinToString("\n")
    .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
    .replace("**", "")
    .replace("`", "")
    .lines()
    .joinToString("\n") { l -> if (l.trimStart().startsWith("- ")) "•  " + l.trimStart().removePrefix("- ") else l }
    .trim()
