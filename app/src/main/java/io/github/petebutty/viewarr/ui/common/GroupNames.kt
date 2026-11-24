package io.github.petebutty.viewarr.ui.common

import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * Channel group names tidied for show (Settings → Playback → Live TV → Tidy group names): the country and quality marks
 * providers add ("UK| SKY SPORTS FHD" → "SKY SPORTS") left out, each one your choice (Settings → Playback → Group name
 * clean-up). Only how a name is shown changes: the group itself, its order, hiding and everything keyed by its name stay
 * as they are. (2026-11-17.)
 */
object GroupNames {
    /** What can be left out, in the order Settings lists them. */
    val words = listOf(
        "UK", "US", "CA", "AU", "IE", "EU", "INT", "HD", "FHD", "UHD", "4K", "SD", "HEVC", "VIP", "RAW", "60FPS", "24/7",
    )
    fun wordKey(w: String) = "gname:" + w.lowercase()
    /** Left out when tidying (out of the box: all of them). */
    fun removes(w: String) = AppColors.palette.shows(wordKey(w))

    /** Tidying on (off out of the box: the mark is kept the other way round, as an added extra's). */
    const val TIDY_KEY = "gname:+tidy"
    val tidy get() = !AppColors.palette.shows(TIDY_KEY)

    private val SEPARATORS = Regex("[|·•#_]+")
    private val EDGES = Regex("^[\\s\\-:–—/]+|[\\s\\-:–—/]+$")
    private val SPACES = Regex("\\s{2,}")
    // Each word on its own (not inside another: "UK" in "UKTV" stays), made once rather than on every name shown.
    private val WORD_MATCH = words.associateWith { Regex("(?i)(?<![\\p{L}0-9])" + Regex.escape(it) + "(?![\\p{L}0-9])") }
    // Small capitals and superscripts some providers use ("ᴿᴬᵂ ⁶⁰ᶠᵖˢ"), read as their plain letters.
    private val SMALL = mapOf(
        'ᴿ' to 'R', 'ᴬ' to 'A', 'ᵂ' to 'W', '⁶' to '6', '⁰' to '0', 'ᶠ' to 'F', 'ᵖ' to 'P', 'ˢ' to 'S', 'ᴴ' to 'H', 'ᴰ' to 'D',
    )

    /** [name] as shown: tidied when Tidy group names is on (never down to nothing: then as it was). */
    fun display(name: String): String {
        if (!tidy) return name
        var s = name.map { SMALL[it] ?: it }.joinToString("").replace(SEPARATORS, " ")
        for (w in words) if (removes(w)) s = s.replace(WORD_MATCH.getValue(w), " ")
        s = s.replace(SPACES, " ").replace(EDGES, "").trim()
        return s.ifBlank { name }
    }
}
