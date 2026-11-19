package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.petebutty.viewarr.data.sync.WatchSource
import io.github.petebutty.viewarr.data.sync.WatchSync
import io.github.petebutty.viewarr.ui.theme.UiOpts

/**
 * Settings → Playback → Watch progress: what's connected and what each one does (nothing to choose: Peter,
 * 2026-11-19, "it should all just work"), then the two optional switches and the sync buttons.
 */
@Composable
fun watchProgressCategory(opts: UiOptsViewModel, appearance: AppearanceViewModel): SettingCategory {
    val status = connectedItems()
    val switches = listOfNotNull(
        if (!WatchSource.hasTrakt) null else optItem(
            opts, "Send plays to Trakt",
            "On: Viewarr tells Trakt about everything you play, wherever it plays from, and what you mark as watched. Off: Viewarr only reads Trakt (if another app already sends your plays there).",
            UiOpts.SEND_TO_TRAKT,
        ),
        onOffItem(
            "Share IPTV progress between TVs",
            "On: where you stopped in an IPTV film or episode, and what you've finished, is saved on your Dispatcharr (in your own user's storage), so every TV signed in as you carries on from the same place. Off: each TV remembers only what was played on it.",
            WatchSync.enabled,
        ) { on -> appearance.setTitlePart(WatchSync.ENABLE_KEY, on); if (on) WatchSync.requestSync() },
    )
    val always = SettingItem(
        "Counts as watched at",
        "A film or episode stopped past 80% of the way through counts as watched (the credits don't need to finish), as Trakt and the servers count it.",
        emptyList(), PlainChoice("80%"), action = {}, onSelect = {},
    )
    return SettingCategory(
        "Watch progress",
        "Viewarr keeps your watching in step everywhere it can: each play is reported to the server it plays from and to Trakt, and ticks, progress bars, Continue watching and Up next come from all of them together.",
        status + switches + traktWatchItems() + dispatcharrItems(appearance) + always,
    )
}

/** One status row: shown, nothing to change. */
private fun info(title: String, description: String, value: String) =
    SettingItem(title, description, emptyList(), PlainChoice(value), action = {}, onSelect = {})

/** What's connected, and what Viewarr does with each. */
private fun connectedItems(): List<SettingItem> = buildList {
    add(
        if (WatchSource.hasTrakt) info(
            "Trakt",
            "Gets every play from Viewarr (from your servers, IPTV or addons) and what you mark as watched. What Trakt says you've watched, in any app, gives ticks and Continue watching here too.",
            "Connected",
        ) else info(
            "Trakt",
            "Not connected. Connect it in Settings → Accounts & sources to keep one history across all your apps and devices.",
            "Not connected",
        ),
    )
    WatchSource.serverNames.forEach { name ->
        add(info(
            name,
            "Films and episodes played from $name are reported to it as you watch (where you stopped, and watched past 80%), as its own apps do. Its ticks and Continue watching show here too.",
            "Connected",
        ))
    }
    add(info(
        "Dispatcharr",
        "IPTV films and episodes are remembered on this TV and shared with your other TVs through Dispatcharr (with \"Share IPTV progress between TVs\" on).",
        if (WatchSync.enabled) "Sharing" else "This TV only",
    ))
}

/** Trakt connected: sync now, and copying what Trakt says you've watched to Plex. */
@Composable
private fun traktWatchItems(): List<SettingItem> {
    val vm: TraktSyncViewModel = hiltViewModel()
    val a by vm.accounts.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val plexStatus by vm.plexStatus.collectAsStateWithLifecycle()
    if (a.trakt == null) return emptyList()
    return listOfNotNull(
        SettingItem(
            "Sync with Trakt now",
            "Fetches your Trakt history again now (it's checked on its own every so often), so titles you've watched in other apps get their ticks." +
                (status?.let { " $it." } ?: ""),
            emptyList(), PlainChoice("OK: sync"), action = { vm.syncNow() }, onSelect = {},
        ),
        if (a.plex == null) null else onOffItem(
            "Copy watched to Plex",
            plexStatus ?: "On: marks on your Plex servers what Trakt says you've watched (never un-marks anything), so Plex's own apps agree.",
            a.traktToPlex,
        ) { on -> if (on != a.traktToPlex) vm.toggleCopy() },
    )
}

/** Dispatcharr: Sync now, and the Viewarr plugin's server sync (off by itself while Trakt is connected). */
private fun dispatcharrItems(appearance: AppearanceViewModel): List<SettingItem> = listOf(
    onOffItem(
        "Sync IPTV progress with my Emby / Jellyfin / Plex",
        "For people without Trakt. On: when you finish or stop an IPTV film or episode, the Viewarr plugin on your Dispatcharr marks the same title in your Emby, Jellyfin or Plex (and the other way round for titles you've watched on IPTV), every 5 minutes. Needs \"Share IPTV progress between TVs\" on and the Viewarr plugin installed. To do it, Viewarr gives the plugin your sign-in for those servers, kept in your own Dispatcharr storage. Off: the sign-in is taken back out and nothing more is synced.",
        WatchSync.serversOn,
    ) { on ->
        appearance.setTitlePart(WatchSync.SERVERS_SWITCH, !on)
        WatchSync.setServers(on)
    }.locked(
        if (!WatchSource.hasTrakt) null
        else "Off while Trakt is connected: Trakt already gets your IPTV plays from Viewarr, and copying them into your servers as well would send Trakt the same watch again.",
    ),
    SettingItem(
        "Sync now",
        "Saves this TV's IPTV watch progress to Dispatcharr and takes in anything newer from your other TVs now, instead of waiting for the next automatic sync (every 5 minutes).",
        emptyList(),
        PlainChoice(WatchSync.lastSync.takeIf { it > 0 }?.let { "Last: " + io.github.petebutty.viewarr.ui.theme.Dates.time(it) } ?: "OK: sync"),
        action = { WatchSync.requestSync() },
        onSelect = {},
    ),
)
