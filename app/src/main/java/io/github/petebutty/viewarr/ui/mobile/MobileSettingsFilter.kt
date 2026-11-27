package io.github.petebutty.viewarr.ui.mobile

/**
 * Settings Previewarr Mobile leaves out ("Source/Section/Title", or a whole "Source/Section/"): the ones only a TV has
 * (2026-11-27: the remote, the left-hand menu, the info bar, the TV guide grid, the TV's sound and picture output,
 * box sizes). On top of Previewarr's own (LauncherLook). Hidden settings keep their values, so a backup carried to a
 * TV still has them.
 */
internal object MobileSettingsFilter {
    private val hidden = setOf(
        "Appearance/Colours & text/Text size", "Appearance/Colours & text/Clock",
        "Appearance/Colours & text/App scaling", "Appearance/Colours & text/List scrolling",
        "Playback/Live TV/Up / Down while watching", "Playback/Live TV/Highlight the corner picture", "Playback/Live TV/Browsing channels",
        "Playback/Player/Player", "Playback/Player/Video view", "Playback/Player/mpv decoding",
        "Playback/Player/Player controls size", "Playback/Player/Controls stay up for",
        "Playback/Audio/Surround passthrough", "Playback/Audio/Sound channels",
        "Playback/Troubleshooting/Touch controls", "Playback/Troubleshooting/Low-memory mode",
    )
    private val hiddenSections = listOf(
        // The TV guide grid, the channel list and the info bar (their look, buttons and sizes).
        "Appearance/Live TV/", "Appearance/Info bar buttons/",
        // Now Playing's artwork, visualiser, screensaver and lyrics: the phone's Now playing has its own.
        "Playback/Music/",
        // Frame-rate matching, picture size and deinterlacing: the phone's player sizes the picture itself.
        "Playback/Video/",
        // Channel rows for TV launchers.
        "Playback/Home screen rows/",
        // Start screen, button hints, the menu and moving around rows with a remote (Notifications stays: it's Playback's).
        "Navigation/",
    )

    fun hides(key: String): Boolean =
        io.github.petebutty.viewarr.BuildConfig.MOBILE && (key in hidden || hiddenSections.any { key.startsWith(it) })
}
