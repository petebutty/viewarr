package io.github.petebutty.viewarr.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * A live score alert from the server (the Viewarr plugin: kick-off, goal, half-time, full time for what you follow),
 * shown for a few seconds at the top of the screen over whatever's on, without taking the remote. (Stage 6, 2026-11-17.)
 */
object AlertBanner {
    /** What's showing (null: nothing). A newer alert replaces it. */
    var text by mutableStateOf<String?>(null)
        private set
    private var seq by mutableStateOf(0)
    fun show(t: String) { text = t; seq++ }

    @Composable
    fun Host() {
        val current = text
        LaunchedEffect(seq) {
            if (text != null) { kotlinx.coroutines.delay(7_000); text = null }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            AnimatedVisibility(
                visible = current != null,
                enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
            ) {
                Text(
                    "🔔  " + (current ?: ""), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 18.dp).widthIn(max = 720.dp)
                        .background(AppColors.Accent.copy(alpha = 0.92f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                )
            }
        }
    }
}
