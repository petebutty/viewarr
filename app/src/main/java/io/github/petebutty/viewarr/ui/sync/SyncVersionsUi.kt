package io.github.petebutty.viewarr.ui.sync

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.remote.services.long
import io.github.petebutty.viewarr.data.remote.services.str
import io.github.petebutty.viewarr.data.sync.SettingsSync
import io.github.petebutty.viewarr.data.sync.SyncOffer
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** A version picked to restore: its copy, how to describe it, and whether it's an earlier one (not the latest). */
private class Picked(val copy: JsonObject, val offer: SyncOffer, val version: Boolean)

private fun dateTime(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(Dates.dayTime)

/**
 * Settings → Backup & sync → Restore from Dispatcharr: "Latest — <TV> · <when>", then each earlier version
 * ("<TV or name> · <date, time>"). Picking one asks first (as the sign-in offer does); restoring keeps this TV's
 * setup as a version beforehand ("Before restore on <TV>"), so it can be undone the same way.
 */
@Composable
fun SyncVersionsContent(vm: SyncViewModel, first: FocusRequester, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<SettingsSync.RestoreChoices?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Picked?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.choices().fold({ choices = it }, { error = it.message }) }
    LaunchedEffect(picked, result, choices, error) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    val note: @Composable (String, Boolean) -> Unit = { t, accent ->
        Text(t, fontSize = 13.sp, color = if (accent) AppColors.Accent else AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp, start = 12.dp, bottom = 6.dp))
    }

    result?.let {
        note(it, true)
        MenuItem(text = "Done", selected = false, onClick = onDone, modifier = Modifier.focusRequester(first))
        return
    }
    picked?.let { p ->
        val o = p.offer
        Text(
            "Saved from ${o.device} ${ago(o.savedAt)}: " +
                (if (o.hasSignIns) "your services (Plex, Jellyfin, Seerr and the rest), " else "") +
                "appearance, home rows, playback and remote settings.",
            fontSize = 14.sp, lineHeight = 20.sp, color = AppColors.TextPrimary,
        )
        note("Settings that depend on this box (surround sound, frame rate, decoding) stay as they are. Your setup now is kept as a version first.", false)
        MenuItem(
            text = if (busy) "Restoring…" else "Restore",
            selected = false,
            onClick = {
                if (!busy) { busy = true; scope.launch { result = vm.restoreFrom(p.copy, p.version); busy = false; picked = null } }
            },
            modifier = Modifier.focusRequester(first),
        )
        MenuItem(text = "Cancel", selected = false, onClick = { if (!busy) picked = null })
        return
    }

    val c = choices
    if (error != null || c == null) {
        note(error ?: "Loading…", error != null)
        MenuItem(text = "Back", selected = false, onClick = onDone, modifier = Modifier.focusRequester(first))
        return
    }
    var firstUsed = false
    fun mod(): Modifier = if (firstUsed) Modifier else { firstUsed = true; Modifier.focusRequester(first) }
    c.latest?.let { copy ->
        MenuItem(
            text = "Latest — ${copy.str("device") ?: "another TV"} · ${ago(copy.long("savedAt") ?: 0)}",
            selected = false,
            onClick = { scope.launch { picked = Picked(copy, vm.offerFor(copy), version = false) } },
            modifier = mod(),
        )
    }
    c.versions.forEach { v ->
        MenuItem(
            text = "${v.name ?: v.device} · ${dateTime(v.savedAt)}",
            selected = false,
            onClick = {
                scope.launch {
                    val copy = vm.copyOf(v)
                    if (copy == null) error = "That version couldn't be read." else picked = Picked(copy, vm.offerFor(copy), version = true)
                }
            },
            modifier = mod(),
        )
    }
    if (c.latest == null && c.versions.isEmpty()) note("Nothing saved on Dispatcharr yet.", false)
    else if (c.versions.isEmpty()) note(if (c.historyUnread) "Earlier versions couldn't be read just now." else "Earlier versions appear here as your setup changes.", false)
    MenuItem(text = "Back", selected = false, onClick = onDone, modifier = mod())
}
