<p align="center">
  <img src="docs/images/banner.png" alt="Viewarr" width="640">
</p>

<p align="center"><b>Live TV and streaming for Android TV, built around Dispatcharr.</b></p>

Viewarr is an Android TV app that brings your live TV and your films and shows together in one place. Live TV comes from your own [Dispatcharr](https://github.com/Dispatcharr/Dispatcharr) server; films and shows come from Plex, Stremio addons and Jellyfin/Emby, with TMDB and Trakt for discovery.

Viewarr doesn't provide any content. It plays only what your own servers and accounts give it.

> **Status: early beta.** It's tested on an Amlogic Android TV box (2 GB). For the smoothest films and full surround sound, a stronger box such as an Nvidia Shield TV Pro helps. Things may break; please report anything odd in [Issues](../../issues).

## Features

**Live TV (Dispatcharr)**
- A TiviMate-style TV guide with channel logos, categories, programmes going back and forward in time, and a progress bar under what's on now.
- Catch-up: replay programmes from the past, where the channel supports it.
- **Source switching:** see every stream behind a channel (and which provider it's from) and switch Dispatcharr to another one from the remote. **Stream info** shows Dispatcharr's live view: source, provider, resolution, codecs, bitrate and viewers. *(Admin users.)*
- **Dispatcharr plugins** (Settings → Dispatcharr plugins): switch installed plugins on and off, press their action buttons, change simple settings, and install or update plugins from your server's plugin store. *(Admin users.)*
- Recordings on your Dispatcharr server: record one programme or a whole series (every episode, or new ones only). Then watch, stop or delete recordings from the app.
- A channel list over the picture (Up/Down while watching), channel groups, and number keys to type a channel number.
- No Dispatcharr? Sign in with your **IPTV provider's (Xtream Codes) login** for channels, guide, catch-up and the provider's films and series.

**Films & shows**
- Netflix-style rows: Continue watching, your Trakt watchlist and recommendations, recently added on Plex, and trending, popular and top-rated titles from TMDB. Choose which rows show, and their order, in **Settings → Home rows**.
- Detail pages with cast, rating, and "In your library" badges.
- Seasons and episodes for shows.
- Play from **Plex** (converted by Plex automatically when the TV can't play the file itself), from **Jellyfin / Emby**, or search your **Stremio addons** (e.g. AIOStreams) for streams.
- Watch progress is sent back to Plex, Jellyfin/Emby and Trakt.
- Two players: **mpv** (the default; plays almost any file, codec or soundtrack, including TrueHD/Atmos and DTS-HD) or the lighter built-in player. Swap between them mid-film from the hold-OK menu and carry on from the same moment.
- **Seek bar with preview pictures:** press Down while watching, scrub with Left/Right, OK to jump. Previews come from Plex / Emby / Jellyfin when the server made them, otherwise the box takes them from the film itself.
- **Smooth playback on weak boxes:** mpv draws straight to the screen by default, the TV can switch to the film's frame rate, and surround passthrough only uses formats your TV or receiver really accepts.
- **Playback settings:** audio and subtitle language, surround passthrough to a receiver, stereo downmix, night mode, subtitle size, resume, and Plex quality.
- Search across films, shows, your Plex library, addons and TV channels.

**Easy setup**
- Scan a QR code on the TV and set everything up on your phone. Passwords and keys are encrypted between your phone and the TV.
- A big choice of colours (an **OLED black** theme by default), square or rounded guide corners, and remote-control options.
- Only want live TV? Turn Movies & Shows off in **Settings → Appearance → Menu**.

## What you need

- An **Android TV** device (Android 8 or newer): Google TV, Nvidia Shield, Formuler, Chromecast with Google TV, Fire TV and so on.
- A phone on the **same Wi-Fi** as the TV, for setup.
- For live TV: a **Dispatcharr** server and an account on it (Standard or Admin user; source switching, stream info and plugins need Admin), or an IPTV provider's Xtream Codes login.
- Optional:
  - A free [TMDB](https://www.themoviedb.org/) API key, needed for the Movies & Shows pages.
  - A Trakt account.
  - A Plex server.
  - A Stremio addon manifest link.
  - A Jellyfin or Emby server.

## Install

1. Download the latest `Viewarr-x.y.z.apk` from [Releases](../../releases).
2. Install it on your TV. The easiest way is the **Downloader** app (by AFTVnews): enter the APK's download link, then allow Downloader to install apps when asked. Alternatively, copy it with **Send Files to TV**, or use `adb install`.
3. Open Viewarr and follow the setup. It takes a few minutes.

The full walkthrough, with what each step needs and troubleshooting tips, is in the **[setup guide](docs/SETUP.md)**.

## Remote control

| Where | Button | What it does |
|---|---|---|
| TV guide | OK | Watch live, or replay a past programme. On a programme still to come: show its details. |
| TV guide | Hold OK | Record options (this programme, or the series) |
| TV guide | Left (tap / hold) | Earlier programmes / categories menu |
| TV guide | Hold Back | Back to the channel that's playing, full screen |
| TV guide | Number keys | Type a channel number |
| Watching live TV | Up / Down | Channel list (Left from there: channel groups) |
| Watching live TV | Channel + / − | Change channel |
| Watching live TV | OK | Player controls (audio, subtitles, aspect, source, stream info, record) |
| Films & shows | Left at the start of a row, or Back | Menu |
| Film player | OK / hold OK | Pause / audio, subtitles and switch player |
| Film player | Down, then Left / Right, OK | Seek bar with previews: move, then jump there |
| Film player | Back | Hide the controls, then stop (your place is saved) |

Most of these can be changed in **Settings → Remote & navigation**.

## Building from source

Requirements: JDK 17 and the Android SDK (platform 35).

```bash
git clone https://github.com/petebutty/viewarr.git
cd viewarr
./gradlew assembleRelease
```

Optional private files in the project root (they're git-ignored):

- `secrets.properties`: `traktClientId=…` from your own app at [trakt.tv/oauth/applications](https://trakt.tv/oauth/applications). Use `urn:ietf:wg:oauth:2.0:oob` as the redirect URI. Without this file, Trakt isn't offered.
- `keystore.properties`: `storeFile`, `storePassword`, `keyAlias` and `keyPassword` for your release signing key. Without it, builds are signed with the debug key.

## Privacy

- Viewarr has no analytics, no ads and no servers of its own. It talks only to the services you connect.
- Account details are stored on the TV, encrypted with the Android Keystore, and are left out of Android backups.
- The phone setup page runs on the TV only while the setup screen is open. It uses a one-time link, and everything you type is encrypted on your phone before it's sent.

## Licence

Viewarr is free software under the **GNU General Public License v3.0**: see [LICENSE](LICENSE).

It's built on:
- [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android) (MIT), which bundles mpv and FFmpeg.
- Jetpack Compose for TV, AndroidX Media3 (ExoPlayer), Hilt, Room, Retrofit/OkHttp, kotlinx.serialization, Coil and ZXing (all Apache 2.0).
- [jellyfin-androidx-media](https://github.com/jellyfin/jellyfin-androidx-media) FFmpeg decoders (GPL-3.0).
- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0) with Mozilla Rhino (MPL-2.0) and jsoup (MIT), for YouTube trailers.
- Bouncy Castle (MIT-style).
- The Poppins font (SIL Open Font License).

Not affiliated with Dispatcharr, Plex, Trakt, TMDB, Stremio, Jellyfin, Emby or YouTube. This product uses the TMDB API but is not endorsed or certified by TMDB.
