package io.github.petebutty.viewarr.data.media

import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.child
import io.github.petebutty.viewarr.data.remote.services.int
import io.github.petebutty.viewarr.data.remote.services.str
import java.time.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

private const val IMG = "https://image.tmdb.org/t/p/"

/**
 * Everything a person's page shows, from one TMDB call (/person/{id} with their combined credits, pictures and the
 * pictures they're tagged in).
 */
data class PersonDetails(
    val id: String,
    val name: String,
    val photo: String?,
    val bio: String?,
    val born: LocalDate?,
    val died: LocalDate?,
    val place: String?,
    /** What they're known for, e.g. "Actor · Producer" (TMDB's department, then the jobs found in their credits). */
    val roles: String?,
    /** How many different titles they're credited on (any role), and the earliest year among them. */
    val titleCount: Int,
    val since: Int?,
    /** Their own work: acting (not appearances as themselves, talk shows or news) and directing / writing. */
    val credits: List<PersonCredit>,
    val pictures: List<PersonPicture>,
    /** A wide picture for the top of the page: one they're tagged in, else their best-known title's backdrop. */
    val backdrop: String?,
)

/** One title in a person's filmography. [jobs]: directing / writing jobs on it ("Director", "Writer"), if any. */
data class PersonCredit(
    val item: MediaItem,
    val date: LocalDate?,
    val votes: Int,
    val popularity: Double,
    val acting: Boolean,
    val jobs: List<String>,
)

/** A picture of the person: [thumb] for the grid, [full] for the full-screen view; [wide] for landscape ones. */
data class PersonPicture(val thumb: String, val full: String, val wide: Boolean)

private fun JsonElement?.dbl(k: String): Double = (child(k) as? JsonPrimitive)?.doubleOrNull ?: 0.0
private fun JsonElement?.date(k: String): LocalDate? = str(k)?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

/** Appearances as themselves ("Self", "Himself"…): not their own work. */
private fun self(j: JsonElement?) = j.str("character")?.trim()?.let { c ->
    c.equals("Self", true) || c.startsWith("Himself", true) || c.startsWith("Herself", true) || c.startsWith("Themselves", true) ||
        c.startsWith("Self ", true) || c.startsWith("Self -", true)
} == true

/** Talk shows and news. */
private fun talkOrNews(j: JsonElement?) = j.array("genre_ids").any { g -> g.toString().let { it == "10767" || it == "10763" } }

private fun typeOf(j: JsonElement?): MediaType? = when (j.str("media_type")) { "movie" -> MediaType.Movie; "tv" -> MediaType.Show; else -> null }

/** The role a crew credit counts toward on the "known for" line, or null. */
private fun crewRole(j: JsonElement?): String? {
    val job = j.str("job").orEmpty()
    val dept = j.str("department").orEmpty()
    return when {
        job == "Director" -> "Director"
        dept == "Writing" -> "Writer"
        job == "Producer" || job == "Executive Producer" -> "Producer"
        job.contains("Composer") -> "Composer"
        else -> null
    }
}

private fun deptRole(d: String?): String? = when (d) {
    null, "" -> null
    "Acting" -> "Actor"
    "Directing" -> "Director"
    "Writing" -> "Writer"
    "Production" -> "Producer"
    "Sound" -> "Composer"
    "Camera" -> "Cinematographer"
    "Editing" -> "Editor"
    else -> d
}

/**
 * The page from TMDB's answer [j]. [bio]: the biography to show (British English, else any English). [tmdbItem] makes
 * a title the same way as everywhere else in the app (same keys and ids).
 */
internal fun parsePersonDetails(id: String, name: String, j: JsonElement, bio: String?, tmdbItem: (JsonElement?, MediaType) -> MediaItem?): PersonDetails {
    val credits = j.child("combined_credits")
    val castAll = credits.array("cast")
    val crewAll = credits.array("crew")

    // Every distinct title, for the count and the first year.
    val all = (castAll + crewAll).mapNotNull { c -> typeOf(c)?.let { t -> c.int("id")?.let { "$t:$it" } }?.let { it to c } }.distinctBy { it.first }
    val since = all.mapNotNull { (_, c) -> (c.str("release_date") ?: c.str("first_air_date"))?.take(4)?.toIntOrNull() }.minOrNull()

    // Their own work: acting, and directing / writing, one entry per title (jobs gathered).
    val cast = castAll.filter { !self(it) && !talkOrNews(it) }
    val crew = crewAll.filter { it.str("job") == "Director" || it.str("department") == "Writing" }
    val byKey = LinkedHashMap<String, PersonCredit>()
    fun add(c: JsonElement, acting: Boolean) {
        val t = typeOf(c) ?: return
        val item = tmdbItem(c, t) ?: return
        val job = if (acting) null else (if (c.str("job") == "Director") "Director" else c.str("job")?.takeIf { it.isNotBlank() } ?: "Writer")
        val old = byKey[item.key]
        byKey[item.key] = if (old == null) PersonCredit(
            item = item, date = c.date("release_date") ?: c.date("first_air_date"),
            votes = c.int("vote_count") ?: 0, popularity = c.dbl("popularity"), acting = acting, jobs = listOfNotNull(job),
        ) else old.copy(acting = old.acting || acting, jobs = (old.jobs + listOfNotNull(job)).distinct())
    }
    cast.forEach { add(it, acting = true) }
    crew.forEach { add(it, acting = false) }
    val list = byKey.values.toList()

    // "Actor · Producer": their department first, then other jobs they've done on several titles.
    val counts = HashMap<String, Int>()
    crewAll.mapNotNull { c -> crewRole(c)?.let { r -> r to "${c.str("media_type")}:${c.int("id")}" } }.distinct()
        .forEach { (r, _) -> counts[r] = (counts[r] ?: 0) + 1 }
    val acted = cast.map { "${it.str("media_type")}:${it.int("id")}" }.distinct().size
    if (acted >= 3) counts["Actor"] = acted
    val first = deptRole(j.str("known_for_department"))
    val others = counts.filter { (r, n) -> r != first && n >= (if (r == "Director" || r == "Writer") 2 else 3) }
        .entries.sortedByDescending { it.value }.map { it.key }
    val roles = (listOfNotNull(first) + others).take(3).joinToString(" · ").ifBlank { null }

    // Pictures: their own portraits, then the stills and backdrops they're tagged in.
    val profiles = j.child("images").array("profiles").mapNotNull { p ->
        p.str("file_path")?.let { PersonPicture(IMG + "w185" + it, IMG + "h632" + it, wide = false) }
    }
    val tagged = j.child("tagged_images").array("results")
    val taggedPics = tagged.mapNotNull { p ->
        val path = p.str("file_path") ?: return@mapNotNull null
        val wide = p.dbl("aspect_ratio") > 1.2
        val full = when (p.str("image_type")) {
            "backdrop" -> "w1280"
            "poster" -> "w780"
            "profile" -> "h632"
            else -> "original"
        }
        PersonPicture(IMG + (if (wide) "w300" else "w185") + path, IMG + full + path, wide)
    }

    val backdrop = tagged.filter { it.str("image_type") == "backdrop" && it.dbl("aspect_ratio") >= 1.6 }
        .sortedWith(compareByDescending<JsonElement> { it.int("vote_count") ?: 0 }.thenByDescending { it.dbl("vote_average") })
        .firstOrNull()?.str("file_path")?.let { IMG + "w1280" + it }
        ?: list.filter { it.item.backdrop != null }.maxByOrNull { it.votes }?.item?.backdrop?.replace("/w780/", "/w1280/")

    return PersonDetails(
        id = id,
        name = j.str("name") ?: name,
        photo = j.str("profile_path")?.let { IMG + "h632" + it },
        bio = bio?.trim(),
        born = j.date("birthday"),
        died = j.date("deathday"),
        place = j.str("place_of_birth")?.takeIf { it.isNotBlank() },
        roles = roles,
        titleCount = all.size,
        since = since,
        credits = list,
        pictures = profiles + taggedPics,
        backdrop = backdrop,
    )
}
