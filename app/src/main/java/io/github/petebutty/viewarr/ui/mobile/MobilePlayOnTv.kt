package io.github.petebutty.viewarr.ui.mobile

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.petebutty.viewarr.data.cast.PlayOnTv
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.launch

/** Play on TV on the phone: the sign-in's key (set by MobileRoot), and the TV list when it's open. */
internal object MobilePlayOnTv {
    /** PlayOnTv.keyOf this phone's sign-in (null: no Dispatcharr sign-in, so no Play on TV). */
    var key: String? = null
    /** The link to send, while the TV list is open, and what to do once it's playing on the TV. */
    var request by mutableStateOf<Pair<String, () -> Unit>?>(null)
    /** Opens the TV list for [link]; [onSent]: e.g. stop playing here. */
    fun ask(link: String, onSent: () -> Unit = {}) { request = link to onSent }
}

/** The Play on TV button for a page's top bar: opens the list of TVs for [link] (null: nothing to send, no button). */
@Composable
internal fun PlayOnTvButton(link: () -> String?, onSent: () -> Unit = {}) {
    if (MobilePlayOnTv.key == null) return
    TopBarIcon(AppIcons.Tv, "Play on TV") { link()?.let { MobilePlayOnTv.ask(it, onSent) } }
}

/** The list of TVs (Previewarr on the same network), over everything while a Play on TV request is open. */
@Composable
internal fun PlayOnTvSheet() {
    val (link, onSent) = MobilePlayOnTv.request ?: return
    val context = LocalContext.current
    val tvs by PlayOnTv.tvs.collectAsStateWithLifecycle()
    var sending by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { val stop = PlayOnTv.discover(context); onDispose { stop() } }
    fun close() { MobilePlayOnTv.request = null }
    BackHandler { close() }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().clickable(onClick = ::close), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color(0xFF1E1E1E))
                    .clickable(enabled = false) {}.navigationBarsPadding().padding(top = 12.dp, bottom = 24.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(MobileColors.Line))
                Text("Play on TV", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp))
                Text("TVs with Previewarr open on your Wi-Fi.", color = AppColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp))
                Spacer(Modifier.height(10.dp))
                if (tvs.isEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = AppColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Text("Looking for TVs…", color = AppColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(start = 14.dp))
                }
                tvs.forEach { tv ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = sending == null) {
                        val key = MobilePlayOnTv.key ?: return@clickable
                        sending = tv.name
                        scope.launch {
                            PlayOnTv.send(tv, link, key).fold(
                                { Toast.makeText(context, "Playing on ${tv.name}", Toast.LENGTH_SHORT).show(); close(); onSent() },
                                { e -> Toast.makeText(context, e.message ?: "Couldn't reach ${tv.name}", Toast.LENGTH_LONG).show(); sending = null },
                            )
                        }
                    }.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Tv, null, tint = AppColors.Accent, modifier = Modifier.size(26.dp))
                        Text(tv.name, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f).padding(start = 16.dp))
                        if (sending == tv.name) CircularProgressIndicator(color = AppColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
