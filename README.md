<p align="center">
  <img src="docs/images/banner.png" alt="Viewarr" width="640">
</p>

<p align="center"><b>Live TV and streaming for Android TV, built around Dispatcharr.</b></p>

Viewarr is an Android TV app that brings your live TV and your films and shows together in one place. Live TV comes from your own [Dispatcharr](https://github.com/Dispatcharr/Dispatcharr) server; films and shows come from Plex, Jellyfin/Emby and addons you add, with TMDB and Trakt for discovery.

Viewarr doesn't provide any content. It plays only what your own servers and accounts give it.

<p align="center">
  <img src="docs/screenshots/menu.png" alt="The glass menu with your streaming services" width="49%">
  <img src="docs/screenshots/guide.png" alt="The TV guide with its channel groups" width="49%">
  <img src="docs/screenshots/movies.png" alt="Movies, with the top-left window and the details beside it" width="49%">
  <img src="docs/screenshots/service.png" alt="A streaming service's page, its trailer playing top left" width="49%">
  <img src="docs/screenshots/title.png" alt="A show's page with seasons, episodes and cast" width="49%">
  <img src="docs/screenshots/epg.png" alt="The TV guide" width="49%">
</p>
<p align="center"><sub>Artwork in the screenshots is blurred: posters, programme pictures and channel logos belong to their owners.</sub></p>

> **Status: early beta.** It's tested on an Amlogic Android TV box (2 GB). For the smoothest films and full surround sound, a stronger box such as an Nvidia Shield TV Pro helps. Things may break; please report anything odd in [Issues](../../issues).

## Highlights

- **One glass menu on the left** that slides the page aside, and a **top-left window** where live TV, a film or a trailer keeps playing while you browse.
- **A TiviMate-style TV guide** for your Dispatcharr channels, up to 7 days ahead, with catch-up, pause and rewind, **Start over**, **Go to show**, **★ favourites** and channel groups in your own order.
- **Recordings, reminders and one-press record** on your Dispatcharr server; **multi-view** of up to four channels.
- **Sports**: follow teams and leagues and see which channel shows each game.
- **No Dispatcharr?** Use an IPTV provider's Xtream Codes login or an M3U playlist, and save several sources.
- **Films and shows** from Plex, Jellyfin, Emby, your addons and your IPTV provider, with Netflix-style rows, full libraries with sort, filter and A–Z, and your **streaming services** in the menu.
- **One Play button** that picks the best copy you have; hold OK on Play to choose the source yourself.
- **Skip intro, next episode** and a **mini player** that keeps a film going while you browse.
- **Music** from Navidrome, Plex, Jellyfin or Emby, with lyrics, the queue, song radio, artist pages and a spinning vinyl Now Playing.
- **An info bar you arrange yourself**: show, hide and move its buttons, and add extras such as Favourite, Last channel, Audio sync, a Sleep timer and TV settings. Browse channels in it without changing until you press OK.
- **Frame rate matching** for films and live TV (the TV switches to 50 Hz for UK channels), with frame-rate badges on the info bar.
- **Every remote button your way**, with TiviMate and Viewarr presets.
- **Profiles** for everyone in the house, sharing with friends in another house, and your setup saved on Dispatcharr.
- **Easy setup** from your phone with a QR code, and **Search settings** to find any option by name.

## What you need

- An **Android TV** device (Android 8 or newer): Google TV, Nvidia Shield, Formuler, Chromecast with Google TV, Fire TV and so on.
- A phone on the **same Wi-Fi** as the TV, for the easiest setup (you can also use the remote).
- For live TV: a **Dispatcharr** server and an account on it (Standard or Admin user; source switching, stream info and plugins need Admin), or an IPTV provider's Xtream Codes login, or an M3U playlist.
- Optional:
  - A free [TMDB](https://www.themoviedb.org/) API key, needed for the Movies & Shows pages.
  - A Trakt account.
  - A Plex server.
  - An addon manifest link.
  - A Jellyfin or Emby server.
  - A Seerr (Overseerr / Jellyseerr) server, for requests.
  - A free MDBList API key, for extra ratings.
  - A Navidrome server, or music libraries on Plex, Jellyfin or Emby, for Music.

## Install

1. On your TV, install the **Downloader** app (by AFTVnews) from the Play Store / Amazon Appstore.
2. In Downloader, enter the code **7623981** (or this link; both always get the newest version):

   `https://github.com/petebutty/viewarr/releases/latest/download/viewarr.apk`

   Allow Downloader to install apps when asked. (Or download `viewarr.apk` from [Releases](../../releases) and copy it over with **Send Files to TV**, or use `adb install`.)
3. Open Viewarr and follow the setup. It takes a few minutes.

The full walkthrough, with what each step needs and troubleshooting tips, is in the **[setup guide](docs/SETUP.md)**.

## Guides

- **[Setup](docs/SETUP.md)**: installing, what to have ready, the first run and setting up on your phone
- **[Getting around](docs/GETTING-AROUND.md)**: the glass menu, Edit menus, the top-left window and the info bar
- **[Live TV](docs/LIVE-TV.md)**: the TV guide, catch-up, recordings, multi-view, sport and several sources
- **[Films & shows](docs/FILMS-AND-SHOWS.md)**: rows, libraries, streaming services, title pages, players, search, Seerr and Trakt
- **[Music](docs/MUSIC.md)**: sources, Now Playing, the queue, artist pages and music settings
- **[Remote control](docs/REMOTE.md)**: what every button does, and changing them
- **[Settings](docs/SETTINGS.md)**: every Settings page, briefly
- **[Profiles and sharing](docs/PROFILES.md)**: profiles, PINs, friends in another house, setup saved on Dispatcharr
- **[Troubleshooting](docs/TROUBLESHOOTING.md)**: channels, playback, a slow box, music
- **[Privacy](docs/PRIVACY.md)**: what's stored and what's sent where
- **[Controlling Viewarr from outside](docs/AUTOMATION.md)**: `viewarr://` links for ADB and home automation

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

Viewarr has no analytics, no ads and no servers of its own: it talks only to the services you connect. Details are in **[Privacy](docs/PRIVACY.md)**.

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
