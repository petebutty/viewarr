package io.github.petebutty.viewarr.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.R
import kotlinx.coroutines.launch

private val Poppins = FontFamily(Font(R.font.poppins_bold))

/** Opening splash: the aperture turns into place, then "viewarr" slides in beside it. */
@Composable
fun ViewarrSplash() {
    val spin = remember { Animatable(-60f) }
    val logo = remember { Animatable(0.85f) }
    val word = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { spin.animateTo(0f, tween(900, easing = FastOutSlowInEasing)) }
        launch { logo.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        launch { kotlinx.coroutines.delay(350); word.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF080808)), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            // The adaptive-icon foreground is drawn on a 108-unit canvas with the mark in the middle ~62 units.
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(260.dp).graphicsLayer {
                    rotationZ = spin.value
                    scaleX = logo.value
                    scaleY = logo.value
                },
            )
            Spacer(Modifier.width(0.dp))
            Text(
                "viewarr",
                fontFamily = Poppins,
                fontSize = 84.sp,
                color = Color.White,
                modifier = Modifier.graphicsLayer {
                    alpha = word.value
                    translationX = (1f - word.value) * 60f
                },
            )
        }
    }
}
