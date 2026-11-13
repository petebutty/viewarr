package io.github.petebutty.viewarr.ui.theme

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * Dates and times as the viewer chose (Settings → Appearance → Date & time): day first ("Tue 10 Nov") or month
 * first ("Tue, Nov 10"), or in numbers ("Tue 10/11", "Tue 11/10"); 24-hour ("19:30") or 12-hour ("7:30 pm").
 * Every screen formats through here.
 */
object Dates {
    private val order get() = AppColors.palette.opt(UiOpts.DATE_ORDER)
    private val monthFirst get() = order == 1 || order == 3
    /** Numbers only (10/11/2026), day first (2) or month first (3). */
    private val numeric get() = order >= 2
    private val twelve get() = AppColors.palette.opt(UiOpts.TIME_FORMAT) == 1
    private val cache = HashMap<String, DateTimeFormatter>()
    private fun f(p: String) = cache.getOrPut(p) { DateTimeFormatter.ofPattern(p, Locale.UK) }

    /** 19:30 / 7:30 pm */
    val time: DateTimeFormatter get() = if (twelve) f("h:mm a") else f("HH:mm")
    /** Tue 10 Nov / Tue, Nov 10 */
    val day: DateTimeFormatter get() = f(dayPattern)
    private val dayPattern get() = when {
        numeric && monthFirst -> "EEE MM/dd"
        numeric -> "EEE dd/MM"
        monthFirst -> "EEE, MMM d"
        else -> "EEE d MMM"
    }
    /** Tue 10 / Tue 10 (short day in lists) */
    val shortDay: DateTimeFormatter get() = f("EEE d")
    /** 10 November 2026 / November 10, 2026 */
    val long: DateTimeFormatter get() = when { numeric && monthFirst -> f("MM/dd/yyyy"); numeric -> f("dd/MM/yyyy"); monthFirst -> f("MMMM d, yyyy"); else -> f("d MMMM yyyy") }
    /** 10 Nov 2026 / Nov 10, 2026 */
    val medium: DateTimeFormatter get() = when { numeric && monthFirst -> f("MM/dd/yyyy"); numeric -> f("dd/MM/yyyy"); monthFirst -> f("MMM d, yyyy"); else -> f("d MMM yyyy") }
    /** Tue 10 Nov, 19:30 */
    val dayTime: DateTimeFormatter get() = f(dayPattern + ", " + (if (twelve) "h:mm a" else "HH:mm"))

    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(time)
    fun time(t: TemporalAccessor): String = time.format(t)
    fun dayTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dayTime)
    fun stamp(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let { it.format(medium) + ", " + it.format(time) }
}
