package io.github.petebutty.viewarr.ui.music

import io.github.petebutty.viewarr.data.music.MusicLibrary
import io.github.petebutty.viewarr.data.music.Song
import io.github.petebutty.viewarr.data.music.TasteArtist
import io.github.petebutty.viewarr.data.music.Taste
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

/*
 * "Your Mix" and the daily mixes: songs picked from what this person plays, then widened with each song's similar
 * songs where the server knows them (Navidrome); a server that doesn't say gets the same artists' albums mixed with
 * random songs from the library instead. A mix is built once a day (the day is the seed), so it doesn't change on
 * every visit.
 */

/** A home row with the songs behind any mix tiles in it (by card id), and where it sorts among rows of its kind. */
internal class RowOut(val row: MusicRow, val songs: Map<String, List<Song>> = emptyMap(), val order: Int = 0)

private const val MIX_SIZE = 50

private class Seeds(val seeds: List<Song>, val pool: List<Song>)

/** The tiles row: Your Mix (the top artists), then three daily mixes, each from one artist (picked for the day). */
internal suspend fun mixesRow(library: MusicLibrary, taste: Taste, day: Long, gentle: Semaphore): RowOut? = coroutineScope {
    val rng = Random(day)
    val top = taste.artists.take(8)
    val daily = top.shuffled(rng).take(3)
    val jobs = listOf(Triple("mix1", "Your Mix", top.take(6))) + daily.mapIndexed { i, a -> Triple("mix${i + 2}", "Mix · ${a.name}", listOf(a)) }
    val built = jobs.map { (id, title, artists) ->
        async { runCatching { buildMix("$id:$day", title, artists, if (artists.size > 1) 1 else 3, rng, library, gentle) }.getOrNull() }
    }.awaitAll().filterNotNull()
    if (built.isEmpty()) null
    else RowOut(MusicRow("mix", "Made for you", built.map { it.first }, RowLook.Tiles), built.associate { (c, songs) -> c.id to songs })
}

/** One mix: its tile and its songs; null when too little came of it. */
private suspend fun buildMix(
    id: String, title: String, artists: List<TasteArtist>, per: Int, rng: Random, library: MusicLibrary, gentle: Semaphore,
): Pair<MusicCard, List<Song>>? {
    val s = seedsFor(library, artists, per, rng, gentle)
    if (s.seeds.isEmpty()) return null
    val similar = coroutineScope { s.seeds.map { seed -> async { gentle.withPermit { runCatching { library.similarOnly(seed, 12) }.getOrDefault(emptyList()) } } }.awaitAll() }
    // The server couldn't say what's alike (or hardly): the artists' own albums, mixed with random songs.
    val fill = if (similar.sumOf { it.size } >= 15) emptyList()
    else interleave(listOf(s.pool.shuffled(rng), gentle.withPermit { runCatching { library.randomSongs(20) }.getOrDefault(emptyList()) }))
    val songs = assemble(s.seeds, similar, fill, rng)
    if (songs.size < 8) return null
    val names = artists.map { it.name }
    val meta = songCount(songs.size) + " · " + names.take(3).joinToString(", ") + if (names.size > 3) " and more" else ""
    return mixCard(id, title, if (artists.size > 1) songCount(songs.size) else "Because you play ${names[0]}", meta, songs) to songs
}

/** A tile for [songs]: a 2×2 of covers from different albums. */
internal fun mixCard(id: String, title: String, sub: String, meta: String, songs: List<Song>) = MusicCard(
    MusicKind.Mix, id, title, sub, null,
    mosaic = songs.distinctBy { it.albumId ?: it.album }.mapNotNull { it.art?.small }.distinct().take(4), meta = meta,
)

/** Songs to start from: [per] from each artist's best albums, and every song of those albums (for the fallback). */
private suspend fun seedsFor(library: MusicLibrary, artists: List<TasteArtist>, per: Int, rng: Random, gentle: Semaphore): Seeds = coroutineScope {
    val lists = artists.map { ta ->
        async { ta.albums.take(3).map { a -> gentle.withPermit { runCatching { library.album(a.id)?.second }.getOrNull().orEmpty() } }.filter { it.isNotEmpty() } }
    }.awaitAll()
    val seeds = ArrayList<Song>()
    lists.forEach { albums ->
        repeat(per) { k -> albums.getOrNull(k % albums.size.coerceAtLeast(1))?.random(rng)?.let { s -> if (seeds.none { it.id == s.id }) seeds += s } }
    }
    Seeds(seeds, lists.flatten().flatten())
}

/** Round robin through [lists]: one from each in turn. */
private fun interleave(lists: List<List<Song>>): List<Song> = buildList {
    val n = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until n) lists.forEach { l -> l.getOrNull(i)?.let(::add) }
}

/** The seeds each followed by what's like them, taken in turn; one copy of each song; lightly shuffled; no artist three times running. */
internal fun assemble(seeds: List<Song>, similar: List<List<Song>>, fill: List<Song>, rng: Random): List<Song> {
    val merged = interleave(seeds.mapIndexed { i, s -> listOf(s) + similar.getOrElse(i) { emptyList() } } + listOf(fill))
    val seen = HashSet<String>()
    val unique = merged.filter { seen.add(it.id) && seen.add(MusicLibrary.norm(it.artist) + "|" + MusicLibrary.norm(it.title)) }
    return spreadArtists(unique.chunked(4).flatMap { it.shuffled(rng) }).take(MIX_SIZE)
}

/** In order, but a song whose artist already has the last two places waits for a later gap (or is dropped). */
internal fun spreadArtists(songs: List<Song>): List<Song> {
    val out = ArrayList<Song>()
    val later = ArrayList<Song>()
    fun same(a: Song, b: Song) = MusicLibrary.norm(a.artist) == MusicLibrary.norm(b.artist)
    fun fits(s: Song) = out.size < 2 || !(same(out[out.size - 1], s) && same(out[out.size - 2], s))
    for (s in songs) {
        if (fits(s)) out += s else later += s
        later.firstOrNull { fits(it) }?.let { out += it; later.remove(it) }
    }
    return out
}
