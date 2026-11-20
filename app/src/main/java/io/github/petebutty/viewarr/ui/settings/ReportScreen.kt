package io.github.petebutty.viewarr.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.report.ReportSender
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.launch

/** Settings → Troubleshooting → Send a report (shown over everything by the app's root; see [ReportUi]). */
object ReportUi {
    var open by mutableStateOf(false)
    /** Send a note: just a message (and the box and version), no log. */
    var noteMode by mutableStateOf(false)
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
    // What the tester writes: the whole of a note, or an optional line with a report.
    var note by remember { mutableStateOf("") }
    val noteMode = ReportUi.noteMode
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val noteField = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ReportSender.build(context, kinds, null, names) } }
    LaunchedEffect(report != null, code) { kotlinx.coroutines.android.awaitFrame(); runCatching { if (noteMode && code == null) noteField.requestFocus() else first.requestFocus() } }
    // Its own window, so the Settings page behind (which pulls the highlight back to itself) lets go while it's up.
    // (ViewarrPopup, the shared pop-up window, and its radius: was a raw Dialog. Peter, 2026-11-17.)
    io.github.petebutty.viewarr.ui.common.ViewarrPopup(onClose = onClose) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(Modifier.width(820.dp).background(AppColors.MenuPanel, RoundedCornerShape(io.github.petebutty.viewarr.ui.theme.Tokens.PopupRadius)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (noteMode) "Send a note" else "Send a report", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val c = code
            if (c != null) {
                // (VR- codes are on the 3-day host; VC- is the backup host, which keeps files: still locked, only the developer can open it.)
                Text(if (c.startsWith("VC-")) "Sent (to the backup host, which keeps it; it's locked so only the developer can read it). Share this code in Discord:"
                    else "Sent. Share this code in Discord so the developer can read it (it's deleted after 3 days):", fontSize = 15.sp, color = AppColors.TextSecondary)
                Text(c, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent, modifier = Modifier.padding(vertical = 8.dp), maxLines = 1, softWrap = false)
                MenuItem(text = "Done", selected = false, onClick = onClose, modifier = Modifier.focusRequester(first))
                return@Column
            }
            Text(if (noteMode) "Found a bug, or have an idea? Write it here and it goes to the developer, with just this box's model and the Viewarr version (no log). It's locked so only the developer can read it."
                else "What this box and Viewarr were doing: the box's model and memory, which kinds of sources you use, and the last part of Viewarr's log. " +
                "Passwords, keys, server addresses, IPs, emails and usernames are taken out, and it's locked so only the developer can read it.",
                fontSize = 14.sp, color = AppColors.TextSecondary)
            // The note: OK opens the keyboard (not just landing on the box); Back closes it; Down goes to Send.
            var editing by remember { mutableStateOf(false) }
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            Box(
                Modifier.fillMaxWidth().height(if (noteMode) 150.dp else 56.dp)
                    .background(AppColors.Panel, RoundedCornerShape(10.dp))
                    .border(1.dp, AppColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (note.isEmpty()) Text(if (noteMode) "What happened, or what would you like? (OK: type)" else "Add a note: what went wrong? (optional, OK: type)", fontSize = 15.sp, color = AppColors.TextSecondary)
                androidx.compose.foundation.text.BasicTextField(
                    value = note,
                    onValueChange = { note = it.take(2_000) },
                    maxLines = if (noteMode) 6 else 2,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Accent),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = AppColors.TextPrimary),
                    readOnly = !editing,
                    modifier = Modifier.fillMaxWidth().focusRequester(noteField)
                        .onFocusChanged { if (!it.isFocused) { editing = false; keyboard?.hide() } }
                        .onPreviewKeyEvent { e ->
                            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                androidx.compose.ui.input.key.Key.DirectionDown -> { editing = false; keyboard?.hide(); runCatching { first.requestFocus() }; true }
                                androidx.compose.ui.input.key.Key.DirectionCenter, androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.NumPadEnter ->
                                    if (editing) false else { editing = true; scope.launch { kotlinx.coroutines.android.awaitFrame(); keyboard?.show() }; true }
                                androidx.compose.ui.input.key.Key.Back -> { editing = false; false }
                                else -> false
                            }
                        },
                )
            }
            // A look at what will be sent (the first lines; it's all cleaned the same way). Not for a note.
            if (!noteMode) Box(Modifier.fillMaxWidth().height(220.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text(report?.lines()?.take(40)?.joinToString("\n") ?: "Getting it ready…", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextSecondary,
                    modifier = Modifier.verticalScroll(rememberScrollState()))
            }
            error?.let { Text(it, fontSize = 14.sp, color = androidx.compose.ui.graphics.Color(0xFFE57373)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(240.dp)) {
                    MenuItem(text = if (sending) "Sending…" else "Send", selected = false, modifier = Modifier.focusRequester(first), onClick = {
                        if (sending) return@MenuItem
                        if (noteMode && note.isBlank()) { error = "Write your note first (OK on the box above to type)."; return@MenuItem }
                        if (!noteMode && report == null) return@MenuItem
                        sending = true; error = null
                        scope.launch {
                            // Built again now with the note in it (a note: no log at all).
                            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ReportSender.build(context, kinds, note.ifBlank { null }, names, withLog = !noteMode) }
                            ReportSender.send(r, context).onSuccess { code = it }.onFailure { error = it.message ?: "Couldn't send it" }
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
