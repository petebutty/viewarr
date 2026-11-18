package io.github.petebutty.viewarr.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.appBackground

/** A Settings page that isn't laid out by SettingsMap (its own screen), found by Search settings too. */
class OtherSettingsPage(val title: String, val words: String, val open: () -> Unit)

/**
 * Search settings: type, and every setting whose name, explanation or section matches is listed with where it lives
 * ("Playback → Video"). OK opens that page on it, highlighted. Pages with their own screens (Remote buttons, Sports…)
 * are found by what they cover.
 */
@Composable
fun SettingsSearchScreen(
    onBack: () -> Unit,
    onOpen: (page: String, section: String, title: String) -> Unit,
    others: List<OtherSettingsPage> = emptyList(),
) {
    // Every setting, built here (not where the query is read): typing doesn't build ~200 settings again per key.
    // Rebuilt only when a setting changes, so descriptions that show current values stay true.
    val pool = settingsPool()
    val index = remember(pool) { SettingsMap.index(pool) }
    SettingsSearchResults(index, onBack, onOpen, others)
}

/** Search settings' box and results over a ready [index] (the query lives here, so only this part redraws as you type). */
@Composable
private fun SettingsSearchResults(
    index: List<SettingsMap.Hit>,
    onBack: () -> Unit,
    onOpen: (page: String, section: String, title: String) -> Unit,
    others: List<OtherSettingsPage>,
) {
    var query by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(true) }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { field.requestFocus() }; keyboard?.show() }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun matches(text: String) = words.isNotEmpty() && words.all { text.lowercase().contains(it) }
    // Names first, then explanations; the pages with their own screens after.
    val hits = index.filter { matches(it.title + " " + it.section) } +
        index.filter { !matches(it.title + " " + it.section) && matches(it.description) }
    val otherHits = others.filter { matches(it.title + " " + it.words) }
    Box(Modifier.fillMaxSize().appBackground(readable = true)) {
        // (Below the live TV window when one is up, as SettingsPage: the title and box sat under it.)
        Column(Modifier.fillMaxSize().padding(horizontal = io.github.petebutty.viewarr.ui.theme.Tokens.Gutter, vertical = 40.dp)
            .padding(top = if (SettingsLayout.belowPreview) 170.dp else 0.dp)) {
            Text("Settings", fontSize = 14.sp, color = AppColors.Accent)
            Text("Search settings", fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 16.dp))
            Box(
                Modifier.fillMaxWidth(0.6f).height(48.dp)
                    .background(AppColors.Panel, RoundedCornerShape(10.dp))
                    .border(1.dp, AppColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (query.isEmpty()) Text("Type a setting, e.g. frame rate, wallpaper, subtitles…", fontSize = 15.sp, color = AppColors.TextSecondary)
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    readOnly = !typing,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Accent),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = AppColors.TextPrimary),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                        keyboard?.hide(); runCatching { firstResult.requestFocus() }
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field)
                        .onFocusChanged { if (it.isFocused) typing = true else { typing = false; keyboard?.hide() } }
                        .onPreviewKeyEvent { e ->
                            // Down: the results (the box would keep the remote).
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { keyboard?.hide(); runCatching { firstResult.requestFocus() }.isSuccess }
                            else false
                        },
                )
            }
            Text(
                when {
                    words.isEmpty() -> "Results show as you type. Down: the results  ·  OK: open it  ·  Back: close"
                    hits.isEmpty() && otherHits.isEmpty() -> "Nothing matches \"${query.trim()}\""
                    else -> "${hits.size + otherHits.size} found"
                },
                fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth(0.8f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The place in the list is part of the key: two settings in one section can share a name.
                itemsIndexed(hits, key = { i, it -> "${it.page}/${it.section}/${it.title}#$i" }) { i, h ->
                    Column {
                        MenuItem(
                            text = h.title, trailing = "${h.pageTitle} → ${h.section}", selected = false,
                            onClick = { onOpen(h.page, h.section, h.title) },
                            // (Left: back out, as the Settings lists' left-hand entries; it did nothing here.)
                            modifier = (if (i == 0) Modifier.focusRequester(firstResult) else Modifier).leftGoesBack(onBack),
                        )
                        if (h.description.isNotBlank()) Text(h.description, fontSize = 12.sp, color = AppColors.TextSecondary, maxLines = 1,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
                    }
                }
                items(otherHits, key = { "other/" + it.title }) { o ->
                    MenuItem(
                        text = o.title, trailing = "Settings → ${o.title}", selected = false, onClick = o.open,
                        modifier = (if (hits.isEmpty() && otherHits.indexOf(o) == 0) Modifier.focusRequester(firstResult) else Modifier).leftGoesBack(onBack),
                    )
                }
            }
        }
    }
}
