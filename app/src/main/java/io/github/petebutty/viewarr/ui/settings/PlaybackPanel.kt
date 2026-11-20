package io.github.petebutty.viewarr.ui.settings

import io.github.petebutty.viewarr.ui.common.GroupNames
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.petebutty.viewarr.data.settings.PlaybackSettings
import io.github.petebutty.viewarr.data.settings.PlaybackStore
import io.github.petebutty.viewarr.data.settings.RemoteChoice
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import io.github.petebutty.viewarr.data.settings.AudioDelay
import io.github.petebutty.viewarr.data.settings.AudioLanguage
import io.github.petebutty.viewarr.data.settings.AutoNext
import io.github.petebutty.viewarr.data.settings.BufferSize
import io.github.petebutty.viewarr.data.settings.CachedOnly
import io.github.petebutty.viewarr.data.settings.Deinterlace
import io.github.petebutty.viewarr.data.settings.Downmix
import io.github.petebutty.viewarr.data.settings.FrameRateMatch
import io.github.petebutty.viewarr.data.settings.MaxResolution
import io.github.petebutty.viewarr.data.settings.MpvDecoding
import io.github.petebutty.viewarr.data.settings.NightMode
import io.github.petebutty.viewarr.data.settings.Passthrough
import io.github.petebutty.viewarr.data.settings.PictureSize
import io.github.petebutty.viewarr.data.settings.PlayerEngine
import io.github.petebutty.viewarr.data.settings.PlexQuality
import io.github.petebutty.viewarr.data.settings.ResumeMode
import io.github.petebutty.viewarr.data.settings.SkipMode
import io.github.petebutty.viewarr.data.settings.StatsOverlay
import io.github.petebutty.viewarr.data.settings.StreamFallback
import io.github.petebutty.viewarr.data.settings.StreamPick
import io.github.petebutty.viewarr.data.settings.SubtitleBackground
import io.github.petebutty.viewarr.data.settings.SubtitleColour
import io.github.petebutty.viewarr.data.settings.SubtitleLanguage
import io.github.petebutty.viewarr.data.settings.SubtitleMode
import io.github.petebutty.viewarr.data.settings.SubtitleSize
import io.github.petebutty.viewarr.data.settings.TrailerDelay
import io.github.petebutty.viewarr.data.settings.TrailerPreview
import io.github.petebutty.viewarr.data.settings.TrailerSound
import io.github.petebutty.viewarr.data.settings.VideoView
import io.github.petebutty.viewarr.data.settings.VolumeBoost
import io.github.petebutty.viewarr.ui.guide.GuideViewModel
import io.github.petebutty.viewarr.ui.guide.ShiftChoice
import io.github.petebutty.viewarr.ui.theme.AppColors
import io.github.petebutty.viewarr.ui.theme.UiOpts

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val store: PlaybackStore,
) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSettings())

    fun set(key: String, value: RemoteChoice) = viewModelScope.launch { store.set(key, value as Enum<*>) }
    /** Just these settings back to their defaults (a page's Reset; the rest stay as they are). */
    fun resetKeys(keys: List<String>) = viewModelScope.launch { keys.forEach { store.setText(it, "") } }
    fun setText(key: String, value: String) = viewModelScope.launch { store.setText(key, value) }
}

private inline fun <reified E> choices(): List<RemoteChoice> where E : Enum<E>, E : RemoteChoice = enumValues<E>().toList()

/** Settings → Playback, full screen. Defaults are the safe choice for most TVs; each setting explains itself. */
@Composable
fun playbackCategories(vm: PlaybackSettingsViewModel = hiltViewModel(), opts: UiOptsViewModel = hiltViewModel()): List<SettingCategory> {
    val s by vm.settings.collectAsStateWithLifecycle()
    // Guide time offset (this Live TV source's; channels' own are set by holding OK in the guide).
    val guideVm: GuideViewModel = hiltViewModel()
    guideVm.shiftVersion.collectAsStateWithLifecycle().value
    val shiftNow = guideVm.sourceShiftMin()
    // (The groups list redraws as the groups arrive, and the starting ticks once the plugin's groups are known.)
    guideVm.groups.collectAsStateWithLifecycle().value
    val liveDefaultsLoaded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { guideVm.loadLiveGroupDefaults(); liveDefaultsLoaded.value = true }
    liveDefaultsLoaded.value
    // Tidy group names and its clean-up: kept with the appearance settings (palette title parts).
    val appearance: AppearanceViewModel = hiltViewModel()
    // Settings → Live TV → Live now's list (ui/settings/LiveNowSettings.kt).
    val liveNowList = liveNowPicksState()
    // (Its default: the first choice, but for the three that start elsewhere.)
    val startsAt = mapOf<String, Any>(PlaybackStore.CACHED_ONLY to io.github.petebutty.viewarr.data.settings.CachedOnly.Off,
        PlaybackStore.STREAM_FALLBACK to io.github.petebutty.viewarr.data.settings.StreamFallback.On,
        PlaybackStore.TRAILER_DELAY to io.github.petebutty.viewarr.data.settings.TrailerDelay.S3)
    fun item(title: String, description: String, key: String, all: List<RemoteChoice>, current: RemoteChoice) =
        SettingItem(title, description, all, current) { vm.set(key, it) }.also { it.changed = current != (startsAt[key] ?: all.firstOrNull()) }
    val k = PlaybackStore.Companion
    // Low-memory mode: what it switches off stays listed, locked, with how to get it back.
    val lowLock = if (AppColors.palette.lowMemory) Locks.LOW_MEMORY else null
    val categories = listOfNotNull(
        SettingCategory(
            "Player", "Which player plays films, shows and recordings, and how it behaves.",
            listOf(
                item("Player", "The built-in player is lighter and handles surround sound best. Choose mpv to use it for everything.", k.ENGINE, choices<PlayerEngine>(), s.engine),
                item("Video view", "Standard: the box's own video hardware shows the film (lighter, HDR and Dolby Vision as the file has them, a smooth glide into the corner). Compatible: drawn by Viewarr, for a box where Standard leaves the picture black.", k.VIDEO_VIEW, choices<VideoView>(), s.videoView),
                item("Resume", "Carry on where you stopped last time, or always start at the beginning.", k.RESUME, choices<ResumeMode>(), s.resume),
                optItem(opts, "Time on the right", "Under the seek bar of films and episodes: how long is left (15:05 left), the time it ends (Ends at 21:43, in your time format), or both.", UiOpts.FILM_TIME_RIGHT),
                optItem(opts, "Controls stay up for", "How long the seek bar and buttons stay on screen after a button press.", UiOpts.CONTROLS_TIME),
                optItem(opts, "Player controls size", "How big the seek bar, title and buttons are while a film or episode plays. Smaller sizes cover less of the picture.", UiOpts.CONTROLS_SIZE),
                optItem(opts, "Seek-bar previews", "Pictures above the seek bar. Without the server's own, the box reads the film a second time to make them: \"Server's only\" or Off saves bandwidth and memory.", UiOpts.SEEK_PREVIEWS),
                item("mpv decoding", "Direct to screen is smoothest, and changes by itself when a film has picture subtitles (as Blu-rays do). Try software only if the picture glitches or shows colour blocks.", k.MPV_DECODING, choices<MpvDecoding>(), s.mpvDecoding),
            ),
        ),
        // Watch progress: which one place keeps track of what you've watched, and its options (WatchProgressItems.kt).
        watchProgressCategory(opts, appearance),
        SettingCategory(
            "Video", "Picture size and smoothness.",
            listOf(
                item("Match frame rate", "Switches the TV to the video's own rate for smoother motion: a film's 24 Hz, or a live channel's 50 or 60 Hz (once its rate is certain, a few seconds in, and only full screen). Some TVs go black for a second when it switches; live TV only switches when a channel's rate differs from the TV's.", k.FRAME_RATE, choices<FrameRateMatch>(), s.frameRate),
                optItem(opts, "Sound after a frame-rate switch", "How long a film waits after the TV switches rate before its sound starts. Some TVs and sound systems (over HDMI ARC especially) need a few seconds to pick the sound back up; too short and a film can start silent. Only used when the TV actually switches.", io.github.petebutty.viewarr.ui.theme.UiOpts.AFR_SOUND_DELAY),
                item("Picture size", "How the picture fills the screen when its shape doesn't match the TV.", k.PICTURE_SIZE, choices<PictureSize>(), s.pictureSize),
                item("Deinterlace", "Smooths combing lines on older TV recordings and broadcasts (mpv).", k.DEINTERLACE, choices<Deinterlace>(), s.deinterlace),
            ),
        ),
        SettingCategory(
            "Audio", "Language, surround sound and volume.",
            listOf(
                item("Language", "The soundtrack picked when a film has several.", k.AUDIO_LANG, choices<AudioLanguage>(), s.audioLanguage),
                item("Surround passthrough", "Only turn on if you have an AV receiver or soundbar that decodes Dolby / DTS. Fixes stuttering sound on weak boxes.", k.PASSTHROUGH, choices<Passthrough>(), s.passthrough),
                item("Sound channels", "Stereo mixes surround down to two speakers, so speech isn't too quiet on TV speakers.", k.DOWNMIX, choices<Downmix>(), s.downmix),
                item("Night mode", "Evens out loud and quiet parts: quieter explosions, clearer speech (mpv).", k.NIGHT, choices<NightMode>(), s.nightMode),
                item("Volume boost", "Louder than normal, for quiet films (mpv).", k.VOLUME_BOOST, choices<VolumeBoost>(), s.volumeBoost),
                item("Audio sync", "If voices are out of step with lips, move the sound earlier or later (mpv).", k.AUDIO_DELAY, choices<AudioDelay>(), s.audioDelay),
            ),
        ),
        SettingCategory(
            "Subtitles", "When subtitles show, and how they look.",
            listOf(
                item("Show subtitles", "Forced only shows the bits in another language (signs, foreign dialogue).", k.SUBS, choices<SubtitleMode>(), s.subtitles),
                item("Language", "The subtitle language picked when there are several.", k.SUB_LANG, choices<SubtitleLanguage>(), s.subtitleLanguage),
                item("Size", "How big subtitles are.", k.SUB_SIZE, choices<SubtitleSize>(), s.subtitleSize),
                item("Colour", "Text colour.", k.SUB_COLOUR, choices<SubtitleColour>(), s.subtitleColour),
                item("Background", "A dark box makes subtitles easier to read on bright scenes.", k.SUB_BACKGROUND, choices<SubtitleBackground>(), s.subtitleBackground),
            ),
        ),
        SettingCategory(
            "Streams", "Quality, Plex and addon streams.",
            listOf(
                optItem(opts, "Play order", "Where Play looks for a film or episode, in turn, playing the first that works. Hold OK on Play (or on an episode) to pick from every source yourself.", UiOpts.PLAY_ORDER),
                item("Addon streams", "Choose from the list yourself, or let Viewarr start the best cached stream within your quality limit.", k.STREAM_PICK, choices<StreamPick>(), s.streamPick),
                item("Cached only", "Leave out Usenet and torrent streams that aren't instant (marked ⚡ by your addon), in the lists and when Viewarr picks. Plain web streams always stay.", k.CACHED_ONLY, choices<CachedOnly>(), s.cachedOnly),
                item("Try the next stream", "If the addon stream Viewarr picked fails, or hasn't started after 20 seconds, the next one plays instead (up to 3 more). The built-in player only.", k.STREAM_FALLBACK, choices<StreamFallback>(), s.streamFallback),
                item("Best quality allowed", "Streams above this are listed last and never auto-picked. Match the TV works out 4K or 1080p for you.", k.MAX_RES, choices<MaxResolution>(), s.maxResolution),
                item("Plex quality", "Original plays the file itself and converts only if the TV can't. Lower settings always convert, for slow Wi-Fi.", k.PLEX_QUALITY, choices<PlexQuality>(), s.plexQuality),
                item("Buffer", "How much video is loaded ahead. Large helps on patchy internet; small helps boxes with little memory.", k.BUFFER, choices<BufferSize>(), s.buffer),
            ),
        ),
        SettingCategory(
            "Auto-play match", "Which addon stream Viewarr starts when it picks one itself: switch rules on and sort them, the most important first. Otherwise the usual order (instant first, then the highest resolution).",
            streamRuleItems(s.streamRules) { vm.setText(k.STREAM_RULES, it) },
        ),
        SettingCategory(
            "Episodes", "Watching shows: skipping intros and credits, and the next episode.",
            listOf(
                item("Skip intros", "A Skip intro button (OK skips it), skipped by itself (with a moment to go back), or off. Where the intro is comes from Plex, Jellyfin or Emby when they marked it, otherwise from online databases (addons, IPTV…; only the title's id and episode are sent).", k.SKIP_INTRO, choices<SkipMode>(), s.skipIntro),
                item("Skip credits", "During the credits: a Next episode button (OK plays it straight away; a film just ends), moved on by itself, or off. From the same places as the intros.", k.SKIP_CREDITS, choices<SkipMode>(), s.skipCredits),
                item("Play next episode automatically", "When an episode ends, the next one plays. Near the end (as the credits start, if they're marked) a countdown card shows it: OK plays it now, Back hides the card.", k.AUTO_NEXT, choices<AutoNext>(), s.autoNext),
                optItem(opts, "After a film or episode", "When it ends, or you stop it (Stop): stay where you were (its page, or wherever you started it), or back to live TV on the channel you were watching. (Back makes it the mini player instead.)", UiOpts.AFTER_FILM),
                optItem(opts, "Back from a film", "Back on a full-screen film or episode makes it the mini player: over its own page (where you started it, usually its title page), or over the TV guide with the menu open on Live TV. Hold Back still stops it.", UiOpts.FILM_BACK),
                optSlider(opts, "Next episode countdown", "How long the countdown runs before the next episode plays (Left / Right, 5 seconds at a time). It starts with the credits, or that long before the end, and always finishes before the episode does. No wait: straight on.", UiOpts.UP_NEXT_S, AppColors.palette.upNextSeconds / 5),
            ),
        ),
        SettingCategory(
            "Live TV", "Watching live channels.",
            listOf(
                optItem(opts, "Highlight the corner picture", "At the edge of a page, pressing towards the picture in the corner (live TV or a film) highlights it: Full screen or Close. Holding Back gives the same choice anywhere. Off: only hold Back.", UiOpts.CORNER_FOCUS).locked(lowLock),
                optItem(opts, "Up / Down while watching", "What Up / Down (and Channel + / −) do on full-screen live TV. Browse: the info bar shows the next channels and what's on while the picture stays put; OK changes to the one shown, Back stays where you were. \"Changes when you stop\" waits as long as Browsing channels (below) says.", UiOpts.LIVE_UPDOWN),
                optItem(opts, "Browsing channels", "With a button set to \"Next channel in the info bar\" (Settings → Remote buttons, On live TV): how long to rest on a channel before it changes. OK changes straight away; Back stays on the one you were watching.", UiOpts.BROWSE_DELAY),
                optItem(opts, "\"All channels\" group", "The first entry in the channel groups. Hidden: just your own groups, and the guide opens on the first of them.", UiOpts.GROUP_ALL),
                optItem(opts, "TV guide days ahead", "How far ahead the guide reaches. Most guides list 4 to 7 days; more days take a little longer to fetch and more room on the box. Changes at the next guide refresh (Settings → Refresh channels & guide does it now).", UiOpts.GUIDE_DAYS),
                optItem(opts, "TV guide refresh", "How often the TV guide is fetched again from your server while Viewarr is open (and when it starts). Only when I ask: Settings → Refresh channels & guide.", UiOpts.GUIDE_REFRESH),
                SettingItem(
                    "EPG offset",
                    "When this source's TV guide is hours out (another time zone, summer time got wrong): every programme moved earlier or later, in the guide, the info bar, reminders, recordings and catch-up. Only some channels out? Hold OK on one in the guide → EPG offset. The listings slide straight away (nothing is fetched again).",
                    ShiftChoice.all,
                    ShiftChoice.all.firstOrNull { it.min == shiftNow } ?: ShiftChoice.all.minBy { abs(it.min - shiftNow) },
                    // (Left / Right, half an hour at a time, −10 to +10 hours.)
                    slider = true,
                ) { c -> guideVm.setSourceShiftMin((c as ShiftChoice).min) },
                onOffItem(
                    "Tidy group names",
                    "Channel group names without the marks providers add: \"UK| SKY SPORTS FHD\" shows as \"SKY SPORTS\". Choose which marks go in Group name clean-up below. Only the names shown change.",
                    GroupNames.tidy,
                ) { on -> appearance.setTitlePart(GroupNames.TIDY_KEY, !on) },
                optItem(opts, "Still watching?", "After this long without a button press, live TV asks whether you're still there and stops if nobody answers within a minute (frees a provider connection when the TV's left on).", UiOpts.STILL_WATCHING),
                optItem(opts, "Start with the best source", "A channel with several streams behind it (Dispatcharr) starts on the best one: the highest quality (Dispatcharr's own check of each stream, or the FHD / HD / SD in its name), streams that failed lately after the rest, Dispatcharr's own order to break ties. A stream that doesn't start or keeps stopping is remembered as failed for a day (three fails in a week: all week) and the next best plays. Off: Dispatcharr's own first stream, as before.", UiOpts.BEST_SOURCE),
                optItem(opts, "Event channels: only with a listing", "With the Viewarr plugin: a channel in an event group (TNT events, live football slots, pay-per-view…) is listed only while the guide has something on it now or starting within 24 hours, and a group of nothing but empty event channels is left out altogether. Reminders, recordings, links and the Sports page still reach them. Off: every event channel, all the time. Club TV channels always show.", UiOpts.EVENT_ONLY_LISTED),
                // Reports (hold OK on a channel → Report a problem): the plugin's changes with Undo, guides to pick, this TV's own.
                SettingItem("Reports", "Problems reported from the TVs in the house (hold OK on a channel → Report a problem): what the Viewarr plugin on Dispatcharr changed from them (admins can undo each one), channels that still need the right guide picked, and this TV's own reports. Without the plugin, reports are kept on this TV.",
                    emptyList(), PlainChoice("OK: open"), action = { SettingsJump.reports = true }, onSelect = {}),
            ),
        ),
        // Tidy group names: which marks are left out.
        SettingCategory(
            "Group name clean-up", "With Tidy group names on (Live TV → Channel groups): the marks left out of channel group names. Switch one off to keep it.",
            GroupNames.words.map { w -> onOffItem(w, "On: \"$w\" is left out of group names", GroupNames.removes(w)) { on -> appearance.setTitlePart(GroupNames.wordKey(w), on) } },
        ),
        // Viewarr's rows on the TV's home screen (Projectivy, Google's launcher): each switched on becomes a row there
        // (HomeRows). Channel groups first, then the Movies and Shows pages' own rows by where they come from.
        SettingCategory(
            "Home screen rows", "Put Viewarr's rows on your TV's home screen: a channel group (each card opens that channel), or any row from Movies and Shows (each card opens that title). After switching one on, your launcher may ask to show the row, or list it in its own settings (Projectivy: Settings → Channels).",
            guideVm.realGroups.map { g ->
                onOffItem("Live TV · " + io.github.petebutty.viewarr.ui.common.GroupNames.display(g.name), "A row of this group's channels, with what's on now  ·  ${g.channelCount} channels (the first 15)",
                    io.github.petebutty.viewarr.data.media.HomeRows.isOn(g.name)) { on ->
                    // (Kept as a part that's present when on; the row itself is made or removed within a few seconds.)
                    appearance.setTitlePart(io.github.petebutty.viewarr.data.media.HomeRows.PART + g.name.trim().lowercase(), !on)
                    guideVm.refreshHomeRows()
                }
            } + listOf(androidHomeApprovalItem(), SettingItem("Add every Just added row", "Every streaming service's Just added rows (films and shows) onto the home screen at once, in order: Netflix, Prime Video, Disney+ and so on.",
                listOf(PlainChoice("OK: add")), PlainChoice("OK: add"), action = { guideVm.addJustAddedRows() }, onSelect = {})) +
                homeScreenMediaRows(appearance) { guideVm.refreshHomeRows() },
        ),
        // Live now: which channel groups it searches (Peter, 2026-11-17: the user picks them). Starting ticks: the event
        // feeds' groups and sporty-named ones.
        SettingCategory(
            "Live now groups", "The channel groups Live now looks through for live sport. To start with: your event channels' groups and any group whose name sounds like sport. Switch on a group it missed, or off one you don't want.",
            guideVm.realGroups.map { g ->
                onOffItem(io.github.petebutty.viewarr.ui.common.GroupNames.display(g.name), "On: Live now looks through this group  ·  ${g.channelCount} channels", guideVm.liveGroupOn(g.name)) { on ->
                    val k = g.name.trim().lowercase()
                    appearance.setTitlePart("lngrp:" + (if (on) "-" else "+") + k, true)
                    appearance.setTitlePart("lngrp:" + (if (on) "+" else "-") + k, false)
                }
            },
        ),
        // Live now: the teams, leagues and sports it's limited to, searched for and added (the old per-sport and
        // per-league switches are gone; Peter, 2026-11-19).
        liveNowCategory(liveNowList),
        // Notifications: everything Viewarr pops up by itself, all off with one switch, or each kind.
        SettingCategory(
            "Notifications", "Everything Viewarr shows by itself. Off: nothing pops up at all. On: choose each kind below.",
            buildList {
                val n = io.github.petebutty.viewarr.ui.common.Notify
                // (A present title part = on here: everything starts off. See Notify.)
                add(onOffItem("Notifications", "Off to start: nothing pops up at all. On: choose each kind below.", !AppColors.palette.shows(n.ON)) { on -> appearance.setTitlePart(n.ON, !on) })
                if (!AppColors.palette.shows(n.ON)) {
                    val sa = io.github.petebutty.viewarr.data.sports.ScoreAlerts
                    val modes = sa.modes.map { PlainChoice(it) }
                    add(SettingItem("Score alerts", "Goals, kick-offs and results for the teams and leagues you follow, and where they show. Which kinds, and per team: Settings → Sports.",
                        modes, modes.first { it.label == sa.mode }) { c -> sa.partsFor(c.label).forEach { (k, v) -> appearance.setTitlePart(k, v) } })
                    add(onOffItem("Reminders", "A card when something you set a reminder for (a programme or a game) is about to start.", !AppColors.palette.shows(n.REMINDERS)) { on -> appearance.setTitlePart(n.REMINDERS, !on) })
                    if (!AppColors.palette.shows(n.REMINDERS)) add(optItem(opts, "Remind me", "How early the reminder comes up: when it starts, or 2, 5, 10 or 15 minutes before.", io.github.petebutty.viewarr.ui.theme.UiOpts.REMIND_LEAD))
                    add(onOffItem("Update available", "A card when a new Viewarr is out, checked when the app opens. Settings → About → Check for updates still works.", !AppColors.palette.shows(n.UPDATES)) { on -> appearance.setTitlePart(n.UPDATES, !on) })
                    add(onOffItem("What's new", "A card once after an update with what changed. Settings → About → Changelog still has it.", !AppColors.palette.shows(n.WHATS_NEW)) { on -> appearance.setTitlePart(n.WHATS_NEW, !on) })
                    add(onOffItem("Reports from the house", "A quiet card when the Viewarr plugin changes a channel from someone's report (admins: OK undoes it), or a wrong-guide report needs someone to pick the right guide (the person who reported it, and admins).", !AppColors.palette.shows(n.REPORTS)) { on -> appearance.setTitlePart(n.REPORTS, !on) })
                }
            },
        ),
        // Low-memory mode: no trailer previews (a second player while you browse): locked.
        SettingCategory(
            "Trailers", "YouTube trailers for films and shows.",
            listOf(
                // (In the order you'd set them: on, where, when, then the big panel. Peter, 2026-11-18: they were muddled.)
                item("Trailer previews", "When you rest on a poster in Movies or Shows, its trailer plays.", k.TRAILER_PREVIEW, choices<TrailerPreview>(), s.trailerPreview),
                optItem(opts, "Trailer previews play", "In the window at the top of the page, or in the poster itself (it widens to a 16:9 picture and shrinks back when you move on).", io.github.petebutty.viewarr.ui.theme.UiOpts.TRAILER_STYLE),
                item("Start after", "How long to rest on a poster before its trailer starts.", k.TRAILER_DELAY, choices<TrailerDelay>(), s.trailerDelay),
                optItem(opts, "Then the big panel", "Trailers in the poster only: how long it plays in the poster before it grows into the big panel. OK grows it straight away; in the panel OK opens the title page.", io.github.petebutty.viewarr.ui.theme.UiOpts.TRAILER_GROW),
                item("Preview sound", "Off plays previews silently. The Trailer button on a title page always has sound.", k.TRAILER_SOUND, choices<TrailerSound>(), s.trailerSound),
            ).map { it.locked(lowLock) },
        ),
        SettingCategory(
            "Music", "Now Playing: the artwork, lyrics, the visualiser and the screensaver.",
            listOf(
                optItem(opts, "Lyrics", "The words on Now Playing, following the song when they're timed (from your Navidrome).", UiOpts.MUSIC_LYRICS),
                optItem(opts, "Lyrics from LRCLIB", "When your server has no lyrics for a song, look on LRCLIB, the free lyrics library. Only the song's artist, title, album and length are sent.", UiOpts.MUSIC_LRCLIB),
                optItem(opts, "Artwork", "How Now Playing shows the album: its cover, a vinyl record with the cover as its label, a CD printed with it, a round cover, or a cover with a reflection. Records and CDs turn while the music plays.", UiOpts.MUSIC_ART),
                optItem(opts, "Visualiser", "How the music is drawn on Now Playing and the now-playing bar.", UiOpts.MUSIC_VIS),
                optItem(opts, "Screensaver", "With music playing and no button pressed for a while, Now Playing comes up big with the artwork and lyrics. Any button puts things back.", UiOpts.MUSIC_SAVER),
            ),
        ),
        SettingCategory(
            "Troubleshooting", "Tools for when something doesn't play right.",
            listOf(
                // Locked and uploaded only on Send; the code it gives is what to share in Discord.
                SettingItem("Send a report", "Something not working? This sends what Viewarr was doing to the developer, with passwords, keys, addresses and names taken out, and gives you a short code to share in Discord.",
                    listOf(PlainChoice("")), PlainChoice(""), action = { ReportUi.noteMode = false; ReportUi.open = true }, onSelect = {}),
                // Just a message (no log): a bug that doesn't need one, or an idea.
                SettingItem("Send a note", "Found a bug that doesn't need a log, or have an idea? Write a message to the developer and get a short code to share in Discord.",
                    listOf(PlainChoice("")), PlainChoice(""), action = { ReportUi.noteMode = true; ReportUi.open = true }, onSelect = {}),
                item("Show playback info", "Shows the video and audio format, decoder and dropped frames while playing.", k.STATS, choices<StatsOverlay>(), s.stats),
                optItem(opts, "Low-memory mode", "For boxes with 1–2 GB. Turns off the heavy extras across the app (box-made seek previews, guide pictures, logos, wallpaper, artwork, trailers, multi-view, extra ratings); their settings show as locked until it's off. Try it if Viewarr is slow or closes.", UiOpts.LOW_MEMORY),
                // Settings codes: this TV's settings to another, or a tester's onto yours (SettingsCodeUi.kt).
            ) + settingsCodeItems(),
        ),
    )
    return categories
}

/**
 * What the Playback page lists, as SettingsMap lays it out (keep the two in step). Subtitles' look, the player
 * controls' size and Now Playing's look are on Appearance, Live TV has its own page, and Show playback info and
 * Low-memory mode are under Troubleshooting, so this Reset leaves them alone.
 */
private val PlaybackKeys = PlaybackStore.run {
    listOf(
        ENGINE, VIDEO_VIEW, RESUME, MPV_DECODING, FRAME_RATE, PICTURE_SIZE, DEINTERLACE,
        AUDIO_LANG, PASSTHROUGH, DOWNMIX, NIGHT, VOLUME_BOOST, AUDIO_DELAY, SUBS, SUB_LANG,
        STREAM_PICK, CACHED_ONLY, STREAM_FALLBACK, MAX_RES, PLEX_QUALITY, BUFFER, STREAM_RULES,
        SKIP_INTRO, SKIP_CREDITS, AUTO_NEXT, TRAILER_PREVIEW, TRAILER_DELAY, TRAILER_SOUND,
    )
}
private val PlaybackOpts = UiOpts.run {
    // (The next episode countdown's older form, UP_NEXT, too: it's read when the newer one isn't set.)
    listOf(FILM_TIME_RIGHT, CONTROLS_TIME, SEEK_PREVIEWS, PLAY_ORDER, AFTER_FILM, FILM_BACK, UP_NEXT_S, UP_NEXT, MUSIC_LRCLIB, SEND_TO_TRAKT)
}

/** The Playback page's Reset: what it lists back to how a fresh install has it. */
fun playbackReset(vm: PlaybackSettingsViewModel, opts: UiOptsViewModel) {
    vm.resetKeys(PlaybackKeys)
    opts.reset(PlaybackOpts)
}

/**
 * Settings → Live TV → Home screen rows: the Movies and Shows pages' rows (as those pages have them loaded), each a
 * switch, sorted IPTV, Library, Catalogue. A name used twice gets the page's name of its source, then a number.
 */
@Composable
private fun homeScreenMediaRows(appearance: AppearanceViewModel, changed: () -> Unit): List<SettingItem> {
    val out = ArrayList<Triple<String, String, SettingItem>>()
    val order = listOf("IPTV", "Library", "Catalogue")
    for (type in io.github.petebutty.viewarr.data.media.MediaType.entries) {
        val vm: io.github.petebutty.viewarr.ui.media.MediaViewModel = hiltViewModel(key = "media-${type.name}")
        // (Loaded here too: the Shows page's rows were only listed once that page had been opened, so its "Just added"
        // and other rows were missing. 2026-11-20.)
        androidx.compose.runtime.LaunchedEffect(type) { vm.load(type) }
        val rows = vm.editableRows.collectAsStateWithLifecycle().value.first.map { it.first }
        val page = if (type == io.github.petebutty.viewarr.data.media.MediaType.Movie) "Movies" else "Shows"
        for (row in rows) {
            val kind = io.github.petebutty.viewarr.data.media.HomeRows.kindOf(row.id)
            val name = "$kind · $page · " + listOfNotNull(row.groupName?.takeIf { it.isNotBlank() && !row.title.contains(it, ignoreCase = true) }, row.title).joinToString(" · ")
            val part = io.github.petebutty.viewarr.data.media.HomeRows.mediaPart(type, row.id)
            out += Triple(kind, name, onOffItem(name, "A row of these titles on the home screen (the first 15), refreshed once a day.",
                io.github.petebutty.viewarr.data.media.HomeRows.isOn(type, row.id)) { on -> appearance.setTitlePart(part, !on); changed() })
        }
    }
    // (Same name twice: numbered, so each stays its own row in the list.)
    val seen = HashMap<String, Int>()
    return out.sortedBy { order.indexOf(it.first) }.map { (_, name, item) ->
        val n = (seen[name] ?: 0) + 1; seen[name] = n
        if (n == 1) item else SettingItem("$name ($n)", item.description, item.choices, item.current, onSelect = item.onSelect)
    }
}
