package io.github.petebutty.viewarr.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import io.github.petebutty.viewarr.ui.player.PlayerBridge
import io.github.petebutty.viewarr.ui.common.InfoRows
import io.github.petebutty.viewarr.ui.player.OsdPoster
import io.github.petebutty.viewarr.ui.player.OsdRow

/**
 * The rows under the film players' controls (Down from the buttons; next episodes among them): one copy for both
 * players (the built-in one and mpv), so they always behave alike. (The live TV shortcut row that used to sit there
 * is gone: the menu on the left has every library and service.)
 */
internal object FilmShortcuts {

    private const val NEXT_TITLE = "Episodes"

    /**
     * The rows under the film bar, in the order chosen (Settings → Rows under the info bar): next episodes (an episode
     * playing) from the player itself, the rest built by the home screen. [withNext]: the Episodes button opened them,
     * so next episodes is there (first) even when it isn't chosen.
     */
    fun downRows(withNext: Boolean = false): List<OsdRow> {
        val next = NextUp.cards().takeIf { NextUp.upcoming.isNotEmpty() && it.isNotEmpty() }?.let { OsdRow(NEXT_TITLE, it) }
        val kinds = InfoRows.active(InfoRows.FILM)
        val rows = kinds.mapNotNull { k ->
            if (k == InfoRows.Kind.NEXT) next
            else PlayerBridge.filmRows.firstOrNull { it.first == k.id }?.second
        }
        return if (withNext && next != null && rows.none { it.title == NEXT_TITLE }) listOf(next) + rows else rows
    }

    fun downSizes(menu: PlayerMenu) = downRows(menu.withNext).map { it.items.size }

    /** Where next episodes is among the rows (the Episodes button lands there); -1: none. */
    fun nextIndex(withNext: Boolean) = downRows(withNext).indexOfFirst { it.title == NEXT_TITLE }

    /** Opens the rows on next episodes (the Episodes button / remote action). */
    fun openEpisodes(menu: PlayerMenu, only: Boolean) {
        menu.withNext = true
        menu.onEpisodes = true; menu.episodesOnly = only
        menu.downRow = nextIndex(true).coerceAtLeast(0)
        menu.episode = NextUp.startAt
    }

    /** OK on a card under the bar: an episode plays next; anything else opens (the film makes way, or stops). */
    fun pickDown(menu: PlayerMenu, r: Int, c: Int) {
        val row = downRows(menu.withNext).getOrNull(r) ?: return
        if (row.title == NEXT_TITLE) NextUp.upcoming.getOrNull(c)?.let { NextUp.playEpisode(it) }
        else row.items.getOrNull(c)?.let { open(menu, it) }
    }

    private fun open(menu: PlayerMenu, card: OsdPoster) {
        menu.open = false; menu.onEpisodes = false
        // (Playing another: that replaces it. Anything else: this film stops, its place kept.)
        if (!card.plays) VodHost.close()
        card.onClick()
    }
}

/** The rows under the film bar load what live TV's do while its controls are up (Sports, Live now). */
@Composable
internal fun FilmShortcutDialogs(menu: PlayerMenu) {
    LaunchedEffect(menu.open) { PlayerBridge.filmMenuOpen = menu.open }
    DisposableEffect(Unit) { onDispose { PlayerBridge.filmMenuOpen = false } }
}
