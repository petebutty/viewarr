package io.github.petebutty.viewarr.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import io.github.petebutty.viewarr.data.sync.ActivityItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "What it did last" on the Viewarr plugin's page: one row per plugin job with its last run's result and time
 * (plugin activity.py, admins only). OK on a row reads it again.
 */
internal fun LazyListScope.pluginActivity(items: List<ActivityItem>, onRefresh: () -> Unit) {
    if (items.isEmpty()) return
    item(key = "act:h") { PluginsHeader("What it did last") }
    items(items, key = { "act:" + it.id }) { a ->
        Row2("act:${a.id}", a.name, a.text, trailing = activityWhen(a)) { onRefresh() }
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val DAY_HM = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)

/** "15:06", "Yesterday 04:31", "Sat 20:58"; a problem or a job switched off says so first. */
internal fun activityWhen(a: ActivityItem): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochSecond(a.at).atZone(zone)
    val today = LocalDate.now(zone)
    val time = when (t.toLocalDate()) {
        today -> t.format(HM)
        today.minusDays(1) -> "Yesterday " + t.format(HM)
        else -> t.format(DAY_HM)
    }
    return when {
        a.off -> "Off"
        a.problem -> "Needs a look · $time"
        else -> time
    }
}
