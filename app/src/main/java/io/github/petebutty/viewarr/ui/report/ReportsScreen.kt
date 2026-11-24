package io.github.petebutty.viewarr.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.profiles.Access
import io.github.petebutty.viewarr.data.report.HouseChange
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.common.ViewarrPopup
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Tokens
import kotlinx.coroutines.android.awaitFrame

/**
 * Settings → Live TV → Reports, over everything (as Send a report is): channels that need the right guide picked
 * ("Needs your pick", OK opens the picker), the changes the Viewarr plugin made from everyone's reports (admins: OK →
 * Undo), and this TV's own reports. Without the plugin, one line says so and the reports stay here. (2026-11-19.)
 */
@Composable
fun ReportsScreen(vm: ReportsViewModel, onClose: () -> Unit) {
    val house by vm.house.data.collectAsStateWithLifecycle()
    val mine by vm.reports.items.collectAsStateWithLifecycle()
    var undoFor by remember { mutableStateOf<HouseChange?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { vm.house.refresh(); awaitFrame(); runCatching { first.requestFocus() } }
    BackHandler(onBack = onClose)
    ViewarrPopup(onClose = onClose) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(820.dp).heightIn(max = (io.github.petebutty.viewarr.ui.common.LocalCanvas.current.height * 0.9f))
                    .background(AppColors.MenuPanel, RoundedCornerShape(Tokens.PopupRadius)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Reports", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text(
                    "Hold OK on a channel → Report a problem. With the Viewarr plugin on Dispatcharr, reports are acted on for the whole house and every change can be undone here.",
                    fontSize = 14.sp, color = AppColors.TextSecondary,
                )
                if (house == null) Text(
                    "Install the Viewarr plugin on Dispatcharr for reports to be acted on; until then they're kept here.",
                    fontSize = 14.sp, color = AppColors.Accent,
                )
                val picks = house?.needsPick.orEmpty()
                val changes = house?.changes.orEmpty()
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    if (picks.isNotEmpty()) {
                        item(key = "h-pick") { Heading("Needs your pick", "Someone reported a wrong guide and the plugin couldn't find a match. OK: choose the right guide.") }
                        picks.forEachIndexed { i, p ->
                            item(key = "p-" + p.reportId) {
                                MenuItem(
                                    text = "${p.channelName.ifBlank { "Channel ${p.channelId}" }} · reported by ${p.by} · ${agoLabel(p.at)}", selected = false, compact = true,
                                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                                    onClick = { ReportFlow.openPicker(p.channelId, p.channelName.ifBlank { "Channel ${p.channelId}" }) },
                                )
                            }
                        }
                    }
                    item(key = "h-changes") { Heading("Changes made from reports", if (Access.admin) "Newest first. OK on one: Undo." else "Newest first. An admin can undo them.") }
                    if (changes.isEmpty()) item(key = "no-changes") { Note(if (house == null) "Nothing yet." else "No changes yet.") }
                    changes.forEachIndexed { i, c ->
                        item(key = "c-" + c.id) {
                            MenuItem(
                                text = "${c.what} · ${c.channelName} · by ${c.by} · ${agoLabel(c.at)}" + if (c.undone) "  (undone)" else "",
                                selected = false, compact = true, dim = c.undone,
                                modifier = if (i == 0 && picks.isEmpty()) Modifier.focusRequester(first) else Modifier,
                                onClick = { if (Access.admin && !c.undone) undoFor = c },
                            )
                        }
                    }
                    item(key = "h-mine") { Heading("Your reports", "What this TV has reported (the last 50, two weeks).") }
                    if (mine.isEmpty()) item(key = "no-mine") { Note("None yet.") }
                    mine.forEach { r ->
                        item(key = "m-" + r.id) {
                            MenuItem(
                                text = "${r.kindLabel} · ${r.channelName.ifBlank { "Channel ${r.channelId}" }} · ${agoLabel(r.at)}" +
                                    listOfNotNull(r.offsetMin?.let { " · " + io.github.petebutty.viewarr.ui.guide.shiftLabel(it) }, r.name?.let { " · \"$it\"" }, r.note?.let { " · $it" }).joinToString(""),
                                selected = false, compact = true, onClick = {},
                            )
                        }
                    }
                }
                MenuItem(text = "Close", selected = false, onClick = onClose, modifier = if (picks.isEmpty() && changes.isEmpty()) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
    undoFor?.let { c ->
        OptionsPopup(
            title = "Undo this change?", subtitle = "${c.what} · ${c.channelName}",
            description = "The plugin puts the channel back as it was before this report.",
            options = listOf(PopupOption("Undo") { vm.undo(c.id) }),
            onClose = { undoFor = null },
        )
    }
}

@Composable
private fun Heading(title: String, note: String) {
    Column(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(note, fontSize = 12.sp, color = AppColors.TextSecondary)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp))
}
