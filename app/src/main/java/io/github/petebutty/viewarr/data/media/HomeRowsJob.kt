package io.github.petebutty.viewarr.data.media

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the home screen's channel rows fresh while Viewarr is closed: Android wakes this every 15 minutes (its
 * shortest), it rewrites the cards' "on now" from the guide already on the box, and stops. No network unless a logo
 * is new to the launcher. Only scheduled while a row is switched on; it survives a restart of the box.
 */
@AndroidEntryPoint
class HomeRowsJob : JobService() {
    @Inject lateinit var rows: HomeRows
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            runCatching { rows.refresh() }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return false }

    companion object {
        private const val ID = 4120

        fun schedule(context: Context, on0: Boolean) {
            // (Android TV's home-screen rows: nothing to do on a phone or tablet.)
            val on = on0 && !io.github.petebutty.viewarr.BuildConfig.MOBILE
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val there = js.getPendingJob(ID) != null
            if (!on) { if (there) js.cancel(ID); return }
            if (there) return
            runCatching {
                js.schedule(
                    JobInfo.Builder(ID, ComponentName(context, HomeRowsJob::class.java))
                        .setPeriodic(15 * 60_000L)
                        .setPersisted(true)
                        .build(),
                )
            }
        }
    }
}
