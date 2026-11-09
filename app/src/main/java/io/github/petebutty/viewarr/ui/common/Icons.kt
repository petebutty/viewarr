package io.github.petebutty.viewarr.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small built-in icons (Material shapes, 24×24), so the app doesn't need the whole icon library. */
object AppIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White))
        .build()

    /** The standard Android microphone (voice search). */
    val Mic: ImageVector by lazy {
        icon(
            "mic",
            "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
        )
    }

    /** Magnifying glass (search). */
    val Search: ImageVector by lazy {
        icon(
            "search",
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16" +
                "c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
        )
    }

    /** Play (triangle). */
    val Play: ImageVector by lazy { icon("play", "M8,5v14l11,-7z") }

    /** Pause (two bars). */
    val Pause: ImageVector by lazy { icon("pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }

    /** Stop (square). */
    val Stop: ImageVector by lazy { icon("stop", "M6,6h12v12H6z") }

    /** Circled i (details: what's playing). */
    val Info: ImageVector by lazy { icon("info", "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z") }

    /** Speaker (audio track). */
    val Audio: ImageVector by lazy { icon("audio", "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z") }

    /** CC box (subtitles). */
    val Subtitles: ImageVector by lazy { icon("cc", "M19,4H5c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM11,11H9.5v-0.5h-2v3h2V13H11v1c0,0.55 -0.45,1 -1,1H7c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v1zM18,11h-1.5v-0.5h-2v3h2V13H18v1c0,0.55 -0.45,1 -1,1h-3c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v1z") }

    /** Bars (media info / stats). */
    val Stats: ImageVector by lazy { icon("stats", "M10,20h4V4h-4v16zM4,20h4v-8H4v8zM16,9v11h4V9h-4z") }

    /** Two arrows (switch player). */
    val Swap: ImageVector by lazy { icon("swap", "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z") }

    /** Tick (watched). */
    val Check: ImageVector by lazy { icon("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }

    /** Next track. */
    val SkipNext: ImageVector by lazy { icon("next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }

    /** Previous track. */
    val SkipPrevious: ImageVector by lazy { icon("previous", "M6,6h2v12H6zM9.5,12l8.5,6V6z") }

    /** Crossed arrows (shuffle). */
    val Shuffle: ImageVector by lazy { icon("shuffle", "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z") }

    /** Loop arrows (repeat). */
    val Repeat: ImageVector by lazy { icon("repeat", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z") }

    /** Loop arrows with a 1 (repeat this track). */
    val RepeatOne: ImageVector by lazy { icon("repeat1", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4zM13,15V9h-1l-2,1v1h1.5v4H13z") }
}
