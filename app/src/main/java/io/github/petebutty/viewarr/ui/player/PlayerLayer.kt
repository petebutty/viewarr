package io.github.petebutty.viewarr.ui.player

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.local.GuideProgram
import io.github.petebutty.viewarr.playback.AspectMode
import io.github.petebutty.viewarr.playback.PlaybackState
import io.github.petebutty.viewarr.playback.PlaybackViewModel
import io.github.petebutty.viewarr.ui.guide.displayTitle
import io.github.petebutty.viewarr.ui.guide.formatNumber
import io.github.petebutty.viewarr.ui.guide.logoUrl
import io.github.petebutty.viewarr.ui.theme.AppColors
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat get() = io.github.petebutty.viewarr.ui.theme.Dates.time
private fun Long.hhmm() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(timeFormat)

@OptIn(UnstableApi::class)
@Composable
fun PlayerLayer(
    vm: PlaybackViewModel,
    fullscreen: Boolean,
    bannerVisible: Boolean,
    current: GuideProgram?,
    next: GuideProgram?,
    now: Long,
    serverUrl: String,
    modifier: Modifier = Modifier,
) {
    val channel by vm.channel.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val catchup by vm.catchup.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    // Read so a new player (after switching source) is attached in update below.
    val playerVersion by vm.playerVersion.collectAsStateWithLifecycle()

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    keepScreenOn = true
                    // Keys are handled in Compose; the view must never take focus from the guide.
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = { view ->
                @Suppress("UNUSED_EXPRESSION") playerVersion
                if (view.player !== vm.player) view.player = vm.player
                view.resizeMode = when (aspect) {
                    AspectMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectMode.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        when (val s = state) {
            PlaybackState.Buffering -> if (fullscreen) {
                Text(
                    "Loading…",
                    fontSize = 16.sp,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            is PlaybackState.Error -> Column(
                Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.message, fontSize = if (fullscreen) 18.sp else 12.sp, color = Color.White)
                if (fullscreen) {
                    Spacer(Modifier.height(8.dp))
                    Text("Press OK to try again", fontSize = 14.sp, color = AppColors.TextSecondary)
                }
            }
            else -> Unit
        }

        if (fullscreen && bannerVisible && channel != null) {
            InfoBanner(
                channel!!, current, next, now, serverUrl,
                catchupPosition = if (catchup != null) position else null,
                paused = paused,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
internal fun InfoBanner(
    channel: ChannelListItem,
    current: GuideProgram?,
    next: GuideProgram?,
    now: Long,
    serverUrl: String,
    catchupPosition: Pair<Long, Long>?,
    paused: Boolean,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, AppColors.Background.copy(alpha = 0.92f), AppColors.Background)))
            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            channel.number?.let(::formatNumber).orEmpty(),
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.width(90.dp),
        )
        Box(Modifier.size(width = 110.dp, height = 62.dp), contentAlignment = Alignment.Center) {
            io.github.petebutty.viewarr.ui.guide.channelLogo(serverUrl, channel)?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f).padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, fontSize = 14.sp, color = AppColors.TextSecondary)
                if (catchupPosition != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (paused) "CATCH-UP · PAUSED" else "CATCH-UP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.OnAccent,
                        modifier = Modifier.background(AppColors.Accent, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                current?.let(::displayTitle) ?: "No guide information",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (current != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${current.startTime.hhmm()} – ${current.endTime.hhmm()}", fontSize = 14.sp, color = Color.White)
                    Spacer(Modifier.width(12.dp))
                    val total = catchupPosition?.second?.takeIf { it > 0 } ?: (current.endTime - current.startTime)
                    val elapsed = catchupPosition?.first ?: (now - current.startTime)
                    val fraction = (elapsed.toFloat() / total).coerceIn(0f, 1f)
                    Box(Modifier.width(220.dp).height(4.dp).background(Color(0x44FFFFFF), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(AppColors.Accent, RoundedCornerShape(2.dp)))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (catchupPosition != null) "${clock(elapsed)} / ${clock(total)}"
                        else "${((current.endTime - now) / 60_000).coerceAtLeast(0)} min left",
                        fontSize = 14.sp,
                        color = AppColors.TextSecondary,
                    )
                }
            }
            if (next != null) {
                Text(
                    "Next  ${next.startTime.hhmm()}  ${displayTitle(next)}",
                    fontSize = 14.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

