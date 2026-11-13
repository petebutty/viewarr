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

**🔍 Search settings** sits at the top: type a word (frame rate, wallpaper, subtitles…) and every setting that matches is listed with where it lives, such as *Playback › Video*. OK opens that page with the setting highlighted. *(0.28.0 and later.)*

How the pages are split: **Appearance** is how things look, **Live TV** and **Playback** are how things behave, **Menu & navigation** is where things are, and **Remote buttons** is which button does what. Each setting lives in one place only.

## Look & controls

### Appearance

Everything to do with how Viewarr looks, in sections:

| Section | What it covers |
|---|---|
| **Theme** | **Background colour** (Viewarr's charcoal, or Classic blue, Ocean blue, Sky blue and others), **Highlight colour** (orange by default, including Bright cyan), **Text size**, **Font**, **Clock**, **Date format** (Tue 10 Nov, Tue, Nov 10, Tue 10/11 or Tue 11/10) and **Time format**. |
| **Wallpaper** | None, one of sixteen built-in designs, or the artwork of the programme you've highlighted. |
| **Glass & transparency** | **Glass**, **Glass colour**, **Guide**, **Wallpaper dimming**, **Pop-up glass** and the info bar's **border**. |
| **TV guide** | Channel numbers, logos, **Programme corners**, progress bars, episode numbers, catch-up icons and programme pictures. |
| **Info bar & controls** | **Info bar size** and **width**, **Preview cards**, the channel number, episode numbers, picture-quality and frame-rate badges, and **Player controls size**. |
| **Menu** | Headings, colour edge and tint, the icon column, hints, the TV and LIVE marks, and **Button hints**. |
| **Posters** | **Library posters** size, **Where it's from** and **Progress bars**. |
| **Title pages** | What a title page shows, and **Age ratings**. |
| **Ratings** | Which ratings show, and their order. |
| **Subtitles** | **Size**, **Colour** and **Background**. |
| **Music** | Now Playing's **Artwork**, **Visualiser**, **Screensaver** and **Lyrics**. |
| **Motion** | **List scrolling** (Instant, the default, or Smooth). |

### Home page & menu

Which libraries the menu lists for Movies and Shows (on or off, order, names), and which rows the Movies and Shows pages show, in what order. Each row says where it comes from. Also **Combine rows from different servers**. See [Films & shows](FILMS-AND-SHOWS.md#choosing-your-rows).

### Remote buttons

Every button on your remote, and what it does in the TV guide, on live TV, in films & shows and in menus & pages. **Presets** (Viewarr or TiviMate), **Skip size**, **Channel change delay**, the **Search button** and extra buttons. See [Remote control](REMOTE.md).

### Menu & navigation

| Section | Settings |
|---|---|
| **Start** | **App opens on**: live TV (**Last channel** plays the channel you watched last, full screen), the TV guide, or one of your libraries. |
| **Menu** | **Menu opens on** (the current page's own list, or the main list) and **Right on a library**. |
| **What the menu lists** | Which entries the main menu shows (Live now, Sports, Movies, Shows, Music, Recordings, Search and your streaming services). |
| **Poster rows** | **Moving between rows** and **Poster position**. |

## Watching

### Live TV

| Section | Settings |
|---|---|
| **Watching** | **Up / Down while watching** (change channel, or browse in the info bar), **Browsing channels**, **Highlight the corner picture**, **Still watching?** |
| **TV guide** | **TV guide days ahead** (1, 3, 5 or 7), **TV guide refresh**, **End of a list**. |
| **Channel groups** | **"All channels" group** (shown or hidden). |

How the guide and info bar look is in **Appearance**. See [Live TV](LIVE-TV.md).

### Playback

| Section | What it covers |
|---|---|
| **Player** | **Player** (built-in or mpv), **Video view**, **Resume**, **Controls stay up for**, **Seek-bar previews**, **mpv decoding**. |
| **Video** | **Match frame rate** (Off, Films and episodes, Live TV, or Both), **Picture size**, **Deinterlace**. |
| **Audio** | **Language**, **Surround passthrough**, **Channels**, **Night mode**, **Volume boost**, **Lip sync**. |
| **Subtitles** | **Show subtitles** and **Language** (how they look is in Appearance). |
| **Streams** | **Addon streams**, **Best quality allowed**, **Plex quality**, **Buffer**. |
| **Episodes** | **Skip intros**, **Skip credits**, **Play next episode automatically**, **After a film or episode**, **Next episode countdown**. |
| **Trailers** | **Trailer previews**, **Start after**, **Preview sound**. |
| **Music** | **Lyrics from LRCLIB**. |

### Sports

Your teams and leagues: type a team, league or sport, or pick a sport, then a league, then a team. Their games show on the **Sports** page in the menu. See [Live TV](LIVE-TV.md#sports).

### Watchlist

Which watchlist **Add / Remove** and the **Your watchlist** row use: Trakt, Plex, Jellyfin favourites or Emby favourites. OK moves to the next choice.

## Live TV server

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

### Troubleshooting

**Show playback info** (format, decoder and dropped frames while playing) and **Low-memory mode** for boxes with 1–2 GB. See [Troubleshooting](TROUBLESHOOTING.md).

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

Signs this TV out of all your accounts and sources. It asks first: **Cancel** (highlighted) or **Sign out**.
