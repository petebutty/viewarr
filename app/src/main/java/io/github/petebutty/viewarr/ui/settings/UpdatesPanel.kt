package io.github.petebutty.viewarr.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.petebutty.viewarr.data.update.UpdateState
import io.github.petebutty.viewarr.ui.update.UpdateViewModel


/** Settings → Updates: the version you have, a "Check now" button and how automatic checks behave. */
@Composable
fun UpdatesSettingsScreen(onBack: () -> Unit, vm: UpdateViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val status = when (val s = state) {
        UpdateState.Idle -> "OK: check"
        UpdateState.Checking -> "Checking…"
        UpdateState.UpToDate -> "You're up to date"
        is UpdateState.Available -> "Viewarr ${s.release.version} is available"
        is UpdateState.Downloading -> "Downloading ${s.release.version}… ${(s.progress * 100).toInt()}%"
        is UpdateState.NeedsPermission -> "${s.release.version} is ready to install"
        is UpdateState.Installing -> "Installing ${s.release.version}…"
        is UpdateState.Failed -> s.message
    }
    val items = listOf(
        SettingItem(
            title = "Check for updates",
            description = "Looks for a newer Viewarr on GitHub. If there is one, you can install it straight away.",
            choices = emptyList(),
            current = PlainChoice(status),
            onSelect = {},
            action = { vm.check() },
        ),
        onOffItem(
            "Check automatically",
            "Checks when Viewarr starts or comes back on screen (at most every 30 minutes) and offers new versions.",
            prefs.auto,
        ) { vm.setAuto(it) },
        onOffItem(
            "Include test versions",
            "Test versions (pre-releases) get new features first. Viewarr's releases are all test versions for now, so turning this off means no updates yet.",
            prefs.beta,
        ) { vm.setBeta(it) },
    )
    SettingsPage(
        title = "Updates",
        allCategories = listOf(SettingCategory("Updates", "You have Viewarr ${vm.currentVersion}.", items)),
        onBack = onBack,
    )
}
