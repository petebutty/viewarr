package io.github.petebutty.viewarr.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.BuildConfig
import io.github.petebutty.viewarr.data.local.GuideDatabases
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** One line on the About page: a name, a value and (optionally) a note under it. */
data class AboutRow(val title: String, val value: String, val note: String = "")

data class AboutInfo(
    val viewarr: List<AboutRow>,
    val device: List<AboutRow>,
    val storage: List<AboutRow>,
    val sources: List<AboutRow>,
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val guides: GuideDatabases,
) : ViewModel() {
    private val _info = MutableStateFlow<AboutInfo?>(null)
    val info = _info.asStateFlow()

    fun load() = viewModelScope.launch { _info.value = withContext(Dispatchers.IO) { gather() } }

    /** Settings → About: artwork comes back as it's needed. */
    fun clearArtwork() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            context.imageLoader.memoryCache?.clear()
            context.imageLoader.diskCache?.clear()
        }
        load()
    }

    /** Update files already installed (kept only to install from). */
    fun clearUpdates() = viewModelScope.launch {
        withContext(Dispatchers.IO) { File(context.cacheDir, "updates").deleteRecursively() }
        load()
    }

    private fun size(f: File?): Long = when {
        f == null || !f.exists() -> 0
        f.isFile -> f.length()
        else -> f.listFiles()?.sumOf { size(it) } ?: 0
    }

    private fun mb(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 10_000_000 -> "%.0f MB".format(bytes / 1e6)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }

    private fun dbSize(name: String): Long =
        listOf("", "-wal", "-shm", "-journal").sumOf { context.getDatabasePath(name + it).let { f -> if (f.exists()) f.length() else 0L } }

    private suspend fun gather(): AboutInfo {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val viewarr = listOfNotNull(
            AboutRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
            pkg?.let { AboutRow("Installed", df.format(Date(it.firstInstallTime))) },
            pkg?.let { AboutRow("Last updated", df.format(Date(it.lastUpdateTime))) },
            AboutRow("Source code", "github.com/petebutty/viewarr"),
        )

        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        val display = runCatching {
            @Suppress("DEPRECATION")
            (context.getSystemService(android.view.WindowManager::class.java)).defaultDisplay
        }.getOrNull()
        val mode = display?.mode
        val device = listOfNotNull(
            AboutRow("Model", "${Build.MANUFACTURER} ${Build.MODEL}".trim()),
            AboutRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            AboutRow("Memory", "${mb(mem.availMem)} free of ${mb(mem.totalMem)}", if (mem.lowMemory) "The box is low on memory right now." else ""),
            stat?.let { AboutRow("Storage", "${mb(it.availableBytes)} free of ${mb(it.totalBytes)}") },
            mode?.let { AboutRow("Screen", "${it.physicalWidth} × ${it.physicalHeight}, ${"%.0f".format(it.refreshRate)} Hz") },
            AboutRow("Processor", Build.SUPPORTED_ABIS.firstOrNull().orEmpty()),
        )

        // Storage: what Viewarr itself uses, by kind.
        val saved = sessions.saved.first()
        // The guide's databases are named from the guide's own key for a source (as GuideRepository keeps it).
        fun guideKey(x: io.github.petebutty.viewarr.data.session.Session) = "${x.serverUrl}|${x.username}|${x.isXc}|${x.isM3u}|${x.m3uEpg}"
        val labelFor = saved.associate { guides.nameFor(guideKey(it)) to it.label }
        val guideNames = guides.cachedNames()
        val guideRows = guideNames.mapIndexed { i, n -> AboutRow("TV guide: ${labelFor[n] ?: "a removed source (${i + 1})"}", mb(dbSize(n))) }
        val guidesTotal = guideNames.sumOf { dbSize(it) }
        val dbDir = context.getDatabasePath("x").parentFile
        val otherDbs = size(dbDir) - guidesTotal
        val artwork = size(File(context.cacheDir, "image_cache"))
        val updates = size(File(context.cacheDir, "updates"))
        val cacheTotal = size(context.cacheDir) + size(context.codeCacheDir) + size(context.externalCacheDir)
        val settings = size(File(context.filesDir, "datastore")) + size(File(context.dataDir, "shared_prefs"))
        val files = size(context.filesDir) - size(File(context.filesDir, "datastore"))
        val dataTotal = size(context.dataDir) + size(context.externalCacheDir) + size(context.getExternalFilesDir(null))
        val apk = runCatching { File(context.applicationInfo.sourceDir).length() + size(File(context.applicationInfo.nativeLibraryDir)) }.getOrDefault(0L)
        val storage = guideRows + listOf(
            AboutRow("Artwork (posters, logos, backdrops)", mb(artwork), "Downloaded again when needed if cleared."),
            AboutRow("Downloaded updates", mb(updates), "Installed already: safe to delete."),
            AboutRow("Other cache (player, previews)", mb((cacheTotal - artwork - updates).coerceAtLeast(0))),
            AboutRow("Other databases", mb(otherDbs.coerceAtLeast(0))),
            AboutRow("Settings and sign-ins", mb(settings)),
            AboutRow("Player and other files", mb(files.coerceAtLeast(0))),
            AboutRow("All of Viewarr's data", mb(dataTotal), "Plus the app itself: ${mb(apk)}."),
        )

        val active = sessions.session.first()
        val guidePrefs = context.getSharedPreferences("guide", Context.MODE_PRIVATE)
        val sources = buildList {
            add(AboutRow("Active source", active?.label ?: "None"))
            saved.forEach { s ->
                val name = guides.nameFor(guideKey(s))
                val cached = name in guideNames
                val counts = if (cached) guides.counts(name) else null
                val last = guidePrefs.getLong("last_sync|${guideKey(s)}", 0L)
                add(AboutRow(
                    s.label + if (s.id == active?.id) " (active)" else "",
                    if (counts != null) "%,d channels · %,d programmes".format(counts.first, counts.second) else "Guide not kept (loads when switched to)",
                    if (cached && last > 0) "Guide updated ${df.format(Date(last))}" else "",
                ))
            }
        }
        return AboutInfo(viewarr, device, storage, sources)
    }
}

private class Value(override val label: String) : RemoteChoice

/** Settings → About: version, this TV, what Viewarr stores (and clearing it), and the Live TV sources. */
@Composable
fun AboutSettingsScreen(onBack: () -> Unit, vm: AboutViewModel = hiltViewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    // Row titles must be unique on a page (they key its list).
    fun rows(list: List<AboutRow>) = list.mapIndexed { i, r ->
        val title = if (list.take(i).any { it.title == r.title }) "${r.title} (${i + 1})" else r.title
        SettingItem(title, r.note, emptyList(), Value(r.value), action = {}, onSelect = {})
    }
    // Always the same four sections (the page sets itself up for its sections once); filled in when gathered.
    val i = info ?: AboutInfo(emptyList(), emptyList(), emptyList(), emptyList())
    val loading = info == null
    val categories = listOf(
        SettingCategory("Viewarr", if (loading) "Gathering details…" else "This version of Viewarr.", rows(i.viewarr)),
        SettingCategory("This TV", if (loading) "Gathering details…" else "The box Viewarr is running on.", rows(i.device)),
        SettingCategory(
            "Storage", if (loading) "Measuring…" else "What Viewarr keeps on this TV.",
            rows(i.storage) + if (loading) emptyList() else listOf(
                SettingItem("Clear artwork", "Posters, logos and backdrops are fetched again as you browse.", emptyList(), Value("OK to clear"), action = { vm.clearArtwork() }, onSelect = {}),
                SettingItem("Delete downloaded updates", "Update files that have already been installed.", emptyList(), Value("OK to delete"), action = { vm.clearUpdates() }, onSelect = {}),
            ),
        ),
        SettingCategory("Live TV sources", "Your saved sources and their guides (the three most recent are kept on the TV).", rows(i.sources)),
    )
    SettingsPage(title = "About", categories = categories, onBack = onBack)
}
