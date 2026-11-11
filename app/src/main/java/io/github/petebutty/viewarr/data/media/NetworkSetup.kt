package io.github.petebutty.viewarr.data.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.remote.services.HttpJson
import io.github.petebutty.viewarr.data.remote.services.array
import io.github.petebutty.viewarr.data.remote.services.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live TV hub → network shortcuts. Viewarr provides no addons: the networks come from addons the user added (rows
 * named "<Name> · Popular / New…", RowSets). This knows the common streaming services and TV channels (their
 * popularity in the UK and US, to order and pre-tick them, and their TMDB ids, for logos and rows), and can fill in
 * an AIOMetadata the user brings (their own address) with rows for the networks they pick.
 */
@Singleton
class NetworkSetup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: HttpJson,
    private val accounts: AccountStore,
    private val stremio: io.github.petebutty.viewarr.data.remote.services.StremioService,
) {
    enum class Region(val label: String, val code: String) { UK("UK", "GB"), US("US", "US") }

    /**
     * A network: a streaming service ([providers]: TMDB watch-provider ids, looked up in [providerRegion]) and/or a
     * channel ([network]: TMDB network id; for a service, its own shows: "Originals"). [films]: film rows too.
     */
    data class Net(
        val name: String,
        val providers: List<Int> = emptyList(),
        val providerRegion: String = "GB",
        val network: Int? = null,
        val films: Boolean = true,
        val channel: Boolean = false,
    )

    /** In order of popularity there (the first ones are ticked to start with). */
    fun recommended(region: Region): List<Net> = when (region) {
        Region.UK -> listOf(
            Net("Netflix", listOf(8, 1796), network = 213),
            Net("Prime Video", listOf(9, 2100), network = 1024),
            Net("Disney+", listOf(337), network = 2739),
            Net("BBC iPlayer", listOf(38)),
            Net("ITVX", listOf(41, 2300)),
            Net("Sky Go", listOf(29)),
            Net("NOW", listOf(39, 591)),
            Net("Apple TV+", listOf(350), network = 2552),
            Net("HBO Max", listOf(1899), network = 3186),
            Net("Paramount+", listOf(531, 2303, 2304), network = 4330),
            Net("My5", listOf(333)),
            Net("UKTV Play", listOf(137), films = false),
            Net("Discovery+", listOf(524), films = false),
            Net("Crunchyroll", listOf(283)),
            Net("Shudder", listOf(99)),
            Net("Hulu", listOf(15), providerRegion = "US", network = 453),
            Net("Peacock", listOf(386, 387), providerRegion = "US", network = 3353),
            Net("BBC One", network = 4, films = false, channel = true),
            Net("ITV1", network = 9, films = false, channel = true),
            Net("BBC Two", network = 332, films = false, channel = true),
            Net("Channel 4", network = 26, films = false, channel = true),
            Net("Channel 5", network = 99, films = false, channel = true),
            Net("Sky Atlantic", network = 1063, films = false, channel = true),
            Net("HBO", network = 49, films = false, channel = true),
            Net("FX", network = 88, films = false, channel = true),
            Net("AMC", network = 174, films = false, channel = true),
            Net("FOX", network = 19, films = false, channel = true),
        )
        Region.US -> listOf(
            Net("Netflix", listOf(8, 1796), "US", network = 213),
            Net("Prime Video", listOf(9, 2100), "US", network = 1024),
            Net("Hulu", listOf(15), "US", network = 453),
            Net("Disney+", listOf(337), "US", network = 2739),
            Net("HBO Max", listOf(1899), "US", network = 3186),
            Net("Peacock", listOf(386, 387), "US", network = 3353),
            Net("Paramount+", listOf(2303, 2616), "US", network = 4330),
            Net("Apple TV+", listOf(350), "US", network = 2552),
            Net("Crunchyroll", listOf(283), "US"),
            Net("Shudder", listOf(99), "US"),
            Net("Discovery+", listOf(520), "US", films = false),
            Net("HBO", network = 49, films = false, channel = true),
            Net("FX", network = 88, films = false, channel = true),
            Net("AMC", network = 174, films = false, channel = true),
            Net("FOX", network = 19, films = false, channel = true),
            Net("NBC", network = 6, films = false, channel = true),
            Net("CBS", network = 16, films = false, channel = true),
            Net("ABC", network = 2, films = false, channel = true),
        )
    }

    /** How many are ticked to start with. */
    val suggested = 12

    private val prefs get() = context.getSharedPreferences("network_setup", Context.MODE_PRIVATE)

    /** The networks picked last time (in order), or null. */
    fun lastPicked(): List<String>? = prefs.getString("picked", null)?.split('|')?.filter { it.isNotBlank() }
    fun lastRegion(): Region? = prefs.getString("region", null)?.let { r -> Region.entries.firstOrNull { it.name == r } }

    // ---------------------------------------------------------------- logos

    private var providerLogos: Map<Int, String>? = null

    /** The network's logo from TMDB: a service's app icon (square), else the channel's logo. */
    suspend fun logo(net: Net, tmdbKey: String): ServiceLogo? = runCatching {
        net.providers.firstOrNull()?.let { id ->
            val all = providerLogos ?: listOf("tv", "movie").flatMap { t ->
                listOf("GB", "US").flatMap { r ->
                    http.get("https://api.themoviedb.org/3/watch/providers/$t?api_key=$tmdbKey&watch_region=$r").body.array("results")
                }
            }.mapNotNull { p -> (p as? JsonObject)?.let { o -> (o["provider_id"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { it to (o.str("logo_path") ?: return@let null) } } }
                .toMap().also { providerLogos = it }
            all[id]?.let { return ServiceLogo("https://image.tmdb.org/t/p/w154$it", square = true) }
        }
        net.network?.let { id ->
            http.get("https://api.themoviedb.org/3/network/$id?api_key=$tmdbKey").body.str("logo_path")
                ?.let { ServiceLogo("https://image.tmdb.org/t/p/w300$it", square = false) }
        }
    }.getOrNull()

    /** Remembers the picks (for opening the picker again). */
    fun remember(region: Region, picked: List<String>) {
        prefs.edit().putString("region", region.name).putString("picked", picked.joinToString("|")).apply()
    }

    /**
     * The networks the user's addons give, in order of popularity in [region] (Viewarr's list), then any others
     * alphabetically.
     */
    fun ranked(region: Region, found: List<String>): List<String> {
        val order = recommended(region).map { it.name }
        return found.sortedWith(compareBy({ order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
    }

    companion object {
        /** TMDB networks with a wide wordmark logo, by shortcut name (wide tiles). */
        val WIDE = mapOf(
            "Netflix" to 213, "Prime Video" to 1024, "Disney+" to 2739, "Apple TV+" to 2552, "HBO Max" to 3186, "Hulu" to 453,
            "Paramount+" to 4330, "Peacock" to 3353, "BBC One" to 4, "BBC Two" to 332, "ITV1" to 9, "Channel 4" to 26, "Channel 5" to 99,
            "Sky Atlantic" to 1063, "HBO" to 49, "FX" to 88, "AMC" to 174, "FOX" to 19, "NBC" to 6, "CBS" to 16, "ABC" to 2,
        )
    }

    fun known(name: String): Net? = Region.entries.firstNotNullOfOrNull { r -> recommended(r).firstOrNull { it.name.equals(name, true) } }

    // ---------------------------------------------------------------- filling in the user's AIOMetadata

    fun lastInstance(): String? = prefs.getString("base", null)
    /** The networks whose rows Viewarr made last time (kept when it's run again). */
    fun lastMade(): List<String> = prefs.getString("made", null)?.split('|')?.filter { it.isNotBlank() }.orEmpty()

    private fun template(): kotlinx.serialization.json.JsonObject =
        http.json.parseToJsonElement(context.resources.openRawResource(io.github.petebutty.viewarr.R.raw.aiometadata_template).bufferedReader().readText()).jsonObject

    private fun slug(s: String) = s.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-')

    private fun row(tpl: kotlinx.serialization.json.JsonObject, type: String, net: Net, kind: String, region: Region, byNetwork: Boolean): kotlinx.serialization.json.JsonObject {
        val base = tpl[type]!!.jsonObject
        val discover = base["metadata"]!!.jsonObject["discover"]!!.jsonObject
        val ukChannel = net.channel && region == Region.UK && net.network !in setOf(49, 88, 174, 19)
        val sort = when (kind) {
            "New" -> if (type == "series") "first_air_date.desc" else "primary_release_date.desc"
            "Top rated" -> "vote_average.desc"
            else -> "popularity.desc"
        }
        val votes = when (kind) {
            "New" -> if (type == "series") 5 else 10
            "Top rated" -> if (ukChannel) 25 else if (type == "series") 150 else 300
            else -> if (type == "series") 25 else 50
        }
        val name = "${net.name} · $kind"
        val params = kotlinx.serialization.json.buildJsonObject {
            discover["params"]!!.jsonObject.forEach { (k, v) -> if (k != "sort_by" && k != "vote_count.gte") put(k, v) }
            put("sort_by", JsonPrimitive(sort)); put("vote_count.gte", JsonPrimitive(votes))
            if (byNetwork) put("with_networks", JsonPrimitive(net.network.toString()))
            else { put("watch_region", JsonPrimitive(net.providerRegion)); put("with_watch_providers", JsonPrimitive(net.providers.joinToString("|"))) }
        }
        val form = kotlinx.serialization.json.buildJsonObject {
            discover["formState"]!!.jsonObject.forEach { (k, v) -> if (k !in setOf("catalogName", "sortBy", "voteCountMin")) put(k, v) }
            put("catalogName", JsonPrimitive(name)); put("sortBy", JsonPrimitive(sort)); put("voteCountMin", JsonPrimitive(votes))
            if (byNetwork) {
                put("withNetworks", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject { put("id", JsonPrimitive(net.network)); put("label", JsonPrimitive(net.name)) })))
                put("networkJoinMode", JsonPrimitive("or"))
            } else {
                put("watchRegion", JsonPrimitive(net.providerRegion))
                put("watchProviders", kotlinx.serialization.json.JsonArray(net.providers.map { id -> kotlinx.serialization.json.buildJsonObject { put("id", JsonPrimitive(id)); put("label", JsonPrimitive(net.name)) } }))
                put("providerJoinMode", JsonPrimitive("or"))
            }
        }
        return kotlinx.serialization.json.buildJsonObject {
            base.forEach { (k, v) -> if (k !in setOf("id", "name", "metadata")) put(k, v) }
            put("id", JsonPrimitive("tmdb.discover.$type.viewarr.${slug(net.name)}.${slug(kind)}"))
            put("name", JsonPrimitive(name))
            put("metadata", kotlinx.serialization.json.buildJsonObject {
                put("discover", kotlinx.serialization.json.buildJsonObject {
                    discover.forEach { (k, v) -> if (k != "params" && k != "formState") put(k, v) }
                    put("params", params); put("formState", form)
                })
            })
        }
    }

    private fun catalogs(tpl: kotlinx.serialization.json.JsonObject, region: Region, picked: List<Net>) = picked.flatMap { net ->
        buildList {
            val service = net.providers.isNotEmpty()
            listOf("Popular", "New", "Top rated").forEach { add(row(tpl, "series", net, it, region, byNetwork = !service)) }
            if (service && net.network != null) {
                val r = row(tpl, "series", net, "Popular", region, byNetwork = true)
                add(kotlinx.serialization.json.JsonObject(r + mapOf(
                    "id" to JsonPrimitive("tmdb.discover.series.viewarr.${slug(net.name)}.originals"),
                    "name" to JsonPrimitive("${net.name} · Originals"),
                )))
            }
            if (service && net.films) listOf("Popular", "New", "Top rated").forEach { add(row(tpl, "movie", net, it, region, byNetwork = false)) }
        }
    }

    sealed interface Result {
        data class Done(val addon: io.github.petebutty.viewarr.data.accounts.AddonInfo) : Result
        data class Failed(val why: String) : Result
    }

    /**
     * Fills in the AIOMetadata at [instance] (the user's own; there's no default) with rows for [picked], using the
     * user's TMDB key, and adds it as an addon. Again on the same instance: updates that setup (same addon link).
     */
    suspend fun apply(instance: String, region: Region, picked: List<Net>): Result {
        val base = instance.trim().trimEnd('/').removeSuffix("/configure").trimEnd('/')
        if (!base.startsWith("http")) return Result.Failed("Enter your AIOMetadata's address, e.g. http://192.168.0.10:3232.")
        val tmdbKey = accounts.current().tmdbKey ?: return Result.Failed("Add your TMDB key first (Settings → Accounts & sources → TMDB).")
        val tpl = template()
        val config = kotlinx.serialization.json.buildJsonObject {
            tpl["base"]!!.jsonObject.forEach { (k, v) -> if (k != "apiKeys") put(k, v) }
            put("language", JsonPrimitive(if (region == Region.UK) "en-GB" else "en-US"))
            put("apiKeys", kotlinx.serialization.json.JsonObject(tpl["base"]!!.jsonObject["apiKeys"]!!.jsonObject + ("tmdb" to JsonPrimitive(tmdbKey))))
            put("catalogs", kotlinx.serialization.json.JsonArray(catalogs(tpl, region, picked)))
        }
        fun body(password: String) = kotlinx.serialization.json.buildJsonObject { put("config", config); put("password", JsonPrimitive(password)) }
        val same = prefs.getString("base", null) == base
        val uuid = prefs.getString("uuid", null).takeIf { same }
        val password = prefs.getString("password", null).takeIf { same } ?: java.util.UUID.randomUUID().toString()
        val installUrl = (if (uuid != null) runCatching { http.put("$base/api/config/update/$uuid", body(password)) }.getOrNull()
            ?.takeIf { it.ok }?.let { it.body.str("installUrl") ?: "$base/stremio/$uuid/manifest.json" } else null)
            ?: run {
                val r = runCatching { http.post("$base/api/config/save", body(password)) }.getOrNull()
                    ?: return Result.Failed("Couldn't reach AIOMetadata at $base.")
                if (!r.ok) return Result.Failed("AIOMetadata didn't accept the setup (${r.code}${r.body.str("error")?.let { ": $it" } ?: ""}). Is it AIOMetadata 3 or newer?")
                val newUuid = r.body.str("userUUID") ?: return Result.Failed("AIOMetadata didn't say where the setup is.")
                prefs.edit().putString("uuid", newUuid).putString("password", password).putString("base", base).apply()
                r.body.str("installUrl") ?: "$base/stremio/$newUuid/manifest.json"
            }
        val addon = stremio.fetchAddon(installUrl) ?: return Result.Failed("The setup was made, but its addon link didn't load ($installUrl).")
        val previous = prefs.getString("addon", null)
        accounts.update { a -> a.copy(addons = a.addons.filterNot { it.baseUrl == addon.baseUrl || it.baseUrl == previous } + addon) }
        prefs.edit().putString("addon", addon.baseUrl).putString("made", picked.joinToString("|") { it.name }).apply()
        remember(region, picked.map { it.name })
        return Result.Done(addon)
    }
}
