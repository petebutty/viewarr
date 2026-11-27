package io.github.petebutty.viewarr.ui.mobile

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.cast.NowPlaying
import io.github.petebutty.viewarr.data.cast.PlayOnTv
import io.github.petebutty.viewarr.data.cast.PlayOnTvService
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Devices page: open or not (the TV button in the pages' top bar). */
internal object MobileDevices {
    var open by mutableStateOf(false)
}

/**
 * Your devices with Previewarr (TVs, and Previewarr Mobile on tablets), each with what it's playing (asked every few
 * seconds while this page is open) and its controls: play / pause, stop, skip or change channel; Send here (what this
 * phone is playing) and Take over (carry on here with what it's playing; it stops there).
 */
@Composable
internal fun MobileDevicesPage() {
    val context = LocalContext.current
    val key = MobilePlayOnTv.key
    BackHandler { MobileDevices.open = false }
    val found by PlayOnTv.tvs.collectAsStateWithLifecycle()
    // (A tablet that receives isn't listed on itself.)
    val self = remember { if (PlayOnTvService.receives(context)) PlayOnTvService.deviceName(context) else null }
    val tvs = found.filter { it.name != self }
    DisposableEffect(Unit) { val stop = PlayOnTv.discover(context, MobilePlayOnTv.session); onDispose { stop() } }
    val status = remember { mutableStateMapOf<String, NowPlaying.State?>() }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tvs.map { it.name }, tick) {
        if (key == null) return@LaunchedEffect
        while (true) {
            tvs.forEach { tv -> launch { status[tv.name] = PlayOnTv.status(tv, key) } }
            delay(3_000)
        }
    }
    val scope = rememberCoroutineScope()
    val links: MusicLinkViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    fun control(tv: PlayOnTv.Tv, cmd: String) {
        key ?: return
        scope.launch {
            if (!PlayOnTv.control(tv, cmd, key)) Toast.makeText(context, "${tv.name} didn't answer", Toast.LENGTH_SHORT).show()
            delay(600); status[tv.name] = PlayOnTv.status(tv, key)
        }
    }
    Box(Modifier.fillMaxSize().background(MobileColors.Background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item(key = "top") {
                BrandBar("Devices", onBack = { MobileDevices.open = false })
                Text("Your TVs and tablets with Previewarr on this Wi-Fi.", color = AppColors.TextSecondary, fontSize = 15.sp,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
            }
            if (key == null) item(key = "none") { PhoneNote("Devices needs a Dispatcharr sign-in (Settings → Accounts & sources → Live TV).") }
            else if (tvs.isEmpty()) item(key = "looking") {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = AppColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Text("Looking for your devices…", color = AppColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(start = 14.dp))
                }
            }
            items(tvs, key = { it.name }) { tv -> DeviceCard(tv, status[tv.name], status.containsKey(tv.name), { control(tv, it) }) { link ->
                // Send here / Take over.
                scope.launch {
                    if (link == null) {
                        val mine = NowPlaying.linkNow(links.library) ?: return@launch
                        PlayOnTv.send(tv, mine, key!!).fold(
                            { io.github.petebutty.viewarr.data.cast.PlaybackControl.run("stop"); Toast.makeText(context, "Playing on ${tv.name}", Toast.LENGTH_SHORT).show() },
                            { e -> Toast.makeText(context, e.message ?: "Couldn't reach ${tv.name}", Toast.LENGTH_LONG).show() },
                        )
                    } else {
                        // (Music: the TV saves its queue on the server and hands back the link to it.)
                        val take = if (link == MUSIC_HANDOFF) PlayOnTv.handoff(tv, key!!) else link
                        if (take == null) { Toast.makeText(context, "Couldn't take over from ${tv.name}", Toast.LENGTH_LONG).show(); return@launch }
                        MobileDevices.open = false
                        MobileLinks.open(android.net.Uri.parse(take))
                        PlayOnTv.control(tv, "stop", key!!)
                    }
                    delay(800); status[tv.name] = PlayOnTv.status(tv, key!!)
                }
            } }
        }
    }
}

@Composable
private fun DeviceCard(tv: PlayOnTv.Tv, s: NowPlaying.State?, asked: Boolean, onControl: (String) -> Unit, onSendOrTake: (String?) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MobileColors.Card).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Tv, null, tint = AppColors.Accent, modifier = Modifier.size(24.dp))
            Text(tv.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp))
            if (s != null && s.kind != "idle") Text(if (s.paused) "Paused" else "Playing", color = if (s.paused) AppColors.TextSecondary else AppColors.Accent, fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        when {
            !asked -> Text("Checking…", color = AppColors.TextSecondary, fontSize = 15.sp)
            s == null -> Text("Not answering: is Previewarr installed and the device switched on?", color = AppColors.TextSecondary, fontSize = 15.sp)
            s.kind == "idle" -> Text("Nothing playing", color = AppColors.TextSecondary, fontSize = 15.sp)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(112.dp).height(63.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                    s.image?.let { AsyncImage(it, null, contentScale = if (s.kind == "live") ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize().then(if (s.kind == "live") Modifier.padding(8.dp) else Modifier)) }
                        ?: Icon(if (s.kind == "music") AppIcons.MusicNote else AppIcons.Play, null, tint = AppColors.TextSecondary, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(s.title, color = Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    s.sub?.let { Text(it, color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (s.durationMs > 0) Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MobileColors.Track)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f)).background(AppColors.Accent))
                    }
                }
            }
        }
        if (s != null) {
            Spacer(Modifier.height(12.dp))
            val playing = s.kind != "idle"
            if (playing) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                val live = s.kind == "live"
                ControlButton(if (live) AppIcons.SkipPrevious else AppIcons.Rewind, if (live) "Previous channel" else "Back 10 seconds") { onControl(if (live) "previous" else "back") }
                Box(Modifier.size(58.dp).clip(CircleShape).background(AppColors.Accent).clickable { onControl("playpause") }, contentAlignment = Alignment.Center) {
                    Icon(if (s.paused) AppIcons.Play else AppIcons.Pause, if (s.paused) "Play" else "Pause", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                ControlButton(AppIcons.Stop, "Stop") { onControl("stop") }
                ControlButton(if (live) AppIcons.SkipNext else AppIcons.FastForward, if (live) "Next channel" else "On 30 seconds") { onControl(if (live) "next" else "forward") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val mine = NowPlaying.sendable
                if (mine) PhoneButton("Send here", Modifier.weight(1f)) { onSendOrTake(null) }
                val take = s.link ?: if (s.kind == "music") MUSIC_HANDOFF else null
                if (playing && take != null) PhoneButton("Take over", Modifier.weight(1f), primary = !mine) { onSendOrTake(take) }
            }
        }
    }
}

/** Take over of a device's music: its link is asked for at that moment (PlayOnTv.handoff). */
private const val MUSIC_HANDOFF = "music-handoff"

@Composable
private fun ControlButton(icon: ImageVector, label: String, onTap: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}
