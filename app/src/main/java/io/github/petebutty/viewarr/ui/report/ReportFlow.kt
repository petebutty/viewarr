package io.github.petebutty.viewarr.ui.report

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.report.ChannelReport
import io.github.petebutty.viewarr.playback.PlaybackViewModel
import io.github.petebutty.viewarr.ui.common.OptionsPopup
import io.github.petebutty.viewarr.ui.common.PopupOption
import io.github.petebutty.viewarr.ui.guide.GuideShiftDialog
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.settings.SettingsJump

/**
 * Hold OK on a channel → Report a problem (the guide, the channel list, the player's menu and the Live TV hub's channel
 * cards all come here). What's asked for is shown by [ReportHost], over everything, so no screen has to host it.
 * (2026-11-19.)
 */
object ReportFlow {
    class Target(val channel: ChannelListItem, val streamId: Int?)
    class Pick(val channelId: Int, val channelName: String)

    /** The channel being reported (with the stream it's playing from, when the menu came from the player). */
    var target by mutableStateOf<Target?>(null)
    /** "Choose the right guide" on its own, for a channel the plugin couldn't match (Settings → Reports, the house card). */
    var pick by mutableStateOf<Pick?>(null)

    fun open(channel: ChannelListItem, streamId: Int? = null) { target = Target(channel, streamId) }
    fun openPicker(channelId: Int, channelName: String) { pick = Pick(channelId, channelName) }
}

/** Everything reports show over the app: the report menu and its steps, the guide picker, Settings → Reports, the house card. */
@Composable
fun ReportHost() {
    val vm: ReportsViewModel = hiltViewModel()
    val context = LocalContext.current
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    ReportFlow.target?.let { t -> ReportProblemDialog(t, vm, ::toast) { ReportFlow.target = null } }
    ReportFlow.pick?.let { p ->
        GuidePickerDialog(p.channelName, vm, allowSkip = false, onClose = { ReportFlow.pick = null }, onPick = { row ->
            ReportFlow.pick = null
            if (row != null) vm.submit(ChannelReport.new(p.channelId, p.channelName, ChannelReport.GUIDE_WRONG, epgDataId = row.id)) { applied ->
                toast("Reported · ${p.channelName}" + if (applied) ", applied" else "")
            }
        })
    }
    if (SettingsJump.reports) ReportsScreen(vm) { SettingsJump.reports = false }
    io.github.petebutty.viewarr.ui.settings.GuideSourcesPage.Host()
    HouseNotice.Host()
}

private enum class Step { Menu, PickGuide, Times, Name, Note }

/**
 * The report menu for one channel, then the step each choice needs: the guide picker (Wrong programme guide), the
 * EPG offset slider (Guide times are out), a name, or a note. Each sends one report (ChannelReports), and says so.
 */
@Composable
private fun ReportProblemDialog(t: ReportFlow.Target, vm: ReportsViewModel, toast: (String) -> Unit, onClose: () -> Unit) {
    val ch = t.channel
    var step by remember { mutableStateOf(Step.Menu) }
    val playback: PlaybackViewModel = hiltViewModel()
    val guideVm: GuideViewModel = hiltViewModel()
    fun send(kind: String, epgDataId: Int? = null, offsetMin: Int? = null, name: String? = null, note: String? = null, streamId: Int? = null) {
        vm.submit(ChannelReport.new(ch.id, ch.name, kind, epgDataId, offsetMin, name, note, streamId)) { applied ->
            toast("Reported · ${ch.name}" + if (applied) ", applied" else "")
        }
        onClose()
    }
    when (step) {
        Step.Menu -> OptionsPopup(
            title = "Report a problem", subtitle = ch.name,
            options = listOf(
                PopupOption("Wrong programme guide", keepOpen = true) { step = Step.PickGuide },
                PopupOption("Guide times are out", keepOpen = true) { step = Step.Times },
                // The stream playing (when known) goes with it, and this box's own order learns at once.
                PopupOption("No picture / dead stream") { t.streamId?.let { playback.noteSourceFailed(ch, it) }; send(ChannelReport.DEAD, streamId = t.streamId) },
                PopupOption("Wrong logo") { send(ChannelReport.LOGO) },
                PopupOption("Wrong name", keepOpen = true) { step = Step.Name },
                PopupOption("Something else", keepOpen = true) { step = Step.Note },
            ),
            onClose = onClose,
        )
        Step.PickGuide -> GuidePickerDialog(ch.name, vm, allowSkip = true, onPick = { row -> send(ChannelReport.GUIDE_WRONG, epgDataId = row?.id) }, onClose = onClose)
        // The usual offset slider; the offset it's left at goes in the report (the plugin can make it the house's).
        Step.Times -> GuideShiftDialog(ch, guideVm, onToast = toast, onClose = { send(ChannelReport.GUIDE_TIMES, offsetMin = guideVm.channelShiftMin(ch)) })
        Step.Name -> TextEntryDialog("Wrong name", "What should this channel be called?", "The right name", ch.name, optional = false, onDone = { send(ChannelReport.NAME, name = it) }, onClose = onClose)
        Step.Note -> TextEntryDialog("Something else", "What's wrong with ${ch.name}? A note is optional.", "What's wrong (optional)", "", optional = true, onDone = { send(ChannelReport.OTHER, note = it.ifBlank { null }) }, onClose = onClose)
    }
}
