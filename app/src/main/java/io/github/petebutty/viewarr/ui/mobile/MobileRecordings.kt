package io.github.petebutty.viewarr.ui.mobile

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.petebutty.viewarr.data.dvr.DvrRecording
import io.github.petebutty.viewarr.data.dvr.DvrState
import io.github.petebutty.viewarr.data.dvr.RecordingProgress
import io.github.petebutty.viewarr.data.dvr.RecordingTags
import io.github.petebutty.viewarr.data.dvr.SeriesRule
import io.github.petebutty.viewarr.data.media.PlayerArt
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.media.VodStream
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.dvr.DvrViewModel
import io.github.petebutty.viewarr.ui.dvr.span
import io.github.petebutty.viewarr.ui.guide.channelLogo
import io.github.petebutty.viewarr.ui.media.VodHost
import io.github.petebutty.viewarr.ui.media.VodSource
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.Dates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A recording to play as soon as the Recordings page has it (Play on TV, or Take over from another device). */
internal object MobileRecordingsPending { var id by androidx.compose.runtime.mutableStateOf<Int?>(null) }

/** What the recordings server said about the last thing asked (recorded, cancelled…), as a short message. */
@Composable
internal fun DvrToasts(vm: DvrViewModel) {
    val ctx = LocalContext.current
    val msg by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(msg) {
        val m = msg ?: return@LaunchedEffect
        if (m == "Working…") return@LaunchedEffect
        Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
        vm.clearMessage()
    }
}

/**
 * Recordings on a phone or tablet: Recorded (big picture cards, how far you've watched; a tap plays, sideways like
 * films; a long press to delete) and Coming up (what's set to record, and the series recordings; a tap to cancel).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MobileRecordings(session: Session?, modifier: Modifier) {
    val vm: DvrViewModel = hiltViewModel()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recs by vm.recordings.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val allowed by vm.allowed.collectAsStateWithLifecycle()
    val xc by vm.xc.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    DvrToasts(vm)
    var tab by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf<DvrRecording?>(null) }
    var pickedRule by remember { mutableStateOf<SeriesRule?>(null) }
    var confirmDelete by remember { mutableStateOf<DvrRecording?>(null) }
    // Recordings play in the films' player (VodHost), with where you stopped kept for the bar and Resume.
    val flow = remember { MutableStateFlow<VodStream?>(null) }
    val art = remember { MutableStateFlow<PlayerArt?>(null) }
    val source = remember {
        VodSource(flow, art, onClose = { flow.value = null }, onProgress = { s, state, pos, dur ->
            RecordingProgress.idOf(s.url)?.let { RecordingProgress.record(ctx.applicationContext, it, pos, dur, state) }
        })
    }
    fun play(r: DvrRecording) = scope.launch {
        val s = vm.playback(r)
        if (s == null) Toast.makeText(ctx, "That recording couldn't be played", Toast.LENGTH_SHORT).show()
        else { art.value = RecordingTags.art(ctx.applicationContext, r); flow.value = s; VodHost.show(source) }
    }
    val serverUrl = session?.serverUrl.orEmpty()
    LaunchedEffect(MobileRecordingsPending.id, recs) {
        val id = MobileRecordingsPending.id ?: return@LaunchedEffect
        recs.firstOrNull { it.id == id }?.let { MobileRecordingsPending.id = null; play(it) }
    }
    val recorded = recs.filter { it.state == DvrState.Recorded || it.state == DvrState.Failed }.sortedByDescending { it.start }
    val coming = recs.filter { it.state == DvrState.Recording || it.state == DvrState.Scheduled }.sortedBy { it.start }
    Column(modifier.background(MobileColors.Background)) {
        BrandBar("Recordings") { TopBarIcon(AppIcons.Person, "Settings") { MobileSettingsNav.open = true } }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Recorded (${recorded.size})", "Coming up (${coming.size})").forEachIndexed { i, label ->
                val on = i == tab
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) AppColors.Accent else MobileColors.Card).clickable { tab = i }.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text(label, color = Color.White, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        val note = when {
            xc -> "Recordings need a Dispatcharr server: an IPTV login can't record."
            allowed == false -> "Your Dispatcharr login isn't allowed to record."
            tab == 0 && recorded.isEmpty() -> "Nothing recorded yet. To record, open a channel and tap a programme still to come."
            tab == 1 && coming.isEmpty() && rules.isEmpty() -> "Nothing set to record. Open a channel and tap a programme still to come."
            else -> null
        }
        note?.let { PhoneNote(it) }
        LazyVerticalGrid(
            GridCells.Adaptive(if (tab == 0) 300.dp else 420.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 170.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab == 0) items(recorded, key = { it.id }) { r ->
                val tag = remember(r.id) { RecordingTags.of(ctx, r.id) }
                val ch = channels[r.channelId]
                val picture = tag?.backdrop ?: tag?.poster ?: ch?.let { channelLogo(serverUrl, it) }
                val done = RecordingProgress.fraction(ctx, r.id)
                Column(Modifier.clip(RoundedCornerShape(18.dp)).background(MobileColors.Card)
                    .combinedClickable(onClick = { if (r.hasFile) play(r) else picked = r }, onLongClick = { picked = r })) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                        if (picture != null) AsyncImage(picture, contentDescription = r.title,
                            contentScale = if (tag?.backdrop != null || tag?.poster != null) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().then(if (tag == null) Modifier.padding(36.dp) else Modifier))
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
                        if (r.state == DvrState.Failed) Text("Failed", color = AppColors.Error, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp))
                        if (RecordingProgress.watched(ctx, r.id)) Icon(AppIcons.CheckCircle, "Watched", tint = AppColors.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(26.dp))
                        if (done != null && done > 0.01f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(done).background(AppColors.Accent))
                        }
                    }
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(tag?.name ?: r.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val ep = listOfNotNull(
                            if (r.season != null && r.episode != null) "S${r.season} E${r.episode}" else null,
                            tag?.episodeName ?: r.subTitle?.takeIf { it.isNotBlank() && !it.equals(r.title, ignoreCase = true) },
                        ).joinToString(" · ")
                        if (ep.isNotEmpty()) Text(ep, color = AppColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(ch?.name, Dates.dayTime(r.start)).joinToString(" · "), color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            else {
                items(coming, key = { it.id }) { r ->
                    val ch = channels[r.channelId]
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MobileColors.Card).clickable { picked = r }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                            ch?.let { channelLogo(serverUrl, it) }?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp)) }
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(r.title, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(ch?.name, span(r.start, r.end)).joinToString(" · "), color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (r.state == DvrState.Recording) Text("● REC", color = Color(0xFFE53935), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (rules.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { GroupTitle("Series recordings", "Every episode of these is recorded. Tap one to stop it.") }
                items(rules, key = { "rule:" + it.tvgId + it.title + it.description }) { rule ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MobileColors.Card).clickable { pickedRule = rule }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Record, null, tint = Color(0xFFE53935), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(14.dp))
                        Text(rule.label, color = Color.White, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    picked?.let { r ->
        val opts = buildList<Pair<String, () -> Unit>> {
            when (r.state) {
                DvrState.Recorded -> {
                    if (r.hasFile) add("Play" to { play(r); Unit })
                    if (r.hasFile && MobilePlayOnTv.key != null) add("Play on TV" to { MobilePlayOnTv.ask(io.github.petebutty.viewarr.data.cast.PlayOnTv.recordingLink(r.id)) })
                    add("Delete recording" to { confirmDelete = r })
                }
                DvrState.Failed -> add("Remove from the list" to { vm.cancel(r); Unit })
                DvrState.Recording -> add("Stop recording" to { vm.stop(r); Unit })
                DvrState.Scheduled -> add("Cancel recording" to { vm.cancel(r); Unit })
            }
        }
        Chooser(r.title, listOfNotNull(span(r.start, r.end), r.description?.takeIf { it.isNotBlank() }?.take(300)).joinToString("\n\n"),
            opts.map { it.first }, null, onPick = { i -> picked = null; opts[i].second() }, onDismiss = { picked = null })
    }
    confirmDelete?.let { r ->
        Chooser("Delete \"${r.title}\"?", "The recording is deleted from your Dispatcharr server for everyone.", listOf("Delete", "Cancel"), null,
            onPick = { i -> confirmDelete = null; if (i == 0) vm.cancel(r) }, onDismiss = { confirmDelete = null })
    }
    pickedRule?.let { rule ->
        Chooser(rule.label, "Episodes already recorded are kept.", listOf("Stop series recording", "Cancel"), null,
            onPick = { i -> pickedRule = null; if (i == 0) vm.removeRule(rule) }, onDismiss = { pickedRule = null })
    }
}
