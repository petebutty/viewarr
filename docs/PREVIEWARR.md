# Previewarr (preview)

> **This is a preview.** Previewarr tries out the next Viewarr interface. It may change from one release to the next, and parts of it may go or work differently later. **Viewarr carries on as the main app**, and everything here also works in Viewarr, only arranged differently.

Previewarr is built from the same code as Viewarr, with the same live TV, TV guide, players, title pages and libraries. What's new is the way in: a **home screen of tiles and rows**, like an Android TV launcher, in place of Viewarr's side menu, and **one simple Settings page** with the basics only.

It installs **alongside Viewarr** as a separate app, so you can try it without changing anything in Viewarr. You don't need both: either one works on its own.

You can tell them apart by the icon (Previewarr's is orange and pink) and the splash screen, which has a small orange **pre** on it.

## Install

1. On your TV, install the **Downloader** app (by AFTVnews) from the Play Store / Amazon Appstore.
2. In Downloader, enter the code **6733457** (or this link; both always get the newest version):

   `https://github.com/petebutty/previewarr/releases/latest/download/previewarr.apk`

   Allow Downloader to install apps when asked. (Or download `previewarr.apk` from [Previewarr's releases](https://github.com/petebutty/previewarr/releases) and copy it over with **Send Files to TV**, or use `adb install`.)
3. Open **Previewarr** from your apps. On Google TV it may be under *Apps → See all*.

Previewarr's downloads have their own page, [petebutty/previewarr](https://github.com/petebutty/previewarr/releases): `previewarr.apk` (always the latest) and `previewarr-<version>.apk` (that version). Viewarr's are on [Viewarr's releases](https://github.com/petebutty/viewarr/releases). The code and these guides are shared, here.

## Setup

Setup is the same as Viewarr's: the same accounts and sources (Dispatcharr or an IPTV login, Plex, Jellyfin / Emby, Trakt, addons, TMDB and the rest), the same setup on your phone with a QR code, and the same optional [Viewarr plugin](LIVE-TV.md#viewarr-plugin-optional) on your Dispatcharr server. Follow the **[setup guide](SETUP.md)**.

Previewarr is a separate app, so it keeps its own sign-ins and settings on the TV: setting it up doesn't change Viewarr, and the other way round.

## The home screen

Previewarr opens on its home screen. From the top:

- **Search**, **Settings (⚙)** and the clock, top right.
- The highlighted title's **name, details and story**, with its artwork faded in behind them in the top-right corner. Stay on a title for a moment and its **trailer** plays there (turn trailers on or off in **Settings → Playback → Trailers**).
- **A row of tiles** (see below).
- **Rows of films and shows**: **Play next**, **Recently added**, **Upcoming episodes**, then a row for each of your streaming services.

Cards are wide (16:9), with the title written over the picture, and a bar showing how far through you are on anything part-watched. A soft glow turns round the card you've highlighted.

### The tiles

The tiles open the main parts of the app:

| Tile | What it opens |
|---|---|
| **Now playing** | Only while something is playing in the corner: back to full screen. |
| **Live TV** | The TV guide. |
| **Movies** | Every film from your servers, IPTV and addons. |
| **TV Shows** | Your shows, next episodes first. |
| **My Libraries** | Your Plex, Jellyfin and IPTV libraries. |
| **Music** | Your music (when it's set up). |
| **Sports** | The fixtures for your teams and leagues, and the channel showing each (the football pitch tile). |
| **Streaming services** | Netflix, Disney+, BBC iPlayer and the rest, each with its own logo: new, originals, popular and top rated on that service. These come from your addons' rows. |

Live TV and Sports need live TV to be set up.

### Moving and hiding tiles

**Hold OK** on a tile:

- **Move**: press **Left / Right** to move it along the row, then **OK** to put it down.
- **Hide**: takes it off the home screen.

Hidden tiles come back in **Settings → Home screen tiles**, where OK switches each one on or off.

### The rows

- **Play next**: the shows you're part-way through, most recently watched first (from Plex, Jellyfin / Emby or Trakt), then new episodes of shows you've been watching. One card a show.
- **Recently added**: what's new on your servers.
- **Upcoming episodes**: episodes on their way for your shows.
- A row shows only when it has something in it.
- **A row for each streaming service**: press **Left** on its first title and the service's own tile slides in at the start of the row, so you know which service it is; OK on it opens the service.

**OK** on a card opens its title page, the same as in Viewarr. **Back** from the title page comes home.

### Getting around the home screen

- **Back** in the middle of a row goes to the start of that row.
- **Back** at the start of a row goes up to the top of the home screen.
- **Back** on the first tile: *Press Back again to exit*.
- There's no side menu. **Left** at the edge of a row does nothing; use **Back** to get home.

Coming back home lands you on the card you left from.

## Films and shows: the mini player

- **Back** while a film or episode plays full screen: it carries on in the **mini player**, in the top-left corner, while you browse.
- The **Now playing** tile (first on the home screen) brings it back full screen.
- **Hold Back** stops it.

Everything else about films and shows (title pages, Play and sources, players, subtitles, Trakt) works as in Viewarr: see [Films & shows](FILMS-AND-SHOWS.md).

## Live TV

- The **Live TV** tile opens the **TV guide**. **OK** on a channel plays it in the guide's window, and full screen.
- **Back** from full screen goes back to the guide.
- **Back** in the guide goes home, and the channel **keeps playing in the corner** of the home screen, with a **Now playing** tile to bring it back full screen.
- Open another page (Movies, a title, Sports…) and the channel keeps playing in that page's corner.
- **Hold Back** stops it.
- Starting a film stops the channel, so there's only ever one picture in the corner.

Going to the TV's home screen or into standby lets the channel go, and it picks up again when you come back.

The guide, catch-up, recordings, reminders, the info bar, source switching (including **[Make this the best stream](LIVE-TV.md#make-this-the-best-stream)**) and everything else about live TV work as in Viewarr: see [Live TV](LIVE-TV.md).

## Sports

The **Sports** tile opens the Sports page: your teams' and leagues' games and the channel showing each. Choose your teams in **Settings → Sports**. See [Live TV → Sports](LIVE-TV.md#sports).

## Settings

**⚙** on the home screen opens Settings: one page, with the list on the left and what the highlighted entry covers beside it. **OK** opens an entry, **Back** comes back to the list, and **Back** again goes home.

It's grouped as Viewarr's Settings, with the basics only:

| Group | Entries |
|---|---|
| **Find** | **Search settings**: find any setting by name. |
| **Accounts** | **Accounts & sources**: your live TV, Plex, Jellyfin, Emby, Trakt, addons and music sign-ins. |
| **Look & controls** | **Appearance**, **Home screen tiles**, **Remote buttons**, **Notifications**. |
| **Watching** | **Live TV**, **Playback**, **Sports**, **Watchlist**. |
| **Live TV server** | **Dispatcharr plugins** (admins only), **Refresh channels & guide**. |
| **This TV** | **Troubleshooting**, **Advanced**, **Home Assistant**, **About**, **Sign out of everything**. |

Kept from Viewarr: the TV guide's look, the info bars, **Auto-play match** rules, Live TV, Playback, subtitles, audio and video, the watchlist, Sports, Home Assistant, plugins, notifications, Remote buttons, Advanced, Troubleshooting and About. Each page works as described in [Settings](SETTINGS.md).

Left out, because Previewarr has one look of its own: themes, wallpapers and colours, glass and transparency, the main menu, the news ticker, browsing styles, poster options (rows are always wide), title-page parts, the order of ratings, and where the app opens. Settings you can't see keep their defaults.

With a film or channel in the corner, Settings moves it over to the top right and you can see the settings through it. **Hold Back** on Settings stops it.

## The remote

Previewarr starts with Viewarr's **Classic** buttons: Up / Down change channel on live TV, OK opens the controls, and so on. Each button can still be changed in **Settings → Remote buttons**. See [Remote control](REMOTE.md).

Where a button in Viewarr would open the side menu, in Previewarr it doesn't: **Back** is the way home. The guide's channel groups still open as in Viewarr.

## Links from outside

Previewarr has its own link name, `viewarrl://`, so links reach the right app when both are installed. The commands are the same as Viewarr's `viewarr://` links: see [Controlling Viewarr from outside](AUTOMATION.md).

## What's different from Viewarr

| | Viewarr | Previewarr |
|---|---|---|
| **The way in** | The glass side menu | A home screen of tiles and rows |
| **Settings** | Every option, with Customise and Advanced pages | One page, the basics only |
| **Look** | Themes, wallpapers, glass, browsing styles | One dark look with an orange highlight |
| **Remote preset** | Viewarr buttons, or Classic | Classic (each button changeable) |
| **Rows** | Posters or wide cards | Always wide cards |
| **Live TV tile / entry** | Live TV in the menu | The TV guide |

Everything else is the same: live TV and the guide, recordings, sports, films and shows, [Music](MUSIC.md), [Home Assistant](HOME-ASSISTANT.md), [Profiles](PROFILES.md) and [Privacy](PRIVACY.md).

## Troubleshooting

**I installed the wrong app**
Check the file name: `previewarr.apk` is Previewarr, `viewarr.apk` is Viewarr. They install side by side, so installing one never replaces the other.

**A tile has gone from the home screen**
It may be hidden: switch it back on in **Settings → Home screen tiles**. Music shows only once music is set up, and Live TV and Sports only with live TV. Streaming service tiles appear once your addons' rows have loaded.

**I can't find a setting I use in Viewarr**
Try **Search settings**. Some of Viewarr's options aren't in Previewarr (see [Settings](#settings)); for those, use Viewarr.

**The old icon still shows on the TV's home screen**
Some launchers keep the old picture. See [Setup → Troubleshooting](SETUP.md#5-troubleshooting).

For channels, playback or a slow box, see [Troubleshooting](TROUBLESHOOTING.md). Found something odd in the preview? Please say so in [Issues](https://github.com/petebutty/viewarr/issues), and mention it's Previewarr.
