package io.github.petebutty.viewarr.ui.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.media.DeepLink
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.common.rememberVoiceSearch
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.media.VodHost
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The mic (the remote's button, the home screen's mic icon): a small bar listens over whatever is on screen, showing
 * the words as they're heard, and nothing else changes meanwhile. A channel named is tuned to; anything else opens
 * search with the words. Back while it listens: cancelled, still where you were.
 */
object VoiceBar {
    /** Each press of the mic (a press while it's listening starts it again). */
    var asks by mutableIntStateOf(0)
        private set
    fun ask() { asks++ }
}

@Composable
fun VoiceBarHost(modifier: Modifier = Modifier) {
    val guide: GuideViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    // Search with the words (or, with none, the keyboard): from the home screen it comes back home when closed.
    fun search(words: String?) {
        if (Launcher.home) { VodHost.minimise(); Launcher.settingsOpen = false; Launcher.openSearch(words = words) }
        else DeepLink.pendingCommand.value = DeepLink.Command("search", words)
    }
    val voice = rememberVoiceSearch(
        onResult = { said ->
            scope.launch {
                val ch = runCatching { guide.voiceChannel(said) }.getOrNull()
                open = false
                if (ch != null) { Launcher.settingsOpen = false; LauncherLibrary.close(); DeepLink.pendingChannel.value = DeepLink.ChannelRequest(ch.uuid, null) }
                else search(said)
            }
        },
        onKeyboard = { open = false; search(null) },
        partial = true,
    )
    val asks = VoiceBar.asks
    LaunchedEffect(asks) {
        if (asks == 0) return@LaunchedEffect
        open = true
        voice.heard = ""
        awaitFrame()
        if (voice.listening) voice.stop()
        voice.toggle()
        // Not listening after a while (the microphone not allowed, no recogniser), or stopped with nothing heard: gone.
        val began = withTimeoutOrNull(20_000) { snapshotFlow { voice.listening }.first { it } }
        if (began != null) { snapshotFlow { voice.listening }.first { !it }; delay(600) }
        open = false
    }
    if (!open) return
    BackHandler { voice.stop(); open = false }
    Row(
        modifier.padding(bottom = 34.dp).widthIn(max = 620.dp)
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(50))
            .padding(start = 10.dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).graphicsLayer { val s = 1f + 0.25f * voice.level; scaleX = s; scaleY = s }
            .background(AppColors.Accent, CircleShape)) {
            Icon(AppIcons.Mic, contentDescription = null, tint = AppColors.OnAccent, modifier = Modifier.align(Alignment.Center).size(18.dp))
        }
        Text(voice.heard.ifEmpty { "Listening…" }, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            color = if (voice.heard.isEmpty()) Color.White.copy(alpha = 0.7f) else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
