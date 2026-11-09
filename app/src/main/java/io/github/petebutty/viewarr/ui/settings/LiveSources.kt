package io.github.petebutty.viewarr.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Live TV sources saved on this TV (several Dispatcharr instances, IPTV logins, playlists) and switching. */
@HiltViewModel
class LiveSourcesViewModel @Inject constructor(
    private val store: SessionStore,
    private val guide: io.github.petebutty.viewarr.data.repository.GuideRepository,
) : ViewModel() {
    /** The active source's groups (name, channels) and which are hidden. */
    fun groups() = guide.allGroups()
    fun hidden() = guide.hiddenGroups()
    /** Saves which groups are hidden and reloads the source (hidden groups' channels and guide aren't kept). */
    fun saveHidden(names: Set<String>) = viewModelScope.launch {
        guide.setHiddenGroups(names)
        store.session.first()?.let { runCatching { guide.sync(it) } }
    }

    val saved = store.saved.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val active = store.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Makes [s] the active source: its channels and guide load; the old one's are cleared. */
    fun switchTo(s: Session) = viewModelScope.launch { if (active.value?.id != s.id) store.save(s) }
    fun rename(s: Session, name: String?) = viewModelScope.launch { store.rename(s.id, name) }
    /** The active one: another saved source takes over (or none, and Live TV needs setting up again). */
    fun remove(s: Session) = viewModelScope.launch { store.remove(s.id) }
}

/**
 * Accounts & sources → Live TV: every saved source by name. OK on one: switch to it, rename it or remove it.
 */
@Composable
fun LiveSourcesItems() {
    val vm: LiveSourcesViewModel = hiltViewModel()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Session?>(null) }
    var choosingGroups by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    if (saved.isEmpty()) return
    Text("Your Live TV sources", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
    saved.forEach { s ->
        val on = s.id == active?.id
        MenuItem(
            text = s.label, trailing = if (on) "Active" else "", selected = false,
            onClick = { open = if (open == s.id) null else s.id; confirmRemove = false },
        )
        if (open == s.id) {
            if (!on) MenuItem(text = "      Switch to this source", selected = false, onClick = { open = null; vm.switchTo(s) })
            MenuItem(text = "      Rename", selected = false, onClick = { renaming = s })
            // Leave out groups you never watch: their channels and guide aren't stored at all.
            // (IPTV logins and playlists: Dispatcharr sorts its groups out itself, with its own plugin.)
            if (on && s.isDirect) MenuItem(text = "      Choose groups", selected = false, onClick = { choosingGroups = true })
            MenuItem(
                text = if (confirmRemove) "      Press OK again to remove it" else "      Remove",
                selected = false, dim = true,
                onClick = { if (confirmRemove) { open = null; confirmRemove = false; vm.remove(s) } else confirmRemove = true },
            )
        }
    }
    if (choosingGroups) GroupsDialog(vm, onClose = { choosingGroups = false })
    renaming?.let { s ->
        RenameSourceDialog(s.name.orEmpty(), s.label, onDone = { vm.rename(s, it); renaming = null }, onCancel = { renaming = null })
    }
}

@Composable
private fun RenameSourceDialog(current: String, fallback: String, onDone: (String?) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.width(520.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Name this source", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Text("For example \"Living room\" or \"Backup provider\". Leave it empty for \"$fallback\".", fontSize = 13.sp, color = AppColors.TextSecondary)
            io.github.petebutty.viewarr.ui.setup.WizardField(
                name, { name = it }, "Name", first,
                androidx.compose.ui.text.input.KeyboardType.Text, androidx.compose.ui.text.input.ImeAction.Done,
                onIme = { onDone(name.trim().ifBlank { null }) },
            )
            MenuItem(text = "Save", selected = false, onClick = { onDone(name.trim().ifBlank { null }) })
            MenuItem(text = "Cancel", selected = false, onClick = onCancel)
        }
    }
}


/**
 * Choose groups: every group of the active source with its channel count, on or off. Saving reloads the source;
 * hidden groups' channels and programmes aren't kept (for IPTV playlists with thousands of channels, most of the
 * guide's size).
 */
@Composable
private fun GroupsDialog(vm: LiveSourcesViewModel, onClose: () -> Unit) {
    // Every group, empty ones too: hiding is your choice.
    val all = remember { vm.groups() }
    var hidden by remember { mutableStateOf(vm.hidden()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(560.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Choose groups", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            val shownChannels = all.filter { it.first !in hidden }.sumOf { it.second }
            Text("${all.size - hidden.count { h -> all.any { it.first == h } }} of ${all.size} groups, $shownChannels channels. Hidden groups' channels and TV guide aren't kept on the TV.",
                fontSize = 13.sp, color = AppColors.TextSecondary)
            MenuItem(text = "Save and reload", selected = false, modifier = Modifier.focusRequester(first), onClick = { vm.saveHidden(hidden); onClose() })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuItem(text = "Show all", selected = false, onClick = { hidden = emptySet() }, modifier = Modifier.weight(1f))
                MenuItem(text = "Hide all", selected = false, onClick = { hidden = all.map { it.first }.toSet() }, modifier = Modifier.weight(1f))
            }
            if (all.isEmpty()) Text("No groups yet: they're listed once the source has loaded.", fontSize = 13.sp, color = AppColors.TextSecondary)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                items(all.size) { i ->
                    val (name, count) = all[i]
                    val on = name !in hidden
                    MenuItem(
                        text = name, trailing = (if (on) "On" else "Off") + "  ·  $count", selected = false, dim = !on,
                        onClick = { hidden = if (on) hidden + name else hidden - name },
                    )
                }
            }
            MenuItem(text = "Cancel", selected = false, onClick = onClose)
        }
    }
}
