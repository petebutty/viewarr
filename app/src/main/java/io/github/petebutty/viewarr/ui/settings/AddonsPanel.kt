package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.accounts.AccountStore
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private enum class AddonSwitch(override val label: String) : RemoteChoice { On("On"), Off("Off") }

@HiltViewModel
class AddonsSettingsViewModel @Inject constructor(private val store: AccountStore) : ViewModel() {
    val accounts = store.accounts.stateIn(viewModelScope, SharingStarted.Eagerly, null as Accounts?)

    fun setEnabled(baseUrl: String, enabled: Boolean) = viewModelScope.launch {
        store.update { a -> a.copy(addons = a.addons.map { if (it.baseUrl == baseUrl) it.copy(enabled = enabled) else it }) }
    }

    fun enableAll() = viewModelScope.launch {
        store.update { a -> a.copy(addons = a.addons.map { it.copy(enabled = true) }) }
    }
}

/**
 * Settings → Addons: switch each Stremio addon on or off without removing it. An addon that's off adds no rows,
 * search results or streams. Adding and removing addons stays in Accounts & sources (phone setup).
 */
@Composable
fun AddonsSettingsScreen(onBack: () -> Unit, vm: AddonsSettingsViewModel = hiltViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val addons = accounts?.addons.orEmpty()
    val items = addons.map { addon ->
        val host = runCatching { android.net.Uri.parse(addon.baseUrl).host }.getOrNull() ?: addon.baseUrl
        SettingItem(
            title = addon.name,
            description = "From $host",
            choices = AddonSwitch.entries,
            current = if (addon.enabled) AddonSwitch.On else AddonSwitch.Off,
        ) { vm.setEnabled(addon.baseUrl, it == AddonSwitch.On) }
    }
    val blurb = if (addons.isEmpty()) "No addons yet. Add them in Settings → Accounts & sources."
    else "An addon that's off adds no rows, search results or streams. Add or remove addons in Accounts & sources."
    SettingsPage(
        title = "Addons",
        categories = listOf(SettingCategory("Addons", blurb, items)),
        onBack = onBack,
        onReset = if (addons.isEmpty()) null else ({ vm.enableAll() }),
    )
}


/** Accounts & sources → Stremio addons: each addon on or off, in place. */
@Composable
fun AddonSwitches(vm: AddonsSettingsViewModel = hiltViewModel()) {
    val a by vm.accounts.collectAsStateWithLifecycle()
    val addons = a?.addons.orEmpty()
    if (addons.isEmpty()) return
    androidx.tv.material3.Text(
        "Addons", fontSize = 13.sp, color = io.github.petebutty.viewarr.ui.theme.AppColors.Accent,
        modifier = androidx.compose.ui.Modifier.padding(start = 12.dp, top = 6.dp),
    )
    addons.forEach { ad ->
        io.github.petebutty.viewarr.ui.common.MenuItem(text = ad.name, trailing = if (ad.enabled) "On" else "Off", selected = false, dim = !ad.enabled, onClick = { vm.setEnabled(ad.baseUrl, !ad.enabled) })
    }
}
