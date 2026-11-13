package io.github.petebutty.viewarr.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.settings.Deinterlace
import io.github.petebutty.viewarr.data.settings.Downmix
import io.github.petebutty.viewarr.data.settings.FrameRateMatch
import io.github.petebutty.viewarr.data.settings.LocalPlayback
import io.github.petebutty.viewarr.data.settings.NightMode
import io.github.petebutty.viewarr.data.settings.Passthrough
import io.github.petebutty.viewarr.data.settings.PictureSize
import io.github.petebutty.viewarr.data.settings.PlaybackStore
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.data.settings.VolumeBoost
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.settings.PlaybackSettingsViewModel
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * The info bars' TV settings (live TV and films): the picture and sound settings that suit the TV, changed over the
 * picture. OK steps each to its next choice (the same settings as Settings → Playback). The TV's current mode is shown
 * at the top, so frame rate matching can be seen working.
 */
@Composable
fun TvSettingsPanel(onClose: () -> Unit, modifier: Modifier = Modifier, vm: PlaybackSettingsViewModel = hiltViewModel()) {
    val s = LocalPlayback.current
    val first = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { first.requestFocus() } }
    // The TV's mode now (frame rate matching changes it), read every second while open.
    val activity = LocalContext.current as? android.app.Activity
    var mode by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            mode = activity?.window?.decorView?.display?.mode?.let { m ->
                "${m.physicalWidth}×${m.physicalHeight} · " + "%.3f".format(m.refreshRate).trimEnd('0').trimEnd('.') + " Hz"
            }.orEmpty()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val k = PlaybackStore.Companion
    // (title, key, choices, current, note)
    val rows = listOf<List<Any?>>(
        listOf("Match frame rate", k.FRAME_RATE, FrameRateMatch.entries, s.frameRate, null),
        listOf("Picture size", k.PICTURE_SIZE, PictureSize.entries, s.pictureSize, null),
        listOf("Deinterlace", k.DEINTERLACE, Deinterlace.entries, s.deinterlace, "mpv"),
        listOf("Surround passthrough", k.PASSTHROUGH, Passthrough.entries, s.passthrough, "from the next thing you play"),
        listOf("Channels", k.DOWNMIX, Downmix.entries, s.downmix, null),
        listOf("Night mode", k.NIGHT, NightMode.entries, s.nightMode, "mpv"),
        listOf("Volume boost", k.VOLUME_BOOST, VolumeBoost.entries, s.volumeBoost, "mpv"),
    )
    Column(
        modifier.padding(24.dp).width(420.dp).background(AppColors.MenuPanel, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("TV settings", fontSize = 15.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(start = 12.dp))
        if (mode.isNotEmpty()) Text("TV now: $mode", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        rows.forEachIndexed { i, r ->
            @Suppress("UNCHECKED_CAST") val all = r[2] as List<RemoteChoice>
            val current = r[3] as RemoteChoice
            val note = r[4] as String?
            MenuItem(
                text = r[0] as String + (note?.let { "  ($it)" } ?: ""),
                trailing = current.label, selected = false, compact = true,
                onClick = { vm.set(r[1] as String, all[(all.indexOf(current) + 1) % all.size]) },
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
        Text("OK: next choice  ·  Back: close", fontSize = 11.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
    }
}
