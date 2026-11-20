package io.github.petebutty.viewarr.ui.update

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.BuildConfig
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.glass
import io.github.petebutty.viewarr.ui.common.stopAt
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

/**
 * The changelog: every version's release notes (assets/changelog.md, built from the GitHub releases by
 * tools/changelog.py), versions on the left, the highlighted one's notes on the right. Opened from Settings → About →
 * Changelog and from the What's new card.
 */
object Changelog {
    var open by mutableStateOf(false)
}

/** One version: its number, its date and its notes as Markdown (the subset tools/changelog.py writes). */
data class ChangelogVersion(val version: String, val date: String, val notes: String)

fun readChangelog(context: Context): List<ChangelogVersion> = runCatching {
    val text = context.assets.open("changelog.md").bufferedReader().readText()
    Regex("""(?m)^## (.+)$""").findAll(text).toList().let { heads ->
        heads.mapIndexed { i, m ->
            val end = heads.getOrNull(i + 1)?.range?.first ?: text.length
            val (version, date) = m.groupValues[1].split(" · ", limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
            ChangelogVersion(version, date, text.substring(m.range.last + 1, end).trim())
        }
    }
}.getOrDefault(emptyList())

/** Drawn at the root (AppRoot) while [Changelog.open]. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ChangelogHost() {
    if (!Changelog.open) return
    val context = LocalContext.current
    val versions = remember { readChangelog(context) }
    // (Back closes it: the dialog window's own dismiss, below.)
    // The version that's running starts highlighted (or the newest, on a build newer than the notes).
    val start = remember(versions) { versions.indexOfFirst { it.version == BuildConfig.VERSION_NAME }.coerceAtLeast(0) }
    var shown by remember { mutableIntStateOf(start) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    val firstFocus = remember { FocusRequester() }
    // One per version: Left from the notes goes back to the version being read (not the one the screen opened on).
    val versionFocus = remember(versions) { versions.map { FocusRequester() } }
    val notesFocus = remember { FocusRequester() }
    val notesScroll = rememberScrollState()
    var notesFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Focus inside the changelog. The page it opened over (Settings → About, or the What's new card) can still hold
    // the remote for a moment and take it back: keep asking until it's really here (as the profile screens do).
    var inside by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(40) {
            awaitFrame()
            if (inside) return@LaunchedEffect
            runCatching { firstFocus.requestFocus() }
            kotlinx.coroutines.delay(60)
            if (inside) return@LaunchedEffect
        }
    }
    LaunchedEffect(shown) { notesScroll.scrollTo(0) }

    // Its own window (a dialog): Android gives it the remote outright. Drawn in the main window, it lost the remote to
    // the page it opened over (Settings fences focus in, so the changelog could never take it).
    // (Through ViewarrPopup, the shared pop-up window: was a raw Dialog. Peter, 2026-11-17.)
    io.github.petebutty.viewarr.ui.common.ViewarrPopup(onClose = { Changelog.open = false }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = io.github.petebutty.viewarr.ui.theme.Tokens.Gutter, vertical = 36.dp).glass(radius = io.github.petebutty.viewarr.ui.theme.Tokens.PopupRadius).padding(24.dp)
                    // The remote stays in here until it's closed (the page underneath can't take it).
                    .onFocusChanged { inside = it.hasFocus }
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                // The versions.
                Column(Modifier.width(230.dp).fillMaxHeight()) {
                    Text("Changelog", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("You have ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (versions.isEmpty()) {
                        Text("The changelog isn't in this build.", fontSize = 14.sp, color = AppColors.TextSecondary)
                        MenuItem(text = "Close", selected = false, onClick = { Changelog.open = false }, modifier = Modifier.focusRequester(firstFocus))
                    } else LazyColumn(Modifier.stopAt(androidx.compose.ui.focus.FocusDirection.Down), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(versions, key = { _, v -> v.version }) { i, v ->
                            val current = v.version == BuildConfig.VERSION_NAME
                            MenuItem(
                                text = v.version + if (current) "  · yours" else "",
                                selected = i == shown,
                                // OK or Right: into the notes, to scroll them.
                                onClick = { shown = i; runCatching { notesFocus.requestFocus() } },
                                modifier = Modifier
                                    .focusRequester(versionFocus[i])
                                    .then(if (i == start) Modifier.focusRequester(firstFocus) else Modifier)
                                    .onFocusChanged { if (it.isFocused) shown = i }
                                    .onPreviewKeyEvent { e ->
                                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { runCatching { notesFocus.requestFocus() }; true } else false
                                    },
                            )
                        }
                    }
                }
                // The highlighted version's notes. Right (or OK) on a version comes here; Up / Down scroll, Left goes back.
                val v = versions.getOrNull(shown)
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .focusRequester(notesFocus)
                        .onFocusChanged { notesFocused = it.isFocused }
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> { scope.launch { notesScroll.animateScrollTo(notesScroll.value + 260) }; true }
                                Key.DirectionUp -> { scope.launch { notesScroll.animateScrollTo((notesScroll.value - 260).coerceAtLeast(0)) }; true }
                                Key.DirectionLeft, Key.Back -> {
                                    if (e.key == Key.Back) return@onPreviewKeyEvent false
                                    // Back to the version being read: scrolled into view first (an item off screen isn't there to take
                                    // focus, so focus vanished after scrolling down the list), then focused.
                                    scope.launch {
                                        listState.scrollToItem((shown - 3).coerceAtLeast(0))
                                        awaitFrame()
                                        runCatching { versionFocus.getOrNull(shown)?.requestFocus() ?: firstFocus.requestFocus() }
                                    }
                                    true
                                }
                                Key.DirectionRight -> true
                                else -> false
                            }
                        }
                        .focusable()
                        .then(if (notesFocused) Modifier.border(1.dp, AppColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp)) else Modifier)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    if (v != null) {
                        Text("Viewarr ${v.version}", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(v.date.takeIf { it.isNotEmpty() }, if (notesFocused) "▲ ▼ scroll  ·  ◀ versions  ·  Back: close" else "▶ read  ·  Back: close").joinToString("   ·   "),
                            fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                        )
                        Column(Modifier.fillMaxWidth().verticalScroll(notesScroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Notes(v.notes)
                            Spacer(Modifier.height(40.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The release notes' Markdown subset: ### headings, - and nested bullets, paragraphs, **bold** and `code`. */
@Composable
private fun Notes(md: String) {
    md.lines().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.isBlank() -> Spacer(Modifier.height(2.dp))
            line.startsWith("### ") -> Text(
                line.removePrefix("### "), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Accent,
                modifier = Modifier.padding(top = 10.dp),
            )
            Regex("""^(\s*)- """).find(line) != null -> {
                val m = Regex("""^(\s*)- """).find(line)!!
                val depth = (m.groupValues[1].length / 2).coerceAtMost(3)
                Row(Modifier.padding(start = (depth * 20).dp)) {
                    Text(if (depth == 0) "•" else "◦", fontSize = 15.sp, color = AppColors.Accent, modifier = Modifier.width(18.dp))
                    Text(inline(line.substring(m.range.last + 1)), fontSize = 15.sp, lineHeight = 21.sp, color = AppColors.TextPrimary)
                }
            }
            else -> Text(inline(line), fontSize = 15.sp, lineHeight = 21.sp, color = AppColors.TextSecondary)
        }
    }
}

/** **bold** and `code` inside a line. */
private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val pattern = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")
    pattern.findAll(s).forEach { m ->
        append(s.substring(i, m.range.first))
        if (m.groupValues[1].isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)) { append(m.groupValues[1]) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(m.groupValues[2]) }
        i = m.range.last + 1
    }
    append(s.substring(i))
}
