package io.github.petebutty.viewarr.ui.media

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * The one row title (Movies / Shows rows, Music rows, Recordings' headings): same size, weight and gap to the row
 * under it everywhere (UI review Tier 3: Movies were 16 sp / 6 dp, Music 15 sp / 8 dp, Recordings 14 sp accent).
 * Put the row's own start padding in [modifier]. (2026-11-17.)
 */
@Composable
internal fun RowTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(bottom = RowTitleGap),
    )
}

/** Between a row's title and its cards. (MediaScreen's top-aligned scrolling allows for title + this gap: 28 dp.) */
internal val RowTitleGap = 6.dp

/** Between one row and the next (title included). */
internal val RowGap = 10.dp
