package io.github.petebutty.viewarr.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Previewarr Mobile's music in the background: while a song plays, Android keeps the app running (screen off, or
 * another app open) and shows the usual music notification and lock-screen controls. It wraps the same player the
 * Music pages use (MusicPlayer), so both always agree. Started by MusicPlayer when music starts (mobile builds only).
 */
class MobileMusicService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        session = MediaSession.Builder(this, MusicPlayer.playerFor(this)).apply { open?.let { setSessionActivity(it) } }.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recent apps: carries on if music is playing, else goes. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // The player itself stays (MusicPlayer owns it): only the session goes.
        session?.release()
        session = null
        super.onDestroy()
    }
}
