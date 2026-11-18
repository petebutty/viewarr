package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.NoScale
import io.github.petebutty.viewarr.ui.theme.Tokens

/** Card aspect ratios for [FocusCard]'s `aspect`. */
object CardAspect {
    const val Poster = 2f / 3f
    const val Wide = 16f / 9f
    const val Square = 1f
}

/**
 * The one card / poster highlight: a [Tokens.FocusBorder] (3 dp) accent ring, no growing, the card shape token.
 * Posters: `aspect = CardAspect.Poster`; info-bar / episode cards: `CardAspect.Wide`; cast and artists:
 * `shape = Tokens.RoundShape, aspect = CardAspect.Square`. [aspect] null: size it with [modifier]. [content] is the
 * card's face (an image, initials…), drawn clipped to the shape; put the caption under the card, outside it.
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape = Tokens.CardShape,
    aspect: Float? = null,
    containerColor: Color = AppColors.Panel,
    focusedContainerColor: Color = containerColor,
    /** False: no highlight ring (the poster whose trailer has grown into the panel: the ring goes with it). */
    ring: Boolean = true,
    /** How much it grows when highlighted (1 = not at all; the row posters: Settings → Appearance → Highlight zoom). */
    focusedScale: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = (if (aspect != null) modifier.aspectRatio(aspect) else modifier).then(if (focusedScale != 1f) Modifier.instantFocusScale(focusedScale) else Modifier),
        scale = if (focusedScale == 1f) NoScale else focusScale(focusedScale),
        shape = ClickableSurfaceDefaults.shape(shape),
        border = if (ring) ClickableSurfaceDefaults.border(focusedBorder = focusRing(shape)) else ClickableSurfaceDefaults.border(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = focusedContainerColor,
            pressedContainerColor = focusedContainerColor,
        ),
        content = content,
    )
}

/**
 * [FocusCard]'s look for a card that can't take focus itself (the film player's cards, highlighted by the player):
 * the same ring when [highlighted], no growing.
 */
@Composable
fun FocusCardStatic(
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = Tokens.CardShape,
    aspect: Float? = null,
    containerColor: Color = AppColors.Panel,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        (if (aspect != null) modifier.aspectRatio(aspect) else modifier)
            .clip(shape)
            .background(containerColor)
            .then(if (highlighted) Modifier.border(Tokens.FocusBorder, AppColors.Accent, shape) else Modifier),
        content = content,
    )
}

/** The shared focus ring as a TV [Border], for a Surface that can't use [FocusCard] (e.g. a list row with a ring). */
fun focusRing(shape: Shape = Tokens.CardShape): Border = Border(BorderStroke(Tokens.FocusBorder, AppColors.Accent), shape = shape)
