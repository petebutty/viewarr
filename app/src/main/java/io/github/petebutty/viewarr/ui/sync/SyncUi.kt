package io.github.petebutty.viewarr.ui.sync

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.sync.SettingsSync
import io.github.petebutty.viewarr.data.sync.SyncMode
import io.github.petebutty.viewarr.data.sync.SyncStatus
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(private val sync: SettingsSync) : ViewModel() {
    val offer = sync.offer
    val status = sync.status.stateIn(viewModelScope, SharingStarted.Eagerly, SyncStatus())
    fun accept(done: () -> Unit) = viewModelScope.launch { sync.acceptOffer(); done() }
    fun decline() = viewModelScope.launch { sync.declineOffer() }
    fun setEnabled(on: Boolean) = viewModelScope.launch { sync.setEnabled(on) }
    fun setSignIns(on: Boolean) = viewModelScope.launch { sync.setSignIns(on) }
    suspend fun checkNow(): String = sync.checkNow()
}

private fun ago(ms: Long): String {
    if (ms <= 0) return ""
    val zone = java.time.ZoneId.systemDefault()
    val d = java.time.Instant.ofEpochMilli(ms).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val day = when (d.toLocalDate()) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> d.format(io.github.petebutty.viewarr.ui.theme.Dates.day)
    }
    return "$day at " + d.format(io.github.petebutty.viewarr.ui.theme.Dates.time)
}

/**
 * Over everything: "Your Viewarr setup is saved on Dispatcharr — restore it?" when this TV signs in to a Dispatcharr
 * user that another TV saved its setup to.
 */
@Composable
fun SyncOfferHost(vm: SyncViewModel = hiltViewModel()) {
    val offer by vm.offer.collectAsStateWithLifecycle()
    val o = offer ?: return
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(o) { awaitFrame(); runCatching { first.requestFocus() } }
    BackHandler { if (!busy) vm.decline() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(600.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(24.dp).trapFocus(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Saved on your Dispatcharr server", fontSize = 12.sp, color = AppColors.Accent)
            Text("Restore your Viewarr setup?", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Text(
                "Saved from ${o.device} ${ago(o.savedAt)}: " +
                    (if (o.hasSignIns) "your services (Plex, Jellyfin, Seerr and the rest), " else "") +
                    "appearance, home rows, playback and remote settings.",
                fontSize = 14.sp, lineHeight = 20.sp, color = AppColors.TextPrimary,
            )
            Text(
                "Settings that depend on this box (surround sound, frame rate, decoding) stay as they are.",
                fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 10.dp),
            )
            MenuItem(
                text = if (busy) "Restoring…" else "Restore",
                selected = false,
                onClick = { if (!busy) { busy = true; vm.accept { busy = false } } },
                modifier = Modifier.focusRequester(first),
            )
            MenuItem(text = "Not now (keep this TV's own settings)", selected = false, onClick = { if (!busy) vm.decline() })
        }
    }
}

/** Settings: save to Dispatcharr on / off, include sign-ins, restore now. */
@Composable
fun SyncSettingsItems() {
    val vm: SyncViewModel = hiltViewModel()
    val st by vm.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    val on = st.mode == SyncMode.On
    MenuItem(
        text = "Save settings to Dispatcharr",
        trailing = if (on) "On" else "Off",
        selected = false,
        onClick = { vm.setEnabled(!on) },
    )
    if (on) MenuItem(
        text = "Include sign-ins",
        trailing = if (st.signIns) "On" else "Off",
        selected = false,
        onClick = { vm.setSignIns(!st.signIns) },
    )
    MenuItem(
        text = "Restore from Dispatcharr",
        selected = false,
        onClick = { scope.launch { note = vm.checkNow().ifEmpty { null } } },
    )
    (note ?: st.message ?: if (on && st.lastSaved > 0) "Saved ${ago(st.lastSaved)}" else null)?.let {
        Text(it, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(horizontal = 12.dp))
    }
}


/** Settings → This TV → Backup & sync: one entry; OK opens its options (save on / off, sign-ins, restore). */
@Composable
fun BackupSyncItem() {
    val vm: SyncViewModel = hiltViewModel()
    val st by vm.status.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    MenuItem(text = "Backup & sync", trailing = if (st.mode == SyncMode.On) "On" else "Off", selected = false, onClick = { open = true })
    if (open) androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
        BackHandler { open = false }
        Column(
            Modifier.width(520.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Backup & sync", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Text(
                "Keeps this TV's setup on your Dispatcharr server, so another TV (or this one after a reinstall) can restore it.",
                fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 10.dp),
            )
            Box(Modifier.focusRequester(first)) { Column { SyncSettingsItems() } }
            MenuItem(text = "Close", selected = false, onClick = { open = false })
        }
    }
}
