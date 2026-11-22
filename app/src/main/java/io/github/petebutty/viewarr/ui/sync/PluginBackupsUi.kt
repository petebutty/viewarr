package io.github.petebutty.viewarr.ui.sync

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.profiles.DispatcharrUsers
import io.github.petebutty.viewarr.data.profiles.House
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.session.SessionStore
import io.github.petebutty.viewarr.data.sync.SettingsSync
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.setup.WizardField
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Whether this login can use the Viewarr plugin's backups (its actions are for Dispatcharr admins only). */
/** [Off]: the plugin is there but an admin switched its Backups off (hidden then). */
enum class PluginAccess { Checking, Ready, NoPlugin, NotAdmin, NoLogin, Off }

/**
 * Backups of this TV's whole setup kept by the Viewarr plugin on Dispatcharr: copies made by hand, kept until
 * deleted (the plugin keeps the newest 30). Shared by Settings → Backup & sync and Users & profiles → Backups.
 */
@HiltViewModel
class PluginBackupsViewModel @Inject constructor(
    private val sessions: SessionStore,
    private val users: DispatcharrUsers,
    private val house: House,
    private val sync: SettingsSync,
) : ViewModel() {
    private val _access = MutableStateFlow(PluginAccess.Checking)
    val access: StateFlow<PluginAccess> = _access
    private val _backups = MutableStateFlow<List<House.Backup>?>(null)
    /** null: loading, or the plugin didn't answer. */
    val backups: StateFlow<List<House.Backup>?> = _backups
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private suspend fun me(): Session? = sessions.session.first()?.takeIf { !it.isDirect }

    /** Admin login and the plugin installed and on. */
    fun check() = viewModelScope.launch {
        val s = me() ?: run { _access.value = PluginAccess.NoLogin; return@launch }
        _access.value = when {
            users.myLevel(s) != 10 -> PluginAccess.NotAdmin
            !house.installed(s) -> PluginAccess.NoPlugin
            !house.backupsOn(s) -> PluginAccess.Off
            else -> PluginAccess.Ready
        }
    }

    fun load() = viewModelScope.launch {
        val s = me() ?: return@launch
        val list = house.backups(s)
        if (list == null && _access.value == PluginAccess.Ready) _access.value = PluginAccess.NoPlugin
        _backups.value = list
    }

    /** The name a new backup gets if none is typed: "<this TV> <date>". */
    fun defaultName(): String =
        sync.thisDevice() + " " + java.time.LocalDateTime.now().format(Dates.dayTime)

    private fun work(block: suspend (Session) -> String?) = viewModelScope.launch {
        val s = me() ?: return@launch
        _message.value = null
        _busy.value = true
        _message.value = runCatching { block(s) }.getOrElse { it.message ?: "That didn't work." }
        _busy.value = false
        load()
    }

    fun save(name: String) = work { s ->
        val ok = house.saveBackup(s, name.trim().ifEmpty { defaultName() }, sync.backupBody(s))
        if (ok) "Backup saved on the Viewarr plugin." else "The Viewarr plugin didn't save it (is it installed and on?)."
    }

    /** Saves this TV's setup as "Before restore" first, so the restore can be undone; nothing is restored if that fails. */
    fun restore(b: House.Backup) = work { s ->
        if (!house.saveBackup(s, "Before restore", sync.backupBody(s))) return@work "Couldn't save this TV's setup first, so nothing was restored."
        val data = house.backup(s, b.id) ?: return@work "That backup couldn't be read."
        if (sync.restoreBackup(data, s)) "Restored ${b.name}. Your setup before it is saved as Before restore."
        else "Settings restored; its sign-ins couldn't be read (your Dispatcharr API key has changed since)."
    }

    fun delete(b: House.Backup) = work { s ->
        if (house.deleteBackup(s, b.id)) "Deleted ${b.name}." else "It couldn't be deleted."
    }
}

/** "name · device · date · size" for a backup's row. */
private fun details(b: House.Backup): String = listOfNotNull(
    b.device.takeIf { it.isNotBlank() },
    b.savedAt.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(Dates.dayTime) },
    b.size.takeIf { it > 0 }?.let { if (it >= 1024 * 1024) "%.1f MB".format(it / 1048576.0) else "${(it + 1023) / 1024} KB" },
).joinToString(" · ")

/**
 * The plugin's backups: save one (an optional name), restore one (confirmed; the current setup is saved as
 * "Before restore" first), hold OK to delete one (confirmed). [first] gets the highlight on each view.
 */
@Composable
fun PluginBackupsContent(first: FocusRequester, vm: PluginBackupsViewModel = hiltViewModel()) {
    val access by vm.access.collectAsStateWithLifecycle()
    val list by vm.backups.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var restore by remember { mutableStateOf<House.Backup?>(null) }
    var delete by remember { mutableStateOf<House.Backup?>(null) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.check(); vm.load() }
    // Moving between the list and a question: the highlight goes to the new view's first item.
    LaunchedEffect(restore, delete, naming) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    val note: @Composable (String, Boolean) -> Unit = { t, accent ->
        Text(t, fontSize = 13.sp, color = if (accent) AppColors.Accent else AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp, start = 12.dp))
    }
    restore?.let { r ->
        Text("Restore ${r.name}? This TV's settings and sign-ins are replaced by the backup's (picture, sound and frame-rate settings stay). Your setup now is saved as Before restore first.", fontSize = 14.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 10.dp))
        MenuItem(text = if (busy) "Restoring…" else "Restore", selected = false, onClick = { if (!busy) { vm.restore(r); restore = null } }, modifier = Modifier.focusRequester(first))
        MenuItem(text = "Cancel", selected = false, onClick = { restore = null })
        return
    }
    delete?.let { d ->
        Text("Delete ${d.name}? It can't be got back.", fontSize = 14.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 10.dp))
        MenuItem(text = "Delete", selected = false, onClick = { if (!busy) { vm.delete(d); delete = null } }, modifier = Modifier.focusRequester(first))
        MenuItem(text = "Cancel", selected = false, onClick = { delete = null })
        return
    }
    if (naming) {
        WizardField(name, { name = it.take(80) }, vm.defaultName(), first, KeyboardType.Text, ImeAction.Done, { vm.save(name); naming = false })
        note("Leave it empty for \"${vm.defaultName()}\".", false)
        MenuItem(text = "Save", selected = false, onClick = { if (!busy) { vm.save(name); naming = false } })
        MenuItem(text = "Cancel", selected = false, onClick = { naming = false })
        return
    }
    Text("Copies you make yourself, kept by the Viewarr plugin until you delete them (the newest 30). Sign-ins stay encrypted with your key.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 10.dp))
    val ready = access == PluginAccess.Ready
    MenuItem(text = if (busy) "Saving…" else "Save a backup now", selected = false, dim = !ready,
        onClick = { if (!busy && ready) { name = ""; naming = true } }, modifier = Modifier.focusRequester(first))
    when {
        access == PluginAccess.NotAdmin || access == PluginAccess.NoLogin -> note("Needs the Viewarr plugin and an admin login.", true)
        access == PluginAccess.NoPlugin -> note("Install the Viewarr plugin in Dispatcharr (Plugins) to keep backups there.", true)
        access == PluginAccess.Off -> note("Backups are switched off in the Viewarr plugin (Settings → Plugins → Viewarr).", true)
        list == null -> note("Loading…", false)
        list.orEmpty().isEmpty() -> note("No backups yet.", false)
        else -> list.orEmpty().forEach { b ->
            MenuItem(text = b.name, trailing = details(b), selected = false, onClick = { if (!busy) restore = b }, onLongClick = { if (!busy) delete = b })
        }
    }
    if (ready && !list.isNullOrEmpty()) note("OK: restore  ·  Hold OK: delete", false)
    message?.let { note(it, true) }
}
