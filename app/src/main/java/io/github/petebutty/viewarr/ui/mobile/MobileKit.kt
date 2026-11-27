package io.github.petebutty.viewarr.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.common.drawViewarrMark
import io.github.petebutty.viewarr.ui.settings.SettingCategory
import io.github.petebutty.viewarr.ui.settings.SettingItem
import io.github.petebutty.viewarr.ui.setup.MaskAllButLast
import io.github.petebutty.viewarr.ui.theme.AppColors

/*
 * The pieces every phone settings page is made of: a page with a back arrow and a big title, section titles, rounded
 * cards of rows (a tap opens or changes something; a switch for on / off), big buttons, text boxes, notes.
 */

/** A phone settings page: back arrow and title at the top, then [content] in a scrolling list. Back (the phone's too) runs [onBack]. */
@Composable
internal fun PhonePage(title: String, onBack: (() -> Unit)?, subtitle: String? = null, content: LazyListScope.() -> Unit) {
    // (No [onBack]: no back arrow, and Back is the phone's own: the first run, with nothing behind it.)
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    Box(Modifier.fillMaxSize().background(MobileColors.Background)) {
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item(key = "top") {
                BrandBar(title, onBack)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = AppColors.TextSecondary, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp))
                }
            }
            content()
        }
    }
}

@Composable
internal fun GroupTitle(name: String, note: String? = null) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp)) {
        Text(name, color = AppColors.TextSecondary, fontSize = 15.sp)
        note?.takeIf { it.isNotBlank() }?.let { Text(it, color = AppColors.TextSecondary.copy(alpha = 0.75f), fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
internal fun PhoneCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MobileColors.Card)) { content() }
}

@Composable
internal fun RowLine(indent: Boolean = false) {
    Box(Modifier.padding(start = if (indent) 58.dp else 18.dp).fillMaxWidth().height(1.dp).background(MobileColors.Line))
}

/** Rows in a card with lines between them. */
@Composable
internal fun CardRows(rows: List<@Composable () -> Unit>) {
    if (rows.isEmpty()) return
    PhoneCard { rows.forEachIndexed { i, r -> if (i > 0) RowLine(); r() } }
}

/**
 * One row: [title], a line under it, and on the right [trailing] (a status or the current choice) or a › when it opens
 * something. [dim]: shown but not usable now.
 */
@Composable
internal fun PhoneRow(
    title: String,
    note: String? = null,
    trailing: String? = null,
    icon: ImageVector? = null,
    dim: Boolean = false,
    danger: Boolean = false,
    opens: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = AppColors.Accent, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = when { danger -> AppColors.Error; dim -> AppColors.TextSecondary; else -> Color.White }, fontSize = 17.sp)
            note?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = AppColors.TextSecondary, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
        trailing?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.width(12.dp))
            Text(it, color = if (dim) AppColors.TextSecondary else AppColors.Accent, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 160.dp))
        }
        if (opens) Text("  ›", color = AppColors.TextSecondary, fontSize = 26.sp)
    }
}

/** An on / off row: a tap anywhere on it flips the switch. */
@Composable
internal fun PhoneSwitch(title: String, note: String? = null, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!on) }.heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else AppColors.TextSecondary, fontSize = 17.sp)
            note?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = AppColors.TextSecondary, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = { onChange(it) }, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Accent, checkedThumbColor = Color.White))
    }
}

/** A big button: [primary] filled in the app's colour, otherwise outlined. */
@Composable
internal fun PhoneButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onTap: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier.heightIn(min = 54.dp).clip(shape)
            .then(if (primary) Modifier.background(if (enabled) AppColors.Accent else AppColors.Accent.copy(alpha = 0.4f)) else Modifier.border(1.5.dp, MobileColors.Line, shape))
            .clickable(enabled = enabled, onClick = onTap).padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else Color.White.copy(alpha = 0.6f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

/** Buttons under a form, full width one above the other (easy to hit with a thumb). */
@Composable
internal fun ButtonColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/**
 * A text box. Keys and tokens show as dots except the last four characters; passwords all dots. [onDone]: the
 * keyboard's Done / Go.
 */
@Composable
internal fun PhoneField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    last: Boolean = false,
    onDone: () -> Unit = {},
) {
    val secret = Regex("(?i)\\b(api key|key|token|secret)\\b").containsMatchIn(label)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        visualTransformation = when {
            password -> PasswordVisualTransformation()
            secret -> MaskAllButLast(4)
            else -> VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret && !password) KeyboardType.Password else keyboard,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, color = Color.White),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AppColors.Accent, unfocusedBorderColor = MobileColors.Line,
            focusedLabelColor = AppColors.Accent, unfocusedLabelColor = AppColors.TextSecondary,
            cursorColor = AppColors.Accent, focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        ),
    )
}

/** A line of text on a page: plain, in the app's colour ([accent]) or red ([error]). */
@Composable
internal fun PhoneNote(text: String, accent: Boolean = false, error: Boolean = false) {
    Text(text, color = when { error -> AppColors.Error; accent -> AppColors.Accent; else -> AppColors.TextSecondary }, fontSize = 15.sp, lineHeight = 21.sp,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
}

/** A code to type somewhere else (Trakt, Jellyfin Quick Connect), large. */
@Composable
internal fun PhoneBigCode(code: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(code, color = AppColors.Accent, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MobileColors.Card).padding(horizontal = 22.dp, vertical = 10.dp))
    }
}

/**
 * Settings in sections (the TV app's settings lists, drawn for a phone): each section a card, a switch for on / off,
 * the current choice on the right of the rest (a tap opens the choices from the bottom), action rows run on a tap.
 */
@Composable
internal fun CategoriesPage(title: String, categories: List<SettingCategory>, onBack: () -> Unit, onReset: (() -> Unit)? = null, resetLabel: String = "Reset to defaults") {
    var choosing by remember { mutableStateOf<SettingItem?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var askReset by remember { mutableStateOf(false) }
    PhonePage(title, onBack) {
        categories.forEachIndexed { ci, c ->
            item(key = "c$ci:${c.name}") {
                GroupTitle(c.name, c.blurb.takeIf { it.length < 140 })
                PhoneCard {
                    c.items.forEachIndexed { i, s ->
                        if (i > 0) RowLine()
                        SettingRowMobile(s) {
                            when {
                                s.locked != null -> note = s.locked
                                s.action != null -> s.action.invoke()
                                s.isToggle -> s.onSelect(s.choices.first { it != s.current })
                                s.choices.size > 1 -> choosing = s
                            }
                        }
                    }
                }
            }
        }
        if (onReset != null) item(key = "reset") {
            Spacer(Modifier.height(18.dp))
            PhoneCard { PhoneRow(resetLabel, danger = true, onTap = { askReset = true }) }
        }
    }
    choosing?.let { s ->
        Chooser(s.title, s.description, s.choices.map { it.label }, s.choices.indexOf(s.current), onPick = { i -> s.onSelect(s.choices[i]); choosing = null }, onDismiss = { choosing = null })
    }
    note?.let { n -> Chooser("Can't be changed here", n, listOf("OK"), null, onPick = { note = null }, onDismiss = { note = null }) }
    if (askReset && onReset != null) Chooser("$resetLabel?", "Every setting on this page goes back to how it started.", listOf(resetLabel, "Cancel"), null,
        onPick = { i -> askReset = false; if (i == 0) onReset() }, onDismiss = { askReset = false })
}

@Composable
internal fun SettingRowMobile(s: SettingItem, onTap: () -> Unit) {
    if (s.isToggle) PhoneSwitch(s.title, s.description, on = s.current.label == "On", enabled = s.locked == null) { onTap() }
    else PhoneRow(s.title, s.description, trailing = s.current.label, dim = s.locked != null, onTap = onTap)
}

/** A question with a text box, from the bottom of the screen: Save hands back what was typed; tapping outside cancels. */
@Composable
internal fun TextSheet(title: String, note: String?, initial: String, label: String = "", keyboard: KeyboardType = KeyboardType.Text, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    var text by remember { mutableStateOf(initial) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color(0xFF1E1E1E))
                .clickable(enabled = false) {}.imePadding().padding(top = 12.dp, bottom = 16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(MobileColors.Line))
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp))
            note?.takeIf { it.isNotBlank() }?.let { Text(it, color = AppColors.TextSecondary, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp)) }
            Spacer(Modifier.height(8.dp))
            PhoneField(text, { text = it }, label.ifBlank { title }, keyboard, last = true, onDone = { onDone(text) })
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhoneButton("Cancel", Modifier.weight(1f), primary = false, onTap = onDismiss)
                PhoneButton("Save", Modifier.weight(1f)) { onDone(text) }
            }
        }
    }
}

/**
 * Every page's top bar: the Previewarr mark on the left (after a back arrow on pages you go back from), the page's name
 * in the middle, its buttons on the right ([actions]: search, settings…). The name stays centred whatever's either side.
 */
@Composable
internal fun BrandBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TopBarIcon(AppIcons.Back, "Back", onBack)
            androidx.compose.foundation.Canvas(Modifier.padding(start = if (onBack != null) 2.dp else 14.dp).size(32.dp)) { drawViewarrMark() }
        }
        Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(horizontal = 104.dp))
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            // The main pages: Devices (Play on TV's devices, what they're playing, their controls) beside their own buttons.
            if (onBack == null && MobilePlayOnTv.key != null) TopBarIcon(AppIcons.Tv, "Devices") { MobileDevices.open = true }
            actions()
        }
    }
}
