package io.github.petebutty.viewarr.ui.homeassistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.homeassistant.HaCard
import io.github.petebutty.viewarr.data.homeassistant.HaClient
import io.github.petebutty.viewarr.data.homeassistant.HaConfig
import io.github.petebutty.viewarr.data.homeassistant.HaEvents
import io.github.petebutty.viewarr.data.homeassistant.HaPlayback
import io.github.petebutty.viewarr.data.homeassistant.HaStore
import io.github.petebutty.viewarr.playback.PlaybackViewModel
import io.github.petebutty.viewarr.ui.common.LiveBehind
import io.github.petebutty.viewarr.ui.common.LocalCanvas
import io.github.petebutty.viewarr.ui.common.glass
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Home Assistant, drawn at the root (AppRoot) over everything: nothing at all until it's set up in Settings → This TV
 * → Home Assistant. Then, while Viewarr is on screen: the WebSocket for pop-ups (closed in the background), live TV's
 * full screen sent as playback events, and the pop-up card itself. The card never takes the remote: playback and
 * every button carry on as normal, and it goes by itself when its time is up.
 */
@Composable
fun HomeAssistantHost() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { HaStore.start(context) }
    val cfg by HaStore.config.collectAsState()
    if (!cfg.ready) return
    if (cfg.popups) {
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(cfg.address, cfg.token) {
            val tv = HaStore.tvName(context)
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { HaEvents.run(cfg, tv) }
        }
        val card by HaEvents.card.collectAsState()
        PopupCard(card, cfg)
    }
    if (cfg.sendPlaying) LiveWatch()
}

/**
 * Live TV on full screen (and each channel change there) as playback events. Only from the first time live TV goes
 * full screen: the live player belongs to the home screen, which is up by then.
 */
@Composable
private fun LiveWatch() {
    var seen by remember { mutableStateOf(false) }
    val fullScreen = LiveBehind.fullScreen
    LaunchedEffect(fullScreen) { if (fullScreen) seen = true }
    if (!seen) return
    val playback: PlaybackViewModel = hiltViewModel()
    LaunchedEffect(playback) {
        combine(snapshotFlow { LiveBehind.fullScreen }, playback.channel) { f, ch -> f to ch?.name }
            .distinctUntilChanged()
            .collect { (f, name) -> HaPlayback.live(f, name) }
    }
    DisposableEffect(Unit) { onDispose { HaPlayback.live(false, null) } }
}

@Composable
private fun PopupCard(card: HaCard?, cfg: HaConfig) {
    // The last card, kept while it fades out.
    val last = remember { arrayOfNulls<HaCard>(1) }
    if (card != null) last[0] = card
    LaunchedEffect(card?.id) {
        val c = card ?: return@LaunchedEffect
        delay(c.seconds * 1000L)
        HaEvents.dismiss(c.id)
    }
    val shown = last[0] ?: return
    val align = when (shown.position) {
        HaCard.Position.TopRight -> Alignment.TopEnd
        HaCard.Position.TopLeft -> Alignment.TopStart
        HaCard.Position.BottomRight -> Alignment.BottomEnd
        HaCard.Position.BottomLeft -> Alignment.BottomStart
        HaCard.Position.Centre -> Alignment.Center
    }
    val width = LocalCanvas.current.width * 0.3f
    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp), contentAlignment = align) {
        AnimatedVisibility(visible = card != null, enter = fadeIn(), exit = fadeOut()) {
            Column(
                // (A solid card under the glass edge: over the guide's text the see-through glass alone was hard to read.)
                Modifier.width(width).background(io.github.petebutty.viewarr.ui.theme.AppColors.palette.main.copy(alpha = 0.94f), RoundedCornerShape(16.dp)).glass(16.dp).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val picture = shown.camera?.let { HaClient.url(cfg, "/api/camera_proxy/$it?width=480") } ?: shown.image
                if (picture != null) HaPicture(picture, live = shown.camera != null, cfg = cfg)
                shown.title?.let {
                    Text(it, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
                shown.message?.let {
                    Text(it, fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 5, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

/**
 * A picture for the card, decoded at card size (480 px across). Home Assistant's own addresses get the token. [live]:
 * a camera, fetched again about once a second (the last picture stays up meanwhile, so it never flickers).
 */
@Composable
private fun HaPicture(url: String, live: Boolean, cfg: HaConfig) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, live) {
        val loader = coil.Coil.imageLoader(context)
        while (true) {
            val request = coil.request.ImageRequest.Builder(context).data(url).size(480, 480).scale(coil.size.Scale.FIT)
                .apply { if (HaClient.isHa(cfg, url)) addHeader("Authorization", "Bearer ${cfg.token}") }
                .apply { if (live) { memoryCachePolicy(coil.request.CachePolicy.DISABLED); diskCachePolicy(coil.request.CachePolicy.DISABLED) } }
                .build()
            (loader.execute(request) as? coil.request.SuccessResult)?.drawable?.let { d ->
                runCatching { d.toBitmap().asImageBitmap() }.getOrNull()?.let { bitmap = it }
            }
            if (!live) break
            delay(1_000)
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}
