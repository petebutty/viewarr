package io.github.petebutty.viewarr.ui.home

import android.content.Context
import androidx.compose.runtime.getValue
import io.github.petebutty.viewarr.data.local.ChannelListItem
import io.github.petebutty.viewarr.data.media.DeepLink
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.data.settings.RemoteSettings
import io.github.petebutty.viewarr.ui.media.VodHost

/**
 * Where you were when Viewarr went to the background (the Home button, another app), so that if Android clears it
 * while it's away (a 2 GB box short of memory) it opens there again, not afresh (2026-11-15: standard).
 * Saved each time it goes to the background; forgotten when you leave on purpose (Back to exit, or Exit) or once
 * it's been a while. Read once, at a cold start.
 */
object ResumeSpot {
    /** [page]: a Page name; [channelId]: live TV playing ([fullscreen]: full screen, else in the guide's window);
     *  [title]: a film / show playing or open (its viewarr://title link, which opens its page). */
    data class Spot(val page: String, val channelId: Int?, val fullscreen: Boolean, val title: String?)

    private const val PREFS = "resume_spot"
    /** Older than this: a fresh start (the start page you've chosen) instead. */
    private const val KEEP_MS = 6 * 60 * 60_000L

    fun save(ctx: Context, s: Spot) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("page", s.page).putInt("channel", s.channelId ?: -1).putBoolean("full", s.fullscreen)
        .putString("title", s.title).putLong("at", System.currentTimeMillis()).apply()

    /** Looked at already in this process (a cold start reads it once; a warm return never does). */
    @Volatile var checked = false

    /** The spot saved last time, if it's recent (not forgotten: see [clear]). */
    fun peek(ctx: Context): Spot? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0L)
        if (at <= 0 || System.currentTimeMillis() - at >= KEEP_MS) return null
        return Spot(p.getString("page", null) ?: return null, p.getInt("channel", -1).takeIf { it >= 0 }, p.getBoolean("full", false), p.getString("title", null))
    }

    fun clear(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    /** The spot saved last time, if it's recent (and forgets it: it's used once). */
    fun take(ctx: Context): Spot? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0L)
        val spot = if (at > 0 && System.currentTimeMillis() - at < KEEP_MS) Spot(
            p.getString("page", null) ?: return null.also { clear(ctx) },
            p.getInt("channel", -1).takeIf { it >= 0 }, p.getBoolean("full", false), p.getString("title", null),
        ) else null
        clear(ctx)
        return spot
    }
}

/**
 * The home screen's part (kept out of HomeScreen's own body): reopening where you were once per process when Android
 * had cleared Viewarr (when the Live TV login is ready), saving the spot each time it goes to the background
 * (forgotten when leaving on purpose), and forgetting it on a normal return. Going to the background also stops live
 * TV and pauses a film; coming back starts live TV again.
 */
@androidx.compose.runtime.Composable
internal fun ResumeEffects(hs: HomeState, remote: RemoteSettings, session: Session?, playing: ChannelListItem?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val vm = hs.vm; val playback = hs.playback
    val now by androidx.compose.runtime.rememberUpdatedState(playing)
    androidx.compose.runtime.LaunchedEffect(remote.loaded, session != null) {
        if (!remote.loaded || ResumeSpot.checked) return@LaunchedEffect
        val spot = ResumeSpot.peek(ctx)
        if (spot?.channelId != null && spot.title == null && session == null) return@LaunchedEffect // wait for the login
        ResumeSpot.checked = true
        ResumeSpot.clear(ctx)
        spot ?: return@LaunchedEffect
        hs.startApplied = true
        when {
            spot.title != null -> DeepLink.handle(android.net.Uri.parse(spot.title))
            spot.channelId != null -> vm.channelById(spot.channelId)?.let { ch -> hs.watchChannel(ch, full = spot.fullscreen) }
            else -> runCatching { Page.valueOf(spot.page) }.getOrNull()?.let { hs.page = it }
        }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        val leaving = (ctx as? android.app.Activity)?.isFinishing == true
        if (leaving) ResumeSpot.clear(ctx)
        else {
            val film = VodHost.source?.takeIf { VodHost.active }?.item?.invoke()
            ResumeSpot.save(ctx, ResumeSpot.Spot(
                page = hs.page.name,
                channelId = now?.id,
                fullscreen = hs.fullscreen,
                title = film?.let { DeepLink.uriFor(it).toString() },
            ))
        }
        playback.pause(); VodHost.remote?.pause()
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) { playback.resume(); if (ResumeSpot.checked) ResumeSpot.clear(ctx) }
    AndroidPipEffects(hs, playing)
}
