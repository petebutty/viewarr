package io.github.petebutty.viewarr.data.repository

import io.github.petebutty.viewarr.data.local.ChannelListItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The TV's own idea of which channels are event channels, for Settings → Live TV → Event channels: only with a
 * listing when the Viewarr plugin isn't running for this house (ServerSports.pluginAlive false). With the plugin, its
 * quiet channels are used instead (GuideRepository.hideEventChannels), exactly as before.
 *
 * The same idea as the plugin's quiet groups (viewarr-plugin streamorder.quiet_groups): every channel in an event-style
 * group is an event channel. A group is event-style when:
 *  - Event Channel Managarr (a Dispatcharr plugin) looks after it ([managedGroups], read from Dispatcharr when it can be), or
 *  - its name says events or pay-per-view ("UK Events", "PPV", "Box Office"), or
 *  - it has 3 or more channels and most of them carry a provider's numbered event-feed names ("UEFA 05:", "NFL B 13:",
 *    "AMAZON UK EVENT 3", "PPV 12"), the same names Live now treats as event feeds.
 * A "Club TV" group is never one (club channels stay listed, as with the plugin). Whether one of these channels is
 * then hidden is up to the guide: nothing on now or in the next 24 hours.
 */
object EventChannelRule {
    private val _managed = MutableStateFlow<Set<String>>(emptySet())
    /** Event Channel Managarr's groups (lower case); empty when it isn't installed or can't be seen by this user. */
    val managedGroups = _managed.asStateFlow()
    fun setManagedGroups(names: Set<String>) { _managed.value = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet() }

    private val EVENT_GROUP = Regex("\\b(events?|ppv|pay[ -]?per[ -]?view|box office)\\b", RegexOption.IGNORE_CASE)
    private val EVENT_FEED = Regex("\\d{1,3}\\s*:|\\bevents?\\s*\\d+|\\bppv\\s*\\d+", RegexOption.IGNORE_CASE)

    /** The ids of every channel in an event-style group (see above). */
    fun eventChannelIds(channels: List<ChannelListItem>, managed: Set<String>): Set<Int> {
        val out = HashSet<Int>()
        for ((group, chans) in channels.groupBy { it.groupName?.trim().orEmpty() }) {
            val key = group.lowercase()
            if (key.isEmpty() || key == "club tv") continue
            val eventStyle = key in managed || EVENT_GROUP.containsMatchIn(group) ||
                (chans.size >= 3 && chans.count { EVENT_FEED.containsMatchIn(it.name) } * 10 >= chans.size * 6)
            if (eventStyle) chans.mapTo(out) { it.id }
        }
        return out
    }
}
