package io.github.petebutty.viewarr.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Tokens

/**
 * The round mic before a search box (search page 44 dp, library search 36, side menu 34): OK starts / stops voice
 * search ([onClick], e.g. `voice.toggle()`). [listening]: filled with the accent and, with [pulse], rings pulsing out
 * that swell with [level] (0–1, the recogniser's loudness), as Android TV's own search does. Grows a little when
 * highlighted (instantly with List scrolling on Instant). Keys (Left → menu, Up blocked…) go in [modifier].
 */
@Composable
fun VoiceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    listening: Boolean = false,
    level: Float = 0f,
    size: Dp = 44.dp,
    pulse: Boolean = true,
) {
    val big = size >= 40.dp
    val grow = if (big) 1.1f else 1.08f
    Box(contentAlignment = Alignment.Center) {
        if (listening && pulse) MicPulse(level, size)
        Surface(
            onClick = onClick,
            modifier = modifier.size(size).instantFocusScale(grow),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            scale = focusScale(grow),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (listening) AppColors.Accent else AppColors.Panel,
                contentColor = if (listening) AppColors.OnAccent else AppColors.TextPrimary,
                focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.tv.material3.Icon(AppIcons.Mic, contentDescription = "Voice search", modifier = Modifier.size(if (big) 22.dp else 18.dp))
            }
        }
    }
}

/** Listening: two rings pulsing out from the mic in the accent, and a glow that swells with [level]. */
@Composable
private fun MicPulse(level: Float, size: Dp) {
    val t = rememberInfiniteTransition(label = "mic")
    val p by t.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "ring")
    val loud by animateFloatAsState(level, tween(120), label = "level")
    val colour = AppColors.Accent
    Box(Modifier.size(size).drawBehind {
        val r = this.size.minDimension / 2f
        drawCircle(colour.copy(alpha = 0.35f), radius = r * (1.05f + 0.45f * loud))
        for (k in 0..1) {
            val f = (p + k * 0.5f) % 1f
            drawCircle(colour.copy(alpha = 0.6f * (1f - f)), radius = r * (1f + 0.9f * f), style = Stroke(width = 3.dp.toPx()))
        }
    })
}

/**
 * The slim pill search box (magnifier, then the text), as on the search page, the library search and the side menu.
 * Visual only: the caller keeps its own keys (OK to start typing, Down to the results…), focusRequester and
 * height in [modifier]. [readOnly] true until the person presses OK keeps the keyboard from popping up as focus
 * passes. [compact]: the 34–36 dp size (14 sp, smaller magnifier). [listening]: shows [listeningText] in the accent.
 * [litWhenFocused] (side menu): highlighted while not typing = accent fill (border only while typing); otherwise the
 * ring thickens to the accent when focused. [onFocusChanged]: the box's own focus (the decoration tracks it too).
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    compact: Boolean = false,
    listening: Boolean = false,
    listeningText: String = "Listening…",
    litWhenFocused: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onFocusChanged: (Boolean) -> Unit = {},
    /** False: a plain text box (no magnifying glass, Done on the keyboard), for typing a value rather than searching. */
    search: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val font = if (compact) 14.sp else 15.sp
    val lit = litWhenFocused && focused && readOnly
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        readOnly = readOnly,
        cursorBrush = SolidColor(AppColors.Accent),
        textStyle = TextStyle(fontSize = font, color = AppColors.TextPrimary),
        keyboardOptions = KeyboardOptions(imeAction = if (search) ImeAction.Search else ImeAction.Done),
        keyboardActions = keyboardActions,
        modifier = modifier.onFocusChanged { focused = it.isFocused; onFocusChanged(it.isFocused) },
        decorationBox = { inner ->
            val ring = when {
                litWhenFocused -> if (!readOnly) Modifier.border(2.dp, AppColors.Accent, Tokens.PillShape) else Modifier
                else -> Modifier.border(if (focused) 2.dp else 1.dp, if (focused) AppColors.Accent else AppColors.TextSecondary.copy(alpha = 0.4f), Tokens.PillShape)
            }
            Row(
                Modifier.fillMaxSize()
                    .background(if (lit) AppColors.Accent else AppColors.Panel, Tokens.PillShape)
                    .then(ring)
                    .padding(horizontal = if (compact) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (search) {
                    androidx.compose.material3.Icon(
                        AppIcons.Search, contentDescription = null, tint = if (lit) AppColors.OnAccent else AppColors.TextSecondary,
                        modifier = Modifier.size(if (compact) 16.dp else 20.dp),
                    )
                    Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (listening) Text(listeningText, fontSize = font, color = if (lit) AppColors.OnAccent else AppColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else if (value.isEmpty()) Text(
                        placeholder, fontSize = font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (lit) AppColors.OnAccent else AppColors.TextSecondary,
                    )
                    inner()
                }
            }
        },
    )
}

/**
 * A round icon button in the search bar's style (the mic's: a dark disc, lit with the accent when highlighted): the
 * menu's Settings gear beside the mic and Search. (Peter, 2026-11-17: the gear was a small dim "⚙".)
 */
@Composable
fun RoundBarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val grow = if (size >= 40.dp) 1.1f else 1.08f
    Surface(
        onClick = onClick,
        modifier = modifier.size(size).instantFocusScale(grow),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = focusScale(grow),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.Accent, focusedContentColor = AppColors.OnAccent,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.tv.material3.Icon(icon, contentDescription = label, modifier = Modifier.size(if (size >= 40.dp) 22.dp else 18.dp))
        }
    }
}
