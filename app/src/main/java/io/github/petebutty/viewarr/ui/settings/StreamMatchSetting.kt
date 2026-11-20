package io.github.petebutty.viewarr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.ViewarrPopup
import io.github.petebutty.viewarr.ui.common.glass
import io.github.petebutty.viewarr.ui.media.StreamMatch
import io.github.petebutty.viewarr.ui.theme.AppColors

private data class RuleChoice(override val label: String) : RemoteChoice

/**
 * Settings → Playback → Auto-play match: every rule in your order (hold OK → Move or Right picks one up, Up / Down move it, Right puts
 * it down; OK switches it on or off, or removes one of your own), then "Add your own…" and "Reset".
 */
@Composable
fun streamRuleItems(saved: String, onSave: (String) -> Unit): List<SettingItem> {
    var typing by remember { mutableStateOf(false) }
    val rules = StreamMatch.parse(saved)
    // A rule already in the list (the same name or pattern): that one is switched on and moved to the top, not added
    // twice (two rows with one name crashed the settings list).
    if (typing) StreamMatchDialog("", onSave = { p ->
        typing = false
        if (p.isNotBlank()) {
            val same = rules.firstOrNull { it.label.equals(p, true) || it.pattern == p }
            onSave(StreamMatch.save(listOf(same?.copy(on = true) ?: StreamMatch.Rule(p, p, on = true, own = true)) + rules.filter { it !== same }))
        }
    }, onClose = { typing = false })
    val on = RuleChoice("On"); val off = RuleChoice("Off"); val remove = RuleChoice("Remove")
    val items = rules.mapIndexed { i, r ->
        val place = if (r.on) "${rules.take(i + 1).count { it.on }} of ${rules.count { it.on }} switched on" else "Off"
        SettingItem(
            r.label,
            place + "  ·  Hold OK: move" + if (r.own) "  ·  your own" else "  ·  ${r.pattern}",
            if (r.own) listOf(on, off, remove) else listOf(on, off), if (r.on) on else off,
            reorder = { delta ->
                val j = (i + delta).coerceIn(0, rules.lastIndex)
                if (j != i) onSave(StreamMatch.save(rules.toMutableList().apply { add(j, removeAt(i)) }))
            },
        ) { c ->
            // Switched on: to the top, to be sorted from there; switched off: just below the ones still on, so the
            // rules in use stay together at the top (Peter, 2026-11-15).
            val rest = rules.filterIndexed { k, _ -> k != i }
            onSave(StreamMatch.save(when (c) {
                remove -> rest
                on -> listOf(r.copy(on = true)) + rest
                else -> rest.filter { it.on } + r.copy(on = false) + rest.filterNot { it.on }
            }))
        }
    }
    val add = SettingItem("Add your own…", "A rule of your own: a word or a pattern (upper / lower case alike), looked for in each stream's addon, name and description. It goes at the top, switched on.",
        listOf(RuleChoice("")), RuleChoice(""), action = { typing = true }, onSelect = {})
    val reset = SettingItem("Reset the rules", "All switched off, in the starting order; your own are removed.",
        listOf(RuleChoice("")), RuleChoice(""), action = { onSave("") }, onSelect = {})
    return items + add + reset
}

@Composable
private fun StreamMatchDialog(start: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(start) }
    val field = remember { FocusRequester() }
    // (A frame first: the window is new, and the box wasn't there yet to take it.)
    LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { field.requestFocus() } }
    val valid = StreamMatch.regex(text) != null
    ViewarrPopup(onClose) {
        Column(
            Modifier.align(Alignment.Center).width(720.dp).glass(16.dp).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add your own rule", fontSize = 22.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("A word, or a search pattern for the more technical, looked for in each stream's addon, name and description. Examples: remux  ·  (atmos|truehd)  ·  ^(?!.*\\bcam\\b) (anything without CAM).", fontSize = 13.sp, color = AppColors.TextSecondary)
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { androidx.compose.material3.Text("Pattern") },
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
            Text(
                when {
                    text.isBlank() -> "Type a word or a pattern."
                    valid -> "OK."
                    else -> "That isn't a valid pattern, so it can't be saved."
                },
                fontSize = 13.sp, color = if (valid || text.isBlank()) AppColors.TextSecondary else AppColors.Accent,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(200.dp)) { MenuItem(text = "Save", selected = false, dim = !valid, onClick = { if (valid) onSave(text.trim()) }) }
                Box(Modifier.width(200.dp)) { MenuItem(text = "Cancel", selected = false, onClick = onClose) }
            }
        }
    }
}
