package io.github.petebutty.viewarr.ui.report

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.PopupPanel
import io.github.petebutty.viewarr.ui.common.PopupTitle
import io.github.petebutty.viewarr.ui.common.SearchField
import io.github.petebutty.viewarr.ui.common.VoiceButton
import io.github.petebutty.viewarr.ui.common.rememberVoiceSearch
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Tokens
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Choose the right guide": Dispatcharr's guides (EPGData) searched by name, the channel's name filled in to start,
 * each listed with its source. The one pop-up used by every way in: the "Wrong programme guide" report (with "Skip,
 * just report it"), Settings → Live TV → Reports → Needs your pick, and the house card for a pick the plugin couldn't
 * make. [onPick] gets the guide, or null for Skip. (Peter, 2026-11-19.)
 */
@Composable
fun GuidePickerDialog(channelName: String, vm: ReportsViewModel, allowSkip: Boolean, onPick: (EpgRow?) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf(channelName) }
    var results by remember { mutableStateOf<List<EpgRow>?>(null) }
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val field = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    val voice = rememberVoiceSearch(
        onResult = { query = it },
        onKeyboard = { runCatching { field.requestFocus() }; editing = true; scope.launch { awaitFrame(); keyboard?.show() } },
    )
    // The search follows the text, a moment after it stops changing (the first, for the channel's name, straight away).
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(query) { results = null; if (!first) delay(350); first = false; results = vm.searchEpg(query) }
    // Opens on the first result when there is one (the channel's own name usually finds it), else on the search box;
    // once only, so results arriving while typing don't pull the highlight off the box.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(results != null) {
        if (results != null && !landed) { landed = true; awaitFrame(); runCatching { (if (results.isNullOrEmpty()) field else firstRow).requestFocus() } }
    }
    PopupPanel(onClose = onClose, width = Tokens.PopupWide) {
        PopupTitle("Choose the right guide: $channelName", "Dispatcharr's guides by name, each with its source. Pick the one this channel should show.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            // OK opens the keyboard (not just landing on the box); Down goes to the results; Back closes the keyboard first.
            SearchField(
                value = query, onValueChange = { query = it }, placeholder = "Search guides", compact = true,
                readOnly = !editing, listening = voice.listening,
                modifier = Modifier.weight(1f).height(36.dp).focusRequester(field)
                    .onFocusChanged { if (!it.isFocused) { editing = false; keyboard?.hide() } }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (editing) false else { editing = true; scope.launch { awaitFrame(); keyboard?.show() }; true }
                            Key.DirectionDown -> { editing = false; keyboard?.hide(); runCatching { firstRow.requestFocus() }; true }
                            Key.Back -> { editing = false; false }
                            else -> false
                        }
                    },
            )
            Spacer(Modifier.width(8.dp))
            VoiceButton(onClick = { voice.toggle() }, listening = voice.listening, level = voice.level, size = 36.dp)
        }
        Spacer(Modifier.height(8.dp))
        val list = results
        when {
            list == null -> Text("Searching…", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(vertical = 6.dp))
            list.isEmpty() -> Text(
                if (query.isBlank()) "Type a name to search." else "No guide matches \"$query\". Try a shorter name, or part of it.",
                fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(vertical = 6.dp),
            )
            else -> list.forEachIndexed { i, row ->
                MenuItem(
                    text = row.name, trailing = row.source.ifBlank { row.tvgId }, selected = false, compact = true,
                    modifier = if (i == 0) Modifier.focusRequester(firstRow) else Modifier,
                    onClick = { onPick(row) },
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).border(1.dp, AppColors.TextSecondary.copy(alpha = 0.25f), RoundedCornerShape(1.dp)))
        if (allowSkip) MenuItem(text = "Skip, just report it", selected = false, compact = true, onClick = { onPick(null) })
        MenuItem(text = "Cancel", selected = false, compact = true, onClick = onClose)
    }
}

/**
 * A short piece of text by remote or voice (a channel's right name; a note with a report): OK on the box opens the
 * keyboard, the mic listens, Done sends it. [optional]: Done works with nothing typed.
 */
@Composable
fun TextEntryDialog(title: String, subtitle: String, placeholder: String, initial: String, optional: Boolean, onDone: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val field = remember { FocusRequester() }
    val done = remember { FocusRequester() }
    val voice = rememberVoiceSearch(
        onResult = { heard -> text = if (text.isBlank() || text == initial) heard else "$text $heard" },
        onKeyboard = { runCatching { field.requestFocus() }; editing = true; scope.launch { awaitFrame(); keyboard?.show() } },
    )
    LaunchedEffect(Unit) { awaitFrame(); runCatching { field.requestFocus() } }
    PopupPanel(onClose = onClose, width = Tokens.PopupStandard) {
        PopupTitle(title, subtitle)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(
                value = text, onValueChange = { text = it.take(200) }, placeholder = placeholder, compact = true,
                readOnly = !editing, listening = voice.listening, search = false,
                // (The on-screen keyboard's own Done / Search key: finishes too. It did nothing, so "Done" seemed broken.)
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = {
                    editing = false; keyboard?.hide()
                    if (optional || text.isNotBlank()) onDone(text.trim()) else runCatching { done.requestFocus() }
                }),
                modifier = Modifier.weight(1f).height(36.dp).focusRequester(field)
                    .onFocusChanged { if (!it.isFocused) { editing = false; keyboard?.hide() } }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (editing) false else { editing = true; scope.launch { awaitFrame(); keyboard?.show() }; true }
                            Key.DirectionDown -> { editing = false; keyboard?.hide(); runCatching { done.requestFocus() }; true }
                            Key.Back -> { editing = false; false }
                            else -> false
                        }
                    },
            )
            Spacer(Modifier.width(8.dp))
            VoiceButton(onClick = { voice.toggle() }, listening = voice.listening, level = voice.level, size = 36.dp)
        }
        Spacer(Modifier.height(8.dp))
        MenuItem(
            text = if (optional && text.isBlank()) "Send without a note" else "Done", selected = false, compact = true,
            dim = !optional && text.isBlank(), modifier = Modifier.focusRequester(done),
            onClick = { if (optional || text.isNotBlank()) onDone(text.trim()) },
        )
        MenuItem(text = "Cancel", selected = false, compact = true, onClick = onClose)
    }
}
