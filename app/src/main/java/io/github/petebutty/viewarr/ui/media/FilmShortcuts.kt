package io.github.petebutty.viewarr.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import io.github.petebutty.viewarr.ui.player.CardMenu
import io.github.petebutty.viewarr.ui.player.CardMenuHost
import io.github.petebutty.viewarr.ui.player.ChooseRowsDialog
import io.github.petebutty.viewarr.ui.player.LiveShortcutsBridge
import io.github.petebutty.viewarr.ui.player.ShortcutOptions

/**
 * The live TV shortcuts in the film players' controls (Down from the buttons), and the rows under them: one copy for
 * both players (the built-in one and mpv), so they always behave alike.
 */
internal object FilmShortcuts {

    /** OK on a shortcut: Refresh (and Sports) work where you are; the rest stop the film (its place kept) and open. */
    fun pick(menu: PlayerMenu, i: Int) {
        LiveShortcutsBridge.items.getOrNull(i)?.let { (sc, go) ->
            if (sc.id.startsWith("action:") || sc.id == "page:Sports" || sc.id == "sport:back") go()
            else { menu.open = false; menu.onShortcuts = false; VodHost.close(); go() }
        }
    }

    /** Hold OK → Move: the one being moved, one place along. */
    fun move(menu: PlayerMenu, by: Int) {
        LiveShortcutsBridge.items.firstOrNull { it.first.id == menu.scMovingId }?.let { LiveShortcutsBridge.move(it.first, by) }
    }

    /** Whether a shortcut has a hold-OK menu (not Refresh). */
    fun canHold(i: Int) = LiveShortcutsBridge.items.getOrNull(i)?.first?.id?.let { !it.startsWith("action:") && it != "sport:back" } == true

    fun rowSizes(i: Int) = LiveShortcutsBridge.rowsFor(i).map { it.items.size }

    private fun card(menu: PlayerMenu, r: Int, c: Int) = LiveShortcutsBridge.rowsFor(menu.shortcut).getOrNull(r)?.items?.getOrNull(c)

    /** OK on a card: opens it (playing another, e.g. Continue watching, this film makes way rather than going to the corner). */
    fun pickCard(menu: PlayerMenu, r: Int, c: Int) {
        card(menu, r, c)?.let { open(menu, it) }
    }

    /** Hold OK on a card: its menu (Go to show, Go to season…); one without a menu opens as a tap. */
    fun holdCard(menu: PlayerMenu, r: Int, c: Int) {
        card(menu, r, c)?.let { if (it.options.isNotEmpty()) CardMenu.open = it else open(menu, it) }
    }

    private const val NEXT_TITLE = "Next episodes"

    /**
     * The rows under the film bar, in the order chosen (Settings → Rows under the info bar): next episodes (an episode
     * playing) from the player itself, the rest built by the home screen. [withNext]: the Episodes button opened them,
     * so next episodes is there (first) even when it isn't chosen.
     */
    fun downRows(withNext: Boolean = false): List<io.github.petebutty.viewarr.ui.player.OsdRow> {
        val next = NextUp.cards().takeIf { NextUp.upcoming.isNotEmpty() && it.isNotEmpty() }?.let { io.github.petebutty.viewarr.ui.player.OsdRow(NEXT_TITLE, it) }
        val kinds = io.github.petebutty.viewarr.ui.common.InfoRows.active(io.github.petebutty.viewarr.ui.common.InfoRows.FILM)
        val rows = kinds.mapNotNull { k ->
            if (k == io.github.petebutty.viewarr.ui.common.InfoRows.Kind.NEXT) next
            else LiveShortcutsBridge.filmRows.firstOrNull { it.first == k.id }?.second
        }
        return if (withNext && next != null && rows.none { it.title == NEXT_TITLE }) listOf(next) + rows else rows
    }

    fun downSizes(menu: PlayerMenu) = downRows(menu.withNext).map { it.items.size }

    /** Where next episodes is among the rows (the Episodes button lands there); -1: none. */
    fun nextIndex(withNext: Boolean) = downRows(withNext).indexOfFirst { it.title == NEXT_TITLE }

    /** Opens the rows on next episodes (the Episodes button / remote action). */
    fun openEpisodes(menu: PlayerMenu, only: Boolean) {
        menu.withNext = true
        menu.onEpisodes = true; menu.episodesOnly = only; menu.episode = 0
        menu.downRow = nextIndex(true).coerceAtLeast(0)
    }

    /** OK on a card under the bar: an episode plays next; anything else opens (the film makes way, or stops). */
    fun pickDown(menu: PlayerMenu, r: Int, c: Int) {
        val row = downRows(menu.withNext).getOrNull(r) ?: return
        if (row.title == NEXT_TITLE) NextUp.upcoming.getOrNull(c)?.let { NextUp.playEpisode(it) }
        else row.items.getOrNull(c)?.let { open(menu, it) }
    }

    private fun open(menu: PlayerMenu, card: io.github.petebutty.viewarr.ui.player.OsdPoster) {
        menu.open = false; menu.onShortcuts = false; menu.onRows = false; menu.onEpisodes = false
        // (Playing another: that replaces it. Anything else: this film stops, its place kept.)
        if (!card.plays) VodHost.close()
        card.onClick()
    }
}

/** The shortcuts' pop-ups in the film players: hold OK's Move / Choose rows / Refresh / Hide, a card's menu, Choose rows. */
@Composable
internal fun FilmShortcutDialogs(menu: PlayerMenu) {
    // The one being moved: the highlight follows it as the row is saved in its new order.
    val scIds = LiveShortcutsBridge.items.map { it.first.id }
    LaunchedEffect(scIds, menu.scMoving) {
        if (!menu.scMoving) return@LaunchedEffect
        scIds.indexOf(menu.scMovingId).takeIf { it >= 0 }?.let { menu.shortcut = it }
    }
    if (menu.open) menu.scOptions?.let { i ->
        LiveShortcutsBridge.items.getOrNull(i)?.first?.let { sc ->
            ShortcutOptions(
                sc, onActivity = { menu.touch() },
                onMove = { menu.scOptions = null; menu.scMovingId = sc.id; menu.scMoving = true; menu.touch() },
                onRefresh = { menu.scOptions = null; LiveShortcutsBridge.refresh(sc) },
                onHide = {
                    menu.scOptions = null
                    LiveShortcutsBridge.hide(sc)
                    menu.shortcut = menu.shortcut.coerceAtMost((LiveShortcutsBridge.items.size - 2).coerceAtLeast(0))
                },
                onCancel = { menu.scOptions = null },
                onChooseRows = { menu.scOptions = null; menu.scRows = sc },
            )
        } ?: run { menu.scOptions = null }
    }
    // The rows that load while live TV's controls are up (Sports, Live now) load for these too.
    LaunchedEffect(menu.open) { LiveShortcutsBridge.filmMenuOpen = menu.open }
    DisposableEffect(Unit) { onDispose { LiveShortcutsBridge.filmMenuOpen = false } }
    if (menu.open) CardMenuHost(
        onActivity = { menu.touch() },
        beforeRun = { plays -> menu.open = false; menu.onShortcuts = false; menu.onRows = false; if (!plays) VodHost.close() },
        onClosed = { menu.touch() },
    )
    if (menu.open) menu.scRows?.let { sc ->
        ChooseRowsDialog(
            sc, onActivity = { menu.touch() },
            onSave = { LiveShortcutsBridge.chooseRows(sc.id, it) },
            onClose = { menu.scRows = null; menu.touch() },
        )
    }
}
