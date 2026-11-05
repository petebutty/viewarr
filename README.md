<p align="center">
  <img src="docs/images/banner.png" alt="Viewarr" width="640">
</p>

<p align="center"><b>Live TV and streaming for Android TV, built around Dispatcharr.</b></p>

Viewarr is an Android TV app that brings your live TV and your films and shows together in one place. Live TV comes from your own [Dispatcharr](https://github.com/Dispatcharr/Dispatcharr) server; films and shows come from Plex, Stremio addons and Jellyfin/Emby, with TMDB and Trakt for discovery.

Viewarr doesn't provide any content. It plays only what your own servers and accounts give it.

> **Status: early beta.** It's tested on a Formuler Android TV box. Things may break; please report anything odd in [Issues](../../issues).

## Features

**Live TV (Dispatcharr)**
- A TiviMate-style TV guide with channel logos, categories, and programmes going back and forward in time.
- Catch-up: replay programmes from the past, where the channel supports it.
- Recordings on your Dispatcharr server: record one programme or a whole series (every episode, or new ones only). Then watch, stop or delete recordings from the app.
- A channel list over the picture (Up/Down while watching), channel groups, and number keys to type a channel number.

**Films & shows**
- Netflix-style rows: Continue watching, your Trakt watchlist and recommendations, recently added on Plex, and trending, popular and top-rated titles from TMDB.
- Detail pages with cast, rating, and "In your library" badges.
- Seasons and episodes for shows.
- Play from **Plex**, converted by Plex automatically when the TV can't play the file itself. Or search your **Stremio addons** (e.g. AIOStreams) for streams.
- Watch progress is sent back to Plex and Trakt.
- Two players: **mpv** (the default; plays almost any file, codec or soundtrack, including TrueHD/Atmos and DTS-HD) or the lighter built-in player.
- **Playback settings:** audio and subtitle language, surround passthrough to a receiver, stereo downmix, night mode, subtitle size, resume, and Plex quality.
- Search across films, shows, your Plex library, addons and TV channels.

**Easy setup**
- Scan a QR code on the TV and set everything up on your phone. Passwords and keys are encrypted between your phone and the TV.
- A big choice of colours and remote-control options.

## What you need

- An **Android TV** device (Android 8 or newer): Google TV, Nvidia Shield, Formuler, Chromecast with Google TV, Fire TV and so on.
- A phone on the **same Wi-Fi** as the TV, for setup.
- For live TV: a **Dispatcharr** server and an account on it (Standard or Admin user).
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
| Watching live TV | OK | Player controls (audio, subtitles, aspect, record) |
| Films & shows | Left at the start of a row, or Back | Catalogue menu |
| Film player | OK / hold OK | Pause / audio and subtitles |

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
