package io.github.petebutty.viewarr.data.music

/*
 * What this person plays, worked out from the album lists the servers already give (most played, recently played,
 * favourites) and what's starred: a ranked list of artists. Nothing is asked of a server here. (Albums carry no
 * genre, so artists are the only signal.)
 */

/** An artist the person plays: how much, their id if an album gave it, and their albums from the lists (best first). */
data class TasteArtist(val name: String, val id: String?, val score: Double, val albums: List<Album>)

data class Taste(val artists: List<TasteArtist>) {
    val isEmpty get() = artists.isEmpty()
    /** The 25 most played artists (names as [MusicLibrary.norm] has them), to tell what's under-played from what isn't. */
    val played: Set<String> by lazy { artists.take(25).map { MusicLibrary.norm(it.name) }.toHashSet() }
}

private val SKIP_ARTISTS = setOf("variousartists", "va", "unknownartist", "soundtrack", "originalsoundtrack")

private class Tally(val name: String) {
    var id: String? = null
    var score = 0.0
    val albums = LinkedHashMap<String, Pair<Album, Double>>()
    fun add(a: Album, weight: Double) {
        if (id == null) id = a.artistId
        score += weight
        albums[a.id] = a to ((albums[a.id]?.second ?: 0.0) + weight)
    }
}

/** The ranked artists. Most played counts most (play counts where the server has them, else position); then recent, favourites, stars. */
fun tasteOf(frequent: List<Album>, recent: List<Album>, favourites: List<Album>, starred: Set<String>): Taste {
    val by = LinkedHashMap<String, Tally>()
    fun tally(a: Album, weight: Double) {
        val key = MusicLibrary.norm(a.artist)
        if (key.isEmpty() || key in SKIP_ARTISTS) return
        by.getOrPut(key) { Tally(a.artist) }.add(a, weight)
    }
    frequent.forEachIndexed { i, a -> tally(a, (a.plays ?: 0) + (frequent.size - i) * 0.5) }
    recent.forEachIndexed { i, a -> tally(a, (recent.size - i) * 0.4) }
    favourites.forEach { tally(it, 6.0) }
    by.values.forEach { t -> if (t.id != null && t.id in starred) t.score += 12.0 }
    val ranked = by.values.sortedByDescending { it.score }
    return Taste(ranked.map { t -> TasteArtist(t.name, t.id, t.score, t.albums.values.sortedByDescending { it.second }.map { it.first }) })
}
