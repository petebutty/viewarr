package io.github.petebutty.viewarr.ui.music

import io.github.petebutty.viewarr.data.music.Artist
import io.github.petebutty.viewarr.data.music.ArtistInfo
import io.github.petebutty.viewarr.data.music.MusicLibrary
import io.github.petebutty.viewarr.data.music.Song
import io.github.petebutty.viewarr.data.music.Taste
import io.github.petebutty.viewarr.data.music.TasteArtist
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

/*
 * The Music home's personal rows, made from what the person plays (Taste) and what their own server knows about
 * those artists (similar artists, top songs, similar songs): Your Mix and the daily mixes, "Because you played …",
 * and "Hidden in your library". They arrive one by one behind the cheap rows already on screen; a server that can't
 * say what's similar simply gives fewer of them (a row with nothing in it isn't made).
 */

/** An artist the person plays, found in the library, with what the server says about them. */
private class ArtistLook(val taste: TasteArtist, val artist: Artist, val info: ArtistInfo?)

/** The rows, each handed to [onRow] (id, row) as it's ready; null means that row has nothing this time. */
internal suspend fun buildPersonalRows(library: MusicLibrary, taste: Taste, day: Long, withMixes: Boolean, gentle: Semaphore, onRow: (String, RowOut?) -> Unit) {
    if (taste.isEmpty) return
    coroutineScope {
        val owned = async { runCatching { library.artists() }.getOrDefault(emptyList()).associateBy { MusicLibrary.norm(it.name) } }
        if (withMixes) launch { onRow("mix", runCatching { mixesRow(library, taste, day, gentle) }.getOrNull()) }
        // Six artists looked at, so that a few with nothing to show don't leave the rows short.
        val looks = taste.artists.take(6).map { ta -> async { runCatching { lookUp(library, ta, owned, gentle) }.getOrNull() } }
        launch {
            val found = looks.awaitAll().filterNotNull()
            val map = owned.await()
            val because = found.mapNotNull { becauseRow(it, map, day) }.take(3)
            because.forEachIndexed { i, r -> onRow(r.row.id, RowOut(r.row, r.songs, i)) }
            onRow("hidden", runCatching { hiddenRow(found, map, taste, day) }.getOrNull())
        }
    }
}

/** The artist as the library has them (by the id an album gave, else by name), and their info. */
private suspend fun lookUp(library: MusicLibrary, ta: TasteArtist, owned: Deferred<Map<String, Artist>>, gentle: Semaphore): ArtistLook? {
    val artist = ta.id?.let { Artist(it, ta.name, 0, null, it.substringBefore(':')) } ?: owned.await()[MusicLibrary.norm(ta.name)] ?: return null
    return ArtistLook(ta, artist, gentle.withPermit { library.artistInfo(artist) })
}

/** The artists like this one that the library has: the server's list matched by name (it can name ones you don't own). */
private fun ownedSimilar(look: ArtistLook, owned: Map<String, Artist>): List<Artist> {
    val me = MusicLibrary.norm(look.artist.name)
    return look.info?.similar.orEmpty().map { owned[MusicLibrary.norm(it.name)] ?: it }.filter { MusicLibrary.norm(it.name) != me }.distinctBy { MusicLibrary.norm(it.name) }
}

/** "Because you played X": artists like them in your library, then X's top songs (a tile) and the albums they're on. */
private fun becauseRow(look: ArtistLook, owned: Map<String, Artist>, day: Long): RowOut? {
    val name = look.artist.name
    val cards = ArrayList(ownedSimilar(look, owned).take(12).map(::artistCard))
    val top = look.info?.topSongs.orEmpty()
    val songs = HashMap<String, List<Song>>()
    if (top.size >= 3) {
        val id = "top:${look.artist.id}:$day"
        cards += mixCard(id, "Top songs", name, "${songCount(top.size)} · $name", top)
        songs[id] = top
    }
    cards += top.filter { it.albumId != null }.distinctBy { it.albumId }.take(6).map { s ->
        MusicCard(MusicKind.Album, s.albumId!!, s.album, s.artist, s.art, meta = listOfNotNull(s.artist.takeIf { it.isNotBlank() }, s.year?.toString()).joinToString(" · "))
    }
    if (cards.isEmpty()) return null
    return RowOut(MusicRow("because:${MusicLibrary.norm(name)}", "Because you played $name", cards), songs)
}

/**
 * Artists you own and don't play: those like your favourites (from the server's similar lists), not among the ones
 * played most. Short of those (a server that can't say what's similar), other artists you own and don't play.
 */
private fun hiddenRow(looks: List<ArtistLook>, owned: Map<String, Artist>, taste: Taste, day: Long): RowOut? {
    val rng = Random(day)
    fun unplayed(a: Artist) = MusicLibrary.norm(a.name) !in taste.played
    val similar = looks.flatMap { ownedSimilar(it, owned) }.filter(::unplayed).distinctBy { MusicLibrary.norm(it.name) }.shuffled(rng).toMutableList()
    if (similar.size < 6) {
        val have = similar.map { MusicLibrary.norm(it.name) }.toHashSet()
        similar += owned.values.filter { unplayed(it) && it.albumCount > 0 && MusicLibrary.norm(it.name) !in have }.shuffled(rng).take(20 - similar.size)
    }
    if (similar.isEmpty()) return null
    return RowOut(MusicRow("hidden", "Hidden in your library", similar.take(20).map(::artistCard)))
}
