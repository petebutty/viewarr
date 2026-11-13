package io.github.petebutty.viewarr.playback

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/** Switches the TV to a refresh rate that matches the video (e.g. 23.976 Hz for films), and back afterwards. */
object FrameRateMatcher {
    private const val TAG = "FrameRate"

    /** Asks for the best display mode for [fps] at the current resolution. Returns the mode id to restore later. */
    fun match(activity: Activity, fps: Float, minHz: Float = 0f): Int? {
        if (fps < 10f) return null
        val display = activity.window.decorView.display ?: return null
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && it.refreshRate >= minHz
        }
        // Exact rate first, then a whole multiple (25 → 50 Hz, 23.976 → 47.952 Hz…).
        val best = candidates
            .mapNotNull { m ->
                val ratio = m.refreshRate / fps
                val multiple = ratio.roundToInt()
                if (multiple in 1..3 && abs(ratio - multiple) < 0.01f) m to multiple else null
            }
            .minByOrNull { it.second }?.first ?: return null
        if (best.modeId == current.modeId) return null
        Log.i(TAG, "video ${"%.3f".format(fps)} fps: switching ${current.refreshRate} → ${best.refreshRate} Hz")
        val previous = activity.window.attributes.preferredDisplayModeId
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = best.modeId }
        return previous
    }

    fun restore(activity: Activity, modeId: Int) {
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = modeId }
    }

    /**
     * Live TV: the TV kept at the channel's rate family (50 Hz for UK channels, 59.94 / 60 for American ones), never
     * below 47 Hz (a 25 or 50 field broadcast at 25 Hz would judder). Switched once the rate is certain; the mode from
     * before the first switch is put back when live TV stops.
     */
    object Live {
        private var restoreMode: Int? = null
        fun apply(activity: Activity, fps: Float) {
            val previous = match(activity, fps, minHz = 47f) ?: return
            if (restoreMode == null) restoreMode = previous
        }
        fun restore(activity: Activity) {
            restoreMode?.let { restore(activity, it) }
            restoreMode = null
        }
    }

    /** The tallest picture the TV can show (2160 on a 4K TV), for "Match the TV" quality. */
    fun maxHeight(context: Context): Int {
        val dm = context.getSystemService(DisplayManager::class.java)
        val d = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: return 1080
        return d.supportedModes.maxOfOrNull { maxOf(it.physicalHeight, minOf(it.physicalWidth, it.physicalHeight)) } ?: 1080
    }
}
