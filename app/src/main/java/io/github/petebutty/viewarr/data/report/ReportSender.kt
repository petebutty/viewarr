package io.github.petebutty.viewarr.data.report

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Base64
import io.github.petebutty.viewarr.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Settings → Troubleshooting → Send a report: what this box and Viewarr were doing (device details and the last part
 * of Viewarr's own log), with passwords, keys, tokens, addresses and names taken out, locked with the developer's
 * public key (only the matching private key, kept off this app, can open it), uploaded to a free file host that deletes
 * it after 72 hours, and a short code returned for the tester to share (e.g. "VR-mhdij5").
 */
object ReportSender {
    // Where the developer is told a report was sent (a push notification; only the code, version and box model go
    // there: the report itself is encrypted on the file host). Anyone could post here, but a fake code can't be read.
    // (From secrets.properties at build time, not the public code; empty in builds without it: no notice.)
    private val NOTIFY = BuildConfig.REPORT_NOTIFY.takeIf { it.isNotBlank() }?.let { "https://ntfy.sh/$it" }

    // The developer's public key (RSA 3072). Reports can only be read with its private key.
    private const val PUBLIC_KEY = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA0yjalM9OUZ5KlrIICKKncgq7GlTYdOKcxyWPiAhhQzmjssF0dPqhxt5fyqmnTTPeRd96JIdRb4+H+4R6nqzEWeXYIk0oFB5VZ3KDUiLMIqjgzPhnFNZj2MiwplluA8cmNisNTgju2hW32NjhJvtNb8Z6vtQ1mUqHsqrmmhtc63iY58e++8AixNd0nd5i5KlI0GNlQ7yNYDW2kYRL0gNU0DoEiRcb7S5ANSujYXQGHVOgsKvgpR8J2DoN/36jlNULMXotx228sR8Zpbq9PPjirpYGVVPlaKBU4Ju0aGicrWrtvttMxeWfWC8nEttK0Wln21X67LmD2JTBn88rrDpJywSrDPVm+WXm23u1hmesUJCoXwtsjI/SMXpoubb3SR7XFphHb9LEb/LRXgBPTrHTOplRpQDoXZeQkrW1CA3sUrAQqAGTtAi1JXMWbt6xlfyZpw4JLubUn0r3b/IKGr7EmB3m1SzrNZmSq8O23Eu4uwYQNXLA0yognXGw4RutD5SJAgMBAAE="

    /** The settings section of a report: each changed option with its chosen wording, then switched-off parts. */
    private fun settingsBlock(context: Context): String = buildString {
        val p = io.github.petebutty.viewarr.ui.theme.AppColors.palette
        val U = io.github.petebutty.viewarr.ui.theme.UiOpts
        appendLine(SETTINGS_HEADING)
        appendLine("Theme: main ${p.main.value.toString(16).take(8)}, highlight ${p.highlight.value.toString(16).take(8)}, wallpaper ${p.wallpaper}")
        val changed = p.opts.filter { (k, v) -> v != (U.defaults[k] ?: 0) }.toSortedMap()
        if (changed.isEmpty()) appendLine("(none)")
        changed.forEach { (k, v) -> appendLine("$k = ${U.choices[k]?.getOrNull(v) ?: v}") }
        // Settings' defaults that weren't touched but matter for layout, always listed.
        listOf(U.PICTURE_SIZE, U.TEXT_SIZE_V2, U.FONT, U.TIME_FORMAT, U.GUIDE_ART_PLACE, U.GUIDE_NEXT, U.GUIDE_ROWS, U.HERO_STYLE).filter { it !in changed }
            .forEach { k -> appendLine("$k = ${U.choices[k]?.getOrNull(p.opt(k)) ?: p.opt(k)} (default)") }
        if (p.titleHidden.isNotEmpty()) appendLine("Switched off / hidden parts: ${p.titleHidden.sorted().joinToString(", ")}")
        // Settings → Playback (its own store; read here, off the main thread: build runs on the IO dispatcher).
        runCatching {
            val store = io.github.petebutty.viewarr.data.settings.playbackStoreOf(context)
            val prefs: Map<androidx.datastore.preferences.core.Preferences.Key<*>, Any> = kotlinx.coroutines.runBlocking { store.data.first() }.asMap()
            if (prefs.isNotEmpty()) appendLine("Playback: " + prefs.entries.sortedBy { it.key.name }.joinToString(", ") { "${it.key.name}=${it.value}" })
        }
    }

    /** What the tester sees before sending: the report as it will be sent (already cleaned). */
    fun build(context: Context, sources: List<String>, note: String?, names: List<String> = emptyList(), withLog: Boolean = true): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val dm = context.resources.displayMetrics
        val head = buildString {
            appendLine("Viewarr ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Box: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Memory: ${mem.totalMem / 1_048_576} MB total, ${mem.availMem / 1_048_576} MB free${if (am?.isLowRamDevice == true) ", low-RAM device" else ""}")
            appendLine("Screen: ${dm.widthPixels}x${dm.heightPixels} at ${dm.densityDpi} dpi, TV font size ${(context.resources.configuration.fontScale * 100).toInt()}%")
            // Every Viewarr setting changed from its default, by name (Peter, 2026-11-19: "the log should have all his
            // settings"; a tester's guide panel lost its synopsis to a 12-hour clock). Only display and behaviour
            // choices: sign-ins, addresses and keys aren't settings here and never go in.
            runCatching { append(settingsBlock(context)) }
            appendLine("Sources: ${sources.ifEmpty { listOf("none") }.joinToString(", ")}")
            note?.takeIf { it.isNotBlank() }?.let { appendLine("Tester's note: $it") }
            appendLine(if (withLog) "----- log -----" else "----- a note: no log -----")
        }
        var text = clean(head + if (withLog) recentLog() else "")
        // Usernames (yours, your profiles') out too.
        names.filter { it.length >= 3 }.distinct().forEach { n -> text = text.replace(n, "[user]", ignoreCase = true) }
        return text
    }

    /**
     * Locks [report] and uploads it; the code to share, or an error message. With [context], a settings code for this
     * TV's settings goes up first and its line is added under the settings heading, so the developer can load the
     * tester's exact settings (SettingsShare). Left out if that upload fails. ([build] stays as it was: no uploads.)
     */
    suspend fun send(report: String, context: Context? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val withCode = context?.let { io.github.petebutty.viewarr.data.sync.SettingsShare.share(it).getOrNull() }
                ?.let { code -> withSettingsCode(report, code) } ?: report
            val blob = lock(withCode).toByteArray()
            upload("https://litterbox.catbox.moe/resources/internals/api.php", mapOf("reqtype" to "fileupload", "time" to "72h"), blob)
                ?.let { "VR-" + it.substringAfterLast('/').substringBefore('.') }
                ?: upload("https://catbox.moe/user/api.php", mapOf("reqtype" to "fileupload"), blob)
                    ?.let { "VC-" + it.substringAfterLast('/').substringBefore('.') }
                ?: error("Couldn't upload the report (no internet, or the file host is down)")
        }.onSuccess { code -> notify(code) }
    }

    /** [report] with "Settings code: …" under its settings heading (at the top if it has none). */
    private fun withSettingsCode(report: String, code: String): String {
        val line = "Settings code: $code"
        val i = report.indexOf(SETTINGS_HEADING)
        if (i < 0) return "$line\n$report"
        val end = report.indexOf('\n', i).let { if (it < 0) report.length else it + 1 }
        return report.substring(0, end) + line + "\n" + report.substring(end)
    }

    private const val SETTINGS_HEADING = "----- settings (changed from the defaults) -----"

    /** Tells the developer (push notification) that [code] was sent. Best effort: the code still works without it. */
    private fun notify(code: String) = runCatching {
        val url = NOTIFY ?: return@runCatching
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("User-Agent", "Viewarr-report/1")
            setRequestProperty("Title", "Viewarr report")
            setRequestProperty("Tags", "tv")
        }
        c.outputStream.use { it.write("New Viewarr report $code · ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL}".toByteArray()) }
        c.responseCode
    }

    /**
     * Viewarr's own log (this app's lines only), the most recent few thousand, without the TV maker's chatter. Some
     * TVs (TCL) write a line for every button press, frame and buffer into the app's log; a report from one was 95%
     * that, and Viewarr's own lines had been pushed out. Those tags are silenced in the read (and any left after it),
     * and the vendor's "perf" buffer isn't read at all.
     */
    private fun recentLog(): String = runCatching {
        val silenced = NOISY_TAGS.map { "$it:S" }
        val p = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "time", "-b", "main,system,crash", "-t", "4000", "--pid=${android.os.Process.myPid()}") +
                silenced + "*:V",
        )
        p.inputStream.bufferedReader().useLines { lines ->
            lines.filterNot { l -> NOISY_LINE.containsMatchIn(l) }.joinToString("\n")
        }.also { p.waitFor() }
    }.getOrElse { "(the log couldn't be read: ${it.message})" }

    /** The TV makers' tags that fill an app's log with nothing about the app (TCL, Amlogic, MediaTek, Android's own). */
    private val NOISY_TAGS = listOf(
        "Quality", "Looper", "ViewRootImpl", "GraphicBufferAllocator", "BufferPoolAccessor2.0", "MessageMonitor_reportData",
        "TclFrameworkFactory", "BufferQueueProducer", "BLASTBufferQueue", "Gralloc3", "Gralloc4", "chatty", "WindowOnBackDispatcher",
    )
    /** Lines from those (or tags with brackets, which logcat can't silence by name) that still came through. */
    private val NOISY_LINE = Regex("""^\S+ \S+ [A-Z]/(Quality|Looper|ViewRootImpl|VRI\[[^\]]*\]|GraphicBufferAllocator|BufferPoolAccessor2\.0|MessageMonitor_reportData|TclFrameworkFactory)\s*\(""")

    /** Passwords, keys, tokens, addresses, IPs, emails and long secrets out. */
    fun clean(text: String): String {
        var t = text
        // key=value / "key": value for anything secret-sounding, including run-together names (xcPassword=, plexToken=,
        // authToken=, X-Plex-Token: …, a data class's toString). No \b before the keyword: there's none between c and P.
        // A "Bearer " / "Basic " in front of the value goes too, or the token after it stayed.
        t = SECRET_KV.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + "[removed]" }
        // Emails before server names (the server part would otherwise go first and leave the name).
        t = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").replace(t, "[email]")
        // Web addresses: scheme and path kept, the server itself out (a user:password@ in front of it too).
        t = Regex("(?i)\\b(https?|rtsp|rtmp|udp|wss?)://[^\\s/\"'?#]+").replace(t) { m -> m.groupValues[1] + "://[server]" }
        // IPTV (Xtream) addresses carry the login in the path: /live/<user>/<password>/123.ts
        t = Regex("(?i)/(live|movie|series|timeshift)/[^/\\s\"']+/[^/\\s\"']+/").replace(t) { m -> "/" + m.groupValues[1] + "/[user]/[pass]/" }
        // …and the short form playlists use: http://host:port/<user>/<password>/<id>
        t = XTREAM_SHORT.replace(t) { m -> "://[server]/[user]/[pass]/" + m.groupValues[1] }
        // Server names quoted in errors ("Unable to resolve host \"my.server.tld\"").
        t = Regex("\"[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(:\\d+)?\"").replace(t, "\"[server]\"")
        // …and unquoted ("Failed to connect to tv.example.com/…", "Hostname x.y.z not verified"); Java / Android
        // package names in stack traces and file names (Foo.kt, report.txt) are left alone.
        t = HOST.replace(t) { m ->
            val name = m.value
            if (PACKAGE_PREFIXES.any { name.startsWith(it) } || m.groupValues[1] in FILE_ENDINGS) name else "[server]"
        }
        t = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b").replace(t, "[ip]")
        t = IPV6.replace(t, "[ip]")
        // Long keys / tokens left over (hex or base64, 24+ characters).
        t = Regex("\\b[A-Za-z0-9_\\-]{32,}\\b").replace(t, "[removed]")
        t = Regex("\\b[a-fA-F0-9]{24,}\\b").replace(t, "[removed]")
        return t
    }

    private val SECRET_KV = Regex(
        "(?i)([A-Za-z0-9_-]*(?:api[_-]?key|apikey|token|password|passwd|pass|secret|authorization|auth|session|cookie|bearer|device[_-]?code|user[_-]?code))" +
            "([\"']?\\s*[:=]\\s*[\"']?|\\s+)((?:bearer|basic)\\s+)?[^\\s&\"',;})]+"
    )
    /** The short Xtream form after the server went: ://[server]/<user>/<pass>/<id>, but not the app's own API paths. */
    private val XTREAM_SHORT = Regex(
        "(?i)://\\[server]/(?!(?:api|live|movie|series|timeshift|library|items|users|videos|audio|proxy|hls|output|rest|photo|web|emby|jellyfin|xmltv\\.php|player_api\\.php|get\\.php)/)" +
            "[^/\\s\"'?#]+/[^/\\s\"'?#]+/(\\d+)(?=[.\\s\"'?#]|$)"
    )
    /** A lower-case dotted name ending in letters, taken whole (not from the middle of Foo.bar or a.b.c). */
    private val HOST = Regex("(?<![\\w.-])[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.([a-z]{2,24})\\b(?![.\\w-])")
    private val PACKAGE_PREFIXES = listOf(
        "java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.", "okhttp3.", "okio.", "io.github.", "com.google.",
        "com.android.", "dalvik.", "libcore.", "sun.", "org.json.", "org.chromium.", "dagger.", "retrofit2.", "coil.", "coil3.",
        "com.squareup.", "is.xyz.",
    )
    private val FILE_ENDINGS = setOf(
        "kt", "java", "txt", "json", "xml", "apk", "so", "png", "jpg", "jpeg", "webp", "gif", "svg", "html", "js", "css", "log",
        "db", "m3u", "m3u8", "ts", "mp4", "mkv", "srt", "vtt", "php", "conf", "cfg", "properties", "dex", "jar", "zip", "gz",
    )
    /** IPv6: at least five groups, or a :: (not times like 12:34:56, nor Kotlin's Foo::bar). */
    private val IPV6 = Regex(
        "(?i)(?<![\\w:.])(?:(?:[0-9a-f]{1,4}:){4,7}[0-9a-f]{1,4}|(?:[0-9a-f]{1,4}:){1,6}:(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,5})?|::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6}))(?:%\\w+)?(?![\\w:])"
    )

    private fun lock(text: String): String {
        val rnd = SecureRandom()
        val aes = ByteArray(32).also(rnd::nextBytes)
        val nonce = ByteArray(12).also(rnd::nextBytes)
        val data = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, nonce)); doFinal(text.toByteArray())
        }
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)))
        // (SHA-256 for MGF1 too: Android's "OAEPWithSHA-256AndMGF1Padding" alone uses SHA-1 there.)
        val k = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
            init(Cipher.ENCRYPT_MODE, pub, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)); doFinal(aes)
        }
        fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        val json = "{\"v\":1,\"k\":\"${b64(k)}\",\"n\":\"${b64(nonce)}\",\"d\":\"${b64(data)}\"}"
        return b64(json.toByteArray())
    }

    /** A multipart form upload; the file's address, or null. (Settings codes upload their copy through this too.) */
    internal fun upload(url: String, fields: Map<String, String>, file: ByteArray, name: String = "report.txt"): String? = runCatching {
        val boundary = "viewarr" + System.nanoTime()
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("User-Agent", "Viewarr-report/1")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        c.outputStream.use { o ->
            fun w(s: String) = o.write(s.toByteArray())
            fields.forEach { (k, v) -> w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
            w("--$boundary\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"$name\"\r\nContent-Type: text/plain\r\n\r\n")
            o.write(file)
            w("\r\n--$boundary--\r\n")
        }
        val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText()?.trim().orEmpty()
        body.takeIf { c.responseCode in 200..299 && it.startsWith("https://") }
    }.getOrNull()
}
