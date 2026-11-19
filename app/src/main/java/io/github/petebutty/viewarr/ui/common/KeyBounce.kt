package io.github.petebutty.viewarr.ui.common

import android.view.KeyEvent

/**
 * Drops a press of a button that comes again within [GAP_MS] of that button being let go: a Bluetooth remote that
 * stutters sends one press as two or three (Peter's Ugoos UR02, 2026-11-18: Right, released, then Right again 56 and
 * 67 ms later). A real double tap is slower than that. Its release is dropped with it.
 */
object KeyBounce {
    private const val GAP_MS = 90L
    private val lastUp = HashMap<Int, Long>()
    private val swallowing = HashSet<Int>()

    /** True: ignore [e]. */
    @Synchronized fun drop(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_UNKNOWN) return false
        return when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) code in swallowing
                else {
                    val up = lastUp[code]
                    val bounce = up != null && e.eventTime - up in 0 until GAP_MS
                    if (bounce) swallowing += code else swallowing -= code
                    if (bounce) android.util.Log.i("KeyBounce", "dropped a repeat of ${KeyEvent.keyCodeToString(code)} ${e.eventTime - up!!} ms after release")
                    bounce
                }
            }
            KeyEvent.ACTION_UP -> {
                // (A dropped press's release counts too: a stutter of three, each 60 ms after the last, is caught whole.)
                lastUp[code] = e.eventTime
                swallowing.remove(code)
            }
            else -> false
        }
    }
}
