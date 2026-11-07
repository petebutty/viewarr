package io.github.petebutty.viewarr.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlexServersViewModel @Inject constructor(private val store: AccountStore) : ViewModel() {
    val accounts = store.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, Accounts())
    fun toggle(machineId: String) = viewModelScope.launch {
        store.update { a -> a.copy(plexOff = if (machineId in a.plexOff) a.plexOff - machineId else a.plexOff + machineId) }
    }
}

/**
 * Settings: each Plex server on the account, on or off (only when there's more than one). A server that's off is
 * left out of rows, Continue watching, search, "In your library", libraries and playback.
 */
@Composable
fun PlexServersItems() {
    val vm: PlexServersViewModel = hiltViewModel()
    val a by vm.accounts.collectAsStateWithLifecycle()
    val main = a.plex ?: return
    val servers = listOf(main) + a.plexMore.filter { it.machineId != main.machineId }
    if (servers.size < 2) return
    Text("Plex servers", fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
    servers.forEach { s ->
        val on = s.machineId !in a.plexOff
        MenuItem(text = "Plex: ${s.serverName}", trailing = if (on) "On" else "Off", selected = false, dim = !on, onClick = { vm.toggle(s.machineId) })
    }
    if (a.plexOff.isNotEmpty()) Text("Pages reload with the change next time you open them.", fontSize = 11.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp))
}
