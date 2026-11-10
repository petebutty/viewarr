package io.github.petebutty.viewarr.ui.theme

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * Dates and times as the viewer chose (Settings → Appearance → Date & time): day first ("Tue 10 Nov") or month
 * first ("Tue, Nov 10"), 24-hour ("19:30") or 12-hour ("7:30 pm"). Every screen formats through here.
 */
object Dates {
    private val monthFirst get() = AppColors.palette.opt(UiOpts.DATE_ORDER) == 1
    private val twelve get() = AppColors.palette.opt(UiOpts.TIME_FORMAT) == 1
    private val cache = HashMap<String, DateTimeFormatter>()
    private fun f(p: String) = cache.getOrPut(p) { DateTimeFormatter.ofPattern(p, Locale.UK) }

    /** 19:30 / 7:30 pm */
    val time: DateTimeFormatter get() = if (twelve) f("h:mm a") else f("HH:mm")
    /** Tue 10 Nov / Tue, Nov 10 */
    val day: DateTimeFormatter get() = if (monthFirst) f("EEE, MMM d") else f("EEE d MMM")
    /** Tue 10 / Tue 10 (short day in lists) */
    val shortDay: DateTimeFormatter get() = f("EEE d")
    /** 10 November 2026 / November 10, 2026 */
    val long: DateTimeFormatter get() = if (monthFirst) f("MMMM d, yyyy") else f("d MMMM yyyy")
    /** 10 Nov 2026 / Nov 10, 2026 */
    val medium: DateTimeFormatter get() = if (monthFirst) f("MMM d, yyyy") else f("d MMM yyyy")
    /** Tue 10 Nov, 19:30 */
    val dayTime: DateTimeFormatter get() = DateTimeFormatter.ofPattern(
        (if (monthFirst) "EEE, MMM d" else "EEE d MMM") + ", " + (if (twelve) "h:mm a" else "HH:mm"), Locale.UK)

    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(time)
    fun time(t: TemporalAccessor): String = time.format(t)
    fun dayTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dayTime)
    fun stamp(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let { it.format(medium) + ", " + it.format(time) }
}
