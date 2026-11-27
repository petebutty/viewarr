package io.github.petebutty.viewarr.ui.launcher

import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.petebutty.viewarr.data.cast.PlayOnTv
import io.github.petebutty.viewarr.data.media.DeepLink
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.home.Page

/**
 * Previewarr on a TV: takes Play on TV requests from Previewarr Mobile on the same network (PlayOnTv), from a phone
 * signed in to the same Dispatcharr account only. Announced under the TV's own name (Android's device name). Anything
 * over the pages (Settings, the Library pages) is closed first, as the remote's own buttons would.
 */
@Composable
internal fun PlayOnTvHost(session: Session?) {
    if (!Launcher.enabled || io.github.petebutty.viewarr.BuildConfig.MOBILE) return
    val context = LocalContext.current
    val key = remember(session) { PlayOnTv.keyOf(session) }
    SideEffect { PlayOnTv.ownKey = key }
    DisposableEffect(Unit) {
        PlayOnTv.onLink = { uri ->
            // Another app in front (or Previewarr in the background): Previewarr comes forward to play it.
            runCatching {
                context.startActivity(android.content.Intent(context, io.github.petebutty.viewarr.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            Launcher.settingsOpen = false
            LauncherLibrary.close()
            // A recording on the home screen: Viewarr's screens play it (they aren't running there).
            if (uri.host == "recording" && Launcher.home) Launcher.open(Page.Recordings)
            DeepLink.handle(uri)
        }
        val name = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
        PlayOnTv.start(context, name)
        onDispose { PlayOnTv.stop(context) }
    }
}
