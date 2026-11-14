package io.github.petebutty.viewarr.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.report.ReportSender
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.launch

/** The Send a report row's (empty) status. */
internal data class ReportLabel(override val label: String) : io.github.petebutty.viewarr.data.settings.RemoteChoice

/** Settings → Troubleshooting → Send a report (shown over everything by the app's root; see [ReportUi]). */
object ReportUi {
    var open by mutableStateOf(false)
}

/**
 * What will be sent, already cleaned (passwords, keys, addresses, names out), with Send and Cancel; then the code to
 * share. Nothing leaves the box until Send.
 */
@Composable
fun ReportScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sources: LiveSourcesViewModel = hiltViewModel()
    val saved by sources.saved.collectAsStateWithLifecycle()
    val active by sources.active.collectAsStateWithLifecycle()
    val kinds = saved.map { if (it.isM3u) "M3U playlist" else if (it.isXc) "IPTV login" else "Dispatcharr" }.groupingBy { it }.eachCount()
        .map { (k, n) -> if (n > 1) "$k ×$n" else k }
    val names = (saved.map { it.username } + listOfNotNull(active?.username)).filter { it.isNotBlank() }
    var report by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ReportSender.build(context, kinds, null, names) } }
    LaunchedEffect(report != null, code) { kotlinx.coroutines.android.awaitFrame(); runCatching { first.requestFocus() } }
    // Its own window, so the Settings page behind (which pulls the highlight back to itself) lets go while it's up.
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(Modifier.width(820.dp).background(AppColors.MenuPanel, RoundedCornerShape(16.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Send a report", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            val c = code
            if (c != null) {
                // (VR- codes are on the 3-day host; VC- is the backup host, which keeps files: still locked, only the developer can open it.)
                Text(if (c.startsWith("VC-")) "Sent (to the backup host, which keeps it; it's locked so only the developer can read it). Share this code in Discord:"
                    else "Sent. Share this code in Discord so the developer can read it (it's deleted after 3 days):", fontSize = 15.sp, color = AppColors.TextSecondary)
                Text(c, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent, modifier = Modifier.padding(vertical = 8.dp))
                MenuItem(text = "Done", selected = false, onClick = onClose, modifier = Modifier.focusRequester(first))
                return@Column
            }
            Text("What this box and Viewarr were doing: the box's model and memory, which kinds of sources you use, and the last part of Viewarr's log. " +
                "Passwords, keys, server addresses, IPs, emails and usernames are taken out, and it's locked so only the developer can read it.",
                fontSize = 14.sp, color = AppColors.TextSecondary)
            // A look at what will be sent (the first lines; it's all cleaned the same way).
            Box(Modifier.fillMaxWidth().height(260.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(report?.lines()?.take(40)?.joinToString("\n") ?: "Getting it ready…", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextSecondary,
                    modifier = Modifier.verticalScroll(rememberScrollState()))
            }
            error?.let { Text(it, fontSize = 14.sp, color = androidx.compose.ui.graphics.Color(0xFFE57373)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(240.dp)) {
                    MenuItem(text = if (sending) "Sending…" else "Send", selected = false, modifier = Modifier.focusRequester(first), onClick = {
                        val r = report ?: return@MenuItem
                        if (sending) return@MenuItem
                        sending = true; error = null
                        scope.launch {
                            ReportSender.send(r).onSuccess { code = it }.onFailure { error = it.message ?: "Couldn't send it" }
                            sending = false
                        }
                    })
                }
                Box(Modifier.width(240.dp)) { MenuItem(text = "Cancel", selected = false, onClick = onClose) }
            }
        }
    }
    }
}
