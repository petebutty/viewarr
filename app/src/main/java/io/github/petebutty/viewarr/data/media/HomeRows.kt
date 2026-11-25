package io.github.petebutty.viewarr.data.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.repository.GuideRepository
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Viewarr's rows on the TV's home screen (2026-11-20: "add TV channels to Projectivy home", then "catalogues,
 * libraries, IPTV: then we can choose what to have"). Switched on one by one in Settings → Live TV → Home screen rows:
 * a channel group (a card per channel: its logo, what's on now, opening that channel), or any row of the Movies or
 * Shows page (a catalogue, a library's row, an IPTV row: a card per title, opening its page). These are Android TV's own home-screen rows, which
 * launchers such as Projectivy and Google's show; the launcher decides whether a row is shown and where.
 *
 * A group that's on is kept as "homerow:<group, lower case>" among the appearance parts (present = on, as the
 * notification switches are). Refreshed when Viewarr starts and every 15 minutes, whether Viewarr is open or not: a
 * small background job ([HomeRowsJob]) wakes for a few seconds to do it (2026-11-20: rather than keeping the
 * whole app running, which would hold its memory all the time and be closed by Android anyway).
 */
@Singleton
class HomeRows @Inject constructor(
    @ApplicationContext private val context: Context,
    private val guide: GuideRepository,
    private val sessions: SessionStore,
    private val theme: io.github.petebutty.viewarr.data.settings.ThemeStore,
    private val catalog: CatalogRepository,
    private val programArt: ProgramArt,
) {
    private val lock = Mutex()

    suspend fun refresh() = lock.withLock {
        runCatching { withContext(Dispatchers.IO) { publish() } }.onFailure { Log.w(TAG, "home rows refresh failed: $it") }
    }

    private suspend fun publish() {
        val cr = context.contentResolver
        // (Read from the saved settings themselves: the background job runs with no screen, so nothing has loaded them.)
        val parts = theme.palette.first().titleHidden
        val groups = parts.filter { it.startsWith(PART) }.map { it.removePrefix(PART) }.toSet()
        val media = parts.filter { it.startsWith(MEDIA_PART) }.map { it.removePrefix(MEDIA_PART) }.toSet()
        val wanted = groups.map { "tv:$it" }.toSet() + media.map { "m:$it" }
        // The background job runs only while there's a row to keep fresh.
        HomeRowsJob.schedule(context, wanted.isNotEmpty())
        // This app's rows now (only its own come back): row key → row id.
        val rows = HashMap<String, Long>()
        val names = HashMap<Long, String>()
        cr.query(TvContractCompat.Channels.CONTENT_URI, arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID, TvContractCompat.Channels.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(1)?.takeIf { it.startsWith(ROW) }?.let { rows[it.removePrefix(ROW)] = c.getLong(0); names[c.getLong(0)] = c.getString(2).orEmpty() }
        }
        // A row switched off (or gone): it goes.
        rows.filterKeys { it !in wanted }.forEach { (_, id) -> runCatching { cr.delete(TvContractCompat.buildChannelUri(id), null, null) } }
        if (wanted.isEmpty()) return
        // Every row there already gets its name on its icon now (not only when its cards are next written).
        rows.filterKeys { it in wanted }.forEach { (_, id) -> names[id]?.takeIf { it.isNotBlank() }?.let { n -> labelRow(id, n.removeSuffix(" · Films").removeSuffix(" · Shows")) } }
        var cards = 0
        HomeRowArt.tidy(context)
        if (groups.isNotEmpty()) cards += publishChannels(cr, groups, rows)
        Log.i(TAG, "home rows: channel rows written ($cards cards)")
        // Films and shows change slowly (the catalogues' "New" moves on over days) and cost requests to your servers and
        // addons: once a day (2026-11-20; it was every 6 hours), or straight away
        // when a row has just been switched on (it has no row on the home screen yet).
        val prefs = context.getSharedPreferences("home_rows", Context.MODE_PRIVATE)
        // (Also straight away when the cards' look has changed: [CARD_STYLE].)
        val due = System.currentTimeMillis() - prefs.getLong("media_at", 0L) > 24 * 3_600_000L || media.any { "m:$it" !in rows } ||
            prefs.getInt("media_style", 0) != CARD_STYLE
        if (media.isNotEmpty() && due) {
            cards += publishMedia(cr, media, rows)
            prefs.edit().putLong("media_at", System.currentTimeMillis()).putInt("media_style", CARD_STYLE).apply()
        }
        Log.i(TAG, "home rows: ${wanted.size} rows, $cards cards written")
    }

    /** The row for [key] on the home screen: the one there already, or a new one named [name]. */
    private fun rowFor(cr: android.content.ContentResolver, rows: Map<String, Long>, key: String, name: String, opens: String): Long? =
        rows[key] ?: runCatching {
            val row = Channel.Builder().setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(name).setInternalProviderId(ROW + key).setAppLinkIntentUri(Uri.parse(opens)).build()
            val id = android.content.ContentUris.parseId(cr.insert(TvContractCompat.Channels.CONTENT_URI, row.toContentValues())!!)
            // (Asks the launcher to show it; a launcher with its own list of rows, as Projectivy has, lists it there.)
            runCatching { TvContractCompat.requestChannelBrowsable(context, id) }
            id
        }.getOrNull()

    /**
     * The row's icon beside it on the home screen: the service's own logo for a service's row (Netflix's for
     * "Netflix · New"), else its name on a tile. Drawn again when the name or the look changes.
     */
    private suspend fun labelRow(rowId: Long, name: String) {
        val prefs = context.getSharedPreferences("home_rows", Context.MODE_PRIVATE)
        val mark = "v2|$name"
        if (prefs.getString("icon_$rowId", null) == mark) return
        val icon = runCatching { catalog.serviceLogo(name) }.getOrNull()?.let { (url, square) -> HomeRowArt.serviceIcon(url, square) }
            ?: HomeRowArt.rowIcon(name)
        runCatching { ChannelLogoUtils.storeChannelLogo(context, rowId, icon) }
            .onSuccess { prefs.edit().putString("icon_$rowId", mark).apply() }
    }

    /**
     * A row's cards, all removed before it's written again (replaced outright: an update keeps old values for anything
     * not set this time). One by one: a delete by row was silently ignored on some boxes, so every refresh added
     * another full set (680 cards in one row, the oldest shown first; 2026-11-20).
     */
    private fun clear(cr: android.content.ContentResolver, rowId: Long) {
        val ids = ArrayList<Long>()
        runCatching {
            cr.query(TvContractCompat.PreviewPrograms.CONTENT_URI, arrayOf(TvContractCompat.PreviewPrograms._ID, TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getLong(1) == rowId) ids += c.getLong(0)
            }
        }
        ids.forEach { id -> runCatching { cr.delete(TvContractCompat.buildPreviewProgramUri(id), null, null) } }
    }

    /** Live TV: a row per channel group, a card per channel with what's on now. */
    private suspend fun publishChannels(cr: android.content.ContentResolver, wanted: Set<String>, rows: Map<String, Long>): Int {
        val session = sessions.session.first() ?: return 0
        val groups = guide.observeGroups().first()
        val now = System.currentTimeMillis()
        var cards = 0
        for (key in wanted) {
            val group = groups.firstOrNull { it.name.trim().lowercase() == key } ?: continue
            val rowName = io.github.petebutty.viewarr.ui.common.GroupNames.display(group.name)
            val rowId = rowFor(cr, rows, "tv:$key", rowName, "${io.github.petebutty.viewarr.BuildConfig.LINK_SCHEME}://guide") ?: continue
            labelRow(rowId, rowName)
            val channels = guide.observeChannels(group.id).first().take(MAX)
            val onNow = guide.programsForChannels(channels.map { it.id }, now, now + 1).associateBy { it.channelId }
            clear(cr, rowId)
            channels.forEachIndexed { i, ch ->
                val logo = DeepLink.publicImage(io.github.petebutty.viewarr.ui.guide.channelLogo(session.serverUrl, ch))
                // What's on now, when its picture can be found (the guide's own, else TMDB's by title): the programme
                // filling the card with the channel's logo on it. Else the logo whole on a dark card (the launcher cut
                // logos to fill its wide cards).
                val card = onNow[ch.id]?.let { kotlinx.coroutines.withTimeoutOrNull(20_000) { programmeCardFor(ch.uuid, it, logo) } } ?: cardFor(ch.uuid, logo, ch.name)
                val p = PreviewProgram.Builder().setChannelId(rowId)
                    .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                    .setTitle(ch.name)
                    .setDescription(onNow[ch.id]?.title.orEmpty())
                    .setInternalProviderId(ch.uuid)
                    .setIntentUri(Uri.parse("${io.github.petebutty.viewarr.BuildConfig.LINK_SCHEME}://channel?uuid=${ch.uuid}"))
                    .setWeight(MAX - i)
                    .setLive(true)
                (card ?: logo?.let(Uri::parse))?.let { p.setPosterArtUri(it).setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9) }
                // The channel itself playing in the card while it's highlighted (launchers that play previews, such as
                // Projectivy). Only Dispatcharr's own stream address, which carries no sign-in; never a provider's.
                if (!session.isDirect) p.setPreviewVideoUri(Uri.parse("${session.serverUrl.trimEnd('/')}/proxy/ts/stream/${ch.uuid}"))
                runCatching { cr.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, p.build().toContentValues()) }.onSuccess { cards++ }
                    .onFailure { Log.w(TAG, "home row card '${ch.name}': $it") }
            }
        }
        return cards
    }

    /**
     * Films and shows: any row of the Movies or Shows page (a catalogue, a library's row, an IPTV row), a card per
     * title opening its page in Viewarr. [wanted]: "<Movie|Show>|<row id>".
     */
    private suspend fun publishMedia(cr: android.content.ContentResolver, wanted: Set<String>, rows: Map<String, Long>): Int {
        var cards = 0
        // Both pages' rows first, then written in one order (a new row is added to the home screen in the order it's
        // made): the services in [JUST_ADDED_ORDER] order, films then shows for each, then everything else.
        val found = ArrayList<Pair<MediaType, MediaRow>>()
        for (type in MediaType.entries) {
            val ids = wanted.filter { it.startsWith(type.name + "|") }.map { it.substringAfter('|') }.toSet()
            if (ids.isEmpty()) continue
            // The page's rows as they load (each source in its own time): wait for the ones asked for, at most 45 s.
            // (Loaded apart and let go after the wait, not cancelled and waited for: a source busy on the network
            // finishes in its own time, and waiting for it held up the whole refresh. 2026-11-20.)
            val latest = java.util.concurrent.atomic.AtomicReference<List<MediaRow>>(emptyList())
            val load = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
                catalog.rows(type).firstOrNull { st -> latest.set(st.rows); st.done || ids.all { id -> st.rows.any { it.id == id && it.items.isNotEmpty() } } }
            }
            kotlinx.coroutines.withTimeoutOrNull(45_000) { load.await() }
            load.cancel()
            Log.i(TAG, "home rows: ${type.name} rows loaded, ${latest.get().count { it.id in ids }} of ${ids.size} wanted")
            latest.get().filter { it.id in ids && it.items.isNotEmpty() }.forEach { found += type to it }
        }
        fun rank(p: Pair<MediaType, MediaRow>): Int {
            val i = JUST_ADDED_ORDER.indexOfFirst { n -> p.second.title.startsWith("$n ·") }
            return (if (i < 0) 1_000 else i * 2) + p.first.ordinal
        }
        for ((type, row) in found.sortedBy(::rank)) {
            val what = if (type == MediaType.Movie) "movies" else "shows"
            // (The name says the page too, as "Netflix · New" is on both Movies and Shows.)
            val rowName = row.title + if (type == MediaType.Movie) " · Films" else " · Shows"
            val rowId = rowFor(cr, rows, "m:${type.name}|${row.id}", rowName, "${io.github.petebutty.viewarr.BuildConfig.LINK_SCHEME}://page?name=$what") ?: continue
            labelRow(rowId, row.title)
            clear(cr, rowId)
            row.items.take(MEDIA_MAX).forEachIndexed { i, item ->
                // A wide card (backdrop and title logo, drawn here), else a picture the home screen can load: the
                // title's own when it needs no sign-in, else TMDB's. (At most 20 s a card: one slow picture mustn't
                // hold up the rest.)
                val art = kotlinx.coroutines.withTimeoutOrNull(20_000) { wideCard(item) }?.let { it.toString() to true }
                    ?: DeepLink.publicImage(item.poster)?.let { it to false } ?: DeepLink.publicImage(item.backdrop)?.let { it to true }
                    ?: runCatching { catalog.publicArt(item) }.getOrNull()
                val p = PreviewProgram.Builder().setChannelId(rowId)
                    .setType(if (type == MediaType.Movie) TvContractCompat.PreviewPrograms.TYPE_MOVIE else TvContractCompat.PreviewPrograms.TYPE_TV_SERIES)
                    .setTitle(item.title)
                    .setInternalProviderId(item.key)
                    .setIntentUri(DeepLink.uriFor(item))
                    .setWeight(MEDIA_MAX - i)
                (item.subtitle?.let { "$it\n" }.orEmpty() + item.overview.orEmpty()).takeIf { it.isNotBlank() }?.let { p.setDescription(it) }
                item.year?.let { p.setReleaseDate(it.toString()) }
                if (art != null) p.setPosterArtUri(Uri.parse(art.first))
                    .setPosterArtAspectRatio(if (art.second) TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9 else TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                runCatching { cr.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, p.build().toContentValues()) }.onSuccess { cards++ }
                    .onFailure { Log.w(TAG, "home row card '${item.title}': $it") }
            }
            Log.i(TAG, "home rows: '$rowName' written")
        }
        return cards
    }

    /** A channel's card for the programme on it now (drawn once per programme); null: no picture for it. */
    private suspend fun programmeCardFor(uuid: String, p: io.github.petebutty.viewarr.data.local.GuideProgram, logo: String?): Uri? {
        val file = "p_" + uuid.filter { it.isLetterOrDigit() || it == '-' }.take(40) + "_" + p.rowId + ".jpg"
        if (java.io.File(HomeRowArt.dir(context), file).isFile) return HomeRowArt.uriFor(file)
        val picture = DeepLink.publicImage(p.image) ?: runCatching { programArt.imageFor(p.title) }.getOrNull()?.let { DeepLink.publicImage(it) } ?: return null
        return HomeRowArt.programmeCard(context, file, picture, logo, p.title)
    }

    /** A film's or show's wide card (its backdrop with its title logo), kept a week; null: no backdrop to draw on. */
    private suspend fun wideCard(item: MediaItem): Uri? {
        val key = item.type.name + "_" + (item.tmdbId?.toString() ?: item.imdbId ?: item.key)
        val file = java.io.File(HomeRowArt.dir(context), "t_" + key.filter { it.isLetterOrDigit() || it == '-' }.take(80) + ".jpg")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < 7 * 86_400_000L) return HomeRowArt.uriFor(file.name)
        val backdrop = DeepLink.publicImage(item.backdrop) ?: runCatching { catalog.publicArt(item) }.getOrNull()?.takeIf { it.second }?.first ?: return null
        val logo = runCatching { catalog.logoFor(item) }.getOrNull()?.let { DeepLink.publicImage(it) }
        return HomeRowArt.titleCard(context, key, backdrop, logo, item.title)
    }

    /** A channel's card picture, kept for a day (logos rarely change; drawing them needs each logo fetched). */
    private fun cardFor(key: String, logo: String?, name: String): Uri? {
        val file = java.io.File(HomeRowArt.dir(context), "ch_" + key.filter { it.isLetterOrDigit() || it == '-' }.take(60) + ".png")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < 86_400_000L) return HomeRowArt.uriFor(file.name)
        return HomeRowArt.channelCard(context, key, logo, name)
    }

    companion object {
        private const val TAG = "HomeRows"
        private const val MAX = 15
        /** A row's own id in the TV's list (so Viewarr finds its rows again). */
        private const val ROW = "viewarr-row:"
        private const val MEDIA_MAX = 15
        /** The film and show cards' look: raised when it changes, so rows already there are drawn again at once. */
        private const val CARD_STYLE = 4
        const val PART = "homerow:"
        /** A Movies / Shows row on the home screen: "homerowm:<Movie|Show>|<row id>". */
        const val MEDIA_PART = "homerowm:"

        /** Whether any row is switched on (as the settings have loaded them). */
        fun anyOn(): Boolean = AppColors.palette.titleHidden.any { it.startsWith(PART) || it.startsWith(MEDIA_PART) }
        fun isOn(group: String) = (PART + group.trim().lowercase()) in AppColors.palette.titleHidden
        fun mediaPart(type: MediaType, rowId: String) = "$MEDIA_PART${type.name}|$rowId"
        fun isOn(type: MediaType, rowId: String) = mediaPart(type, rowId) in AppColors.palette.titleHidden

        /** The streaming services in the order their "Just added" rows go on the home screen (as the plugin names them). */
        val JUST_ADDED_ORDER = listOf("Netflix", "Prime Video", "Disney+", "Apple TV+", "HBO Max", "NOW", "BBC iPlayer", "ITVX",
            "Paramount+", "Sky Go", "UKTV Play", "My5", "Discovery+", "Crunchyroll", "Shudder")

        /** Every service's "Just added" rows (films, then shows), in [JUST_ADDED_ORDER]: their settings parts. */
        fun justAddedParts(): List<String> = JUST_ADDED_ORDER.flatMap { n ->
            val slug = n.lowercase().filter { it.isLetterOrDigit() }
            MediaType.entries.map { t -> mediaPart(t, "justadded-$slug-${t.name}") }
        }

        /** Viewarr's home-screen rows the launcher isn't showing yet, oldest first (the order they were made). */
        fun notShown(context: Context): List<Long> = runCatching {
            val out = ArrayList<Long>()
            context.contentResolver.query(TvContractCompat.Channels.CONTENT_URI,
                arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID, TvContractCompat.Channels.COLUMN_BROWSABLE),
                null, null, TvContractCompat.Channels._ID)?.use { c ->
                while (c.moveToNext()) if (c.getString(1)?.startsWith(ROW) == true && c.getInt(2) == 0) out += c.getLong(0)
            }
            out
        }.getOrDefault(emptyList())

        /** Where a Movies / Shows row comes from, for the settings list: IPTV, Libraries or Catalogues. */
        fun kindOf(rowId: String): String = when {
            rowId.startsWith("xc-") -> "IPTV"
            rowId.startsWith("stremio-") || rowId.startsWith("tmdb-") || rowId.startsWith("trakt-reco") || rowId.startsWith("justadded-") -> "Catalogue"
            else -> "Library"
        }
    }
}
