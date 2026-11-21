# Settings

A quick tour of the Settings menu, page by page. Open it from the bottom of the main menu.

A line at the top of Settings describes the highlighted entry in full, and every section says what it covers. Settings with several choices open a drop-down list.

**Appearance**, **Live TV** and **Playback** open on their main choices; the finer settings are under **Customise** on the left, which shows how many of them you've changed. Any setting that isn't at its default is marked **Changed**. Settings most people never need are on their own **Advanced** page. Appearance, Playback and Advanced each have a **Reset to defaults** button at the bottom.

Some entries only show for some people: **Users & profiles** and **Dispatcharr plugins** are for Dispatcharr admins, and an admin can lock accounts, settings or signing out for a profile (see [Profiles](PROFILES.md)).

## Accounts & people

### Accounts & sources

Add, change, check or remove everything you connect:

- **Live TV**: Dispatcharr, an IPTV login (Xtream Codes) or an M3U playlist. Save several sources, name them, and use **Choose groups** to leave out groups you never watch (logins and playlists).
- **TMDB**: posters, descriptions and discovery for Movies & Shows.
- **Trakt**: watchlist, history, recommendations and continue watching. **Sync with Trakt now** and **Copy watched to Plex** are in **Playback → Watch progress**.
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

**🔍 Search settings** sits at the top: type a word (frame rate, wallpaper, subtitles…) and every setting that matches is listed with where it lives, such as *Playback › Episodes*. OK opens that page with the setting highlighted. *(0.28.0 and later.)*

How the pages are split: **Appearance** is how things look, **Live TV** and **Playback** are how things behave, **Menu & navigation** is where things are, **Remote buttons** is which button does what, and **Advanced** holds the player's internals, sizes and guide data. Each setting lives in one place only.

**Recordings** isn't a Settings page: it's in the main menu (see [Live TV](LIVE-TV.md#recordings)).

## Look & controls

### Appearance

The page opens on **Look**: the few big choices. Everything finer is under **Customise** on the left, closed until you open it; it shows how many settings there you've changed. Any setting that isn't at its default is marked **Changed**. **Reset to defaults** at the bottom puts the page back.

**Look**: **Browsing style**, **Background colour** (Viewarr's charcoal, or Classic blue, Ocean blue, Sky blue and others), **Highlight colour** (orange by default, including Bright cyan), **Wallpaper**, **Text size** and **Clock**.

**Text size** (Normal, Small or Large) changes only the words. Pages, posters and the guide keep their layout, and long names are cut short with "…". To shrink the whole picture for a TV that cuts off the edges, use **App scaling** in [Advanced](#advanced) instead.

Under **Customise**:

| Section | What it covers |
|---|---|
| **Text & dates** | **Font**, **Date format** (Tue 10 Nov, Tue, Nov 10, Tue 10/11 or Tue 11/10) and **Time format**. |
| **Wallpaper** | **Wallpaper**: None, **Built-in** (pick its **Design**: seventeen in all, Christmas among them) or **What's selected** (the artwork of what you've highlighted). With the Christmas design, **Falling snow** (on or off): snow behind every page, never over text or posters; it stops while a film or live TV plays full screen. **Movies and Shows background**: What's selected (the highlighted title's artwork, default), your wallpaper, or None (no picture at all). **Wallpaper in the TV guide**: off gives the guide a plain background. **Wallpaper shade**: what darkens the picture (your background colour, or black). |
| **Glass & transparency** | **Glass**, **Glass colour**, **Guide**, **Wallpaper dimming**, **Pop-up glass** and the info bar's **border**. |
| **TV guide** | Channel numbers, logos, **Programme corners**, **Next and Then** (the two lines at the top right of the guide; off hides them), **Episode badge at the top** (the "S12 E4" badge under them), **Catch-up badge at the top**, progress bars, **Channel list width** (Medium, Narrow or Wide), **Channel list progress** (a bar under the programme in the channel list: every channel, the highlighted one only, or off) and its **Highlighted progress colour**, episode numbers, **Timeline**, **Catch-up icons** (programmes and channels, channel names only, the highlighted channel only, or off), **Elapsed shading** (the part of a programme on now that's already gone), **Programme pictures** and **Programme picture shows as** (Background or Banner). |
| **Info bar & controls** | **Info bar style** (Glass, Square glass, Pill, Fade, Full-width bar, Minimal, Faded glass, Feathered glass, Side fade, Outline, Corner card, Floating card, Slim strip or Frosted), **Clock on the info bar**, **Rows under the info bar** (which rows each player shows when you press Down, and their order, with a preview), and the channel number, episode numbers, picture-quality, source (where the live stream comes from) and frame-rate badges. |
| **Menu** | Headings, colour edge and tint, hints, and the TV and LIVE marks. |
| **Info bar buttons** | Which buttons are on the info bar, and their order (see [Live TV](LIVE-TV.md#arranging-the-info-bars-buttons)). |
| **News ticker** | A scrolling headline strip (Sky Sports, BBC News, ESPN-style looks): **With the info bar**, **In the TV guide**, **On Movies, Shows and Sports**, **Speed**, and each feed (built in, such as **Live scores**, or **Add your own feed…** with any RSS / Atom address) Off or On in its own look. |
| **Browsing style** | **Viewarr**, **Cinema** or **Lite**: the look of Movies, Shows and the streaming services' pages in one go. See [Films & shows](FILMS-AND-SHOWS.md#browsing-styles). |
| **Posters** | **Where it's from**, **Progress bars**, **Other posters faded** (a slider, Off to 100%) and **Highlight zoom**. |
| **Title pages** | What a title page shows, and **Age ratings**. |
| **Ratings** | Which ratings show, and their order. |
| **Subtitles** | **Size**, **Colour** and **Background**. |
| **Music** | Now Playing's **Artwork**, **Visualiser**, **Screensaver** and **Lyrics**. |

Sizes (the info bar, preview cards, player controls, top panel, posters, guide rows) and **List scrolling** are in [Advanced](#advanced), as every page is laid out around their normal sizes.

### Home page & menu

Which libraries the menu lists for Movies and Shows (on or off, order, names), and which rows the Movies and Shows pages show, in what order. Each row says where it comes from. **My Library** has its own list of rows. See [Films & shows](FILMS-AND-SHOWS.md#choosing-your-rows).

Also here:

- **Combine rows from different servers**: one Continue watching, and one Recently added per library, instead of a row for each server.
- **Use Just added instead of New**: each streaming service's **Just added** row takes the place of its **New** row on Movies and Shows (the New rows are hidden, not removed). See [Films & shows](FILMS-AND-SHOWS.md#just-added).
- **Network rows on Movies / Shows**: the streaming services' rows from your addons on Movies and Shows too. Off: only on each service's own page.
- **Continue watching from**: one switch for each server, IPTV and Trakt (see [Films & shows](FILMS-AND-SHOWS.md#continue-watching-from)).

### Remote buttons

Every button on your remote, and what it does in the TV guide, on live TV, in films & shows and in menus & pages. **Presets** (Viewarr or Classic), **Skip size**, **Channel change delay**, the **Search button** and extra buttons. See [Remote control](REMOTE.md).

### Menu & navigation

| Section | Settings |
|---|---|
| **Start** | **App opens on**: live TV (**Last channel** plays the channel you watched last, full screen), the TV guide, or one of your libraries. If Android closes Viewarr while it's in the background (the Home button, another app), it opens where you were instead: the channel, the film's page, or the page you were on (within 6 hours). Leaving with Back or Exit starts fresh next time. |
| **Menu** | **Button hints**: what the buttons do on each screen. |
| **What the menu lists** | Which entries the main menu shows (Sports, Movies, Shows, Music, Recordings, Search and your streaming services), and **Hidden groups row** (a "Hidden groups" row at the end of the channel groups; off, hold OK on any group → **Show hidden groups**). |
| **Poster rows** | **Moving between rows** and **Poster position**. |
| **Notifications** | Everything Viewarr pops up by itself. *(0.29.0 and later.)* |

#### Notifications

Every kind of pop-up is **off** until you turn it on. First switch on **Notifications**, then choose each kind:

- **Score alerts**: Off, **Banner**, **News ticker**, or **Banner and ticker**. Which kinds of alert, and for which teams, are set in **Settings → Sports** (see [Sports](#sports)).
- **Reminders**: a card when a programme or game you set a reminder for is about to start. **Remind me** sets how early: when it starts, or 2, 5, 10 or 15 minutes before.
- **Update available**: a card when a new Viewarr is out. **Settings → Updates → Check for updates** always works.
- **What's new**: a card once after an update. **Settings → About → Changelog** still has it.
- **Reports from the house**: a quiet card when the Viewarr plugin changes a channel from someone's report (admins can undo it there), or when a wrong-guide report needs someone to pick the right guide.

## Watching

### Live TV

The page opens on the main choices: **Up / Down while watching**, **Start with the best source**, the **"All channels" group** and **Reports**. Everything finer is under **Customise** on the left.

| Setting or section | What it covers |
|---|---|
| **Up / Down while watching** | Change channel, or browse in the info bar (see [Live TV](LIVE-TV.md#browsing-channels-in-the-info-bar)). |
| **Start with the best source** | On to start with: a Dispatcharr channel starts on its best-ranked stream (quality, then whether it has failed lately, then Dispatcharr's order), and a stream that fails is remembered and moved down. Off: Dispatcharr's own first stream. See [Live TV](LIVE-TV.md#source-switching-and-stream-info). |
| **"All channels" group** | Shown or hidden. |
| **Reports** | Problems reported from the TVs (hold OK on a channel → **Report a problem…**): **Needs your pick** (channels whose right guide the plugin couldn't find: OK chooses one), **Changes made from reports** (what the Viewarr plugin changed, newest first; admins: OK → **Undo**) and **Your reports**. Without the plugin, reports are kept on the TV. See [Live TV](LIVE-TV.md#report-a-problem). |
| **Watching** *(Customise)* | **Browsing channels**, **Highlight the corner picture**, **Still watching?** |
| **TV guide** *(Customise)* | **EPG offset** (the whole source's guide moved earlier or later) and **End of a list**. |
| **Channel groups** *(Customise)* | **Tidy group names** (leaves out the marks providers add, so "UK\| SKY SPORTS FHD" shows as "SKY SPORTS"), and **Group name clean-up**: which marks are left out. |
| **Home screen rows** *(Customise)* | Viewarr's rows on your TV's home screen: channel groups and any Movies or Shows row, **Show them on the Android TV home screen** and **Add every Just added row**. See [Live TV](LIVE-TV.md#viewarrs-rows-on-your-tvs-home-screen). |
| **Live now groups** *(Customise)* | Which channel groups Live now looks through. |
| **Live now** *(Customise)* | Your list of teams, leagues and sports for Live now. Empty: it shows everything live. **Add a team, league or sport** searches (or press Down for the sport buttons) and OK adds one; Live now then shows only what's in the list. OK on one removes it. |

**TV guide days ahead**, **TV guide refresh** and **Event channels: only with a listing** are in [Advanced](#advanced). How the guide and info bar look is in **Appearance**. See [Live TV](LIVE-TV.md).

### Playback

The page opens on **Watching**: **Match frame rate**, audio **Language**, **Show subtitles**, **Addon streams**, **Play next episode automatically**, **Skip intros** and **Trailer previews**. Everything finer is under **Customise** on the left. **Reset to defaults** at the bottom puts the page back.

| Section | What it covers |
|---|---|
| **Player** | **Resume**, **Time on the right** (time left, ends at, or both), **Controls stay up for**, **Seek-bar previews**. |
| **Watch progress** | Nothing to choose: Viewarr keeps your watching in step everywhere it can. At the top, what's connected and what it does: **Trakt** (gets every play and every Mark as watched; its history gives ticks and Continue watching), each of **Plex / Jellyfin / Emby** (told where you are and when you finish, as its own apps would) and **Dispatcharr** (IPTV progress shared between your TVs). Then two switches, both on to start: **Send plays to Trakt** (off: Viewarr only reads Trakt) and **Share IPTV progress between TVs**. Also **Sync with Trakt now**, **Copy watched to Plex** (the last 30 days, to your own Plex server), **Sync IPTV progress with my Emby / Jellyfin / Plex** (for people without Trakt: off by itself while Trakt is connected, so Trakt doesn't get the same watch twice; with the Viewarr plugin the server does it for the house, without it each TV does it while Viewarr runs) and **Sync now**. **Counts as watched at** 80%. |
| **Audio** | **Night mode** and **Volume boost**. |
| **Subtitles** | Subtitle **Language** (how they look is in Appearance). |
| **Streams** | **Play order**, **Cached only**, **Try the next stream**. |
| **Auto-play match** | Rules (4K, 1080p, HEVC, Atmos, English…) switched on and sorted by importance; **Add your own…**; **Reset the rules**. |
| **Episodes** | **Skip credits**, **After a film or episode**, **Back from a film**, **Next episode countdown**. |
| **Trailers** | **Trailer previews play** (in the window at the top or in the poster), **Start after** (1 to 5 seconds), **Then the big panel** (1 to 5 seconds, or Never), **Preview sound**. |
| **Music** | **Lyrics from LRCLIB**. |

The player itself (built-in or mpv), how the picture is fitted, surround sound, quality limits and the buffer are in [Advanced](#advanced).

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

Two ways to keep a copy of Viewarr's setup on your Dispatcharr server (Dispatcharr sign-ins only):

- **Saved on Dispatcharr**: automatic, keeps recent versions.
- **Plugin backups**: copies you make yourself, kept until you delete them.

The options:

- **Save settings to Dispatcharr**: on or off. Saves your setup a few seconds after each change, and keeps the last few versions so you can go back: the 5 newest, plus the newest from each of the last 7 days (12 at most). Small changes add a version at most every 30 minutes.
- **Include sign-ins**: whether your other sign-ins are saved too (encrypted).
- **Save a copy now**: saves this TV's setup straight away and keeps it as a version.
- **Restore from Dispatcharr**: pick **Latest** or an earlier version. Before it's restored, your setup now is kept as a version (**Before restore on** this TV), so you can undo it the same way. Saving carries on afterwards.
- **Backups on the Viewarr plugin** *(admins, with the [Viewarr plugin](https://github.com/petebutty/viewarr-plugin) installed)*: save a backup with a name, restore one (your setup now is saved as **Before restore** first), or hold OK on one to delete it. The plugin keeps the newest 30.

See [Profiles](PROFILES.md#your-setup-saved-on-dispatcharr) and [Privacy](PRIVACY.md).

### Home Assistant

Optional. Your Home Assistant's **Address** and an **Access token**, **Test connection**, and switches for **Send what's playing** and **Pop-ups from Home Assistant**. **How to use it** explains the events. The token stays on this TV. See [Home Assistant](HOME-ASSISTANT.md).

### Troubleshooting

**Show playback info** (format, decoder and dropped frames while playing) and **Low-memory mode** for boxes with 1–2 GB. See [Troubleshooting](TROUBLESHOOTING.md).

**Settings codes** copy your settings from one TV to another:

- **Share my settings**: gives you a code like `VS-ab12cd`, valid for 3 days. It holds your look (Appearance), Playback and remote settings. Sign-ins, Live TV sources, home rows, libraries, favourites, channel numbers and followed teams aren't in it, nor this box's own settings (surround sound, decoding, frame rate, picture size, buffer, the remote's buttons).
- **Use someone's settings**: type a code, then **Replace**. Your sign-ins, sources, home rows and libraries stay as they are, and so do this box's own settings.
- **Undo the last settings code**: shows after you've used a code, and puts back the settings you had before.

A report sent with **Send a report** lists every setting you've changed from its default, and adds a settings code, so the developer can try your exact setup. **Send a note** sends a message without a log.

### Advanced

Settings most people never need, under **This TV** after Troubleshooting. Each section starts with a warning: leave them alone unless you've been asked to change one or know why. **Reset to defaults** at the bottom puts everything on this page back (and only this page). Search settings still finds them and opens them here.

| Section | What's there |
|---|---|
| **Player** | Player (built-in or mpv), Video view, mpv decoding. |
| **Video & sound** | Sound after a frame-rate switch, Picture size (how a film's shape fills the screen), Deinterlace, Surround passthrough, Sound channels, Audio sync. |
| **Streams** | Best quality allowed, Plex quality, Buffer. |
| **Screen & sizes** | **App scaling** (100%, 97.5%, 95%, 92.5% or 90%: shrinks the whole picture for a TV that cuts off the edges of the screen; every page keeps exactly the same layout, and video still fills the screen), List scrolling (Instant or Smooth), Channel rows (10, 12, 9 or 8 in the guide), Info bar size, width and corners, Preview cards, Player controls size, Top panel, Row posters, Library posters. |
| **TV guide data** | TV guide days ahead, TV guide refresh, Event channels: only with a listing. |

Every page is laid out on one canvas, the same on every TV whatever its resolution, so a page that fits on one box fits on all of them. **App scaling** shrinks that whole canvas evenly; it never changes the layout. To change only the size of the words, use **Appearance → Text size**.

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
