package io.github.petebutty.viewarr.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * Settings → Live TV → Home screen rows → Show them on the Android TV home screen. Google's Android TV home shows an
 * app's extra rows only once each is approved, and only the app itself can ask (nothing from outside can switch them
 * on). This asks for each of Viewarr's rows not shown yet, one after another, in the order they were made (the order
 * they then sit in on the home screen): OK on each prompt shows it. Launchers that show apps' rows by themselves
 * (Projectivy) never need it. (Peter, 2026-11-20: the Shield.)
 */
@Composable
internal fun androidHomeApprovalItem(): SettingItem {
    val context = LocalContext.current
    var queue by remember { mutableStateOf<List<Long>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf(0) }
    lateinit var askNext: () -> Unit
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) shown++
        queue = queue.drop(1)
        askNext()
    }
    askNext = {
        val id = queue.firstOrNull()
        if (id == null) {
            status = if (shown > 0) "$shown shown" else "Nothing to show"
        } else {
            val ok = runCatching {
                launcher.launch(android.content.Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContractCompat.EXTRA_CHANNEL_ID, id))
            }.isSuccess
            if (!ok) { queue = emptyList(); status = "This home screen shows rows by itself" }
        }
    }
    val label = status ?: "OK: ask"
    return SettingItem(
        "Show them on the Android TV home screen",
        "For Google's Android TV home (the Shield's): it shows Viewarr's rows only once each is approved. This asks for every row not shown yet, in order; press OK on each. Not needed with Projectivy.",
        listOf(PlainChoice(label)), PlainChoice(label),
        action = {
            shown = 0
            queue = io.github.petebutty.viewarr.data.media.HomeRows.notShown(context)
            status = if (queue.isEmpty()) "All shown already" else null
            askNext()
        },
        onSelect = {},
    )
}
