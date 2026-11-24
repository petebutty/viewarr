package io.github.petebutty.viewarr.ui.home

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.ui.media.VodHost

/**
 * Android's own picture-in-picture (2026-11-24): pressing Home while a channel or a film plays keeps it playing
 * in a small window over the Android home screen. Nothing playing: Home leaves as before.
 */
object AndroidPip {
    /** In Android's small window now (MainActivity sets it). */
    var active by mutableStateOf(false)
    /** Something is playing that would go into the window (the home screen sets it). */
    var wanted by mutableStateOf(false)

    private fun canPip(a: Activity) = a.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** When Viewarr last opened a screen of Android's itself (a permission, approving home-screen rows). Older Android
     *  calls that "leaving" too, and it mustn't shrink Viewarr into the window behind it. */
    @Volatile private var ownLaunchAt = 0L
    fun ownLaunch(a: Activity? = null) {
        ownLaunchAt = android.os.SystemClock.elapsedRealtime()
        // Android 12+: going into the window by itself is switched off until Viewarr is back (MainActivity.onResume
        // calls update), so its own screen doesn't shrink it either.
        if (a != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canPip(a)) runCatching {
            a.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }

    /**
     * Android 12 and later: told ahead whether leaving should go into the window, so Android does it itself when Home
     * is pressed. (2026-11-24: on the AM9 Home went straight to the launcher; asking only once Home was pressed
     * came too late there.)
     */
    fun update(a: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canPip(a)) return
        runCatching {
            a.setPictureInPictureParams(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
                .setAutoEnterEnabled(wanted).setSeamlessResizeEnabled(true).build())
        }.onFailure { android.util.Log.w("AndroidPip", "couldn't set: ${it.message}") }
    }

    /** Home was pressed (MainActivity.onUserLeaveHint): older Android, into the window now. */
    fun enter(a: Activity): Boolean {
        android.util.Log.i("AndroidPip", "leaving: playing=$wanted")
        if (!wanted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !canPip(a)) return false
        if (android.os.SystemClock.elapsedRealtime() - ownLaunchAt < 2_000) return false
        return runCatching { a.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
            .onFailure { android.util.Log.w("AndroidPip", "couldn't enter: ${it.message}") }.getOrDefault(false)
    }
}

/**
 * The home screen's part: says whether something's playing, and while in the window shows just the picture (the film
 * or channel full screen), putting things back as they were on coming back to Viewarr.
 */
@Composable
internal fun AndroidPipEffects(hs: HomeState, playing: ChannelListItem?) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? Activity
    SideEffect { AndroidPip.wanted = playing != null || VodHost.active }
    LaunchedEffect(AndroidPip.wanted) { activity?.let { AndroidPip.update(it) } }
    var before by remember { mutableStateOf<Triple<Boolean, Boolean, Boolean>?>(null) }
    LaunchedEffect(AndroidPip.active) {
        if (AndroidPip.active) {
            before = Triple(hs.fullscreen, VodHost.mini, VodHost.behind)
            if (VodHost.active) { VodHost.behind = false; VodHost.mini = false }
            else if (playing != null) hs.fullscreen = true
        } else before?.let { (full, mini, behind) ->
            before = null
            if (VodHost.active) { VodHost.mini = mini; VodHost.behind = behind } else hs.fullscreen = full
        }
    }
}
