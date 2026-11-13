# Settings

A quick tour of the Settings menu, page by page. Open it from the bottom of the main menu.

A line at the top of Settings describes the highlighted entry, and every section says what it covers. Settings pages that have many options also have a reset button.

Some entries only show for some people: **Users & profiles** and **Dispatcharr plugins** are for Dispatcharr admins, and an admin can lock accounts, settings or signing out for a profile (see [Profiles](PROFILES.md)).

## Accounts & people

### Accounts & sources

Add, change, check or remove everything you connect:

- **Live TV**: Dispatcharr, an IPTV login (Xtream Codes) or an M3U playlist. Save several sources, name them, and use **Choose groups** to leave out groups you never watch (logins and playlists).
- **TMDB**: posters, descriptions and discovery for Movies & Shows.
- **Trakt**: watchlist, history, recommendations and continue watching. Includes **Sync with Trakt now** and **Copy watched to Plex**.
- **Plex**: your Plex sign-in, and **Plex servers** to switch each server on or off.
- **Addons**: addon links such as AIOMetadata and AIOStreams. Switch each one on or off; an addon that's off adds no rows, search results or streams.
- **Jellyfin** and **Emby**.
- **IPTV films & series**: your provider's films and series through Dispatcharr (an IPTV login includes them already).
- **Seerr**: requests (Overseerr / Jellyseerr).
- **Music**: Plex, Jellyfin or Emby music libraries and/or a Navidrome server.
- **MDBList ratings**: IMDb, Rotten Tomatoes, Metacritic and Letterboxd ratings on every title (free key).

**Set up on your phone** brings back the QR code, so you can type everything on your phone. See [Setup](SETUP.md).

### Switch profile, Ask who's watching, Users & profiles, Profile PIN

- **Switch profile** and **Ask who's watching** show when there's more than one profile on the TV.
- **Users & profiles** (admins): create and manage Dispatcharr users, what they get and what they can do, and away addresses.
- **Profile PIN**: an optional PIN for your profile on this TV.

See [Profiles](PROFILES.md).

## Look & controls

### Appearance

How Viewarr looks. Changes show straight away.

| Section | What it covers |
|---|---|
| **Colours & text** | **Highlight colour** (orange by default, with ten softer ones to choose from), **Text size** (four sizes), **Font** (twelve), **Clock**, **Date format** (day first or month first) and **Time format** (24- or 12-hour). |
| **Wallpaper** | None, a built-in design, or the artwork of the programme you've highlighted, behind the guide, menus and settings. Movies and Shows always show the highlighted title's artwork. |
| **Transparency** | Sliders for the **Glass** (the menu and channel list), **Glass colour** (twelve tints; Plain is the Viewarr look), the **Guide**, and **Wallpaper dimming**. |
| **Live TV** | The guide (channel numbers, **Programme corners**, progress bars, channel logos, episode numbers, end of a list, catch-up icons, programme pictures) and the info bar (**Pop-up glass**, border, channel number, episode numbers, picture quality, frame rate, **Info bar size**, **Preview cards**). See [Live TV](LIVE-TV.md). |
| **Posters** | **Where it's from** (e.g. "Plex · Living room") and **Progress bars** on posters. |
| **Title pages** | What a title page shows (the logo, trailer, episodes row and each tab), and **Age ratings** (UK or US). Anything off isn't fetched. |
| **Ratings** | Which ratings show, and their order. |
| **Main menu** | Headings, colour edges, tints, the icon column, marks and hints, and which entries the menu lists. See [Getting around](GETTING-AROUND.md#main-menu-settings). |

### Home page & menu

Which libraries the menu lists for Movies and Shows (on or off, order, names), and which rows the Movies and Shows pages show, in what order. Each row says where it comes from. Also **Combine rows from different servers**. See [Films & shows](FILMS-AND-SHOWS.md#choosing-your-rows).

### Remote buttons

Every button on your remote, and what it does in the TV guide, on live TV, in films & shows and in menus & pages. **Presets** (Viewarr or TiviMate), **Skip size**, **Channel change delay**, the **Search button** and extra buttons. See [Remote control](REMOTE.md).

### Navigation

How you get around.

| Section | Settings |
|---|---|
| **Start** | **App opens on**: live TV (**Last channel** plays the channel you watched last, full screen), the TV guide, or one of your libraries. |
| **Menu** | **Menu opens on** (the current page's own list, or the main list), **Right on a library** (list its genres, collections and so on, or just open it), **Button hints** (first few times, always, or off). |
| **Poster rows** | **Moving between rows** (back to the poster you were on, or the first) and **Poster position** (keep the highlighted poster at the left edge, or scroll only when needed). |

## Watching

### Playback

| Section | What it covers |
|---|---|
| **Player** | **Player** (built-in or mpv), **Video view** (Standard, or Compatible for a box where Standard leaves the picture black), **Resume**, **Controls stay up for**, **Player controls size**, **Seek-bar previews**, **mpv decoding**. |
| **Video** | **Match frame rate**, **Picture size**, **Deinterlace**. |
| **Audio** | **Language**, **Surround passthrough**, **Channels** (stereo downmix), **Night mode**, **Volume boost**, **Lip sync**. |
| **Subtitles** | **Show subtitles**, **Language**, **Size**, **Colour**, **Background**. |
| **Streams** | **Addon streams** (choose yourself, or start the best cached stream), **Best quality allowed**, **Plex quality**, **Buffer**. |
| **Episodes** | **Skip intros**, **Skip credits**, **Play next episode automatically**, **After a film or episode** (stay where you were, or back to live TV), **Next episode countdown**. |
| **Live TV** | **Highlight the corner picture**, **Browsing channels**, **TV guide refresh**, **Still watching?**. |
| **Trailers** | **Trailer previews**, **Start after**, **Preview sound**. |
| **Music** | **Lyrics**, **Lyrics from LRCLIB**, **Artwork**, **Visualiser**, **Screensaver**. See [Music](MUSIC.md#music-settings). |
| **Troubleshooting** | **Show playback info** (format, decoder and dropped frames while playing) and **Low-memory mode**. See [Troubleshooting](TROUBLESHOOTING.md). |

### Sports

Your teams and leagues: type a team, league or sport, or pick a sport, then a league, then a team. Their games show on the **Sports** page in the menu. See [Live TV](LIVE-TV.md#sports).

### Watchlist

Which watchlist **Add / Remove** and the **Your watchlist** row use: Trakt, Plex, Jellyfin favourites or Emby favourites. OK moves to the next choice.

## Live TV

### Dispatcharr plugins

*(Admins only.)* Switch your Dispatcharr server's plugins on and off, press their action buttons, change simple settings, and install or update plugins. See [Live TV](LIVE-TV.md#dispatcharr-plugins).

### Refresh channels & guide

Loads the channel list and TV guide again now.

## This TV

### Backup & sync

Viewarr's setup saved on your Dispatcharr server (Dispatcharr sign-ins only):

- **Save settings to Dispatcharr**: on or off.
- **Include sign-ins**: whether your other sign-ins are saved too (encrypted).
- **Restore from Dispatcharr**: check for a saved setup now.

See [Profiles](PROFILES.md#your-setup-saved-on-dispatcharr) and [Privacy](PRIVACY.md).

### Updates

- **Check for updates**: looks for a newer Viewarr on GitHub. If there is one, you can install it straight away.
- **Check automatically**: checks when Viewarr starts (at most twice a day).
- **Include test versions**: test versions (pre-releases) get new features first. Viewarr's releases are all test versions for now, so turning this off means no updates yet.

### About

- **Viewarr**: the version, when it was installed and last updated, and where the source code is.
- **Show the tour**: the tour of how Viewarr works, from the first run.
- **This TV**: the model, Android version, memory, storage, screen and processor.
- **Storage**: what Viewarr keeps on this TV (each source's TV guide, artwork, downloaded updates and so on). **Clear artwork** frees space; posters and logos are fetched again as you browse. Downloaded updates that are installed already are safe to delete.
- Your **Live TV sources**, which is active, and when each guide was last updated.

### Sign out of everything

Signs this TV out of all your accounts and sources. Press OK twice to confirm.
