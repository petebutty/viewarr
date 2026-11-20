package io.github.petebutty.viewarr.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.sync.SettingsShare
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.PopupPanel
import io.github.petebutty.viewarr.ui.common.PopupTitle
import io.github.petebutty.viewarr.ui.report.TextEntryDialog
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Tokens
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Settings codes' pop-ups (shown over everything by the app's root, like Send a report). */
object SettingsCodeUi {
    sealed interface Step {
        data object Sharing : Step
        data class Shared(val code: String) : Step
        data object Entering : Step
        data object Loading : Step
        data class Confirm(val body: JsonObject) : Step
        data class Failed(val message: String) : Step
    }
    var step by mutableStateOf<Step?>(null)
    /** Whether "Undo the last settings code" shows (null: not looked yet). */
    var hasUndo by mutableStateOf<Boolean?>(null)
}

/** Settings → Troubleshooting: Share my settings, Use someone's settings, and Undo after a code was used. */
@Composable
fun settingsCodeItems(): List<SettingItem> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val undo = SettingsCodeUi.hasUndo ?: SettingsShare.hasUndo(context).also { SettingsCodeUi.hasUndo = it }
    return buildList {
        add(SettingItem("Share my settings", "Gives you a code that puts your look, playback and remote settings on another TV. Sign-ins, sources, home rows and libraries aren't in it.",
            listOf(PlainChoice("")), PlainChoice(""), action = {
                SettingsCodeUi.step = SettingsCodeUi.Step.Sharing
                scope.launch {
                    SettingsCodeUi.step = SettingsShare.share(context).fold(
                        { SettingsCodeUi.Step.Shared(it) },
                        { SettingsCodeUi.Step.Failed(it.message ?: "Couldn't share your settings.") },
                    )
                }
            }, onSelect = {}))
        add(SettingItem("Use someone's settings", "Type a code from Share my settings on another TV to use its look, playback and remote settings here.",
            listOf(PlainChoice("")), PlainChoice(""), action = { SettingsCodeUi.step = SettingsCodeUi.Step.Entering }, onSelect = {}))
        if (undo) add(SettingItem("Undo the last settings code", "Puts back the settings this TV had before you last used a code.",
            listOf(PlainChoice("")), PlainChoice(""), action = {
                scope.launch {
                    val ok = SettingsShare.undo(context)
                    SettingsCodeUi.hasUndo = SettingsShare.hasUndo(context)
                    Toast.makeText(context, if (ok) "Your settings are back" else "Nothing to undo", Toast.LENGTH_SHORT).show()
                }
            }, onSelect = {}))
    }
}

@Composable
fun SettingsCodeHost() {
    val step = SettingsCodeUi.step ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val close = { SettingsCodeUi.step = null }
    when (step) {
        SettingsCodeUi.Step.Sharing -> Busy("Sharing your settings…", close)
        SettingsCodeUi.Step.Loading -> Busy("Loading the settings…", close)
        is SettingsCodeUi.Step.Shared -> Message("Your settings code", step.code, "Valid for 3 days. On the other TV: Settings → Troubleshooting → Use someone's settings.", close)
        is SettingsCodeUi.Step.Failed -> Message("Couldn't do that", null, step.message, close)
        SettingsCodeUi.Step.Entering -> TextEntryDialog(
            title = "Use someone's settings", subtitle = "Type the code from the other TV, like VS-ab12cd.",
            placeholder = "VS-", initial = "", optional = false,
            onDone = { code ->
                SettingsCodeUi.step = SettingsCodeUi.Step.Loading
                scope.launch {
                    SettingsCodeUi.step = SettingsShare.fetch(code).fold(
                        { SettingsCodeUi.Step.Confirm(it) },
                        { SettingsCodeUi.Step.Failed(it.message ?: "Couldn't load those settings.") },
                    )
                }
            },
            onClose = close,
        )
        is SettingsCodeUi.Step.Confirm -> Confirm(step.body, onReplace = {
            SettingsCodeUi.step = null
            scope.launch {
                SettingsShare.applyCode(context, step.body)
                SettingsCodeUi.hasUndo = true
                Toast.makeText(context, "Settings applied", Toast.LENGTH_SHORT).show()
            }
        }, onClose = close)
    }
}

@Composable
private fun Busy(title: String, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    PopupPanel(onClose = onClose, width = Tokens.PopupNarrow) {
        PopupTitle(title)
        MenuItem(text = "Cancel", selected = false, compact = true, modifier = Modifier.focusRequester(first), onClick = onClose)
    }
}

@Composable
private fun Message(title: String, big: String?, text: String, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    PopupPanel(onClose = onClose, width = Tokens.PopupStandard) {
        PopupTitle(title)
        big?.let { Text(it, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.padding(vertical = 8.dp), maxLines = 1, softWrap = false) }
        Text(text, fontSize = 14.sp, color = AppColors.TextSecondary)
        Spacer(Modifier.height(12.dp))
        MenuItem(text = "OK", selected = false, compact = true, modifier = Modifier.focusRequester(first), onClick = onClose)
    }
}

@Composable
private fun Confirm(body: JsonObject, onReplace: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    val app = (body["app"] as? JsonPrimitive)?.contentOrNull
    PopupPanel(onClose = onClose, width = Tokens.PopupStandard) {
        PopupTitle("Replace your look, playback and remote settings with these?", "Your sign-ins, sources, home rows and libraries stay as they are.")
        app?.let { Text("Shared from Viewarr $it.", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 8.dp)) }
        MenuItem(text = "Replace", selected = false, compact = true, modifier = Modifier.focusRequester(first), onClick = onReplace)
        MenuItem(text = "Cancel", selected = false, compact = true, onClick = onClose)
    }
}
