package io.github.petebutty.viewarr.data.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import dagger.hilt.android.AndroidEntryPoint
import io.github.petebutty.viewarr.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Play on TV, all the time: Previewarr on a TV (and Previewarr Mobile on a tablet) listens for a phone's requests in
 * the background from the moment the device starts, not only while the app is open, and keeps its name and address on
 * the user's Dispatcharr account. A request to play something brings the app forward to play it (which, from the
 * background, Android allows with "Display over other apps"); play / pause / stop work on what's already playing.
 */
@AndroidEntryPoint
class PlayOnTvService : Service() {
    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var music: io.github.petebutty.viewarr.data.music.MusicLibrary
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Play on TV", NotificationManager.IMPORTANCE_MIN))
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("Ready for Play on TV").setContentText("Your phone can send things to play here.")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        }
        val name = deviceName(this)
        PlayOnTv.start(this, name)
        scope.launch {
            sessions.session.distinctUntilChanged().collect { s ->
                PlayOnTv.ownKey = PlayOnTv.keyOf(s)
                PlayOnTv.register(s, name)
            }
        }
        // The app on screen hands links to its own pages (PlayOnTvHost / the phone screens); off screen, it comes forward.
        PlayOnTv.offScreen = { bringForward(this, null) }
        // Music: the songs fetched from this device's own music servers and played from the same point; on a TV the
        // app comes forward too (a tablet just plays, as music does with the screen off).
        PlayOnTv.onMusic = { uri -> PlayOnTv.musicOf(uri)?.let { ask -> scope.launch {
            // (On a TV the app comes forward if it wasn't on screen: the home screen's Now playing tile shows it.)
            if (playMusic(this@PlayOnTvService, music, ask) && !io.github.petebutty.viewarr.BuildConfig.MOBILE &&
                !io.github.petebutty.viewarr.AppVisible.visible.value) bringForward(this@PlayOnTvService, null)
        } } }
        // Take over from a phone: what's playing here as a link (music: its queue saved on its server first).
        PlayOnTv.onHandoff = { NowPlaying.linkNow(music) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        PlayOnTv.stop(this)
        super.onDestroy()
    }

    companion object {
        /**
         * Music from another device ([PlayOnTv.musicLink]): the queue loaded from the music server it was saved on (or,
         * from a server without queues, the songs fetched by id) and played from the same point. False: not found here.
         */
        suspend fun playMusic(context: Context, library: io.github.petebutty.viewarr.data.music.MusicLibrary, ask: PlayOnTv.MusicAsk): Boolean {
            val got = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    if (ask.src != null && ask.q != null) library.loadQueue(ask.src, ask.q)
                    else library.songs(ask.ids).takeIf { it.isNotEmpty() }?.let { Triple(it, 0, null) }
                }.getOrNull()
            }
            if (got == null || got.first.isEmpty()) {
                android.widget.Toast.makeText(context, "Couldn't get that music from this device's music servers", android.widget.Toast.LENGTH_LONG).show()
                return false
            }
            val (songs, index, serverPos) = got
            val mp = io.github.petebutty.viewarr.playback.MusicPlayer
            mp.play(context, songs, index)
            // (The link's position: taken when it was sent; the server's own if the link has none.)
            val at = if (ask.positionMs > 0) ask.positionMs else serverPos ?: 0
            if (at > 3_000 && (ask.src != null || songs.first().id == ask.ids.firstOrNull())) mp.seekTo(at)
            return true
        }

        private const val CHANNEL = "play_on_tv"
        private const val ID = 4711

        fun deviceName(context: Context): String =
            runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

        /** Whether this device takes Play on TV requests: Previewarr on a TV, or Previewarr Mobile on a tablet. */
        fun receives(context: Context): Boolean = io.github.petebutty.viewarr.BuildConfig.LAUNCHER &&
            (!io.github.petebutty.viewarr.BuildConfig.MOBILE || context.resources.configuration.smallestScreenWidthDp >= 600)

        fun start(context: Context) {
            if (!receives(context)) return
            runCatching {
                val i = Intent(context, PlayOnTvService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }

        /** The app forward with [uri] to play (MainActivity hands it to DeepLink). */
        fun bringForward(context: Context, uri: Uri?) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri, context, io.github.petebutty.viewarr.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
    }
}

/** The device switched on: Play on TV starts listening (PlayOnTvService). */
class PlayOnTvBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) PlayOnTvService.start(context)
    }
}
