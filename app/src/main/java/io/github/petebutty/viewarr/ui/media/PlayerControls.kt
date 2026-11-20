package io.github.petebutty.viewarr.ui.media

import androidx.compose.foundation.horizontalScroll
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.playback.SeekThumbs
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.delay
import io.github.petebutty.viewarr.data.media.PlayerArt
import io.github.petebutty.viewarr.playback.AvSync
import io.github.petebutty.viewarr.playback.SleepTimer
import io.github.petebutty.viewarr.ui.common.AppIcons
import io.github.petebutty.viewarr.ui.common.PosterImage
import io.github.petebutty.viewarr.ui.common.RemoteAction
import io.github.petebutty.viewarr.ui.common.RemoteKeys
import io.github.petebutty.viewarr.ui.player.InfoBarPanel
import io.github.petebutty.viewarr.ui.player.OsdCardStatic
import io.github.petebutty.viewarr.ui.player.OsdRow
import io.github.petebutty.viewarr.ui.player.OsdRowHeading
import io.github.petebutty.viewarr.ui.player.QualityBadges
import io.github.petebutty.viewarr.ui.player.StreamQuality
import io.github.petebutty.viewarr.ui.theme.Dates
import io.github.petebutty.viewarr.ui.theme.UiOpts
import io.github.petebutty.viewarr.ui.common.BarLayout
import io.github.petebutty.viewarr.ui.player.InfoBarTop

/**
 * Seek-bar scrubbing shared by both players. Down puts you on the seek bar; Left / Right move a marker (faster the
 * longer you hold) while the film keeps playing; OK jumps there; Up or Back leaves it where it was.
 */
class Scrubber {
    /** Where the marker is, while on the seek bar; null otherwise. */
    var targetMs by mutableStateOf<Long?>(null)
        private set
    val active get() = targetMs != null

    fun begin(positionMs: Long) { targetMs = positionMs.coerceAtLeast(0) }
    fun cancel() { targetMs = null }

    /** One press or repeat of Left (-1) / Right (+1). Steps grow while the button is held. */
    fun step(direction: Int, repeatCount: Int, durationMs: Long) {
        val t = targetMs ?: return
        val step = when {
            repeatCount < 4 -> 10_000L
            repeatCount < 12 -> 30_000L
            repeatCount < 25 -> 60_000L
            else -> maxOf(120_000L, durationMs / 50)
        }
        val max = if (durationMs > 0) durationMs - 1_000 else Long.MAX_VALUE
        targetMs = (t + direction * step).coerceIn(0, max.coerceAtLeast(0))
    }

    /** OK: where to jump to; leaves the seek bar. */
    fun commit(): Long? = targetMs.also { targetMs = null }
}

/** The buttons under the seek bar. [Guide]: the film goes to the corner, the TV guide opens. */
enum class PlayerButton { Guide, PlayPause, Stop, Details, Audio, Subtitles, Info, SwitchPlayer, PrevEpisode, NextEpisode, Episodes, Sync, Sleep, Movies, Shows, Recordings, Search, Transparency, BarStyle, TvSettings, Settings }

/** A control under the seek bar: an icon, with its name shown under it when focused; [groupEnd]: the divider after it. */
class OsdButton(val icon: ImageVector, val label: String, val groupEnd: Boolean = false)

fun PlayerButton.osd(paused: Boolean, infoOn: Boolean, otherPlayer: String) = OsdButton(
    when (this) {
        PlayerButton.PlayPause -> if (paused) AppIcons.Play else AppIcons.Pause
        PlayerButton.Stop -> AppIcons.Stop
        PlayerButton.Details -> AppIcons.Info
        PlayerButton.Audio -> AppIcons.Audio
        PlayerButton.Subtitles -> AppIcons.Subtitles
        PlayerButton.Info -> AppIcons.Stats
        PlayerButton.Settings -> AppIcons.Settings
        PlayerButton.Transparency -> AppIcons.Opacity
        PlayerButton.Search -> AppIcons.Search
        PlayerButton.Episodes -> AppIcons.Episodes
        PlayerButton.PrevEpisode -> AppIcons.SkipPrevious
        PlayerButton.NextEpisode -> AppIcons.SkipNext
        PlayerButton.Sync -> AppIcons.Equalizer
        PlayerButton.Sleep -> AppIcons.Bedtime
        PlayerButton.Movies -> AppIcons.Movie
        PlayerButton.Shows -> AppIcons.Tv
        PlayerButton.Recordings -> AppIcons.Recordings
        PlayerButton.TvSettings -> AppIcons.Tune
        PlayerButton.BarStyle -> AppIcons.Style
        PlayerButton.Guide -> AppIcons.Guide
        PlayerButton.SwitchPlayer -> AppIcons.Swap
    },
    label(paused, infoOn, otherPlayer),
)

/**
 * The player's controls, opened with OK: the seek bar first (Left / Right move the marker, OK jumps there), the
 * buttons below it (Down). Play/Pause on the remote pauses; OK no longer does. Closes by itself after a while
 * (not while paused), or with Back.
 */
class PlayerMenu {
    /** Where the highlight starts in row [r] under the bar (the Episodes row: the episode playing). */
    var rowStart: (Int) -> Int = { 0 }
    var open by mutableStateOf(false)
    var onBar by mutableStateOf(true)
    var button by mutableIntStateOf(0)
    var infoOn by mutableStateOf(false)
    /** An episode playing: Down shows the next episodes in the bar, [episode] highlighted. */
    var onEpisodes by mutableStateOf(false)
    var episode by mutableIntStateOf(0)
    /**
     * The rows under the bar (Settings → Rows under the info bar; next episodes is one of them): [onEpisodes] is on
     * them, [downRow] the one you're on and [episode] the card along it. [withNext]: opened with the Episodes button,
     * so next episodes is there even when it isn't one of the chosen rows.
     */
    var downRow by mutableIntStateOf(0)
    var withNext = false
    /** The row was opened straight from playback (Down): Up / Back closes the bar too, not back to its buttons. */
    var episodesOnly = false
    var lastKey by mutableLongStateOf(0L)
    /**
     * The Info bar buttons list is open over the bar (hold OK on a button; FilmBarEditor sets it). It replaced editing
     * the buttons in place on the bar (OK shows / hides, hold OK picks up, Left / Right move): Peter, 2026-11-17 UI review.
     */
    var editing by mutableStateOf(false)
    /** Hold OK on a button: opens the Info bar buttons list (filmHooks sets it). */
    var editBar: () -> Unit = {}
    /** Where the highlight is among [buttons]. */
    @Suppress("UNUSED_PARAMETER")
    fun focusIndex(buttons: List<PlayerButton>): Int = button
    // An OK pressed on a button, not yet let go: a tap presses it on release, a hold opens the Info bar buttons list.
    private var btnOkDown = false
    /** Where Play / Pause is among the buttons. */
    var home = 0
    /**
     * Opens on the Play / Pause button (not the seek bar), not the button used last time. (Peter: it used to open on
     * the TV guide button.)
     */
    fun show() { btnOkDown = false; onEpisodes = false; episodesOnly = false; downRow = 0; withNext = false; open = true; onBar = false; button = home; touch() }
    fun touch() { lastKey = System.currentTimeMillis() }

    /**
     * Keys while the controls are open and not scrubbing (the players handle scrubbing themselves). True when used.
     * [beginScrub]: start the marker at the current position and move it one step ([dir], [repeat] for speed).
     */
    fun handle(
        e: KeyEvent, buttons: List<PlayerButton>, beginScrub: (dir: Int, repeat: Int) -> Unit, press: (PlayerButton) -> Unit, upFromBar: () -> Unit = {},
        /** Unused since the bar's buttons are edited in the Info bar buttons list (VodKeys still passes them). */
        /** Left from the first button: the menu, over the film (it plays on full screen behind it). */
        leftFromFirst: () -> Unit = {},
        /** The rows under the bar (next episodes among them): how many cards each has, and picking one (row, card). */
        downSizes: List<Int> = emptyList(),
        pickDown: (Int, Int) -> Unit = { _, _ -> },
    ): Boolean {
        val down = e.type == KeyEventType.KeyDown
        // (Buttons taken off in the Info bar buttons list: the highlight stays on the bar.)
        if (buttons.isNotEmpty() && button > buttons.lastIndex) button = buttons.lastIndex
        val a = RemoteKeys.action(e)
        val repeat = e.nativeKeyEvent.repeatCount
        if (down) touch()
        // The next episodes: Left / Right along them, OK plays one, Up (or Back) leaves the row.
        // The rows under the bar: Left / Right along one, Down / Up between them (rows passed slide away above; Up from
        // the first goes back to the buttons), OK plays / opens the card. (Back to the buttons is Back's step in
        // FilmControls.kt filmBackStep: VodKeys takes Back before the controls see it.)
        if (onEpisodes) {
            if (downSizes.isEmpty()) { onEpisodes = false; if (episodesOnly) open = false; return true }
            downRow = downRow.coerceIn(0, downSizes.lastIndex)
            val count = downSizes[downRow]
            when (a) {
                RemoteAction.Left -> if (down) episode = (episode - 1).coerceAtLeast(0)
                RemoteAction.Right -> if (down) episode = (episode + 1).coerceAtMost((count - 1).coerceAtLeast(0))
                RemoteAction.Ok -> if (down && repeat == 0) { val r = downRow; open = false; onEpisodes = false; pickDown(r, episode) }
                RemoteAction.Up -> if (down) {
                    if (downRow > 0) { downRow--; episode = rowStart(downRow) } else { onEpisodes = false; if (episodesOnly) open = false }
                }
                RemoteAction.Down -> if (down && downRow < downSizes.lastIndex) { downRow++; episode = rowStart(downRow) }
                else -> return false
            }
            return true
        }
        when {
            onBar && (a == RemoteAction.Left || a == RemoteAction.Right) -> {
                if (down) beginScrub(if (a == RemoteAction.Left) -1 else 1, repeat)
            }
            onBar && a == RemoteAction.Down -> if (down && buttons.isNotEmpty()) onBar = false
            // Up from the seek bar: pause, with what's playing (its synopsis) on screen.
            onBar && a == RemoteAction.Up -> if (down && repeat == 0) upFromBar()
            // OK on the seek bar: down onto the buttons (Play / Pause is there), rather than nothing happening.
            onBar && a == RemoteAction.Ok -> if (down && repeat == 0 && buttons.isNotEmpty()) onBar = false
            !onBar && a == RemoteAction.Left -> if (down) { if (button == 0) { if (repeat == 0) { open = false; leftFromFirst() } } else button -= 1 }
            !onBar && a == RemoteAction.Right -> if (down) button = (button + 1).coerceAtMost(buttons.lastIndex)
            !onBar && a == RemoteAction.Up -> if (down) onBar = true
            !onBar && a == RemoteAction.Down -> if (down && downSizes.isNotEmpty()) {
                onEpisodes = true; episodesOnly = false; downRow = 0; episode = rowStart(0)
            }
            // A tap presses the button (on release); a hold opens the Info bar buttons list (as on live TV).
            !onBar && a == RemoteAction.Ok -> when {
                down && repeat == 0 -> btnOkDown = true
                down && btnOkDown -> { btnOkDown = false; editBar() }
                !down && btnOkDown -> { btnOkDown = false; buttons.getOrNull(button)?.let(press) }
            }
            else -> return false
        }
        return true
    }
}

/** A button's label (Play / Pause follow the state; Media info shows whether it's on). */
fun PlayerButton.label(paused: Boolean, infoOn: Boolean, otherPlayer: String) = when (this) {
    PlayerButton.PlayPause -> if (paused) "Play" else "Pause"
    PlayerButton.Details -> "Details"
    PlayerButton.Audio -> "Audio"
    PlayerButton.Subtitles -> "Subtitles"
    PlayerButton.Info -> if (infoOn) "Hide media info" else "Media info"
    PlayerButton.SwitchPlayer -> "Switch to $otherPlayer"
    PlayerButton.Stop -> "Stop"
    PlayerButton.Settings -> "Settings"
    PlayerButton.Transparency -> "Transparency"
    PlayerButton.Search -> "Search"
    PlayerButton.Episodes -> "Episodes"
    PlayerButton.PrevEpisode -> NextUp.previous?.let { "Previous: S${it.season} · E${it.number}" } ?: "Previous episode"
    PlayerButton.NextEpisode -> NextUp.episode?.let { "Next: S${it.season} · E${it.number}" } ?: "Next episode"
    PlayerButton.Sync -> "Audio sync: " + AvSync.label()
    PlayerButton.Sleep -> SleepTimer.label()
    PlayerButton.Movies -> "Movies"
    PlayerButton.Shows -> "Shows"
    PlayerButton.Recordings -> "Recordings"
    PlayerButton.TvSettings -> "TV settings"
    PlayerButton.BarStyle -> BarLayout.styleLabel()
    PlayerButton.Guide -> "TV guide"
}

/** The controls over a playing film or episode: title, seek bar (with preview while scrubbing) and times. */
@Composable
fun PlayerOsd(
    title: String,
    positionMs: Long,
    durationMs: Long,
    paused: Boolean,
    buffering: Boolean,
    scrubMs: Long?,
    thumbs: SeekThumbs?,
    modifier: Modifier = Modifier,
    /** The title's logo: shown above the episode line instead of the title as text. */
    logo: String? = null,
    /** The controls (opened with OK): button labels, which is highlighted, and whether the seek bar has focus. */
    buttons: List<OsdButton>? = null,
    buttonFocus: Int = -1,
    /** The Info bar buttons list is open over the bar (hold OK on a button), for the hint line. */
    editingButtons: Boolean = false,
    /** The picture / sound / frame rate badges, as live TV's info bar has them. */
    quality: StreamQuality? = null,
    barFocused: Boolean = false,
    /** The rows under the bar (Down from the buttons; next episodes among them), [downRow] the one you're on and
     *  [episodeFocus] its highlighted card; null: not showing. */
    downRows: List<OsdRow>? = null,
    downRow: Int = 0,
    episodeFocus: Int = 0,
    /** Where it's playing from ("Plex · Living room", "AIOStreams", "IPTV"), on the bar's small line. */
    from: String? = null,
    /** How far ahead the player has loaded: drawn on the seek bar, lighter, ahead of what's played. */
    bufferedMs: Long = 0,
) {
    val scrubbing = scrubMs != null
    // Settings → Appearance → Info bar & controls → Player controls size.
    val k = AppColors.palette.controlsScale
    fun d(v: Int) = (v * k).dp
    fun t(v: Int) = (v * (0.5f + k / 2)).sp
    // The preview: fetched once the marker rests for a moment, the last one stays up meanwhile.
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(scrubMs?.div(5_000), thumbs) {
        if (scrubMs == null || thumbs == null) { if (scrubMs == null) thumb = null; return@LaunchedEffect }
        delay(120)
        val b = thumbs.at(scrubMs)
        android.util.Log.i("SeekThumbs", "preview for ${scrubMs / 1000}s: ${if (b == null) "none" else "${b.width}x${b.height}"}")
        if (b != null) thumb = b
    }
    // The same bar as live TV's info bar (its panel, logo, title and progress line), with the buttons inside it.
    InfoBarPanel(modifier) {
        // The film or show's own logo in the logo's place (the play state there without one).
        var logoShown by remember(logo) { mutableStateOf(false) }
        // Decoded at the logo box's size (110 × 62, InfoBarTop), not the original (thousands of pixels wide), and asked
        // for once rather than on every redraw.
        val logoRequest = rememberLogoRequest(logo, 110, 62)
        InfoBarTop(
            number = null,
            logo = {
                if (!logoShown) Text(if (buffering) "◌" else if (paused) "❚❚" else "▶", fontSize = 30.sp, color = Color.White)
                if (logoRequest != null) coil.compose.AsyncImage(
                    model = logoRequest,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    onSuccess = { logoShown = true },
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                )
            },
            smallLine = {
                // The show's name (episodes) or "Film", and the play state.
                // (Shrinks with "…" in the narrow info bar styles, so the badges after it aren't cut off.)
                Text((if (title.contains(" · ")) title.substringBefore(" · ") else "Film") + (from?.let { "  ·  from $it" } ?: ""), fontSize = 14.sp, color = AppColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val state = if (buffering) "LOADING" else if (paused) "PAUSED" else null
                if (state != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(state, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.OnAccent, maxLines = 1, softWrap = false,
                        modifier = Modifier.background(AppColors.Accent, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
                QualityBadges(quality)
            },
            title = if (title.contains(" · ")) title.substringAfter(" · ") else title,
            progress = {
                Column {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val barWidth = maxWidth
                        val played = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                        val marker = if (durationMs > 0 && scrubMs != null) (scrubMs.toFloat() / durationMs).coerceIn(0f, 1f) else played
                        Column {
                            if (scrubbing && thumbs != null) {
                                // Preview above the marker, kept on screen at the ends.
                                val w = 128.dp
                                val x = (barWidth * marker - w / 2).coerceIn(0.dp, (barWidth - w).coerceAtLeast(0.dp))
                                Column(Modifier.offset(x = x).width(w), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier.width(w).height(72.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black)
                                            .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(6.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val b = thumb
                                        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().fillMaxHeight())
                                        else Text("…", fontSize = 22.sp, color = Color.White.copy(alpha = 0.6f))
                                    }
                                    Text(clockMs(scrubMs ?: 0), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp), maxLines = 1, softWrap = false)
                                }
                            } else if (scrubbing) {
                                val w = 120.dp
                                val x = (barWidth * marker - w / 2).coerceIn(0.dp, (barWidth - w).coerceAtLeast(0.dp))
                                Text(clockMs(scrubMs ?: 0), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White,
                                    modifier = Modifier.offset(x = x).width(w).padding(bottom = 8.dp), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                            }
                            val barHeight = if (scrubbing) 10.dp else 6.dp
                            Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
                                Box(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.25f))) {
                                    // What's loaded ahead (the buffer), filling in front of the played part.
                                    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(played, 1f) else 0f
                                    if (buffered > played) Box(Modifier.fillMaxHeight().fillMaxWidth(buffered).background(Color.White.copy(alpha = 0.35f)))
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(played).background(if (scrubbing) Color.White.copy(alpha = 0.55f) else AppColors.Accent))
                                }
                                if (scrubbing) Box(
                                    Modifier.offset(x = (barWidth * marker - 9.dp).coerceAtLeast(0.dp)).size(18.dp).clip(CircleShape).background(AppColors.Accent)
                                        .border(2.dp, Color.White, CircleShape),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Across the whole bar, so the time on the right sits at its end (it stopped part-way along).
                    Row(Modifier.fillMaxWidth()) {
                        Text(clockMs(positionMs), fontSize = t(14), color = Color.White, maxLines = 1, softWrap = false)
                        Text(
                            when {
                                editingButtons -> "Editing the info bar buttons"
                                downRows != null -> "◀ ▶ move  ·  OK: play / open  ·  " + (if (downRows.size > 1) "▲ ▼ rows  ·  " else "▲ back  ·  ") + "Back: buttons"
                                scrubbing -> "◀ ▶ move  ·  OK: jump here  ·  Back: cancel"
                                buttons != null && barFocused -> "◀ ▶ seek  ·  ▼ options  ·  ▲ pause + details"
                                buttons != null -> "▲ seek bar  ·  ⏯ play / pause  ·  Hold OK: edit buttons"
                                else -> "OK: controls  ·  ◀ ▶ skip  ·  ⏯ play / pause"
                            },
                            // All the room in the middle, centred: the time on the right always ends flush with the bar. (A
                            // share it didn't fill left a gap at the end that moved with the bar's width and the hint.)
                            fontSize = t(13), color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        // Settings → Playback → Player → Time on the right: time left, when it ends, or both.
                        val leftMs = (durationMs - positionMs).coerceAtLeast(0)
                        val endsAt = Dates.time(System.currentTimeMillis() + leftMs)
                        Text(
                            if (durationMs <= 0) "" else when (AppColors.palette.opt(UiOpts.FILM_TIME_RIGHT)) {
                                1 -> "Ends at $endsAt"
                                2 -> "${clockMs(leftMs)} left  ·  ends $endsAt"
                                else -> "${clockMs(leftMs)} left"
                            },
                            fontSize = t(14), color = Color.White, maxLines = 1,
                        )
                    }
                }
            },
        )
        // Scrolls sideways when there are more buttons than fit, keeping the highlighted one in view.
        val btnScroll = rememberScrollState()
        val itemPx = with(LocalDensity.current) { d(72).toPx() }
        LaunchedEffect(buttonFocus, buttons?.size) {
            if (buttonFocus < 0) return@LaunchedEffect
            val target = (buttonFocus * itemPx - btnScroll.viewportSize / 2f + itemPx / 2f).toInt().coerceIn(0, btnScroll.maxValue)
            btnScroll.scrollTo(target)
        }
        if (buttons != null) Row(Modifier.padding(top = d(14)).horizontalScroll(btnScroll), verticalAlignment = Alignment.Top) {
            buttons.forEachIndexed { i, b ->
                val on = i == buttonFocus
                // Icons only; the focused one's name underneath (so each is still easy to learn).
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(d(72))) {
                    Box(
                        Modifier.size(d(48)).background(if (on) AppColors.Accent else Color.White.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(b.icon, contentDescription = b.label, tint = if (on) AppColors.OnAccent else Color.White, modifier = Modifier.size(d(26)))
                    }
                    Text(
                        if (on) b.label else "", fontSize = t(12), color = Color.White, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(top = d(4)).then(Modifier.wrapContentWidth(unbounded = true)),
                    )
                }
                if (b.groupEnd) Box(Modifier.padding(horizontal = d(10), vertical = d(10)).width(1.dp).height(d(28)).background(Color.White.copy(alpha = 0.3f)))
            }
        }
        // The rows under the bar (next episodes among them), drawn with the same cards as live TV's rows: the one
        // you're on, the next peeking in below; rows passed have slid away above.
        val curIndex = downRows?.let { downRow.coerceIn(0, (it.size - 1).coerceAtLeast(0)) } ?: 0
        val curRow = downRows?.getOrNull(curIndex)
        // Every card's picture asked for as the row opens, not only the few in view (the rest arrived blank).
        val ctx = LocalContext.current
        LaunchedEffect(curRow?.items?.map { it.key }) {
            curRow?.items?.forEach { e ->
                (e.image ?: e.fallbackImage)?.let { coil.Coil.imageLoader(ctx).enqueue(coil.request.ImageRequest.Builder(ctx).data(it).size(256, 144).build()) }
            }
        }
        if (downRows != null && curRow != null) Column(Modifier.padding(top = 6.dp)) {
            val r = curIndex
            OsdRowHeading(curRow.title, null, position = if (downRows.size > 1) "${r + 1} of ${downRows.size}" else null)
            val epState = rememberLazyListState()
            // The highlighted card stays in the middle of the row (earlier ones to the left, later to the right), on
            // purpose: the episode that's playing is the middle one, so going back or forward an episode is one press
            // either way (Peter, 2026-11-20). The cards slide under it, as the poster rows move.
            // (A row just opened isn't measured yet: a frame or two first, then straight there with no slide.)
            val epSlide = rememberRowSlide(epState) { curRow.items.size }
            val placed = remember(r) { booleanArrayOf(false) }
            LaunchedEffect(r, episodeFocus) {
                runCatching {
                    repeat(3) { if (epState.layoutInfo.visibleItemsInfo.isEmpty()) withFrameNanos { } }
                    val info = epState.layoutInfo
                    val card = info.visibleItemsInfo.firstOrNull()?.size ?: 0
                    val width = info.viewportSize.width
                    val middle = if (card > 0 && width > 0) (width - card) / 2 else 0
                    if (!placed[0] || !epSlide.moveTo(episodeFocus, middle)) epState.scrollToItem(episodeFocus, -middle)
                    placed[0] = true
                }
            }
            androidx.compose.runtime.key(r) {
                LazyRow(
                    state = epState,
                    modifier = Modifier.padding(top = 2.dp).offset(x = (-12).dp).then(epSlide.modifier),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false,
                ) {
                    // The same size as live TV's row cards (Settings → Preview cards sizes both).
                    items(curRow.items.size) { i -> OsdCardStatic(curRow.items[i], highlighted = i == episodeFocus) }
                }
            }
        }
    }
}

/**
 * A title logo decoded to fit a [widthDp] × [heightDp] box (logos come thousands of pixels wide: decoded whole, each
 * filled the image cache with megabytes). An explicit size, as some boxes start 0 high until the logo has loaded.
 */
@Composable
internal fun rememberLogoRequest(url: String?, widthDp: Int, heightDp: Int): coil.request.ImageRequest? {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    return remember(url, widthDp, heightDp, density) { url?.let { logoRequest(ctx, density, it, widthDp, heightDp) } }
}

/** The same outside composition (the hero loads the logo before showing it). */
internal fun logoRequest(ctx: android.content.Context, density: androidx.compose.ui.unit.Density, url: String, widthDp: Int, heightDp: Int): coil.request.ImageRequest =
    with(density) {
        coil.request.ImageRequest.Builder(ctx).data(url).size(widthDp.dp.roundToPx(), heightDp.dp.roundToPx()).scale(coil.size.Scale.FIT).build()
    }

fun clockMs(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}


/**
 * While a film or episode opens: its backdrop with a dark fade towards the bottom, and its logo fading in and then
 * slowly "breathing" (a gentle grow and shrink) in the middle; the title as text when there's no logo. Fades away
 * when the picture starts.
 */
@Composable
fun PlayerLoading(title: String, art: PlayerArt?, visible: Boolean = true, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250)),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)),
        modifier = modifier,
    ) {
        val breath = androidx.compose.animation.core.rememberInfiniteTransition(label = "breath")
        val scale by breath.animateFloat(
            initialValue = 1f, targetValue = 1.05f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(2_000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "scale",
        )
        // Fades in once, shortly after the screen appears.
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val fadeIn by androidx.compose.animation.core.animateFloatAsState(
            if (shown) 1f else 0f, androidx.compose.animation.core.tween(700, delayMillis = 300), label = "fadeIn",
        )
        val context = LocalContext.current
        var logoFailed by remember(art?.logo) { mutableStateOf(false) }
        val showLogo = art?.logo != null && !logoFailed
        Box(Modifier.fillMaxWidth().fillMaxHeight().background(Color.Black)) {
            art?.backdrop?.let { bg ->
                coil.compose.AsyncImage(
                    model = bg, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopEnd,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                )
            }
            Box(
                Modifier.fillMaxWidth().fillMaxHeight().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.4f to Color.Black.copy(alpha = 0.6f), 0.75f to Color.Black.copy(alpha = 0.82f), 1f to Color.Black.copy(alpha = 0.92f)),
                ),
            )
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showLogo) coil.compose.AsyncImage(
                    model = remember(art?.logo) { coil.request.ImageRequest.Builder(context).data(art?.logo).build() },
                    contentDescription = title, contentScale = ContentScale.Fit,
                    onError = { logoFailed = true },
                    modifier = Modifier.width(320.dp).height(140.dp)
                        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; scaleX = scale; scaleY = scale; alpha = fadeIn },
                )
                else Text(
                    title.substringBefore(" · "), fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 120.dp).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; scaleX = scale; scaleY = scale; alpha = fadeIn },
                )
                title.substringAfter(" · ", "").takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 17.sp, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(top = 20.dp).graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = fadeIn }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}


/**
 * What's playing, over the picture: poster, title (or logo), the episode, facts and the synopsis. Opened with the
 * remote's Info button (or Details), and shown by itself while paused, as other TV players do.
 */
@Composable
fun PlayerDetails(title: String, art: PlayerArt?, modifier: Modifier = Modifier) {
    // (The shade follows Settings → Appearance → Pop-up glass, so the paused controls on top look as set: it made
    // the info bar look dark whatever the slider said. A little is kept so the synopsis can be read.)
    val k = maxOf(0.3f, AppColors.palette.popupAlpha)
    Box(modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.88f * k), 0.55f to Color.Black.copy(alpha = 0.6f * k), 1f to Color.Transparent))) {
        Row(Modifier.padding(start = 64.dp, top = 56.dp, end = 64.dp).fillMaxWidth(0.7f), verticalAlignment = Alignment.Top) {
            art?.poster?.let {
                PosterImage(it, null, Modifier.width(180.dp).height(270.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(28.dp))
            }
            Column(Modifier.weight(1f)) {
                var logoShown by remember(art?.logo) { mutableStateOf(false) }
                // (At its box's size: the box starts 0 high, so Coil can't take the size from the layout.)
                val logoRequest = rememberLogoRequest(art?.logo, 360, 72)
                if (logoRequest != null) coil.compose.AsyncImage(
                    model = logoRequest,
                    contentDescription = title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                    onSuccess = { logoShown = true },
                    modifier = Modifier.height(if (logoShown) 72.dp else 0.dp).width(360.dp),
                )
                if (!logoShown) Text(title.substringBefore(" · "), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                art?.episodeLine?.let { Text(it, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = AppColors.Accent, modifier = Modifier.padding(top = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                art?.facts?.let { Text(it, fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                art?.overview?.let { Text(it, fontSize = 17.sp, lineHeight = 25.sp, color = Color.White, maxLines = 7, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp)) }
                Text("Info or Back to close", fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.padding(top = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
