package io.github.petebutty.viewarr.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.update.Release
import io.github.petebutty.viewarr.data.update.UpdateManager
import io.github.petebutty.viewarr.data.update.UpdatePrefs
import io.github.petebutty.viewarr.data.update.UpdateState
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(private val updates: UpdateManager) : ViewModel() {
    val state = updates.state
    val prompt = updates.prompt
    val prefs = updates.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, UpdatePrefs())
    val currentVersion = updates.currentVersion

    fun autoCheck() = viewModelScope.launch { updates.autoCheck() }
    fun check() = viewModelScope.launch { updates.check(fromUser = true) }
    fun download(r: Release) = viewModelScope.launch { updates.download(r) }
    fun install(r: Release) = updates.install(r)
    fun later() = updates.later()
    fun openPermission() = updates.openInstallPermission()
    fun canInstall() = updates.canInstall()
    fun setAuto(on: Boolean) = viewModelScope.launch { updates.setAuto(on) }
    fun setBeta(on: Boolean) = viewModelScope.launch { updates.setBeta(on) }
}

/**
 * The update pop-up, drawn over the whole app. It appears when a check finds a newer version and stays
 * through download, permission and install; "Later" (or Back) hides it until the next check.
 */
@Composable
fun UpdateHost(vm: UpdateViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.autoCheck() }
    // Back from Android's "Install unknown apps" screen: carry on installing if it's now allowed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val obs = LifecycleEventObserver { _, e ->
            val s = state
            if (e == Lifecycle.Event.ON_RESUME && s is UpdateState.NeedsPermission && vm.canInstall()) vm.install(s.release)
            // Back to the front (e.g. from the TV's home screen): look for a newer version if it's been a while.
            else if (e == Lifecycle.Event.ON_RESUME && (s is UpdateState.Idle || s is UpdateState.UpToDate)) vm.autoCheck()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    if (!prompt) return
    val s = state
    val release = when (s) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.NeedsPermission -> s.release
        is UpdateState.Installing -> s.release
        is UpdateState.Failed -> s.release
        else -> null
    } ?: return
    // Its own window: it gets the remote's focus even over pages that trap focus (settings, players).
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (s !is UpdateState.Downloading) vm.later() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
    UpdateDialog(
        state = s,
        release = release,
        current = vm.currentVersion,
        onUpdate = { vm.download(release) },
        onInstall = { vm.install(release) },
        onPermission = { if (!vm.openPermission()) vm.install(release) },
        onLater = vm::later,
    )
    }
}

@Composable
private fun UpdateDialog(
    state: UpdateState,
    release: Release,
    current: String,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onPermission: () -> Unit,
    onLater: () -> Unit,
) {
    val first = remember { FocusRequester() }
    // Refocus whenever the buttons change (e.g. download finished, permission needed).
    LaunchedEffect(state::class) { awaitFrame(); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (release.prerelease) "Test version available" else "Update available",
                fontSize = 12.sp, color = AppColors.Accent,
            )
            Text("Viewarr ${release.version}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Text("You have $current. Your settings are kept.", fontSize = 12.sp, color = AppColors.TextSecondary)
            if (release.notes.isNotBlank()) {
                Box(Modifier.fillMaxWidth().heightIn(max = 140.dp).background(AppColors.PanelAlt, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    Text(
                        release.notes, fontSize = 12.sp, lineHeight = 17.sp, color = AppColors.TextPrimary,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
            when (state) {
                is UpdateState.Downloading -> {
                    Text("Downloading… ${(state.progress * 100).toInt()}%", fontSize = 15.sp, color = AppColors.TextPrimary)
                    Box(Modifier.fillMaxWidth().height(6.dp).background(AppColors.PanelAlt, RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(state.progress.coerceIn(0f, 1f)).background(AppColors.Accent, RoundedCornerShape(3.dp)))
                    }
                    // Something must hold focus inside the box while downloading.
                    Row { OutlinedButton(onClick = {}, modifier = Modifier.focusRequester(first)) { Text("Please wait") } }
                }
                is UpdateState.NeedsPermission -> {
                    Text(
                        "One-time step: Android needs your OK for Viewarr to install apps. Open the setting, switch Viewarr on, " +
                            "then press Back to return here and the update installs.",
                        fontSize = 14.sp, lineHeight = 20.sp, color = AppColors.TextPrimary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onPermission, modifier = Modifier.focusRequester(first)) { Text("Open the setting") }
                        OutlinedButton(onClick = onInstall) { Text("I've done it") }
                        OutlinedButton(onClick = onLater) { Text("Later") }
                    }
                }
                is UpdateState.Installing -> {
                    Text("Installing… Android will ask you to confirm. Viewarr restarts by itself when it's done.", fontSize = 14.sp, color = AppColors.TextPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onInstall, modifier = Modifier.focusRequester(first)) { Text("Show the install screen again") }
                        OutlinedButton(onClick = onLater) { Text("Later") }
                    }
                }
                is UpdateState.Failed -> {
                    Text(state.message, fontSize = 14.sp, color = AppColors.Accent)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onUpdate, modifier = Modifier.focusRequester(first)) { Text("Try again") }
                        OutlinedButton(onClick = onLater) { Text("Later") }
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onUpdate, modifier = Modifier.focusRequester(first)) {
                        Text("Update now" + (release.apkSize.takeIf { it > 0 }?.let { "  ·  ${it / 1_000_000} MB" } ?: ""))
                    }
                    OutlinedButton(onClick = onLater) { Text("Later") }
                }
            }
        }
    }
}
