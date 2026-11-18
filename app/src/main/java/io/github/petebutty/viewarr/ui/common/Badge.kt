package io.github.petebutty.viewarr.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * A small label on a programme or a row ("NEW", "LIVE", "S3 E4", "↺ Catch-up"): one size and shape everywhere.
 * [filled]: the accent fill (the ones that matter most); otherwise a quiet outline in the secondary text colour.
 */
@Composable
fun Badge(text: String, filled: Boolean = true, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    val box = if (filled) modifier.background(AppColors.Accent, shape) else modifier.border(1.dp, AppColors.TextSecondary.copy(alpha = 0.6f), shape)
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = if (filled) AppColors.OnAccent else AppColors.TextSecondary,
        maxLines = 1,
        softWrap = false,
        modifier = box.padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
