package io.github.petebutty.viewarr.ui.settings

import io.github.petebutty.viewarr.ui.common.GroupNames
import io.github.petebutty.viewarr.data.guide.LiveSports
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
    // Tidy group names, its clean-up and Live now sports: kept with the appearance settings (palette title parts).
    val appearance: AppearanceViewModel = hiltViewModel()
    fun item(title: String, description: String, key: String, all: List<RemoteChoice>, current: RemoteChoice) =
        SettingItem(title, description, all, current) { vm.set(key, it) }
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
        // Watch progress for IPTV and addon films and shows, shared through Dispatcharr (data/sync/WatchSync.kt): other
        // TVs signed in to the same Dispatcharr user, and companion plugins (e.g. syncing with Emby / Jellyfin).
        SettingCategory(
            "Watch progress", "Only for films and episodes you play from your IPTV provider or addons (not Plex, Jellyfin or Emby: they keep their own). If you use Trakt for your watch history, you don't need any of these: leave them off.",
            listOf(
                onOffItem(
                    "Sync with Dispatcharr",
                    "On: where you stopped in an IPTV or addon film or episode, and what you've finished, is saved on your Dispatcharr (in your own user's storage), so every TV signed in as you carries on from the same place. Off: each TV remembers only what was played on it.",
                    io.github.petebutty.viewarr.data.sync.WatchSync.enabled,
                ) { on -> appearance.setTitlePart(io.github.petebutty.viewarr.data.sync.WatchSync.ENABLE_KEY, on); if (on) io.github.petebutty.viewarr.data.sync.WatchSync.requestSync() },
                onOffItem(
                    "Sync IPTV progress with my Emby / Jellyfin / Plex",
                    "For people without Trakt. On: when you finish or stop an IPTV film or episode, the Viewarr plugin on your Dispatcharr marks the same title in your Emby, Jellyfin or Plex (and the other way round for titles you've watched on IPTV), every 5 minutes. Needs \"Sync with Dispatcharr\" on and the Viewarr plugin installed. To do it, Viewarr gives the plugin your sign-in for those servers, kept in your own Dispatcharr storage. Off: the sign-in is taken back out and nothing more is synced.",
                    io.github.petebutty.viewarr.data.sync.WatchSync.serversOn,
                ) { on ->
                    appearance.setTitlePart(io.github.petebutty.viewarr.data.sync.WatchSync.SERVERS_SWITCH, !on)
                    io.github.petebutty.viewarr.data.sync.WatchSync.setServers(on)
                },
                SettingItem(
                    "Sync now",
                    "Saves this TV's IPTV watch progress to Dispatcharr and takes in anything newer from your other TVs now, instead of waiting for the next automatic sync (every 5 minutes).",
                    emptyList(),
                    PlainChoice(io.github.petebutty.viewarr.data.sync.WatchSync.lastSync.takeIf { it > 0 }?.let { "Last: " + io.github.petebutty.viewarr.ui.theme.Dates.time(it) } ?: "OK: sync"),
                    action = { io.github.petebutty.viewarr.data.sync.WatchSync.requestSync() },
                    onSelect = {},
                ),
            ),
        ),
        SettingCategory(
            "Video", "Picture size and smoothness.",
            listOf(
                item("Match frame rate", "Switches the TV to the video's own rate for smoother motion: a film's 24 Hz, or a live channel's 50 or 60 Hz (once its rate is certain, a few seconds in, and only full screen). Some TVs go black for a second when it switches; live TV only switches when a channel's rate differs from the TV's.", k.FRAME_RATE, choices<FrameRateMatch>(), s.frameRate),
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
            ),
        ),
        // Tidy group names: which marks are left out.
        SettingCategory(
            "Group name clean-up", "With Tidy group names on (Live TV → Channel groups): the marks left out of channel group names. Switch one off to keep it.",
            GroupNames.words.map { w -> onOffItem(w, "On: \"$w\" is left out of group names", GroupNames.removes(w)) { on -> appearance.setTitlePart(GroupNames.wordKey(w), on) } },
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
        // Live now (📡 in the TV guide's groups): the sports you want in it.
        SettingCategory(
            "Live now sports", "Which sports go in Live now (📡 Live now in the TV guide's channel groups). Switch off the ones you're not interested in. Events whose sport isn't known always show.",
            LiveSports.names.map { name ->
                onOffItem(name, "On: $name events go in Live now", AppColors.palette.shows(LiveSports.key(name))) { on -> appearance.setTitlePart(LiveSports.key(name), on) }
            },
        ),
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
                }
            },
        ),
        // Live now by league, or only what you follow (Peter, 2026-11-17). The leagues: those with games in the
        // server's list (the Viewarr plugin) right now.
        SettingCategory(
            "Live now leagues", "Only what I follow: Live now shows just your teams' and leagues' games (Settings → Sports); darts, snooker, racing and other events still go by Live now sports. Below: the leagues the server has listed games for (none without the Viewarr plugin: use Only what I follow then), switch off the ones you don't want.",
            listOf(
                SettingItem("Follow a team or league", "Find a team or league and follow it (Settings → Sports): its games are then in Live now with \"Only what I follow\" and on the Sports page, and it can have score alerts (OK on it in Settings → Sports).",
                    emptyList(), PlainChoice("OK: follow"), action = { SettingsJump.sports = true }, onSelect = {}),
                onOffItem("Only what I follow", "On: Live now shows only your followed teams' and leagues' games (Settings → Sports). Off: every league below.", LiveSports.followedOnly) { on -> appearance.setTitlePart("live:everything", !on) },
            ) +
                io.github.petebutty.viewarr.data.sync.ServerSports.latest?.games?.values.orEmpty()
                    .mapNotNull { g -> g.leaguePath?.let { it to (g.league ?: it) } }.distinctBy { it.first }.sortedBy { it.second }
                    .map { (lp, name) -> onOffItem(name, "On: this league's games go in Live now", LiveSports.leagueOn(lp)) { on -> appearance.setTitlePart(LiveSports.leagueKey(lp), on) } },
        ),
        // Low-memory mode: no trailer previews (a second player while you browse): locked.
        SettingCategory(
            "Trailers", "YouTube trailers for films and shows.",
            listOf(
                item("Trailer previews", "When you rest on a poster in Movies or Shows, its trailer plays in a small window at the top.", k.TRAILER_PREVIEW, choices<TrailerPreview>(), s.trailerPreview),
                item("Start after", "How long to stay on a poster before its trailer starts.", k.TRAILER_DELAY, choices<TrailerDelay>(), s.trailerDelay),
                optItem(opts, "Then the big panel", "With trailers in the poster: how long it plays in the card before it grows into a big panel in the middle with sound, the same trailer playing on (OK does it at once). In the panel OK opens the title page, Back closes it.", io.github.petebutty.viewarr.ui.theme.UiOpts.TRAILER_GROW),
                optItem(opts, "Trailer previews play", "In a corner window at the top of the page, or in the poster itself: it widens to a 16:9 picture with the trailer playing inside, and shrinks back when you move on.", io.github.petebutty.viewarr.ui.theme.UiOpts.TRAILER_STYLE),
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
            ),
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
    listOf(FILM_TIME_RIGHT, CONTROLS_TIME, SEEK_PREVIEWS, PLAY_ORDER, AFTER_FILM, FILM_BACK, UP_NEXT_S, UP_NEXT, MUSIC_LRCLIB)
}

/** The Playback page's Reset: what it lists back to how a fresh install has it. */
fun playbackReset(vm: PlaybackSettingsViewModel, opts: UiOptsViewModel) {
    vm.resetKeys(PlaybackKeys)
    opts.reset(PlaybackOpts)
}
