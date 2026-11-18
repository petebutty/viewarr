package io.github.petebutty.viewarr.ui.music

import io.github.petebutty.viewarr.data.media.LibraryQuery
import io.github.petebutty.viewarr.data.music.Album
import io.github.petebutty.viewarr.data.music.AlbumList
import io.github.petebutty.viewarr.data.music.MusicLibrary
import io.github.petebutty.viewarr.data.music.Playlist
import io.github.petebutty.viewarr.data.music.Song
import io.github.petebutty.viewarr.data.music.Taste
import io.github.petebutty.viewarr.data.music.tasteOf
import io.github.petebutty.viewarr.data.music.decades
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/*
 * The Music home's rows, built from what the music servers already give (their recently played / newest / most
 * played / random / favourite album lists, genres, decades and playlists): nothing new is asked of a server. Every
 * call runs side by side and each is small; a row that comes back empty simply isn't shown. The view model keeps the
 * result for the session (built again after a while, behind the rows already showing).
 */

internal const val NO_MUSIC = "No music yet. Connect Navidrome, Jellyfin or Plex in Settings → Accounts & sources."

internal data class HomeBuild(val rows: List<MusicRow>, val starred: Set<String>, val taste: Taste)

/** Where each row sits on the page, whichever of them arrives first (the personal rows come in behind these). */
private fun homeRank(id: String) = when {
    id == "mix" -> 0; id == "jump" -> 1; id.startsWith("because") -> 2; id == "new" -> 3; id == "hidden" -> 4; id == "rediscover" -> 5
    id == "fav" -> 6; id == "most" -> 7; id == "random" -> 8; id == "genres" -> 9; id == "decades" -> 10; id == "playlists" -> 11
    else -> 99
}

/** The rows in page order: the cheap ones and the personal ones (among those of one kind, the [RowOut.order]). */
internal fun orderedRows(base: List<MusicRow>, extra: Collection<RowOut>): List<MusicRow> =
    (base.map { it to 0 } + extra.map { it.row to it.order }).sortedWith(compareBy({ homeRank(it.first.id) }, { it.second })).map { it.first }

/**
 * The cheap rows, in the order they show (the personal rows come in among them: MusicPersonalRows.kt). [gentle] limits
 * the small look-ups to a few at a time; [onTaste] is called as soon as the lists that say what's played are in.
 */
internal suspend fun buildHomeRows(library: MusicLibrary, gentle: Semaphore, onTaste: (Taste) -> Unit): HomeBuild = coroutineScope {
    suspend fun <T> quiet(f: suspend () -> List<T>): List<T> = runCatching { f() }.getOrDefault(emptyList())
    val recent = async { quiet { library.albums(AlbumList.Recent, 60).first } }
    val newest = async { quiet { library.albums(AlbumList.Newest, 30).first } }
    val frequent = async { quiet { library.albums(AlbumList.Frequent, 60).first } }
    val favourites = async { quiet { library.albums(AlbumList.Favourites, 30).first } }
    val starred = async { runCatching { library.starredIds() }.getOrDefault(emptySet()) }
    val randomSongs = async { quiet { library.randomSongs(30) } }
    val playlists = async { quiet { library.playlists() } }
    val genres = async { genreTiles(library, gentle) }
    val decades = async { decadeTiles(library, gentle) }
    // Favourite artists: only looked for when something is starred (the artists list can be long).
    val favArtists = async { val s = starred.await(); if (s.isEmpty()) emptyList() else quiet { library.artists() }.filter { it.id in s } }
    val playlistCards = async { playlistCards(library, playlists.await(), gentle) }

    val recentL = recent.await()
    val frequentL = frequent.await()
    val favouriteL = favourites.await()
    val starredIds = starred.await()
    // What's played is known now: the personal rows start while the picture tiles below are still being made.
    val taste = tasteOf(frequentL, recentL, favouriteL, starredIds)
    onTaste(taste)
    val recentIds = recentL.map { it.id }.toHashSet()
    val rows = listOf(
        MusicRow("jump", "Jump back in", recentL.take(20).map(::homeAlbumCard)),
        MusicRow("new", "New in your library", newest.await().map(::homeAlbumCard)),
        // Albums you played a lot, but not lately.
        MusicRow("rediscover", "Rediscover", frequentL.filter { it.id !in recentIds }.shuffled().take(20).map(::homeAlbumCard)),
        MusicRow("fav", "Your favourites", favArtists.await().map(::artistCard) + favouriteL.map(::homeAlbumCard)),
        MusicRow("most", "Most played", frequentL.take(20).map(::homeAlbumCard)),
        MusicRow("random", "Random picks", randomPicks(library, randomSongs.await())),
        MusicRow("genres", "Genres", genres.await(), RowLook.Tiles),
        MusicRow("decades", "Decades", decades.await(), RowLook.Tiles),
        MusicRow("playlists", "Playlists", playlistCards.await()),
    ).filter { it.cards.isNotEmpty() }
    HomeBuild(rows, starredIds, taste)
}

/** An album card with the hero's longer line: artist · year · songs. */
internal fun homeAlbumCard(a: Album) = albumCard(a).copy(
    meta = listOfNotNull(a.artist.takeIf { it.isNotBlank() }, a.year?.toString(), a.songCount.takeIf { it > 0 }?.let(::songCount)).joinToString(" · "),
)

/** Random songs as the albums they're from (one card per album); too few of those, a random album list instead. */
private suspend fun randomPicks(library: MusicLibrary, songs: List<Song>): List<MusicCard> {
    val fromSongs = songs.filter { it.albumId != null }.distinctBy { it.albumId }.map { s ->
        MusicCard(MusicKind.Album, s.albumId!!, s.album, s.artist, s.art, meta = listOfNotNull(s.artist.takeIf { it.isNotBlank() }, s.year?.toString()).joinToString(" · "))
    }
    if (fromSongs.size >= 6) return fromSongs.take(20)
    return runCatching { library.albums(AlbumList.Random, 20).first }.getOrDefault(emptyList()).map(::homeAlbumCard)
}

/** The biggest genres (up to 20) as tiles, each with the covers of its first four albums. */
private suspend fun genreTiles(library: MusicLibrary, gentle: Semaphore): List<MusicCard> = coroutineScope {
    val genres = runCatching { library.genres() }.getOrDefault(emptyList()).take(20)
    genres.map { (name, count) ->
        async {
            val covers = gentle.withPermit { runCatching { library.albumsInGenre(name, 4).first }.getOrDefault(emptyList()) }.mapNotNull { it.art?.small }
            MusicCard(MusicKind.Genre, name, name, if (count > 0) albumsText(count) else "", null, mosaic = covers.take(4))
        }
    }.awaitAll()
}

/** The decades (2020s back to the 1950s) that have albums, each tile with four of its newest covers. */
private suspend fun decadeTiles(library: MusicLibrary, gentle: Semaphore): List<MusicCard> = coroutineScope {
    decades().map { v ->
        async {
            val q = LibraryQuery("year", true, filters = mapOf("decade" to listOf(v)))
            val albums = gentle.withPermit { runCatching { library.browseAlbums(q, 4).first }.getOrDefault(emptyList()) }
            if (albums.isEmpty()) null else MusicCard(MusicKind.Decade, v.id, v.label, "", null, mosaic = albums.mapNotNull { it.art?.small }.take(4))
        }
    }.awaitAll().filterNotNull()
}

/** Every playlist; the first few without a cover of their own get the covers of their first songs as a mosaic. */
private suspend fun playlistCards(library: MusicLibrary, playlists: List<Playlist>, gentle: Semaphore): List<MusicCard> = coroutineScope {
    val mosaicFor = playlists.filter { it.art == null }.take(10).map { it.id }.toHashSet()
    playlists.map { p ->
        async {
            val card = playlistCard(p)
            if (p.id !in mosaicFor) card
            else {
                val songs = gentle.withPermit { runCatching { library.playlist(p.id)?.second }.getOrNull().orEmpty() }
                card.copy(mosaic = songs.mapNotNull { it.art?.small }.distinct().take(4))
            }
        }
    }.awaitAll()
}
