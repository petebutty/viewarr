package io.github.petebutty.viewarr.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Tokens
import kotlinx.coroutines.android.awaitFrame

/**
 * The one pop-up container: its own window ([ViewarrPopup]: the remote can't be left behind it, the held OK that
 * opened it is ignored), a light dim, and a centred glass panel ([Tokens.PopupRadius]) of [width]
 * ([Tokens.PopupNarrow] / [Tokens.PopupStandard] / [Tokens.PopupWide]) that scrolls when taller than the screen and
 * keeps the highlight inside. Back closes it ([onClose]). Use it for any pop-up that isn't a plain list of options
 * (those use [OptionsPopup]); request focus on your first control yourself.
 */
@Composable
fun PopupPanel(
    onClose: () -> Unit,
    width: Dp = Tokens.PopupStandard,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onClose)
    BackLayer()
    ViewarrPopup(onClose = onClose) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
            Column(
                modifier.width(width)
                    .heightIn(max = (io.github.petebutty.viewarr.ui.common.LocalCanvas.current.height * 0.92f))
                    .glass(Tokens.PopupRadius)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 20.dp)
                    .trapFocus(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
    }
}

/** The title (and optional subtitle) at the top of a [PopupPanel], as every options pop-up shows it. */
@Composable
fun PopupTitle(title: String, subtitle: String? = null) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (subtitle.isNullOrBlank()) Spacer(Modifier.height(8.dp))
    else Text(
        subtitle, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
    )
}

/**
 * One choice in an [OptionsPopup]. [destructive]: asks first ("Delete recording?" with "Keep it" highlighted, so a
 * stray OK never deletes anything); [confirm] is that question's small print ("It can't be undone."), and
 * [confirmTitle] overrides the question (default: the label without "…", plus "?"). [keepOpen]: the pop-up stays
 * open after it (e.g. a choice that shows a message); otherwise it closes once the choice has run.
 */
data class PopupOption(
    val label: String,
    val destructive: Boolean = false,
    val confirm: String? = null,
    val confirmTitle: String? = null,
    val keepOpen: Boolean = false,
    val onClick: () -> Unit,
)

/** The "Move" option that picks a row up for [Modifier.reorderable] (see [Carry]). */
fun Carry.moveOption(id: String, label: String = "Move"): PopupOption = PopupOption(label) { pickUp(id) }

/**
 * The standard hold-OK menu: [title], optional [subtitle] / [description], the [options] in order with the
 * highlight on the first, then "Cancel" always last (don't add your own Cancel / Close). The OK held to open it is
 * ignored. A choice runs, then the pop-up closes ([onClose]) unless it's `keepOpen`; destructive ones confirm first.
 * [message]: a status line under the choices (e.g. "Working…").
 */
@Composable
fun OptionsPopup(
    title: String,
    options: List<PopupOption>,
    onClose: () -> Unit,
    subtitle: String? = null,
    description: String? = null,
    message: String? = null,
    width: Dp = Tokens.PopupStandard,
) {
    var asking by remember { mutableStateOf<PopupOption?>(null) }
    PopupPanel(onClose = { if (asking != null) asking = null else onClose() }, width = width) {
        val q = asking
        if (q == null) {
            PopupTitle(title, subtitle)
            description?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = AppColors.TextPrimary, maxLines = 7, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 14.dp))
            }
            val rows: List<Pair<String, () -> Unit>> = options.map { o ->
                val run: () -> Unit = {
                    if (o.destructive) asking = o
                    else { o.onClick(); if (!o.keepOpen) onClose() }
                }
                o.label to run
            } + ("Cancel" to onClose)
            OptionRows(rows, key = "main")
        } else {
            PopupTitle(q.confirmTitle ?: (q.label.trimEnd('…', '.', ' ') + "?"), q.confirm)
            OptionRows(listOf(
                "Keep it" to { asking = null },
                q.label.trimEnd('…', '.', ' ') to { asking = null; q.onClick(); if (!q.keepOpen) onClose() },
            ), key = "confirm")
        }
        message?.let { Text(it, fontSize = 13.sp, color = AppColors.Accent, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** The choices as compact menu rows, the first one highlighted when shown (again each time [key] changes). */
@Composable
private fun OptionRows(rows: List<Pair<String, () -> Unit>>, key: String) {
    val first = remember(key) { FocusRequester() }
    LaunchedEffect(key) { awaitFrame(); runCatching { first.requestFocus() } }
    rows.forEachIndexed { i, (label, action) ->
        MenuItem(text = label, selected = false, onClick = action, compact = true, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
    }
}
