package io.github.petebutty.viewarr.ui.common

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * How long ago this run of the app started, so background work can wait until the first screen has settled instead
 * of all landing at once (Peter, 2026-11-18: the opening on the AM9 did everything together).
 */
object AppStart {
    fun sinceMs(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
    /** Waits until [ms] after the app started (no wait when it's later than that already). */
    suspend fun after(ms: Long) { (ms - sinceMs()).takeIf { it > 0 }?.let { delay(it) } }
}
