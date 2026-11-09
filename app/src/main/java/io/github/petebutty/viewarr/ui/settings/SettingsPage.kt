package io.github.petebutty.viewarr.ui.settings

import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import io.github.petebutty.viewarr.ui.theme.appBackground

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.trapFocus
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame

/** One setting: a toggle (two choices called On/Off) or a list of choices. */
class SettingItem(
    val title: String,
    val description: String,
    val choices: List<RemoteChoice>,
    val current: RemoteChoice,
    /** An action row (e.g. "Check now"): OK runs this, and [current]'s label shows its status. */
    val action: (() -> Unit)? = null,
    val onSelect: (RemoteChoice) -> Unit,
) {
    val isToggle get() = action == null && choices.size == 2 && choices.map { it.label }.toSet() == setOf("On", "Off")
}

class SettingCategory(val name: String, val blurb: String, val items: List<SettingItem>)

/**
 * Settings pages: Left from the right-hand side goes back to the entry selected on the left (it went to whichever was
 * nearest on screen). Only when nothing on the right used the Left itself.
 */
fun Modifier.leftReturnsTo(target: () -> FocusRequester?): Modifier = onKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) target()?.let { runCatching { it.requestFocus() }.isSuccess } ?: false
    else false
}

/**
 * Full-screen settings: categories on the left, their settings on the right, each with a plain explanation.
 * OK flips a toggle, or opens the list of choices.
 */
@Composable
fun SettingsPage(
    title: String,
    categories: List<SettingCategory>,
    onBack: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    var category by remember { mutableIntStateOf(0) }
    // The settings list starts at the top for each category (it kept the last one's scroll).
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pageScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(category) { runCatching { listState.scrollToItem(0) } }
    var choosing by remember { mutableStateOf<SettingItem?>(null) }
    val catFocus = remember { categories.map { FocusRequester() } }
    // After a choice list closes, focus goes back to the setting it was opened from (it used to vanish).
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    var returnTo by remember { mutableStateOf<String?>(null) }
    fun closeChoices() {
        returnTo = choosing?.title
        choosing = null
    }
    LaunchedEffect(returnTo) {
        val key = returnTo ?: return@LaunchedEffect
        awaitFrame(); awaitFrame()
        runCatching { rowFocus[key]?.requestFocus() }
        returnTo = null
    }
    BackHandler { if (choosing != null) closeChoices() else onBack() }
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { catFocus[0].requestFocus() }
    }
    Box(Modifier.fillMaxSize().appBackground().trapFocus()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            Column(Modifier.width(300.dp).fillMaxHeight()) {
                Text("Settings", fontSize = 14.sp, color = AppColors.Accent)
                Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.padding(bottom = 20.dp))
                categories.forEachIndexed { i, c ->
                    MenuItem(
                        text = c.name,
                        selected = i == category,
                        onClick = { category = i },
                        modifier = Modifier
                            .focusRequester(catFocus[i])
                            .focusProperties { canFocus = choosing == null }
                            .onFocusChanged { if (it.isFocused) category = i }
                            // Right: the category's first setting (not the one nearest on screen).
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                                    val first = categories[i].items.firstOrNull()?.title ?: return@onPreviewKeyEvent false
                                    pageScope.launch {
                                        runCatching { listState.scrollToItem(0) }
                                        awaitFrame()
                                        runCatching { rowFocus[first]?.requestFocus() }
                                    }
                                    true
                                } else false
                            },
                    )
                }
                Spacer(Modifier.weight(1f))
                onReset?.let {
                    var confirm by remember { mutableStateOf(false) }
                    MenuItem(
                        text = if (confirm) "Press OK again to reset" else "Reset to defaults",
                        selected = false,
                        modifier = Modifier.focusProperties { canFocus = choosing == null }.onFocusChanged { if (!it.isFocused) confirm = false },
                        onClick = { if (confirm) { it(); confirm = false } else confirm = true },
                    )
                }
            }
            Spacer(Modifier.width(40.dp))
            val c = categories[category]
            Column(Modifier.weight(1f).fillMaxHeight().leftReturnsTo { catFocus[category] }) {
                Text(c.blurb, fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 26.dp, bottom = 14.dp, start = 4.dp))
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    itemsIndexed(c.items, key = { _, it -> c.name + it.title }) { _, item ->
                        SettingRow(item, enabled = choosing == null, focus = rowFocus.getOrPut(item.title) { FocusRequester() }) {
                            if (item.action != null) item.action.invoke()
                            else if (item.isToggle) item.onSelect(item.choices.first { it != item.current })
                            else choosing = item
                        }
                    }
                }
            }
        }
        choosing?.let { item -> ChoiceList(item, onDone = ::closeChoices) }
    }
}

@Composable
private fun SettingRow(item: SettingItem, enabled: Boolean, focus: FocusRequester, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).focusProperties { canFocus = enabled },
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Panel,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent,
            focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(item.description, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(20.dp))
            if (item.isToggle) Toggle(item.current.label == "On")
            else Text(item.current.label, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(260.dp))
        }
    }
}

@Composable
private fun Toggle(on: Boolean) {
    Box(
        Modifier.size(width = 48.dp, height = 26.dp)
            // Green / grey so it reads clearly whatever the highlight colour is.
            .background(if (on) Color(0xFF2EBD5A) else Color(0xFF6B6F7A), RoundedCornerShape(13.dp))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).background(Color.White, CircleShape))
    }
}

@Composable
private fun ChoiceList(item: SettingItem, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).background(AppColors.Panel, RoundedCornerShape(14.dp)).padding(24.dp).trapFocus(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Text(item.description, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 12.dp))
            // Focus starts on the current choice; when it isn't one of them (e.g. a "Custom" theme), on the first.
            val start = item.choices.indexOf(item.current).coerceAtLeast(0)
            item.choices.forEachIndexed { i, choice ->
                MenuItem(
                    text = (if (choice == item.current) "✓  " else "     ") + choice.label,
                    selected = choice == item.current,
                    modifier = if (i == start) Modifier.focusRequester(focus) else Modifier,
                    onClick = { item.onSelect(choice); onDone() },
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
