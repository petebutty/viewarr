package io.github.petebutty.viewarr.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/*
 * Scrolling names (a long programme name on the highlighted guide cell or channel list row), moved on about 20 times
 * a second rather than every frame. They still scroll all the time at the same speed; only the steps are coarser.
 * (A name scrolling at the full 60 frames a second redrew the screen that often while it sat there: about half a core
 * on a Shield. Peter, 2026-11-22: keep it scrolling, but at about 20 frames a second.)
 */

/** How often a scrolling name moves on: about every 50 ms (20 steps a second). */
const val MARQUEE_STEP_MS = 50L

/**
 * Calls [step] with the milliseconds since it began, about every [MARQUEE_STEP_MS], until it returns false. It runs
 * on the frame clock, so it waits while the app is in the background (the frame clock is paused then), and it stops
 * with the caller (a highlight moving on, a row scrolled off screen).
 */
suspend fun marqueeSteps(step: (Long) -> Boolean) {
    val start = withFrameMillis { it }
    if (!step(0L)) return
    while (true) {
        // (A little under the step: the next frame then lands close to 50 ms on.)
        delay(MARQUEE_STEP_MS - 10)
        if (!withFrameMillis { step(it - start) }) return
    }
}

/**
 * The same as Compose's basicMarquee(iterations = forever) as the app used it: when the content is wider than its
 * room, it waits [initialDelayMillis], slides along at [velocity] until its copy (a third of the room behind it) is
 * where it started, waits [repeatDelayMillis], and again. Content that fits stays still. Only the drawing moves (the
 * text isn't measured again), at about 20 steps a second ([marqueeSteps]).
 */
fun Modifier.slowMarquee(initialDelayMillis: Int = 1_200, repeatDelayMillis: Int = 2_000, velocity: Dp = 30.dp): Modifier =
    this then SlowMarqueeElement(initialDelayMillis, repeatDelayMillis, velocity)

private data class SlowMarqueeElement(val initialDelayMillis: Int, val repeatDelayMillis: Int, val velocity: Dp) :
    ModifierNodeElement<SlowMarqueeNode>() {
    override fun create() = SlowMarqueeNode(initialDelayMillis, repeatDelayMillis, velocity)
    override fun update(node: SlowMarqueeNode) = node.update(initialDelayMillis, repeatDelayMillis, velocity)
}

private class SlowMarqueeNode(
    private var initialDelayMillis: Int,
    private var repeatDelayMillis: Int,
    private var velocity: Dp,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode {
    private var contentWidth by mutableIntStateOf(0)
    private var containerWidth by mutableIntStateOf(0)
    // Read only while drawing, so a step redraws just this, with no measuring or layout.
    private var offset by mutableFloatStateOf(0f)
    private var job: Job? = null

    private val spacing get() = containerWidth / 3f

    override fun onAttach() = restart()

    fun update(initial: Int, repeat: Int, speed: Dp) {
        if (initial == initialDelayMillis && repeat == repeatDelayMillis && speed == velocity) return
        initialDelayMillis = initial; repeatDelayMillis = repeat; velocity = speed
        restart()
    }

    private fun restart() {
        if (!isAttached) return
        job?.cancel()
        job = coroutineScope.launch {
            // (Started again whenever the content or its room changes: a new name, a different width.)
            snapshotFlow { if (contentWidth > containerWidth) contentWidth + spacing else 0f }.collectLatest { travel ->
                offset = 0f
                if (travel <= 0f) return@collectLatest
                val pxPerMs = with(requireDensity()) { velocity.toPx() } / 1000f
                var wait = initialDelayMillis.toLong()
                while (true) {
                    delay(wait)
                    marqueeSteps { t ->
                        val at = t * pxPerMs
                        offset = minOf(at, travel)
                        at < travel
                    }
                    // At the end the copy sits exactly where the start was: back to the start shows no jump.
                    offset = 0f
                    wait = repeatDelayMillis.toLong()
                }
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints.copy(maxWidth = Constraints.Infinity))
        val width = constraints.constrainWidth(placeable.width)
        containerWidth = width
        contentWidth = placeable.width
        return layout(width, placeable.height) { placeable.place(0, 0) }
    }

    override fun ContentDrawScope.draw() {
        val o = offset
        if (contentWidth <= containerWidth) { drawContent(); return }
        clipRect(0f, 0f, size.width, size.height) {
            translate(left = -o) { this@draw.drawContent() }
            // The copy following it round, once it comes into view.
            val copyAt = contentWidth + spacing - o
            if (copyAt < size.width) translate(left = copyAt) { this@draw.drawContent() }
        }
    }
}
