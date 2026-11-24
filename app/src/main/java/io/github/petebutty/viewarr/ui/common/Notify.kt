package io.github.petebutty.viewarr.ui.common

import io.github.petebutty.viewarr.ui.theme.AppColors

/**
 * Settings → Menu & navigation → Notifications (2026-11-18: "we need to be able to turn off all
 * notifications, and turn on only what the user wants"). Everything Viewarr pops up by itself: one switch for all of
 * it, then each kind. Kept as palette title parts, on to start (as before): "notify:on", then "notify:<kind>".
 * Score alerts keep their own detail in Settings → Sports (data/sports/ScoreAlerts).
 */
object Notify {
    const val ON = "notify:on"
    const val REMINDERS = "notify:reminders"
    const val UPDATES = "notify:updates"
    const val WHATS_NEW = "notify:whatsnew"
    const val REPORTS = "notify:reports"
    const val NEW_EPISODES = "notify:newepisodes"
    // Off to start, every kind (2026-11-18: "notifications stay off, users can decide to turn them on"): a
    // part that's PRESENT means on, the opposite way round from the other switches.
    private fun shows(k: String) = !AppColors.palette.shows(k)
    /** Notifications at all. */
    val on get() = shows(ON)
    /** The reminders you set (Remind me on a programme or game): the card when it starts. */
    val reminders get() = on && shows(REMINDERS)
    /** The "update available" card when Viewarr starts (Settings → About → Check for updates still works). */
    val updates get() = on && shows(UPDATES)
    /** The "what's new" card once after an update (Settings → About → Changelog still has it). */
    val whatsNew get() = on && shows(WHATS_NEW)
    /** A quiet card when the Viewarr plugin changes a channel from someone's report (admins: with Undo), or a wrong-guide
     *  report needs someone to pick the right guide (the reporter and admins). See data/report/HouseSync. */
    val reports get() = on && shows(REPORTS)
    /** A new episode of a show you follow is in your library (data/media/FollowedShows): the card, and Play next. */
    val newEpisodes get() = on && shows(NEW_EPISODES)
    /** How long before the start a reminder comes up (Settings → Notifications → Remind me). */
    val reminderLeadMs: Long get() = listOf(0L, 2L, 5L, 10L, 15L)[AppColors.palette.opt(io.github.petebutty.viewarr.ui.theme.UiOpts.REMIND_LEAD).coerceIn(0, 4)] * 60_000L
}
