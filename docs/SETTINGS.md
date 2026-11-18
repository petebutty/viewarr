# Settings

A quick tour of the Settings menu, page by page. Open it from the bottom of the main menu.

A line at the top of Settings describes the highlighted entry in full, and every section says what it covers. Settings with several choices open a drop-down list. Settings pages that have many options also have a reset button.

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
| **Wallpaper** | None, one of sixteen built-in designs, or the artwork of the programme you've highlighted. **Wallpaper shade**: what darkens the wallpaper and the Movies / Shows artwork, your **Background colour** or **Black** (darker without tinting, so pictures keep their own colours). |
| **Glass & transparency** | **Glass**, **Glass colour**, **Guide**, **Wallpaper dimming**, **Pop-up glass** and the info bar's **border**. |
| **TV guide** | Channel numbers, logos, **Programme corners**, progress bars, **Channel list width** (Medium, Narrow or Wide), **Channel list progress** (a bar under the programme in the channel list: every channel, the highlighted one only, or off) and its **Highlighted progress colour**, episode numbers, **Timeline**, **Catch-up icons** (programmes and channels, channel names only, the highlighted channel only, or off), **Elapsed shading** (the part of a programme on now that's already gone), **Programme pictures**, **Programme picture shows as** (Background or Banner) and **Programme title logos**. |
| **Info bar & controls** | **Info bar style** (Glass, Square glass, Pill, Fade, Full-width bar, Minimal, Faded glass, Feathered glass, Side fade, Outline, Corner card, Floating card, Slim strip or Frosted) and **corners**, **Info bar size** and **width**, **Clock on the info bar**, **Preview cards**, **Rows under the info bar** (which rows each player shows when you press Down, and their order, with a preview), the channel number, episode numbers, picture-quality, source (where the live stream comes from) and frame-rate badges, and **Player controls size**. |
| **Menu** | Headings, colour edge and tint, hints, the TV and LIVE marks, and **Hidden groups row** (a "Hidden groups" row at the end of the channel groups; off, hold OK on any group → **Show hidden groups**). |
| **Browsing style** | **Viewarr**, **Cinema** or **Lite**: the look of Movies, Shows and the streaming services' pages in one go. Under it, **Top panel** (Normal, Big or Off) and **Row posters**. See [Films & shows](FILMS-AND-SHOWS.md#browsing-styles). |
| **Posters** | **Library posters** size, **Where it's from**, **Progress bars**, **Other posters faded** (a slider) and **Highlight zoom**. |
| **Title pages** | What a title page shows, and **Age ratings**. |
| **Ratings** | Which ratings show, and their order. |
| **Subtitles** | **Size**, **Colour** and **Background**. |
| **Music** | Now Playing's **Artwork**, **Visualiser**, **Screensaver** and **Lyrics**. |
| **Motion** | **List scrolling** (Instant, the default, or Smooth). |
| **News ticker** | A scrolling headline strip (Sky Sports, BBC News, ESPN-style looks): **With the info bar**, **In the TV guide**, **On Movies, Shows and Sports**, **Speed**, and each feed (built in, or **Add a feed** with any RSS / Atom address) Off or On in its own look. *(0.28.7 and later.)* |
| **Title logos by group** | With **Programme title logos** on (TV guide): each channel group On / Off. *(0.28.7 and later.)* |

### Home page & menu

Which libraries the menu lists for Movies and Shows (on or off, order, names), and which rows the Movies and Shows pages show, in what order. Each row says where it comes from. Also **Combine rows from different servers**, and **Continue watching from**: one switch for each server, IPTV and Trakt (see [Films & shows](FILMS-AND-SHOWS.md#continue-watching-from)). See [Films & shows](FILMS-AND-SHOWS.md#choosing-your-rows).

### Remote buttons

Every button on your remote, and what it does in the TV guide, on live TV, in films & shows and in menus & pages. **Presets** (Viewarr or Classic), **Skip size**, **Channel change delay**, the **Search button** and extra buttons. See [Remote control](REMOTE.md).

### Menu & navigation

| Section | Settings |
|---|---|
| **Start** | **App opens on**: live TV (**Last channel** plays the channel you watched last, full screen), the TV guide, or one of your libraries. If Android closes Viewarr while it's in the background (the Home button, another app), it opens where you were instead: the channel, the film's page, or the page you were on (within 6 hours). Leaving with Back or Exit starts fresh next time. |
| **Menu** | **Button hints**: what the buttons do on each screen. |
| **What the menu lists** | Which entries the main menu shows (Live now, Sports, Movies, Shows, Music, Recordings, Search and your streaming services). |
| **Poster rows** | **Moving between rows** and **Poster position**. |
| **Notifications** | Everything Viewarr pops up by itself. *(0.29.0 and later.)* |

#### Notifications

Every kind of pop-up is **off** until you turn it on. First switch on **Notifications**, then choose each kind:

- **Score alerts**: Off, **Banner**, **News ticker**, or **Banner and ticker**. Which kinds of alert, and for which teams, are set in **Settings → Sports** (see [Sports](#sports)).
- **Reminders**: a card when a programme or game you set a reminder for is about to start. **Remind me** sets how early: when it starts, or 2, 5, 10 or 15 minutes before.
- **Update available**: a card when a new Viewarr is out. **Settings → Updates → Check for updates** always works.
- **What's new**: a card once after an update. **Settings → About → Changelog** still has it.

## Watching

### Live TV

| Section | Settings |
|---|---|
| **Watching** | **Up / Down while watching** (change channel, or browse in the info bar), **Browsing channels**, **Highlight the corner picture**, **Still watching?** |
| **TV guide** | **TV guide days ahead** (1, 3, 5 or 7), **TV guide refresh**, **EPG offset** (the whole source's guide moved earlier or later), **End of a list**. |
| **Channel groups** | **"All channels" group** (shown or hidden). |
| **Live now groups** and **Live now sports** | Which channel groups Live now looks through, and which sports it shows. |
| **Live now leagues** | **Only what I follow**: Live now shows just your teams' and leagues' games. **Follow a team or league** takes you to Settings → Sports. With the [Viewarr plugin](LIVE-TV.md#viewarr-plugin-optional), the leagues with games listed follow, each On or Off. *(0.29.0 and later.)* |

How the guide and info bar look is in **Appearance**. See [Live TV](LIVE-TV.md).

### Playback

| Section | What it covers |
|---|---|
| **Player** | **Player** (built-in or mpv), **Video view**, **Resume**, **Time on the right** (time left, ends at, or both), **Controls stay up for**, **Seek-bar previews**, **mpv decoding**. |
| **Video** | **Match frame rate** (Off, Films and episodes, Live TV, or Both), **Picture size**, **Deinterlace**. |
| **Audio** | **Language**, **Surround passthrough**, **Channels**, **Night mode**, **Volume boost**, **Lip sync**. |
| **Subtitles** | **Show subtitles** and **Language** (how they look is in Appearance). |
| **Streams** | **Play order**, **Addon streams**, **Cached only**, **Try the next stream**, **Best quality allowed**, **Plex quality**, **Buffer**. |
| **Auto-play match** | Rules (4K, 1080p, HEVC, Atmos, English…) switched on and sorted by importance; **Add your own…**; **Reset the rules**. |
| **Episodes** | **Skip intros**, **Skip credits**, **Play next episode automatically**, **After a film or episode**, **Next episode countdown**. |
| **Trailers** | **Trailer previews**, **Start after**, **Then the big panel**, **Trailer previews play** (in a corner window or in the poster), **Preview sound**. |
| **Music** | **Lyrics from LRCLIB**. |

### Sports

Your teams and leagues: type a team, league or sport, or pick a sport, then a league, then a team. Their games show on the **Sports** page in the menu. See [Live TV](LIVE-TV.md#sports).

**Score alerts** *(0.29.0 and later)*: Off, Banner, News ticker, or Banner and ticker; then each kind on or off (**Goals and scores**, **Kick-off / tip-off**, **Half-time**, **Full time / final**). No team has alerts to start with: hold OK on a team or league in the search results (followed or not) to turn its alerts on. Teams with alerts are listed with 🔔; OK on one turns its alerts off. Alerts also need **Notifications** switched on (see [Notifications](#notifications)).

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
- **Check automatically**: checks when Viewarr starts or comes back on screen (at most every 30 minutes). A card about a new version only shows if **Notifications → Update available** is on.
- **Include test versions**: test versions (pre-releases) get new features first. Viewarr's releases are all test versions for now, so turning this off means no updates yet.

### About

- **Viewarr**: the version, when it was installed and last updated, and where the source code is.
- **Changelog**: what changed in each version, newest first. Versions are on the left; **Right** goes into the notes, **Up / Down** scroll them, **Left** goes back to the versions and **Back** closes it. The What's new card after an update also has a **Full changelog** button.
- **Show the tour**: the tour of how Viewarr works, from the first run.
- **This TV**: the model, Android version, memory, storage, screen and processor.
- **Storage**: what Viewarr keeps on this TV (each source's TV guide, artwork, downloaded updates and so on). **Clear artwork** frees space; posters and logos are fetched again as you browse. Downloaded updates that are installed already are safe to delete.
- Your **Live TV sources**, which is active, and when each guide was last updated.

### Sign out of everything

Signs this TV out of all your accounts and sources. It asks first: **Cancel** (highlighted) or **Sign out**.
