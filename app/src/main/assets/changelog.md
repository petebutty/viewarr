# Viewarr changelog (built by tools/changelog.py from the GitHub releases; don't edit by hand)

## 0.29.9.4 · 25 Nov 2026

Live TV follows Dispatcharr's stream order again when Start with the best source is off, plus two fixes.

### Fixes
- **Start with the best source off:** a channel always plays as Dispatcharr orders its streams. Viewarr kept asking for a stream it had switched to before, so Dispatcharr couldn't change stream for that channel and showed the stream rather than the channel as playing (most noticed on channels with regional streams). Nothing to clear: it works as soon as you update.
- **Trending's first row:** going into it after it had moved along by itself, Left went through posters you couldn't see until the start of the row. The highlight now lands on the poster that was showing, and any row jumps to show a poster that would be off the edge.
- **"Out tomorrow"** no longer shows for an episode that's already on one of your servers or IPTV: the button plays it.

## 0.29.9.3 · 24 Nov 2026

Trending and What's new pages, your own Play order, following shows for new episodes, icons in the menu, and a long list of fixes.

### New pages and menu
- **Trending** and **What's new** in the menu: films and shows together, there with or without live TV. Trending's first row moves along by itself until you go into the rows.
- **Movies and Shows keep their 8 main rows** (Continue watching, Recently added, your watchlist, Recommended…). Settings → Home page & menu → **Main rows only on Movies / Shows** to see every row again.
- **Icons in the main menu** for every entry; streaming services show their own logo.

### Playing
- **Play order is yours:** Settings → Playback → **Play order** lists six places: Plex in your house, Jellyfin / Emby in your house, Plex outside it, Jellyfin / Emby outside it, IPTV and addons. Put them in any order, switch any off. Films, episodes and the Play from list all follow it.
- **Internet copies:** the biggest file Viewarr picks from a server outside your house. Automatic goes by this box's own speed.
- **Dolby Vision** copies say what a TV without it will see ("Dolby Vision + HDR10"), and the TV's own answer is checked where the box can ask it.
- **Picture and sound badges on the info bar:** Dolby Vision, HDR10, HLG, Dolby Atmos, Dolby Digital+, TrueHD, DTS and more, read from the file playing.
- **Picture-in-picture:** press Home while something plays and it carries on in a small window over the Android home screen.

### Shows
- **Follow a show:** the bell on its page. Shows you're watching are followed by themselves (switch that off if you like). Settings → Menu & navigation → Notifications → **New episodes**: a card when a new episode of one is in your library, and the episode on the home screen's Play next row.
- **Drop show** from Upcoming episodes too (hold OK on it): out of Upcoming and Continue watching, and no longer followed.
- **Right on a season's last episode** goes on to the next season.

### Films
- **Release dates in full** for films: "18 Dec 2026", or "Coming 18 Dec 2026" when it isn't out yet.

### Your servers
- **Jellyfin-style servers that list folders** (Titles, Genres, Years…) open them like collections.
- A Jellyfin library called Music that isn't marked as music is picked up for Music.

### Downloads (optional)
- Settings → Playback → Streams → **Tell the server about internet films**: off to start. On, the box tells your Dispatcharr server once a minute while a film plays from outside your house, for a server script that slows downloads meanwhile. It says only that one is playing and the box's speed.

### Viewarr plugin
- **Just added works behind a VPN:** JustWatch refuses servers behind a VPN, so a TV now asks it from your own connection, and an admin's TV hands the lists to the plugin for everyone. Needs the Viewarr plugin 0.6.9. Without the plugin each TV asks for itself, as before.

### Fixes
- **"Not in your library"** for titles from addon rows ranked "#5 …" or with "(2026)" after the name, and for titles with only an IMDb id. Same-name shows are matched more carefully ("Doctor Who" 2005 isn't the 1963 series).
- **Resolution badge:** a film cropped wide (1920×960) now says 1080p, not 720p.
- **Play** tries your other servers and IPTV when the first copy won't open, a file this box can't play goes last, and quick play waits for the title's details before trying addons.
- **Picture edges** on Trending's banner and the wallpaper fade out instead of ending in a hard line.
- **Trending films / Trending shows** rows show again.
- **Main rows only** takes effect straight away.
- **Menu:** Left from a library opened from My Library goes back to your libraries; Left on My Library opens its list; Left on Trending, What's new, Sports or a service no longer jumps into another list.
- **Left on the first episode or season** opens the menu, and episode 1 can be reached when it's scrolled off the left.
- **Episodes** pressed before every server has answered no longer skip your other servers.
- **Play from:** the ★ marks the copy Play will actually choose.
- **Appearance → Reset** no longer switches Play order places back on.
- **Picture-in-picture** no longer starts when Viewarr opens one of Android's own screens.

## 0.29.9.2 · 23 Nov 2026

What the Viewarr plugin did last, sports found in Schedules Direct guides, and what's coming for the plugin.

### Viewarr plugin (admins)
- **Settings → Plugins → Viewarr → What it did last:** one line for each plugin job, with when it last ran and what it did (for example "Best stream first · 15:06 · 2 channels reordered"). Anything that failed says **Needs a look**. Press OK on a line to read it again. Needs the Viewarr plugin 0.6.8.

### Fixes
- **Sports:** games a guide lists as "Live: NHL Hockey" with the teams underneath (the episode title, as Schedules Direct does) are now found on the right channel.
- Boxes where you type a value (plugin settings, reports) are now plain text boxes with **Done** on the keyboard, not search boxes.

### Coming next: Viewarr with and without the plugin
- **Viewarr will always work fully without the Viewarr plugin.**
- The jobs the plugin does better (sport recordings with every TV off, score alerts, Just added, IPTV progress sync) will be **off by default on servers without the plugin**. Each can be switched on to run on the TV itself, with a note that it works better with the plugin.
- **If you already use them without the plugin, your settings stay as they are.**
- Why: without the plugin every TV does the same work on its own. Done once on the server it's quicker, above all on smaller boxes, and keeps going with the TVs off.
- Later: install and set up the plugin from inside Viewarr, once it's in Dispatcharr's official plugin store.

## 0.29.9.1 · 23 Nov 2026

Every remote press counts again, pause and rewind buttons on the info bar, and TV guide sources from the app.

### Fixes
- **Remote presses were being ignored:** a filter added for one stuttering remote also threw away quick real presses on other remotes (Fire TV, Shield…), so the guide could feel like it wasn't responding. It's gone: every press counts.
- Addons whose link carries a personal key after the address (`manifest.json?token=…`) now keep it on every request, so they load and play.

### Live TV
- **Rewind, Pause / Play and Fast forward** on the live info bar (press OK while watching) whenever the channel can go back: catch-up, or Live timeshift with the Viewarr plugin. Fast forward and Go live appear once you're behind live.

### TV guide sources (admins)
- **Settings → Live TV → TV guide sources:** every guide source on your Dispatcharr with its status (last updated, updating, or the problem), an on/off switch, Update now, rename, change address, how often it updates, delete, and **Add a guide source** (an XMLTV link or Schedules Direct account). **Update all now** too.

## 0.29.9 · 22 Nov 2026

Live timeshift: pause and rewind any live channel, and every Viewarr plugin feature can now be switched off from the app.

### Live timeshift (needs the Viewarr plugin 0.6.3 or later)
- **Pause and rewind every live channel**, with or without catch-up: your server keeps a rolling copy of the channel you're watching (2 hours to start with), taken from Dispatcharr's own stream, so no extra connection to your provider.
- Pause and Play carry on from where you paused; Left steps back instantly; Right or Go live returns to live. Further back than the copy, catch-up channels use the provider's catch-up as before.
- The copy is deleted as soon as you change channel. Two TVs on one channel share it.
- **Settings → Live TV → Customise → Live timeshift** to switch it off for yourself.
- In Settings → Plugins → Viewarr (admins): the buffer length, how many channels at once, where the copies are kept (Dispatcharr's data folder or your TV recordings folder), and whether it covers all channels or only those without catch-up (plugin 0.6.5).
- Tip: Settings → Remote buttons → Live TV: Left = Skip back, Right = Skip forward, to rewind with the arrows.

### The Viewarr plugin from the app
- **Settings → Plugins → Viewarr** now has an on/off switch for every plugin feature: Live now, followed games, sport recordings, score alerts, event channels, Just added, IPTV progress sync, best stream first, reports, stream measuring, Live timeshift and backups. Text settings (measuring times, groups) can be edited there too.
- A feature switched off in the plugin is off for the house: Viewarr hides it rather than doing it on the TV.

## 0.29.8 · 22 Nov 2026

A faster, lighter Viewarr on smaller boxes, a full Home Assistant remote, and fixes.

### Faster and lighter
- **Scrolling names** (a long programme title in the guide) keep scrolling but draw far less often: on a 2 GB box the still guide went from about half the processor to about 5%.
- **Movies and Shows:** each row loads the next posters ahead, every poster is decoded once at its card size, and the big background picture no longer pushes posters out of memory, so scrolling, especially on a first run, stutters much less.
- **With the Viewarr plugin:** Live now is only worked out again when the plugin's data actually changes (it was redone every few seconds).
- The guide redraws only the rows a move touches.

### Home Assistant
- **Remote buttons:** Home Assistant can now press any remote button in Viewarr (arrows, OK, Back, Menu, Info, colours, numbers), so a dashboard can be a full remote for each TV. See the Home Assistant guide.

### Also new
- **Settings codes** (Settings → Troubleshooting → Share my settings) now include your **remote buttons**, so someone can have exactly your remote setup.
- Finishing an episode puts the **next episode first** in Continue watching straight away.

### Fixes
- The backup player (mpv) could close Viewarr when a channel or film started before the picture was ready.
- The "What's new" card showed an old version's notes.

## 0.29.7 · 21 Nov 2026

Home Assistant, voice channel changes, a new actor page and smoother details.

### Home Assistant (optional)
**Settings → This TV → Home Assistant**: your Home Assistant's address and a long-lived access token. Nothing happens until it's set up. See the Home Assistant guide.
- **What's playing:** Viewarr tells Home Assistant when a film, episode, recording, live TV or music starts, pauses or stops, so lights can follow what you watch (DreamView on for a film in the evening, back to normal on pause).
- **Pop-ups on the TV:** Home Assistant can show a message, a picture or a live camera in the corner, over whatever's playing.
- **Commands:** change channel, pause, play, stop, open the guide and more from Home Assistant, so from your phone, a dashboard button or Alexa / Google ("Alexa, turn on BBC One").
- The token stays on the TV: it isn't in settings codes, backups or reports.

### Say a channel
- With the remote's mic, say a channel's name or number ("BBC Two", "Channel 104", "put on Sky Atlantic") and it plays straight away. Anything else is searched as before.
- Search and voice understand older names too: "Sky Movies" finds Sky Cinema, "BBC 1" BBC One, "BT Sport" TNT Sports.
- Search is see-through over full-screen live TV, so the programme carries on behind it.

### Actor pages
- A new page for each person: portrait, born, birthplace, credits and biography (Read more), a big picture, and tabs for **Overview** (Known for, Latest), **Filmography** (every title with year and rating, filter Films / Shows, sort by date or rating) and **Media** (their photos, full screen).

### Live now in your order
- **Settings → Live TV → Live now:** OK on a team, league or sport to **Move up**, **Move down** or **Move to the top**. Live now shows games in that order.

### Details and recordings
- The top panel's title and details slide in once they're ready, and its shade is full size from the start.
- Over the artwork on the right, the description gets two shorter lines, ending with "…" instead of being cut off.
- Recordings in Continue watching show the episode's name, when it was recorded and its description; so does the player's info panel while one plays.

### Fixes
- The keyboard's own Done key did nothing in text boxes (Home Assistant address, report notes): it saves now.

## 0.29.6 · 21 Nov 2026

A small update for the Christmas wallpaper.

- **Snow on every page:** with the Christmas wallpaper, snow now falls behind the guide, Settings, Movies, Shows and the menus, not just the TV guide. It's part of the wallpaper, so it's always behind text and posters, never over them.
- **Stops during full-screen live TV** as well as films, so it never animates behind a programme for nothing.

## 0.29.5 · 21 Nov 2026

Everything now works without the Viewarr plugin, plus TMDB-organised recordings, recordings on Trakt, and a Christmas wallpaper.

### Works without the Viewarr plugin
With the plugin, your Dispatcharr server still does these once for the whole house. Without it, each TV now does them itself while Viewarr is running:
- **Just added** rows: the TV checks JustWatch once a day.
- **Live score alerts** and the **Live scores** news ticker: the TV checks ESPN while your games are on.
- **Event channels: only with a listing**: the TV spots event groups by their names and hides channels with nothing on.
- **Record all <team>** and **Record when it's in the guide**: the TV schedules the recording once the game's channel is in the guide.
- **Sync IPTV progress with my Emby / Jellyfin / Plex**: the TV keeps them in step every 15 minutes and soon after you stop.

### Recordings
- **Organised with TMDB:** Recorded is split into Shows (each show's episodes together), Films, and Sport & other, with posters and episode names.
- **Recorded is the first tab**, and each tab has its own date order: press OK on the tab you're on to turn it round.
- **Series recordings** show readable names ("Celtic games") instead of search patterns.
- **On Trakt:** recordings matched on TMDB are sent to Trakt like any film or episode, so they appear in your Trakt history and Continue watching.
- **As a source:** a film's recording is listed in Play from.
- Recordings in Continue watching use the show's or film's poster.

### Christmas 🎄
- **Christmas wallpaper** (Settings → Appearance → Wallpaper → Built-in → Christmas): a winter night with fairy lights and gently falling snow behind the pages. Falling snow can be switched off, and it stops while a film plays full screen.

### Fixes
- The order button on the Recordings page did nothing (the list stayed where it was).

## 0.29.4 · 21 Nov 2026

A recordings update, smoother library pages, and Continue watching that keeps up.

### Recordings
- **Three lists:** Scheduled (with anything recording now at the top), Recorded and Series recordings, each sorted by date either way. Viewarr remembers which list you were on and each list's order.
- **Watched progress:** a bar on recordings you've started, a tick once watched, and playing carries on where you stopped.
- **In Continue watching:** recordings you've started, and new ones from the last week you haven't played yet, appear in Continue watching (shows) with the channel's logo. Pressing one plays it straight away.

### Continue watching
- A film or episode you stop partway moves to the front of its Continue watching row straight away, on My Library, Movies and Shows.

### Smoother pages
- **Library grids** (My Library → Left → a library) move a row the moment you press, then glide into place, and sit higher under the live TV window.
- **My Library** with live TV in the corner glides between rows as Movies and Shows do.
- With a film in the corner, the top panel's text on the right sits on a shade, so it's easy to read over bright artwork.

### Streaming services and the home screen
- Each service's page (Netflix, Prime Video…) opens with **Just added** first (needs the Viewarr plugin 0.6.2).
- **Settings → Live TV → Home screen rows → Show them on the Android TV home screen:** for Google's Android TV home, which shows an app's rows only once each is approved; this asks for every row in order.

### Fixes
- The Recordings page's tabs couldn't all be reached with the remote.

## 0.29.3 · 20 Nov 2026

A big tidy-up of settings and scaling, Viewarr's rows on your TV's home screen, and "Just added" rows showing what really arrived on each streaming service.

### Pages that always fit
- **Every page is laid out the same on every TV**, whatever its resolution, so a page that fits on one box fits on all of them. Posters are eight across on every TV (1080p boxes used to get smaller ones).
- **App scaling** (Settings → Advanced): shrinks the whole picture a little for a TV that cuts off the edges of the screen. Pages keep exactly the same layout, and video still fills the screen.
- **Text size** (Normal, Small, Large) now changes only the words, not the whole app. Long names are cut with "…" instead of pushing things off the page.

### Simpler settings
- **Appearance, Playback and Live TV open on the main choices**; everything finer is under **Customise**, which shows how many settings there you've changed.
- **Changed** marks any setting that isn't at its default.
- **Settings → Advanced** gathers the settings most people never need (player internals, sizes, guide data), behind a warning, with its own Reset.

### On your TV's home screen
- **Settings → Live TV → Customise → Home screen rows**: put Viewarr's rows on your launcher's home screen (Projectivy, Android TV and others). Channel groups, with each card showing what's on now and playing a preview where the launcher supports it, and any row from Movies and Shows.
- Rows have their service's logo, film and show cards are wide with the title's artwork and logo, and everything stays fresh while Viewarr is closed.

### Just added
- **"Netflix · Just added"** and the same for 15 UK services: what actually arrived on each service in the last fortnight, older films newly added and new seasons included. Needs the Viewarr plugin 0.6.2.
- **Settings → Home page & menu → Use Just added instead of New** swaps each service's New row for its Just added row.

### Also new
- **Backups:** saving to Dispatcharr keeps earlier versions, and Restore lets you pick one (each restore can be undone). Plugin backups are in Backup & sync too.
- **Remote mic button:** with Viewarr chosen for voice search, the mic opens Viewarr's search, listening.
- **Settings codes** copy look and playback settings between TVs (Settings → Troubleshooting), and reports include all settings.
- Narrower menus, guide channel column and live TV programme list; Channel rows option (10, 12, 9 or 8); poster fade up to 100%.
- Episode cards and the rows under the player's controls slide like the poster rows; the film player keeps the playing episode in the middle.

### Fixes
- Wide films (like 1920 × 800) were labelled 720p; Plex, Jellyfin and Emby's own resolution is used now.
- Copy watched to Plex only copies the last 30 days to your own server (it marked old films on every server at once, so Plex asked for reviews of them).
- A trailer kept playing while you scrolled past its poster.
- Fast scrolling could leave a row's left side empty.
- "What's selected" wallpaper in the TV guide with Programme pictures off.
- Programme title logos in the guide are gone (they cost thousands of lookups a day).

## 0.29.2 · 19 Nov 2026

A small update that makes Viewarr much lighter on memory and quicker to keep its guide up to date.

### Uses far less memory
- **Graphics memory down from over 200 MB to under 40 MB** on a 4K box. Some soft fades and edges made Android keep hundreds of hidden drawing buffers; they're drawn a lighter way now. Boxes with 2 GB, like the Shield, have much more room to spare, so Viewarr is less likely to be closed in the background.
- The main menu's soft fade at its top and bottom edges is gone (entries now end cleanly at the edge).

### Faster guide updates
- **Only what's new is fetched.** When nothing has changed on the server since the last update, Viewarr fetches just the new hours at the end of the guide (about a second) instead of the whole guide again (about 50 seconds). A full update still happens when the server's guide or your channels change, and at least once a day.

### TV guide
- Rows are a little thinner, so ten whole channels fit on screen.

## 0.29.1 · 19 Nov 2026

A smaller update: smoother browsing, live TV that picks the best stream, and reports from the TV.

### Smoother Movies and Shows
- **Much smoother scrolling**, above all on 4K boxes: the page draws far less on every frame, and works out rows and channels in the background.
- **Works like the Android TV home screen**: the highlighted poster stays in the first place and the row slides under it; the highlight goes with the poster as it arrives and grows into place. Up and Down bring the next row to the top.
- **Title and artwork wait until you stop** on a poster, so moving along a row stays quick.
- **Eight posters across** a row and two whole rows under the top panel; rows start nearer the screen's edge, so no half poster peeks in.
- **A quieter background**: the title's artwork fills just the top right of the page and fades into the background.
- **Settings → Appearance → Wallpaper → Movies and Shows background**: What's selected, your wallpaper, or None (no picture at all). **Wallpaper in the TV guide** can be switched off for a plain guide.
- The corner trailer window stays empty until the trailer plays (no still first).
- My Library pages fit under the live TV or mini-player window.

### Live TV
- **Start with the best source** (Settings → Live TV, on to start with): each channel starts on its best-quality stream, and a stream that fails is remembered and moved down.
- **Report a problem**: hold OK on a channel → Report a problem (wrong guide, guide times, no picture, wrong logo or name). With the Viewarr plugin on Dispatcharr the fix is made for the whole house, with Undo; **Choose the right guide** searches every guide source. Channels that won't play are reported by themselves. **Settings → Live TV → Reports** shows what was changed.
- **Live now**: Settings → Live TV → Live now is now a list you build: search for any team, league or sport to add it, OK on one to remove it. Empty, Live now shows everything live.
- **Event channels: only with a listing** (Settings → Live TV, off to start with): event slots with nothing in the guide in the next 24 hours are hidden, and their group goes too until a game is listed.
- Channels Dispatcharr hides (an event manager's empty slots) are hidden in Viewarr too.

### TV guide
- **Settings → Appearance → TV guide**: **Next and Then**, **Episode badge at the top** and **Catch-up badge at the top** can each be switched off. The Catch-up badge now sits on the right.
- Hold OK on a channel with no guide opens its menu (favourites, guide offset, Report a problem) instead of toggling the favourite.

### Also
- Removed: the "Your films and shows" wallpaper, and the automatic pause and play after a frame-rate switch.
- Fixes for crashes found while testing.

## 0.29.0 · 18 Nov 2026

### Browsing styles for Movies and Shows
- **Settings → Appearance → Browsing style**: three looks for the film and show pages.
  - **Viewarr**: two rows to a page, with a gap after every second row, so the next row's title never peeks in.
  - **Cinema**: one big row of posters along the bottom of the screen, with a large trailer panel above it, the title's TMDB logo and its synopsis. Up and Down turn a whole page, quickly, and the new row lands where the old one was.
  - **Lite**: a simple, fast strip, best for weaker boxes.
- **Trailers in the poster**: rest on a poster and its trailer starts inside it, then grows into the big panel (Settings: Start after, Then the big panel). OK on a playing trailer opens the big panel with sound; OK in the panel opens the title page.
- **Full-screen trailer**: ⏯ Play/Pause on the remote while the top window's trailer plays.
- **Other posters faded** (a slider) and **Highlight zoom**: the poster you're on stands out.
- **Top panel**: the title's logo and description sit on the left, and the trailer window on the right shows a still until the trailer plays. With a live channel in the top-left window, the details sit on the right.
- **Continue watching from**: choose which libraries (Plex, Jellyfin, Emby, IPTV…) fill Continue watching, one switch each.
- **Mark watched** takes the title out of Continue watching straight away.
- Smaller buttons on the title page, so the posters under them fit.

### Music, rebuilt
- **A new Music home** made from what you play:
  - **Your Mix** and three **daily mixes**, built fresh each day;
  - **Because you played…**: artists like the ones you play, and their top songs;
  - **Hidden in your library**: artists you own and never play;
  - Recently played, newest, most played, favourites, genres (as tiles), decades and your playlists.
- **Endless radio**: start a radio from a song or artist and it keeps going with songs like the last one, never repeating in a session.
- Everything comes from your own music server. Navidrome gives the most; Jellyfin and Plex give fewer suggestions.

### Notifications: off until you choose
- **Settings → Menu & navigation → Notifications**: every kind of pop-up is **off** until you turn it on (score alerts, reminders, updates, What's new).
- If you want to hear about new versions, turn on **Update available** there. Settings → About → Check for updates always works.
- **Score alerts per team** (Settings → Sports → Score alerts): choose the teams you want alerts for, whether you follow them or not. Kick-offs, goals, half-time and results, with the right words for each sport (Tip-off, Puck drop, TOUCHDOWN). No alert for every basket in basketball.

### Sports and Live now
- **Only what I follow**: Live now shows just your teams and leagues.
- **Live now leagues** (with the Viewarr plugin): choose which leagues show, with **Follow** buttons beside them.
- **NBA and other US games** now show in Live now.
- **Remind me** shows 🔔 once a reminder is set, and you can choose how long before the start it reminds you.
- **With the Viewarr plugin** on your Dispatcharr (viewarr-plugin), Live now, the games' channels, sport recordings and score alerts are worked out once for the whole house, even with every TV off. Without it, Viewarr does it all itself, as before.

### TV guide
- **A clearer info panel**: the channel, times, what's next and badges.
- **Cleaner cells**: tiny programmes merged, gaps between cells, rounded rows, and **Elapsed shading** for the part of a programme that's gone.
- **Programme picture shows as**: Background or Banner.
- **Catch-up icons**: choose all channels or the highlighted channel only.
- The "+2h" next to channel names is gone, and the **Hidden groups** row in the menu is optional.
- No more shade over the guide when you open the menu.
- Left at "now" opens the menu.

### Look and feel
- **A new start-up animation**: the play button presses in with a glow.
- **Wallpaper shade**: choose Background colour or Black, so the theme and the wallpaper don't fight.
- **Settings explain themselves**: the highlighted option shows its full description, and choices are drop-down menus.
- Remote buttons no longer list the old Live TV shortcuts.

### Faster
- **Quicker start-up**: the app ships with a start-up profile, so the first screens no longer stutter.
- **Rows, ratings, guide pictures, title logos and your Trakt history are kept on the box**, so pages open straight away and refresh quietly behind.
- **The menu doesn't load every page you fly past**: it waits until you stop.
- Background jobs start one after another instead of all at once.

### Fixes
- Many remote-control fixes across the app: Back goes one step at a time, focus lands where you expect, and no more dead ends.
- Series recordings mark their next episode in the guide.
- Continue watching cards from your servers get their posters.
- Trailers play again in every style.

## 0.28.9 · 17 Nov 2026

### A new look
- **New logo**: three play buttons, one behind the other, in emerald to cyan. On the start-up screen (animated), in the menu, the app icon and the Android TV banner.
- **The menu takes the page's own background**: the wallpaper (or the film's picture on Movies and Shows) runs on under the menu, with no join at its edge.
- **The top of the menu stays put**: the logo, ⚙ Settings, the mic and Search stay where they are when you go into the channel groups (or any list), and the list starts level with the main menu. Up from the list lands on ⚙ Settings; Left goes along to the mic and Settings.
- **⚙ Settings is a round button** like the mic beside it.
- **Shared pop-ups, pills and cards** across the app: the same options pop-up everywhere (with Cancel and a confirm step for anything you can't undo), the same way to move things (hold OK → Move), and the same search bar.

### My Library
- **Highlight My Library in the menu** and your own servers' rows show behind it (Plex, Jellyfin, Emby, IPTV): Continue watching, Recently added, each library's Recently added and your collections, films and shows together.
- **My Library home** at the top of its list goes into them.
- **Settings → Home page & menu → My Library**: choose and order its rows.

### Sports
- **Many more games get their channel**, for any sport: Viewarr reads the Sky guide's listings itself (UK and Ireland), knows each team's own channel (club TV) and event channels, and matches team names the way ESPN writes them. Each game shows how sure it is and why.
- **The Sports page is a fixture guide**: times down the side, compact rows, your followed teams and leagues as pills along the top (hold OK on a pill: Move or Stop following).
- **A richer game page**, and **Remind me** works from it.
- **Watch** on a game goes to the channel (it used to play behind the Sports page).

### Live TV
- **📡 Live now is a group in the TV guide**: what's live in sport right now, from the guide and your Sports schedule. Only real guide entries marked live count: placeholders ("No Fixtures Today"), stream names and dead channels are left out, and event feeds show only around their event's time. Move or hide the group like any other.
- **Settings → Live TV → Live now sports**: choose which sports Live now shows.
- **EPG offset** (hold OK on a channel → EPG offset): a slider, −10 to +10 hours. The listings slide straight away and it's saved, with no guide download. The whole-guide offset in Settings → Live TV works the same way.
- **Timeline** in the guide's hold-OK menu: 1½, 2, 2½, 3, 4 or 6 hours across the screen.
- **Tidy group names** (Settings → Live TV): "UK| SKY SPORTS FHD" shows as "SKY SPORTS"; choose which marks go.
- **Up / Down while watching** (browse mode): the first press shows the channel you're on, the next one browses.

### Info bar
- **Choose its buttons in Settings → Appearance → Info bar buttons**, with ticks. The main player buttons are locked on (they can be moved, not removed), shown first with a line after them.
- The edit page stays up until you've finished.

### Remote buttons
- The second button preset is now called **Classic** (the layout most IPTV apps use). If you had it chosen, you still do.

### Fixes
- Trailers no longer make the TV change frame rate on the home pages and title pages.
- The menu's Settings and Search row no longer scrolls away with the menu.
- The top of the TV guide is no longer cut off.
- Title pages fill the space under the details with a second row.
- The Sports page is no longer see-through.
- Faster, smoother Live now and Sports (work moved off the main thread).

## 0.28.8 · 16 Nov 2026

### Fixes
- **Widescreen SD channels show at the right shape**: on some boxes (Amlogic ones, such as the Ugoos AM9 Pro) the box's video decoder reported square pixels, so SD widescreen channels (720×576 marked 16:9) came out narrow with black bars, like 4:3. Viewarr now goes by the stream's own shape when the decoder gets it wrong, for live TV and for films and shows. Boxes that already showed it right are unchanged.

## 0.28.7 · 16 Nov 2026

### Source switching that copes with every provider
- **Viewarr picks the way by itself**: gap-free when the provider has a connection free; Dispatcharr's own switch when it doesn't (providers allowing **one line** now switch properly, instead of failing three times).
- **Another source from the same one-line provider** (backup to backup): the stream is let go first, then the new one starts, so the provider never sees two connections and cuts one off.
- **A busy or broken source**: you stay on what you're watching, with a note, within a few seconds (it used to wait up to 40 seconds and reconnect for nothing).
- **No more frozen picture after a switch**: if the new picture doesn't arrive, the channel starts afresh.
- New **Settings → Playback → Live TV → Source switching**: **Automatic**, or **Classic only** (never a second connection).

### Sports: the game page
- **OK on a game** opens its page: line-ups and formations, each side's **last five results**, this season's meetings, goals and cards, match stats, venue and TV channels (refreshed every minute while it's live).
- **Remind me**, **Record**, or **Record all of a team's games** straight from a game, even before the guide lists it: set up by itself once it does, never twice.
- **Settings → Sports → Game page** turns it off.

### News ticker
- A scrolling headline strip in Sky Sports, BBC News, ESPN or glass looks, with built-in feeds and **your own** (any RSS / Atom address).
- Choose where: **with the info bar**, **in the TV guide**, **on Movies, Shows and Sports**; its speed; and each feed On / Off in its own look.
- Settings → Appearance → **News ticker** (off until you switch a place on).

### TV guide
- **Programme title logos**: the highlighted programme's own logo at the top of the guide (Settings → Appearance → TV guide), off for chosen channel groups (**Title logos by group**).
- **Long programme names scroll** on the highlighted programme.

### Menu and search
- The top of the menu: **⚙**, a **mic** and a small **search box** you type straight into; the search page opens as soon as you send it, and **closes once you pick something**.
- **Edit menus** has gone from the top: **hold OK on any entry** to edit the menu.

### Fixes
- **Same size on every box**: Viewarr is laid out for the standard TV screen whatever the box reports (on some boxes, such as Ugoos, the mini player was tiny and the guide stretched).
- The info bar no longer flickers "No guide information" while the guide loads, and its progress row keeps its place.
- Settings no longer opens over a full-screen film's controls.
- Live TV lists load the next rows ahead while you browse.

## 0.28.6 · 16 Nov 2026

### Channel list
- **Narrower**: Settings → Appearance → TV guide → **Channel list width** (Medium, the new standard; Narrow; or Wide, as before).
- **Long programme names scroll** on the highlighted channel, so they can be read in full; every row stays one line.
- **Progress bars**: Channel list progress can now be **Highlighted channel only**, and the highlighted channel's bar has its own colour (**Highlighted progress colour**, white as standard), so it shows on the highlight.
- Opening the channel list closes the info bar and the controls.

### Info bar
- Stays the same size from channel to channel (it grew and shrank between channels with and without picture / sound badges, or without a "Next" programme).
- The lighter styles (Faded glass, Feathered glass, Outline…) follow Pop-up glass evenly, instead of jumping to half see-through at the first step.

### Search
- **The mic shows it's listening**: rings pulse out from it and a glow swells with your voice.

## 0.28.5 · 15 Nov 2026

### Live TV
- **Switching source** (the Source button) is quick and clean now: the channel keeps playing while the new stream opens in the background, then it swaps in, usually in 1–3 seconds, with no black, no hang and no second load. A busy backup just leaves you on the current one.
- **Stalled streams reconnect by themselves**: live TV stuck loading for 8 seconds after it had been playing (Dispatcharr moving to a backup stream, for one) reconnects, as turning the channel off and on did.
- **Your provider on the info bar** (Happy TV, a backup after switching…). Settings → Appearance → Info bar: source.
- **Progress bars in the channel list**. Settings → Appearance → TV guide → Channel list progress.
- The info bar waits until the picture has finished growing to full screen when you open a channel from the guide.

### Films and shows
- **Auto-play match** (Settings → Playback → Auto-play match): rules for one thing each (4K, 1080p, REMUX, Dolby Vision, HDR, HEVC, Atmos, 5.1, English, Not CAM / TS…), switched on and sorted by importance; add your own (a word or a regex).
- **Cached only**: leave out Usenet / torrent streams that aren't instant.
- **Try the next stream**: if the stream Viewarr picked fails or doesn't start in 20 seconds, the next one plays.
- **Back from a film** goes to its own page now (Settings → Playback → Episodes → Back from a film, to have the TV guide and menu instead).
- **Up next** checks the real season lengths: no more episodes that don't exist (S1 · E10 of a 9-episode season).
- **Match frame rate for films and shows works like live TV**: the TV switches as it opens, behind the loading screen, using the frame rate Plex, Jellyfin or Emby gives, so the sound works from the start (it could go silent when the TV switched mid-film). Without a frame rate from the server, the TV is left as it is.

### TV guide and remote
- **Back in the guide**: with something playing it goes back to it full screen (jumping to now first); with nothing playing, ask to exit or the menu. New: **jump to now first** On / Off (Remote buttons → Back).
- The Classic preset's hold OK in the guide opens the record options.
- Right on Edit menus moves on.
- Holding Left on what's on now (Classic layout) steps back one programme into catch-up, instead of racing past several.

### Looks
- **Transparency that does what it says**: Pop-up glass now goes from 0% (solid) to 100% (clear), and every info bar style, pop-up and notice follows it. Glass and Guide are exactly the percentage set (your current ones will look more see-through: about 20% / 40% is the old look).
- **Clock on the info bar**: Settings → Appearance → Info bar & controls.

### Also
- If Android closes Viewarr while it's in the background, it opens where you were (the channel, the film's page or the page you were on).
- Less memory used in the background, so Android closes it less often.
- Season / episode / date centred under the Episodes cards; the info bar no longer jumps between rows; shorter addon stream names in Play from.

## 0.28.4 · 15 Nov 2026

### A new Viewarr button layout
**Use Viewarr buttons** (Settings → Remote buttons, and how Viewarr now comes) is the Classic layout with a few changes:
- **TV guide**: **Left** goes back a programme and carries straight on into catch-up, with no hold needed (it stops at the start of catch-up). **Menu (≡)** opens the main menu, and so does Back on what's on now when nothing's playing. Hold OK opens the record options. **Red / Green / Yellow / Blue**: record options, channel groups, a day back, a day forward.
- **Live TV**: **Up / Down change channel** (they used to open the channel list), OK opens the controls, Left / Right rewind and skip, **hold Play / pause** goes back to live, hold OK or Menu opens the main menu. Red / Green / Yellow / Blue: TV guide, channel groups, the controls, Settings.
- Applying either preset now resets everything, including the Search button and any extra buttons you'd taught.

### Button settings can't break the remote any more
- OK keeps its main job (watching in the guide, the controls on live TV and in films) until Long OK, an arrow or Menu is given it.
- Back and hold Back can't be changed where they work the mini player and close the player. OK can't be set to do nothing.
- The Search button and extra buttons own their keys: those rows show what the key does instead of choices that would never run. Play / pause and the channel buttons can't be taken for either.
- Holding OK in the TV guide opens the record options again, even when OK has been changed.
- Fixed presses being swallowed: the OK after a long press on live TV, the button after browsing channels, the rest of a press that left a film.
- Holding Back stops a film even with Audio & subtitles, Audio sync, TV settings or Transparency open.

### Play order
- **Settings → Playback → Streams → Play order**: where Play looks for a film or episode. As standard: your servers, then IPTV, then your addons.
- **Hold OK on Play** (or on an episode) now searches your addons straight away and lists their streams with your own copies.

### Also new
- **Three more info bar styles**: Floating card, Slim strip and Frosted.
- **TV guide timeline**: Settings → Appearance → TV guide → Timeline shows 1½ to 6 hours across the screen.
- **Live TV controls** open on **Stop**. In catch-up, Down from the seek bar goes to **Go live**. The seek bar shows a dot when you're on it.
- **Episodes row** in the film player shows the whole season, centred on the episode you're watching.
- **Up next** works out where you're up to from all your watch history (Plex, Jellyfin / Emby, Trakt).
- **Changelog** in Settings → About.
- **Send a note** in Settings → Playback → Troubleshooting.
- **Refresh guide** button on the live TV info bar.
- Your library copies say where they're from and whether they direct play or transcode.
- Settings drop-downs open in place under their row, and pop-ups have their own window, so the remote can't get stuck behind them.
- Profiles are switched off for now (coming soon).

### Fixes
- In catch-up, pausing and then pressing Left no longer opens the channel list.
- OK (or a button set to Watch) on an aired programme plays its catch-up, rather than tuning the channel live.
- Frame rate matching remembers each channel's frame rate, so changing channel is quicker.
- Film playback through mpv checks HTTPS certificates.
- Guide data saved under the right Live TV source when switching sources quickly.

## 0.28.3 · 14 Nov 2026

### The remote works the same way everywhere
- **Back** in the player: a tap switches between full screen and the small picture (films go to the corner, live TV to the guide window); **hold Back** to close the player.
- **Left** from the first poster opens the menu on that page. Left on a menu entry opens its own list, Left again goes to the main menu. **Right / OK** on an entry goes to its rows.
- **Back** in any menu list closes the menu.
- **TV guide, classic style**: Left goes back a programme; on what's on now it opens the channel groups (Left again: the main menu); **hold Left** goes back into catch-up.
- The old "Menu opens on", "Guide Left" and "Row Left" settings are gone: there's one way for everyone now.
- **Button hints** say so the first few times: on full screen "Back: mini player · hold Back: close the player", on the mini player "Back: full screen · hold Back: close the player", for live TV and films alike.

### Five new info bar styles
**Settings → Appearance → Info bar & controls → Info bar style**, or the Style button on the bar:
- **Faded glass**: the glass panel, solid along the bottom and fading to clear at the top, with no hard edge. It keeps the bar's width and corners (Fade spans the whole screen).
- **Feathered glass**: glass with no edges at all, melting into the picture.
- **Side fade**: dark behind the text on the left, the picture clear on the right.
- **Outline**: almost clear, with a fine edge in your highlight colour.
- **Corner card**: a smaller panel in the bottom left, leaving most of the picture clear.

### Also new
- **Programme pictures are back**: recent Dispatcharr versions stopped sending programme pictures with the guide, so the guide's picture and the "What's selected" wallpaper only showed what TMDB could match. Viewarr now asks Dispatcharr for the highlighted programme's own picture (once per programme), and only uses TMDB when the guide has none.
- **Collections and playlists** from your Plex / Jellyfin servers, alongside the libraries.
- **Choosing a copy**: when a film is on your servers more than once (4K or 1080p, Dolby Vision or SDR, Atmos or stereo), Play picks the best one your TV can show; hold OK on Play to choose.

### Fixes
- Moving info-bar buttons quickly (holding Left / Right) could put the highlight on another button and undo the move. The same fix applies to the menu and rows editors.
- Movies / Shows from the menu could open on a library left open from My Library.
- **My Library**: Left from a library you opened there goes back to My Library's list, with that library highlighted and your place kept.
- **Main menu**: Up from Live TV goes to the ⚙ (Settings).
- Programme pictures given as `//host/…` or as a path on the Dispatcharr server now show.
- Back on Music, Recordings and Search now opens the menu instead of jumping to Live TV.
- Left in the guide did nothing when there was no guide data or you were at the start of catch-up; it now opens the menu.
- Focus could get lost after rows finished loading, in channel groups, or on Search.
- **Send a report** leaves out the TV maker's own log chatter (some TCL TVs log every button press and frame), so reports keep Viewarr's useful lines.
- **Home screen cleanup**: the main screen's code was split up after it hit the size that crashed Viewarr on some boxes earlier in the day, leaving it well inside the limit and easier to work on. You won't see a difference, but it's steadier, and it made the remote changes above possible.

Update from **Settings → Updates**, or download **viewarr.apk** with Downloader.

## 0.28.2 · 14 Nov 2026

### Watch progress for IPTV films and shows
Dispatcharr and IPTV providers don't remember where you stopped, so Viewarr now does, on the box, for each profile.
- **Resume** IPTV films and episodes where you left off (saved every 10 seconds and when you stop).
- **Watched** at the credits (or 90% through): episodes get their tick, and **Play** moves on to the next episode, into the next season when one ends.
- **Continue watching on IPTV** on Movies and Shows.
- Hold OK for **Mark as watched / not watched** and **Remove from Continue watching** (films), or **Mark as watched** on an episode.

### Rows under the info bar, your way
Press **Down** from the info bar's buttons for rows to jump to. Choose which rows and their order, separately for live TV and films & shows, with a preview: **Settings → Appearance → Info bar & controls → Rows under the info bar**.
- Next episodes · Continue watching (all, IPTV, Trakt, or Plex / Jellyfin) · Recent channels · Favourite channels · On now in this group · Coming up on this channel · Recordings · Recently added · Your watchlist · More like this
- One row at a time with **2 of 4** beside its name; Down / Up between rows, Back to the buttons.
- **Continue watching (Trakt)**: the shows you've watched lately in any app, each at its next episode.

### Also new
- **My Library** in the menu: every library on all your servers, and your music, in one list.
- The seek bar shows the **buffer** loading ahead of where you are.
- **Previous episode / Next episode** buttons you can add to the film bar.
- Film time on the right: **15:05 left**, **Ends at 22:40**, or both (Settings → Playback → Player).
- **Info bar styles**: Glass, Square glass, Pill, Fade, Full-width bar, Minimal, with corner rounding and an Info bar style button.
- The Play button says when the next episode is out.
- Any channel group can be hidden, ★ Favourites and All channels included.
- **Send a report** (Settings → Troubleshooting): the app's log, cleaned of passwords, keys and addresses and locked so only the developer can read it, with a code to share.
- Password and API key fields are hidden as you type; title info and the "what's highlighted" wallpaper fade in smoothly.

### Fixes
- mpv could close the app when moving to the next episode.
- The end of a film or episode wasn't reported to Plex / Jellyfin / Trakt in the built-in player, and the old episode's last report could land on the next one.
- Next episode now plays from Jellyfin / Emby and IPTV too, and only for the show that's playing.
- Viewarr could freeze (and be closed by Android) leaving the picture in the corner.
- Next episodes sometimes missing from the info bar on the first play.
- Live TV: typed channel numbers, search and reminders going nowhere with All channels hidden; Up / Down starting from the wrong group; old "Recording failed" notes at start-up; Day back / forward an hour off on clock-change days; a rare guide crash.
- Trakt could sign itself out (two refreshes at once, or another TV's older login).
- Sign-ins can no longer be wiped by a momentary key-store error; account changes no longer overwrite each other.
- Switching profile or source no longer keeps the last one's IPTV films, Plex episodes or Trakt lists.
- Updates install without risking "not responding"; a beta is no longer offered as newer than its release.
- Smoother film controls with mpv, fewer pause / play reports to Trakt, and live TV no longer restarting in the background.

Update from **Settings → Updates**, or download **viewarr.apk** with Downloader.

## 0.28.0 · 13 Nov 2026

### Live TV
- **★ Favourites**: hold OK on a channel → *Add to favourites*. A Favourites group heads the channel groups.
- **Move channel groups**: hold OK on a group, Up / Down, OK. **"All channels"** can be hidden (Settings → Live TV).
- **The guide goes up to 7 days ahead** (Settings → Live TV → TV guide days ahead: 1, 3, 5 or 7). The date at the top follows the day you're looking at, going back or forward.
- **Browse channels in the info bar** without changing: Settings → Live TV → *Up / Down while watching* → *Browse in the info bar, OK changes*.
- **Frame rate matching for live TV**: Settings → Playback → Match frame rate → *Live TV* or *Both* (UK channels switch the TV to 50 Hz).
- **Frame-rate badges** that are always right (no more 45 / 48 fps), on live TV and films; **Stream info** now shows what the box itself is getting too.
- Holding Left back into catch-up and tapping Left keeps browsing; channel changing wraps round at the ends of the list.

### The info bar, your way
- **Hold OK on any button** in the controls to edit them: OK shows / hides, hold OK picks one up to move. Live TV and films share one layout.
- **Extra buttons** to add: Favourite, Last channel, **Audio sync**, **Sleep timer**, **TV settings** (frame rate, picture size, passthrough… over the picture), Movies, Shows, Recordings and Episodes.
- **Search** and **Transparency** buttons in the film player too; Transparency shows the info bar while you change it.

### Settings
- **Reorganised**: Appearance (Theme, Wallpaper, Glass, TV guide, Info bar, Menu, Posters, Title pages, Ratings, Subtitles, Music, Motion), Live TV, Playback, Menu & navigation, Troubleshooting.
- **🔍 Search settings** at the top: type a word, OK opens the setting.
- **Background colour**: Classic blue, Ocean blue and Sky blue are back, with a Bright cyan highlight.
- **Numeric dates** (Tue 10/11), **ten new wallpapers**, a ⚙ beside Edit menus, Back in the menu goes to Live TV.
- **Hold OK in the guide → ✎ Edit this menu** to choose what that pop-up shows.
- **Sign out** asks first.

### Smoother
- No more trailing highlight when you go through lists fast (Appearance → Motion → List scrolling).
- Moving through channel groups no longer loads every group on the way.

Install: download **viewarr.apk** (or use the in-app update).

## 0.27.2 · 13 Nov 2026

- The TV guide and the full-screen channel list open at your channel.
- **viewarr:// links:** a link to a channel Viewarr doesn't know yet (added on Dispatcharr since it last looked) now fetches the channel list (channels only, a few seconds) and tunes. New `viewarr://refresh` (channels) and `viewarr://refresh?guide=1` (the whole guide). See Controlling Viewarr from outside.
- Fixes: a link could cancel its own channel refresh and leave "Updating channels…" on screen; a restarted Viewarr no longer replays the last link it was opened with.

Install with Downloader code **7623981**, or update from inside Viewarr.

## 0.27.1 · 13 Nov 2026

- **Info bar size** and **Preview cards** (Settings → Appearance → Live TV): make the info bar, its buttons and its rows smaller (or larger). Live TV and films share the one info bar, so both change together.
- The main menu, the Settings list and the Movies / Shows list open already in place, without scrolling themselves up or down.
- **Colour tint** now covers the whole menu so both panels match; Settings keeps the plain glass with a highlight-coloured edge.

New: user guides for every part of Viewarr in docs/, including controlling Viewarr from outside.

Install with Downloader code **7623981**, or update from inside Viewarr.

## 0.27.0 · 13 Nov 2026

**First run**
- A new tour of how Viewarr works, drawn in the glass look, then three pages on what setup will ask for (live TV, your libraries, extras).

**The menu**
- A hint says what Right does on the highlighted entry; inside a list, "‹ Left: main menu".
- Optional list headings (or the path), colour edges, tints, an icon column, a TV mark and a LIVE mark (Settings → Appearance → Main menu).
- Right goes straight into Movies, Shows, Recordings and the streaming services; Settings needs OK or Right.
- Left from the guide opens the channel groups first; the guide follows the group as you move through them.
- Many focus fixes (closing a settings page, coming back from a service, Up from Live TV, Edit menus).

**Live TV**
- Browse channels in the info bar: set a button to "Next / Previous channel in the info bar" and the channel changes only when you stop on one (Settings → Playback → Browsing channels).
- Rounded or square guide programmes; Sports finds the channel for games on now.
- Transparency in the live controls now also has the Glass colour and the info bar's glass.

**Films & shows**
- Home page & menu: one list each for Movies and Shows, each row showing where it comes from (Plex, Jellyfin, AIOMetadata…), rows can be moved.
- Streaming services' rows always in order: New shows, New films, Originals, Popular, Top rated.
- Title pages keep their header in place; Play highlights in your colour; hold Play on a show to pick a source.
- Next episodes show their release date, darker and "Not out yet" before they air.
- The film controls' Settings button keeps the film playing in the window.

**Music**
- Lyrics, the queue, a sleep timer, song and artist radio, favourites (★), hold OK on songs and albums, artist pages (biography, top songs, similar artists), Artist › on albums.
- A screensaver, artwork as a spinning vinyl record or CD, and four visualiser styles (Settings → Playback → Music).

**Look**
- Twelve glass colours that really differ.

**Outside control** (home automation / ADB)
- `viewarr://channel?uuid=…` or `?number=…`, `viewarr://channel?step=up|down`, `viewarr://stop`, `viewarr://guide`, `viewarr://page?name=…`, `viewarr://record?uuid=…`, `viewarr://search?q=…`, `viewarr://title?tmdb=…&type=movie|show`. See the README.

Install with Downloader code **7623981**, or update from inside Viewarr.

## 0.26.1 · 12 Nov 2026

### Sports
- **Sports in the menu** shows your teams' and leagues' games as panels, each with both teams' badges, the score or kick-off time, and LIVE / FT tags: on now first, then a row per team or league.
- **OK on a game** goes to the channel showing it: your guide (including the EPG & Sports Editor plugin's listings), the channel the UK Sport on TV plugin names, or an event channel named after the match.
- Games appear straight away and their channels fill in one at a time, so slower boxes stay responsive.
- Settings → Sports: search a team, or pick a league and choose a team from it.

### Films and shows
- **The mini player for films too:** Back from a film or episode keeps it playing in the top-left window while you browse. Back on the page brings it full screen; Stop ends it.
- **Next episodes:** watching a show, press **Down** for a row of the next episodes; OK plays one.
- **Where it's playing from** shows on the info bar (your Plex server, Jellyfin, Emby, an addon or IPTV).

### TV guide
- **Episode numbers** ("S15E4") can be turned off in the guide and channel list (Appearance → Live TV).
- **Guide refresh:** every 2, 4, 8 or 12 hours or once a day while Viewarr is open, or only when you ask (Settings → Playback).
- **OK on a recorded programme** plays the recording.
- The highlighted channel stays in the middle of the list; channel logos no longer cut off.

### Make it yours
- **Pop-up glass** slider (see-through to solid) for the info bar and every pop-up; **Glass colour** pastel tints; **12 fonts**; softer highlight colours.
- **Ratings** on title pages in the order you choose (Appearance → Ratings).

### Fixes
- Settings pages make room for the mini player instead of being squeezed beside it.
- Holding Back on the film mini player no longer jumps to live TV.
- Library grids start higher; settings wording brought up to date; many smaller fixes.

Download **viewarr.apk** (or `viewarr-0.26.1.apk`) below, or update from inside the app.

## 0.26.0 · 12 Nov 2026

### A new look: the glass design
- **One menu on the left, made of glass.** It's see-through on every page and slides the page and the picture aside instead of covering them.
- **Pages change as you move.** Moving down the menu shows each page straight away: Live TV, Live now, Sports, Movies, Shows, your **streaming services** (Netflix, Disney+, Peacock… from your AIOMetadata rows), Music and Recordings.
- **One panel at a time.** Right or OK opens an entry's list (channel groups, libraries) in place of the menu; Left brings the menu back. From full-screen live TV, Left goes all the way to the main menu.
- **Live TV keeps playing in the top-left window** while you browse Movies, Shows, a service, a library, a title's page, Recordings or Music. The details of whatever you're on sit beside it, with the posters below. With nothing on, the highlighted title's **trailer** plays there.
- **The menu over a film:** press OK, then Left from the first button, and the menu opens while the film plays on full screen.

### One player bar
- **Live TV and films share one glass info bar,** with the programme or film, its progress and the controls' buttons inside it.
- **Every pop-up uses the same glass:** skip intro, the next-episode countdown, reminders, notes, hints, the audio & subtitles panel and the guide's programme options.
- **Settings → Appearance → Info bar:** glass Light / Medium / Dark, and the border on or off.
- **The shortcut row under the player is gone.** Everything it held is in the menu. **Live now** and **Sports** have their own menu entries.
- **Long OK in films** no longer opens its own pop-up; audio and subtitles are in the bar.

### Make the menu yours
- **✎ Edit menus** (top of the menu): show, hide and reorder the menu's entries and services; reorder or hide a service's rows (the page changes as you do it); edit Movies' and Shows' libraries. When you're done, what's off is hidden.
- **Settings → Appearance → Main menu** has the same on / off switches.

### Remote buttons, rewritten
- **Every button has its own entry:** OK and Long OK, Back, the arrows and their long presses, Menu, Ch + / −, play controls, Record, Info and the colour buttons.
- **Each button lists what it does** in the TV guide, on live TV, in films & shows and in menus & pages, each with its own choice. The **Classic** and **Viewarr** presets set them all at once.

### Theme and settings
- **The Viewarr theme:** charcoal glass with an orange highlight. Everyone moves to it once on updating; the highlight can still be changed.
- **New: Font** (Standard, Condensed, Light, Medium or Serif).
- **Tidier settings:** options the new design made pointless are gone (theme list, info bar style, corners, shortcut settings).

### Faster on slower boxes
- The splash waits until the channels are in, things load a few at a time after start-up, pictures are smaller, and channels start sooner.

Download **viewarr.apk** (or `viewarr-0.26.0.apk`) below, or update from inside the app.

## 0.25.0 · 12 Nov 2026

### Shortcuts, without being tied to live TV
- **Shortcuts are always there.** The separate "Live TV hub" style is gone. On live TV, press **OK, then Down** for your shortcuts and their rows.
- **Browsing a shortcut** keeps live TV playing in the corner while you look through its posters. **Choosing a title**, or a card on a row, stops it.
- **Films stop when you browse.** Leaving a film for a shortcut stops it, with its place kept. **Back** on library pages follows your Back setting.
- **New: After a film or episode** (Settings → Playback) can take you back to live TV when it ends or you stop it.
- **The picture in the corner:** press towards it at a page's edge to highlight it, then choose **Full screen** or **Close**. Holding Back gives the same choice.

### Sports
- **Follow teams and leagues** in Settings → Sports. Type a team, league or sport, or pick a sport, then a league, then a team. It covers almost every competition, using ESPN's data.
- **The Sports shortcut** shows what's on now, then each team's or league's next games and results, with the channel showing each one from your guide.
- **Pin a team** to give it its own shortcut. **Hold OK on a game** to be reminded, record it, or pin the team.
- **With the UK Sport on TV Dispatcharr plugin,** games show their UK channel weeks ahead, and games not on UK TV show the club channel that streams them.

### Shortcuts and rows
- **Choose rows:** hold OK on a shortcut to choose which rows appear under it, in what order.
- **Continue watching:** OK carries on watching. Hold OK for Go to show, Go to season, or Play from the start.
- **Highlighting:** shortcuts grow a little when highlighted, and progress bars are no longer hidden behind the highlight.

### Skip intro, next episode and live TV (from 0.24.1)
- **Skip intro and Next episode:** buttons using Plex, Jellyfin or Emby markers, or TheIntroDB / IntroDB for addons and IPTV. A countdown you set (0–180 s) leads into the next episode.
- **Live now:** sport and events on now and starting soon, with match pictures.
- **Info bar options:** glass or solid panels, channel and episode numbers, plus HD / 4K, 5.1 and frame-rate badges.
- **Channel changes:** an optional channel change delay stops zapping getting stuck. IPTV title pages now fill in from TMDB.

### Live TV
- **End of a list** (Appearance → Live TV): the guide, channel list and groups can jump from the last channel back to the first.
- **The channel list** opens with your channel at the top, and moving through the groups no longer lags.
- **Search** now works while a film is playing full screen.

### Faster and fixed
- **Smoother:** shortcuts and their rows are remembered between redraws, and search and Live now share one copy of the channel list.
- **Fewer requests:** fewer calls to the sports services, and Live now reads the guide in one go.
- **Fixes:** crash and focus fixes from a full review, covering title pages on long shows, search suggestions, the Sports page, the corner picture and the channel list.

**Install:** Downloader code **7623981**, or download `viewarr.apk` below.

## 0.24.0 · 11 Nov 2026

### Live TV hub (optional)
- **A new way to use Viewarr:** Settings → Navigation → Start → Style → **Live TV hub**. Viewarr opens on your last channel, and Back from any page goes back to it. **Classic menus stay the default** and work as before.
- **Shortcuts on live TV:** press **OK, then Down** for a row of shortcuts: pages (Movies, Shows, TV guide, Music, Recordings, Search, Settings), **networks** (Netflix, BBC One, HBO… each opening its Popular / New / Top rated rows), your Plex / Jellyfin / Emby libraries and rows, and your music. **Down again** previews the highlighted shortcut's rows.
- **The channel carries on in the corner** while you browse from a shortcut; Back takes it full screen again.
- **Films get the same row** (OK, then Down ×3), and **Back sends a film to the corner** instead of stopping it.
- **Make the row yours:** round, rounded-square or wide logo tiles in three sizes. **Hold OK** on a shortcut to **move** it along the row, **refresh** just its rows, or **hide** it. **↻ Refresh** at the end of the row fetches every row again from your addons and servers.
- **Choose network shortcuts:** builds network rows from your own addons, or on your own **AIOMetadata** with your TMDB key (UK and US lists to pick from). Viewarr doesn't supply addons: you add your own.
- **A guide when you turn it on** says what the hub needs and checks your addons for network rows.

### Remote buttons
- **Presets:** **Classic** (Up / Down change channel, Left the channel list, Right the previous channel; in the guide Left goes back to now then the groups, hold Left for catch-up, Back shows the groups then asks to exit, hold Back goes full screen) or **Viewarr**. The buttons a preset sets are listed under it to change; it then shows **Custom (from …)**. Every preset keeps a way to the menu and a way out.
- **Live TV Left / Right:** rewind / skip ahead, or channel list / previous channel. **OK** can also show the channel list.
- **Stop** on live TV stops the channel and leaves you on the guide (also in the button table).
- **Back on the guide asks to exit again** with Classic menus (0.23 made it go full screen for everyone by mistake), and **Back twice quickly** from live TV always exits.
- **Rewind?** Left on live TV asks first; press or hold it again to rewind.

### Menu and libraries
- Going into a list **slides the one before it away**, like the guide's channel groups; Left or Back brings it back one level at a time.
- **Right on a library** lists what to jump into: its categories (IPTV) or genres, collections, decades, years, studios / networks or age ratings (Settings → Navigation → Menu → Right on a library).
- **Edit the Movies / Shows list in the menu:** hold OK on a library, or **Edit this list**: switch libraries on or off and move them, right there.
- **Jump to a row** now lands on the row.

### Settings
- **Remote buttons** and **Navigation** are now separate, and a line at the top of Settings says what the highlighted entry covers. Every section says what it's for.
- With Classic menus, the shortcut lists are one **Live TV hub** section explaining it, with **Turn on the Live TV hub…**.
- Settings that need the hub or are off in low-memory mode show 🔒 with why and how to unlock them.
- The remote picture is smaller and the same size everywhere, and **Right always reaches the settings under it** (it couldn't on some TVs).

### Also
- A new look: logo, splash screen and banner.
- Addons with their own titles (no IMDb / TMDB id) get proper title pages with seasons and episodes; addon TV, sports and event catalogues play straight away.
- The catch-up search shows episode numbers and names.
- Smoother on boxes with accessibility services such as Projectivy.
- Focus fixes: hold Back on a title page, Settings toggles, the shortcut lists, and more.

**Install / update:** in the app, or with Downloader code **7623981**.

## 0.23.0 · 10 Nov 2026

### Remote & navigation
- **A picture of the remote for each screen** (TV guide, Live TV, Films & shows, Movies & Shows) showing what the main buttons do there. It follows your settings.
- **Choose what buttons do:** Hold Back, Record, Red / Green / Yellow / Blue and Info, per screen, from actions that make sense there. IPTV-style colour defaults:
  - Guide: Red record options, Green channel groups, Yellow back a day, Blue forward a day
  - Live TV: Red record options, Green channel list, Yellow start over, Blue info banner
  - Films: Green audio & subtitles, Blue details
- **One-press record:** the Record button schedules what's on (or the highlighted programme) straight away. Already set: it opens the options so you can cancel.
- **Reset these buttons** per screen, and **Reset buttons** for the whole page (buttons only, nothing else).
- **Extra buttons:** give any action a spare button your remote has (Buttons while watching). Remotes with **Guide** and **Record** buttons use them.
- **Button hints:** a small pill at the bottom of the screen says what the buttons do where you are (first few times, always, or off).
- Holding Back reacts faster (about a quarter of a second).

### TV guide and live TV
- **Back** from full screen: the channel glides into the guide's preview. **Back** in the guide: full screen again. The guide always opens on the channel you're watching, in its group, whatever you browsed before.
- **Hold Back** in the guide: the menu on the channel groups (Left: the main menu; Right: back to the groups, the main menu sliding away).
- **Left** steps back through programmes and catch-up; **hold Left** scrolls fast.
- **Live TV controls (OK):** new **Transparency** button (sliders for the channel list, guide and wallpaper dimming, shown over the picture), **Settings** (and Back to the channel), and **Down** for your own **shortcuts** to libraries, catalogue rows, Music, Recordings and Search (choose them in Remote & navigation → Live TV shortcuts).
- Transparency sliders now match what you see (50% is half-see-through, 100% keeps text readable); wallpaper dimming changes in every step.

### Search
- **Catch-up:** a row and a pill for programmes from the last few days you can replay, using Dispatcharr's own guide search (provider logins search the TV's guide). OK plays from the start.
- **Search catch-up** in the guide's hold-OK menu: every showing of that programme you can replay.
- Moving onto a pill brings that kind of result to the top; OK on it filters.

### Films and shows
- **Badges on title pages:** age rating (UK or US: Settings → Appearance → Title pages), 4K / 1080p, HDR / HDR10+ / Dolby Vision, Dolby Atmos / DTS:X / DTS-HD MA / TrueHD…, 5.1 / 7.1, CC, and **Ends at** (for part-watched films, the time left).
- **Built-in player is now the default** (lighter, surround passthrough). If it can't play a file, **mpv takes over by itself** at the same moment. Switching player keeps your soundtrack and subtitles.
- **Video view: Standard** shows films through the box's video hardware (lighter, HDR and Dolby Vision as the file has them); **Compatible** is there for any box where the picture stays black.
- Back closes the controls or details, then stops (your place is saved). Picture-in-picture for films is off for now.
- The controls always open on Play / Pause.

### Fixes
- mpv on Amlogic boxes (e.g. Formuler) where surround passthrough never started: it now switches to decoding in the box instead of sitting still.
- A menu left open under a full-screen film no longer takes the Back presses.

**Install / update:** in the app, or with Downloader code **7623981**.

## 0.22.0 · 10 Nov 2026

### Profiles
- **Profiles:** one per person (a Dispatcharr user), each with their own settings, sign-ins and library views, an optional PIN, and **Who's watching?** when Viewarr opens if you want it.
- **Settings → Users & profiles** (Dispatcharr admins): create and delete users, reset passwords, set live TV limits (streams at once, channel groups), choose which services each person gets (including their own Navidrome user), and decide what they can do. Seerr requests, recording, catch-up, IPTV films, accounts, Live TV sources and settings can each be allowed or locked. Your Trakt and Plex sign-ins are never handed out.

### Friends in another house
- **Away addresses:** set each server's outside address once. Viewarr uses the home address when it can and switches to the outside one by itself when it can't.
- **Sign-in details for another house** gives you the outside address and that person's API key to send them. On their TV they choose **I was invited**, type the address and paste the key, and the setup you prepared for them goes on automatically. Your Dispatcharr login page never needs to be open to the internet.
- **First run:** a new install asks "your own server or invited?", then shows a five-page tour (again any time from Settings → About).

### TV guide and Seerr
- Hold OK on a programme for **Start over** (the channel's catch-up if it has it, else your own servers or addons), **Go to show** / **Go to film**, recording, reminders and **Request on Seerr…**. Long-press works in the channel list's programme panel too.
- **Request on Seerr** from the guide: one line instead of three, a film or a show's missing seasons only (seasons already on your servers can't be ticked).
- Better matching of guide programmes to films and shows: the year has to agree, so a 2024 film no longer matches a 2022 one with the same name.
- Titles you only have on your IPTV provider can now be requested; titles already on your servers say "Already available".
- "Record series" is hidden for films.

### Controls and look
- **Icon controls:** live TV now uses the same icon buttons as films and shows, with the name shown under the highlighted button. Title page buttons are icons too.
- In the film/show player, pressing Up from the seek bar pauses and shows the details.
- Progress bars sit inside the poster, so they no longer blend into the highlight.
- **Date and time format:** Settings → Appearance → Colours & text (day or month first; 24 or 12-hour). Used in the guide, clock, recordings, reviews and About.
- **Appearance → Transparency:** sliders (Left / Right) for the channel list over live TV, the guide (channel names and programmes now match), and wallpaper dimming.
- **Watchlist setting** (Settings → Watching): Trakt, your Plex watchlist, or Jellyfin / Emby favourites. Title pages, the long-press menu and the "Your watchlist" row follow it.

### Menu
- **Search** keeps your recent searches and has voice search; Right on Search goes into the panel.
- **Recordings** is cleaner: full-width titles with day, channel and length underneath, "Recording now" in red, full dates for what's coming up, and **All recordings ›**.
- **Music:** every Music page has search with voice, and Left goes back to the Music list, not the main menu. Movies, Shows and Recordings open on their own list too.

### Settings
- Clearer section headings (small capitals with a line), regrouped into Accounts & people / Look & controls / Watching / Live TV / This TV.
- Settings pages stay readable over bright picture wallpapers.
- Duplicate "Skip with Left / Right" removed; consistent wording ("Signed in as…", "Not set up", "IPTV films & series", "Language", "Current").
- **Low memory mode** hides the settings it switches off (programme pictures, title logos, ratings, wallpapers, trailers), so nothing looks broken.

### Fixes
- **Multi-view** on the Fire TV Stick 4K Max: windows had sound but no picture.
- Turning Low memory mode on or off from Troubleshooting could close Settings.
- An unwatched show could open on its last season; seasons are now in order and it starts at S1 · E1.
- The channel list over live TV ignored Menu transparency.
- Episode numbers are "S1 · E1" everywhere and seasons are always "Season N".
- The ★ on the Movies/Shows header for library titles was really Plex's audience score; it's gone there and labelled "TMDB" elsewhere.
- The poster menu says **Resume** for something part-watched.
- A typed server address is tried as https first (no redirect on every request).
- Focus fixes: the last title-page tab, the last setting in a list, the profiles page, Back from Accounts & sources and from Users & profiles, and the search box after a guide search that found nothing.

## 0.21.0 · 9 Nov 2026

### New: library sorting and filtering
For every library: Plex, Jellyfin, Emby, IPTV films and series, and Music.
- **Sort and Filter, from your own server.** Plex reports its own sorts and filters for each library (for films: title, release date, critic and audience rating, duration, date added, date watched, resolution, random; filters such as genre, decade, year, age rating, collection, studio / network, country, resolution, HDR, Dolby Vision, HDR10+, Atmos, unwatched, in progress, audio and subtitle language, edition, labels). Jellyfin and Emby offer theirs; IPTV keeps its categories.
- **Order switch** (A → Z / Z → A, newest / oldest first, highest / lowest first), and several values of a filter at once.
- **Filter chips** under the toolbar: OK removes one, or Clear all.
- **One continuous grid** instead of pages: only what's near you is loaded, so big libraries stay quick.
- **A–Z strip** down the right side: Right from the end of a row steps onto it, moving through the letters shows the letter big and the grid jumps when you stop (or press OK), and letters with nothing in them are faded. Picking a letter while sorted another way sorts by title and keeps your filters.
- **Back** in a grid goes up to Sort / Filter / search, keeping your place; Back again leaves.
- **Remembered per library** (and ready for profiles).

### Music
- Albums, Recently added, Favourites and genre grids get the same Sort, Filter and A–Z strip, with each music source's own options: Navidrome (title, artist, date added, recently / most played, year, random; favourites, genre, decade), Plex (its own sorts plus mood, style, record label, format…), Jellyfin / Emby. With several sources on, you get what they share.
- **Artists** get the A–Z strip.

## 0.20.0 · 9 Nov 2026

### New: Music
Off unless you turn it on: **Settings → Accounts & sources → Music** (or from your phone with the QR code).
- **Any of your servers:** Navidrome (or another Subsonic server), and the music libraries on your Plex, Jellyfin or Emby. They're joined into one library: an album on two servers shows once. Each source can be switched on or off (only Navidrome, only Plex…).
- **Navidrome** is found on your network automatically (port 4533); only a secure token is kept, never your password.
- **Music in the menu:** Music home (Recently played, Recently added, Your playlists, Most played, Rediscover), plus Albums A–Z, Artists, Playlists, Genres, Favourites and Recently added as grids, Shuffle my library, and Now playing.
- **Search** includes music: artists and albums open on the Music page, songs play straight away (and a Music filter).
- **Now Playing:** big cover, progress, previous / play-pause / next, shuffle, repeat, up next, and a **visualiser** that follows the music (a button makes it fill most of the screen).
- **Keep browsing:** Back shrinks Now Playing to a small now-playing bar (with its own mini visualiser); hold Back to bring it back. The remote's play/pause, next, previous and stop work from anywhere. Starting a film or Live TV stops the music, and the other way round.
- **Plays count** on the server they came from: Navidrome scrobbles (so ListenBrainz / Last.fm too), Plex and Jellyfin mark them played.

### Fixed
- **Posters in library grids were cropped** (Plex, Jellyfin, Emby and IPTV libraries): the grid squeezed them wider than box art. They're 2:3 again, and box art of another shape (square covers, wide images) now shows whole instead of being cut off.

## 0.19.1 · 9 Nov 2026

### New
- **Player controls size** (Settings → Playback → Player): the seek bar, title and buttons over a film or episode are now smaller by default (**Medium**) and take less of the screen's height. **Small** goes further; **Large** is the old size.

### Fixed
- Starting another film or episode while one was playing in the corner (picture-in-picture) froze the picture and only played the new one's sound. Each new film or episode now gets a fresh player.

## 0.19.0 · 9 Nov 2026

### New: make Viewarr yours
**Settings → Appearance**
- **Text size:** Small, Normal, Large or Extra large (everything scales together).
- **Clock** in the top corner.
- **TV guide:** channel logos on/off; programme pictures on/off (off: no pictures are looked up).
- **Posters:** the "where it's from" label and progress bars, each on/off.
- **Title pages:** switch off the title logo, Up next line, trailer button, seasons & episodes row, and each tab (cast & crew, reviews, collection, more like this, details).
- **Ratings:** choose which show: TMDB, IMDb, Rotten Tomatoes critics and audience, Metacritic, Letterboxd, Trakt.
- Anything switched off isn't fetched either, so title pages have less to load.

**Settings → Playback**
- **Back while watching:** shrink to the corner (picture-in-picture) or stop.
- **Picture-in-picture:** size (small / medium / large) and corner.
- **Controls stay up for** 3, 4, 6 or 10 seconds.
- **Seek-bar previews:** the server's, else made by the box / the server's only / off (saves bandwidth and memory).
- **Next episode countdown:** 5, 10 or 20 seconds.
- **Still watching?** (Live TV): after 2-6 hours with no button press, it asks, and stops if nobody answers within a minute, freeing a provider connection when the TV's left on.
- **Low-memory mode** (Troubleshooting): one switch for 1-2 GB boxes: no box-made seek previews, no programme pictures, no title logos, only TMDB's rating.

### Also new
- **From the start:** a button next to Resume plays the film or episode from the beginning.

### Fixed
- **Where you're up to** on a show opened from search: when Plex names nothing "on deck" and Trakt doesn't know, it's worked out from your watched ticks (Crystal Lake said Play S1·E1 after E1 was watched).
- **Settings pages:** Left from a setting goes back to the section you came from (it jumped to whichever was nearest), and Right goes to the section's first setting with the list back at the top.

## 0.18.0 · 9 Nov 2026

### New
- **Picture-in-picture:** press Back while a film, episode or recording plays and it shrinks to a corner and keeps playing while you browse the app. **Hold Back** to bring it back full screen. Starting a Live TV channel ends it (one stream, one sound).
- **Show pages:** the seasons and that season's episodes now sit on the show's page itself, opening on the season you're up to, with watched ticks and progress bars. OK plays an episode, hold OK picks its source. The page also loads much faster (everything is fetched at once instead of one thing after another) and is a little more compact.
- **Films:** **Audio & subtitles** before Play (films on your Plex, Jellyfin or Emby list their tracks; applies to that play), and **Mark watched / unwatched** on your servers and Trakt.
- **Libraries:** an **A–Z** jump (pick a letter, the grid goes to the first title starting with it), and each library remembers its sort and filters after a restart.
- **Player controls are icons:** Play/Pause and Stop together, then Details, Audio, Subtitles, Media info and Switch player; the focused one shows its name. The remote's Stop key stops too.
- **Settings → About:** version, this TV (model, Android, memory, storage, screen), what Viewarr stores (each Live TV source's guide, artwork, updates, settings) with **Clear artwork** and **Delete downloaded updates**, and your Live TV sources with their channel and programme counts.

### Fixed
- **Voice search on the Nvidia Shield:** it now listens inside Viewarr (Android's speech recogniser) instead of Google's old voice box, which never listened on the Shield. Viewarr asks for the microphone the first time you press the mic. Left in an empty search box always reaches the mic.
- **Watch from the start** (and anything else played over Live TV): the channel no longer keeps playing underneath; it's stopped and its connection freed.
- The library grid's background follows the highlighted title (it kept the previous page's artwork).
- A big library's page count no longer wraps mid-line.

### Note
- New permission: **microphone**, only for voice search and only when you allow it.

## 0.17.0 · 9 Nov 2026

### New
- **Several Live TV sources:** add more than one Dispatcharr instance, IPTV (Xtream Codes) login or M3U playlist in Accounts & sources → Live TV → **Add another source**. Give each a name, switch to it, or remove it there.
  - **Switch** from the guide's side menu (the source line at the top of Live TV) or full screen: Up for the channel list, Left for groups, Left again for **Sources**. What's playing carries on until you pick a channel from the new source.
  - The **three most recent sources keep their TV guide** on the box, so switching between them is instant; older ones are cleared.
- **M3U playlists:** paste a playlist link (setup's address box, or the new M3U tab on the phone page), with an optional XMLTV guide link. A provider's `get.php?username=…&password=…` link signs in as an Xtream login instead (catch-up, films and series too).
- **Choose groups** (IPTV logins and playlists): switch off groups you never watch; their channels and guide aren't stored at all.
- **Smaller TV guide:** programme synopses are stored compressed and unpacked only when shown.

### Fixes
- Catch-up: rewinding to the start of a programme and then fast-forwarding went back to the beginning. Every jump now asks for the stream from that moment; quick presses are combined.
- Watch from the start: when several titles share a name (e.g. "Adultery"), the search opens to pick from instead of playing the wrong one; titles with an episode name added ("Only Fools and Horses To Hull and Back") still find the show.
- Search: the on-screen keyboard no longer flashes when coming back to the search page; it opens when you press OK on the box.
- Menu button: a tap opens audio & subtitles when released; holding it (search) no longer opens them as well.
- Setup: a Dispatcharr found on your network is filled in, but waits for Continue (handy with several instances).
- Switching sources no longer leaves a frozen picture with the new channel's sound.

Everything from 0.16 is in the 0.16.1 notes.

## 0.16.1 · 8 Nov 2026

### New
- **What's playing, in the player:** the remote's **Info** button (or the new **Details** button in the controls) shows the poster, title, episode, year, rating, genres, runtime and synopsis. It also appears by itself a moment after you pause, and goes when you play again.

### Fixes
- Guide: holding OK now opens the programme's options for things that have already aired (**Watch from the start**) and things still to come (**Watch it now**), not just what's on now. Programmes that have finished no longer offer "Record".

Everything else new in 0.16 is in the 0.16.0 notes.

## 0.16.0 · 8 Nov 2026

### New
- **Trakt watched history:** Viewarr now reads what you've watched on Trakt (films and every episode), checked on start and every half hour. Episodes you've watched anywhere get their ✓, and Play / Resume on a show carries on from whichever of Plex, Jellyfin/Emby or Trakt is furthest along.
  - **Sync with Trakt now** in Accounts & sources → Trakt, showing how many films and episodes it found.
  - **Copy watched to Plex** (off unless you switch it on): marks on your Plex servers what Trakt says you've watched. It never un-marks anything.
- **Watch from the start:** hold OK on a programme in the guide that's already started and choose **Watch from the start (your library / addons)**. Viewarr finds the film, or the show's episode, and plays it from the beginning from your Plex, Jellyfin, Emby or addons (same order as the Play button). For a show it plays that episode only if you're up to it; otherwise it opens the episodes at the season you got up to, so you don't skip ahead. Guide titles with an episode name tacked on ("Only Fools and Horses To Hull and Back") still find the show.
- **New controls for films and shows:** OK brings up the seek bar and a row of buttons (Play / Pause, Audio, Subtitles, Media info, Switch player). The remote's Play/Pause button pauses (OK no longer does); hold OK still opens audio & subtitles.
- **Rewinding live TV (catch-up channels):** press OK, Up to the progress bar, and Left moves a marker back with the time it'll jump to ("Back to 18:18 · 2 min ago"); OK jumps, or it jumps when you stop.

### Fixes
- Catch-up: the seek bar now shows where you're playing within the programme and how far through it live is (it used to look nearly finished).
- Rewinding from live by a short step did nothing (it went straight back to live).

## 0.15.0 · 8 Nov 2026

### New
- **Multi-view:** watch up to four live channels at once. Press OK while watching and choose **Multi-view**, then **+ Add a channel**. The arrows move between windows and the sound follows the selected one; OK makes a window full screen; hold OK (or Menu) to change or remove a window. Each window is a stream on your provider, and Dispatcharr's failover to a backup provider works per window.
- **Wallpapers** (Settings → Appearance → Wallpaper): a built-in design (Aurora, Sunset, Deep sea, Forest, Nebula, Highlight glow) or the artwork of the programme you've highlighted, behind the guide, menus and settings, with adjustable dimming.
- **Movies and Shows** now fill the whole page with the highlighted title's artwork.
- **Nine more colour themes:** Forest, Sunset, Slate, Nord, Dracula, Mocha, Rose gold, Gold and High contrast.

### Fixes
- Live TV: after scrolling the guide away from the channel you're watching, its info bar said "No guide information". It now always shows what's on.
- Multi-view: typing a channel number, a reminder or search now leaves multi-view for that channel.
- Shows with many seasons: you can move along the seasons again (each season's episodes used to pull you back into the list), Up from the episodes goes to the season you're viewing, and that season's pill stands out.
- Title pages: moving down to the cast before the library check finished no longer throws you back to Play.
- A programme already set to record opens straight to **Cancel this recording**.
- Settings: choice lists with a "Custom" value (e.g. your own colours) no longer lose focus.
- "What's new" no longer repeats after small fix updates.

## 0.14.3 · 7 Nov 2026

### Fix
- **Shows with several seasons:** you can move along the seasons again (Season 1 → Season 2 → …). Before, each season's episodes pulled you back down into the episode list as soon as they loaded.

Everything new in 0.14.1 is in the 0.14.1 notes.

## 0.14.2 · 7 Nov 2026

### Fix
- **Fire TV / Fire Stick:** pressing OK on live TV to bring up the info bar no longer flashes it and drops you back to the guide. (The release of the OK press was landing on the bar's "TV guide" button.)

Everything new in 0.14.1 is in the 0.14.1 notes.

## 0.14.1 · 7 Nov 2026

### Watching
- **One Play button.** OK plays the best copy: your servers at home first, then your servers away, then Stremio addons, then IPTV. A copy whose file has gone is skipped. **Hold OK on Play** (or on an episode) to choose the source yourself.
- **Shows: Play / Resume S·E** on the title page, carrying on from Plex's on deck, Jellyfin/Emby's next up or your Trakt progress.
- **Pause and rewind live TV** on catch-up channels, using Dispatcharr's catch-up: pause and carry on from that moment, rewind, fast-forward back to live, or press Go live.
- **IPTV Movies / IPTV Shows**: your provider's films and series through Dispatcharr (switch on Provider VOD in Accounts & sources), with sorting, search and the provider's categories.

### Browsing
- **Shorter Movies / Shows menu**: your libraries, with the rows under "Jump to a row". Repeated rows across servers are combined (can be switched off).
- **Settings → Home page & menu**: what the page shows and what the menu lists are now set separately; rename libraries by holding OK.
- **Upcoming episodes** row on Shows.
- **Actor pages** with photo, dates and biography; **Collection** tab on films in a series.
- **Search** puts what you meant first (person, channel or your library), shows where you have each title (Plex, IPTV…) and channel logos.
- Libraries open newest first; Down at the bottom of a page turns the page.
- Guide progress bars: all channels, only what you're watching, or off.

### Setup
- Finds Jellyfin, Emby and Seerr on your network; Jellyfin Quick Connect starts by itself.
- Tick several Plex servers.
- QR codes for Dispatcharr's API key page and for typing your login on your phone.
- Choose your own remote button for search (for remotes like the Shield's).

### Fixes
- Back returns to the poster you opened, and through titles opened from "More like this".
- A film on two Plex servers plays from the one that still has the file.
- Smoother menus and channel-list groups; search no longer shows the guide through it.
- The app checks for updates every 30 minutes and when you come back to it.

## 0.14.0 · 7 Nov 2026

**Install:** Viewarr offers this update by itself (or Settings → Updates → Check for updates). Otherwise download `Viewarr-0.14.0.apk` below and install it over your current version; your settings are kept.

**Title pages, rebuilt**
- Everything on **one screen**: the title's logo, details, description and buttons, then tabs underneath.
- **Ratings:** TMDB, IMDb, Rotten Tomatoes 🍅 (critics) and 🍿 (audience), Metacritic, Letterboxd and Trakt.
  - IMDb and Rotten Tomatoes come from your **Plex** when it has them.
  - For **every** title (owned or not), add a free **MDBList** key: Settings → Accounts & sources → MDBList ratings (type it on the TV or scan the QR code and paste it on your phone).
- **Cast & crew** with photos and characters; OK on a person opens their films and shows.
- **Reviews** from TMDB and Trakt users (most liked first); OK reads the whole review, spoilers stay hidden until you open them.
- **More like this** and **Details** (release date, runtime, studio/network, language, writers, budget, box office).
- Ratings also show on the Movies/Shows header under the title.

**Logos & loading**
- Title **logos** on the Movies/Shows header, title pages, episodes page and the player controls.
- A new **loading screen** while a film or episode starts: its backdrop with the logo gently "breathing".

**Settings, tidied**
- Grouped: Accounts · Look & feel · Remote · Watching · Live TV · This TV.
- **Plex servers** can be switched on or off individually (Accounts & sources → Plex), and **addons** too (Accounts & sources → Stremio addons).
- **Backup & sync** (saving your setup on Dispatcharr) is one entry with its options.

**Fixes**
- Titles that aren't on your Plex no longer show "Play on Plex" (Plex's search also returns online titles; those are now ignored).
- Trailer previews: sound restored for trailers whose audio track isn't labelled.
- Clearer player message when a server lists a title but can't find its file.

## 0.13.0 · 6 Nov 2026

**Install:** Viewarr offers this update by itself (or Settings → Updates → Check for updates). Otherwise download `Viewarr-0.13.0.apk` below and install it over your current version; your settings are kept.

**Your setup, saved on Dispatcharr** *(needs Dispatcharr 0.33 or newer)*
- Viewarr keeps a copy of its setup on your Dispatcharr server, under your user: your services (Plex, Jellyfin, Emby, Seerr, Trakt, TMDB, Stremio addons), home rows and libraries, appearance, playback and remote settings.
- On a new box, or after a reinstall: sign in to Dispatcharr and Viewarr offers to **restore** everything, so there's no setup to repeat.
- Sign-ins are **encrypted on the TV** (with a key made from your Dispatcharr API key) before they're saved, so the server only holds scrambled data for them.
- Settings that depend on the box (surround sound, frame rate, decoding, buffer, audio delay) stay on each TV.
- **Settings → Save settings to Dispatcharr / Include sign-ins / Restore from Dispatcharr.** Choosing "Not now" on the restore card keeps that TV's own settings.

**TV guide**
- The highlighted programme shows **its own picture from your guide data** (e.g. Sky's artwork) when the guide has one, and a TMDB picture otherwise.

**Also**
- The Settings list scrolls (Sign out was pushed off the bottom).

## 0.12.2 · 6 Nov 2026

**Install:** Viewarr offers this update by itself (or Settings → Updates → Check for updates). Otherwise download `Viewarr-0.12.2.apk` below and install it over your current version; your settings are kept.

**New**
- **Recordings in the menu:** hold OK on a recording for Play, Delete (with a "Keep it" confirmation), Stop recording or Cancel recording.

**Fixes for 0.12.1**
- **Crash fixed:** moving around the menu (Movies / Shows / Recordings lists) could close the app.
- The library search box starts empty when you open another library.
- Genres from one library no longer show in another if you switch quickly.
- The channel list's programme panel no longer waits on "Loading…" when the server is slow.

Everything new in 0.12.1 (whole-library browsing with sort, filters and search; several Plex servers; the channel programme panel) is in this version too. See the 0.12.1 notes.

## 0.12.1 · 6 Nov 2026

**Install:** Viewarr offers this update by itself (or Settings → Updates → Check for updates). Otherwise download `Viewarr-0.12.1.apk` below and install it over your current version; your settings are kept.

**Browse your whole library**
- Movies and Shows in the menu now list your **Plex, Jellyfin and Emby libraries** and your **catalogues** (the page's rows). OK or Right opens one.
- A library opens as a grid, **a page at a time** (60 titles), so even very big libraries stay quick. Previous / Next page cards at either end.
- **Sort** by A–Z, date added, release date or rating.
- **Filter** by genre (your server's own genres) or unwatched only.
- **Search the library** from the box at the top, with a mic for voice. Works with the sort and filters.
- Hide or reorder the menu's libraries in **Settings → Home rows → Libraries in the menu**.

**Plex**
- **Several Plex servers:** every server on your Plex account is found and used in rows, Continue watching, search and "In your library". Each title plays from the server it's on.
- Rows from Stremio addons that pass on your own library (e.g. AIOStreams' Plex catalogue) now say where the title really is.

**Live TV**
- In the channel list, press **Right** on a channel to see its programmes: earlier today, now and coming up. Earlier ones play from **catch-up**, now plays live, later ones can be recorded. Left goes back to that channel.

**Fixes**
- Seerr **Request** now works for titles from Stremio addons too.
- "In your library" finds titles with accents (e.g. Filipiñana).
- Debrid / AIOStreams streams no longer get refused mid-film (no second connection for seek previews on addon streams).
- Search: Down goes to the quick picks first, and they're no longer cut off when highlighted.
- Menu: Right on Movies / Shows always opens their list.

## 0.12.0 · 6 Nov 2026

**Install:** Viewarr offers this update by itself (or Settings → Updates → Check for updates). Otherwise download `Viewarr-0.12.0.apk` below and install it over your current version; your settings are kept.

**Search, rebuilt**
- Pops up over any screen — live TV, the guide, films and shows — and Back returns you to where you were. Hold **Menu (≡)**, or pick a button in Settings → Remote & navigation → Search button.
- **In your library:** Plex, Jellyfin and Emby (and your provider's films) in one row; TMDB results you already have get a tick.
- **People:** search an actor or director and open their films and shows.
- **On TV:** finds programmes in the TV guide. On now → watch it; later → record it or set a reminder.
- Quick picks as you type, filters (Films · Shows · Live TV · In my library) and **voice search**.

**Live TV**
- **Reminders:** "Remind me when it starts" (from search or the guide's record options) pops up when the programme begins.
- Small cards when a recording **starts, finishes or fails**.
- The guide shows a **picture of the highlighted programme**; the current-time line is thinner.
- If a channel can't start because your provider's connections are all in use, Viewarr says what's using them (Dispatcharr admins).

**Films & shows**
- **Watch Next:** your Continue watching appears in the Android TV home screen's Watch Next row, with where you stopped. Pick a card to open it in Viewarr.
- **Seerr:** connect it in Accounts & sources, then hold OK on a poster → **Request on Seerr** (hidden for things you already have).
- The menu shows **recently added** titles next to Movies and Shows, and your **latest recordings** next to Recordings (OK plays one).
- Episode lists show a short synopsis for each episode.

**Also**
- A short "What's new" card after each update.

## 0.11.4 · 6 Nov 2026

**Install:** Viewarr offers this update by itself (or Settings → Updates → Check for updates). Otherwise download `Viewarr-0.11.4.apk` below and install it over your current version; your settings are kept.

**Fixed**
- **"Checking your library…" is now quick, however big your library.** Viewarr used to read every Plex / Jellyfin / Emby library to find out whether you own a title, which took minutes on big servers. It now asks your servers about just the title you open: a couple of seconds.
- **Switching live TV to a different server** (another Dispatcharr, another user, or Dispatcharr ↔ IPTV provider) now reloads the channels and guide straight away. Before, the old server's channel list could stay for hours and channels could point at the wrong stream. The guide reloads once after this update.

## 0.11.3 · 6 Nov 2026

**Install:** Viewarr offers this update by itself (or Settings → Updates → Check for updates). Otherwise download `Viewarr-0.11.3.apk` below and install it over your current version; your settings are kept.

**Whole-library browsing removed.** 0.11.2's library grid, menu library list and "All titles" cards were too heavy for TV boxes with big libraries, so they're gone. Films and shows go back to how they were in 0.11.1: rows for discovery, and **Search** finds anything in your Plex / Jellyfin / Emby library.

**Fixed**
- Title pages stuck on "Checking your library…" with big Plex / Jellyfin libraries: the app ran out of memory reading the whole library in one go. It now reads it in small pieces. On very large libraries the first check after opening the app can still take a little while.
- Movies / Shows could lose the highlight while slow rows were still loading, so OK did nothing until you pressed a direction.
- With many Plex libraries, one slow library could stop all of them showing.
- A title that appears twice in a row can no longer break that row.

## 0.11.2 · 6 Nov 2026

**Install:** Viewarr offers this update by itself (or Settings → Updates → Check for updates). Otherwise download `Viewarr-0.11.2.apk` below and install it over your current version; your settings are kept.

**Browse your whole library (Plex, Jellyfin, Emby)**
- **Every library gets its own row** on Movies and Shows, now for Jellyfin and Emby too (before, they had one combined "Recently added" row).
- **All titles:** the last card in a library's row opens the whole library as a poster grid. It loads more as you scroll, and you can sort it A–Z, Recently added, Newest or Top rated.
- **From the menu:** highlight Movies or Shows and press **Right** for a list of your libraries; OK on one opens it.
- **Settings → Home rows:** show, hide and reorder the library rows like any other row, and choose which libraries the menu lists (at the bottom of the page).
- Back in a library: first back to the top, then back to the page's rows.

**Fixed**
- With many Plex libraries, one slow library could stop all of them showing; each library now loads on its own.
- A title that appears twice in a library can no longer break its row.

## 0.11.1 · 6 Nov 2026

**Install:** if you have 0.11.0, Viewarr offers this update by itself (or Settings → Updates → Check for updates). Otherwise download `Viewarr-0.11.1.apk` below and install it over your current version; your settings are kept.

**Films & shows**
- **Smoother playback.** mpv now draws the picture straight to the screen (Settings → Playback → mpv decoding → *Hardware, direct to screen*, the new default). Much lighter on weak boxes.
- **Seek bar with preview pictures.** While a film plays, press **Down**, move with **Left / Right** (faster the longer you hold), **OK** to jump there. Previews come from Plex, Emby or Jellyfin when the server made them; otherwise the box takes them from the film itself.
- **Swap players mid-film.** Hold OK → *Switch to the built-in player* (or *Switch to mpv*) and carry on from the same moment.
- **Jellyfin & Emby playback:** films and episodes, resume where you left off, progress sent back.
- **Match frame rate** now switches the TV before the film starts, so the sound doesn't stall.
- **Surround passthrough** only uses the formats your TV or receiver really accepts, and if the sound still doesn't start, Viewarr decodes it itself after a few seconds instead of freezing.
- **Settings → Home rows:** choose which rows the Movies and Shows pages show, and in what order.
- The Movies / Shows menu entries open their page straight away (the catalogue sub-list is gone).

**Live TV**
- **Source** (Dispatcharr admins): see every stream behind a channel with its provider, and switch Dispatcharr to another one from the remote.
- **Stream info** (Dispatcharr admins): Dispatcharr's live view of the stream: source, provider, resolution, codecs, bitrate, viewers.
- **Settings → Dispatcharr plugins** (admins): switch plugins on and off, press their action buttons, change on/off and choice settings, and install or update plugins from your server's plugin store.
- **TV guide:** progress bar under what's on now, channel names on tiles, softer outlines, and **square corners** (Appearance → TV guide → Corners).
- From the guide, **Left** slides out the channel groups first; Left again brings the main menu.
- The menu and channel list **slide in** from the left.

**Appearance**
- New **OLED black** theme, now the default for new installs (pick it in Appearance → Theme, or *Reset to defaults*).
- **Live TV only:** Appearance → Menu → *Movies & Shows: Off* hides films and shows completely.
- Channel numbers on/off.

**Fixed**
- Holding OK to open a pop-up no longer picks its first option (e.g. "Record from now").
- Audio & subtitles could not be changed in the built-in player; the seek bar couldn't be reached.
- Back in a film: closes the menu, then hides the controls, then stops.
- Plugins page lost focus after an update or install.
- Settings → Appearance → Reset to defaults now resets every Appearance setting.
- Various small stability fixes.

## 0.11.0 · 5 Nov 2026

**Install:** download `Viewarr-0.11.0.apk` below and install it over your current version; your settings are kept. **This is the last update you install by hand**: from now on Viewarr updates itself (see below).

**New**
- **Automatic updates.** Viewarr checks GitHub for new versions and offers them in a small pop-up: press *Update now* and confirm. Settings → Updates has *Check for updates*, and switches for automatic checks and test versions.
- **IPTV provider login (Xtream Codes / XC).** No Dispatcharr? Sign in with the server, username and password from your IPTV provider. You get channels, the TV guide and catch-up, plus your provider's films and series in Movies & Shows. (Recordings still need Dispatcharr.)
- **Much simpler setup.**
  - Viewarr opens on one *Set up Viewarr* page: pick each service, or set everything up from your phone.
  - **Live TV finds your Dispatcharr server by itself** and asks for your API key.
  - TMDB and Stremio steps have QR codes for your phone; Plex is a QR code straight to the Plex sign-in, with no code to type.
- **Hold OK on a poster** for quick options: Play, Details, Watch trailer, Add to watchlist.
- **Settings → Appearance** rebuilt as a full page like the others.

**Remote and navigation**
- **Back** steps out one level at a time; on a main page it asks *"Press Back again to exit"*.
- **Movies & Shows:** Back first jumps to the top row (like Netflix).
- **Films:** Back hides the controls first, then stops (like Plex and Netflix).
- **TV guide:** Left on what's on now opens the menu, starting on the channel groups. Hold Left (or ⏪) for catch-up.
- **Channel list while watching:** browsing the groups shows each group's channels as you go.
- **Menu:** Right does the same as OK on menu items.
- **Recordings:** OK plays a recording; hold OK for options.
- **Everywhere:** the skip time from Settings → Remote is used everywhere, and the Menu button opens audio & subtitles.

**Fixed**
- Signing in could show the sign-in screen again after it had worked, which looked like a wrong password.
- Text boxes trapped the remote and the keyboard covered the screen; the keyboard now opens only when you press OK.
- Choosing a setting made the highlight disappear.
- Presses in the first seconds after opening the app were ignored.
- Clearer sign-in messages: Dispatcharr wants your **website login or API key**, not your XC password.

## 0.10.1 · 5 Nov 2026

**Install:** download `Viewarr-0.10.1.apk` below and install it over your current version; your settings are kept.

**Fixed**
- The app closed when you switched from Movies to Shows (or back) through the menu. This affected every version since 0.8.0, so updating is recommended.

Everything else is the same as 0.10.0: trailers, the mpv player, the new Playback, Addons and Remote & navigation settings pages.

## 0.10.0 · 5 Nov 2026

**Install:** download `Viewarr-0.10.0.apk` below and install it over your current version; your settings are kept. New to Viewarr? Follow the setup guide.

**New**
- **mpv player** (now the default): plays almost any file and soundtrack. The built-in ExoPlayer is still there as a lighter option. Works on 32-bit and 64-bit boxes.
- **Trailers from YouTube:**
  - Stay on a poster in Movies or Shows and its trailer plays in a small window at the top.
  - A new **Trailer** button on each title page plays it full screen.
  - Needs a TMDB key.
- **Settings → Playback**, a full page covering:
  - Player, video (frame-rate matching, picture size, deinterlacing), audio (language, passthrough, stereo downmix, night mode, volume boost, lip sync) and subtitles (language, size, colour, background).
  - Stream quality limits and automatic stream picking, plus next-episode autoplay.
  - Trailer options, and a playback info overlay for troubleshooting.
- **Settings → Addons:** switch each Stremio addon on or off without removing it.
- **Settings → Remote & navigation:** rebuilt as a full page, grouped by where you are, with every option explained.

**Changed**
- The menu now shows the Viewarr logo. "My list" is removed for now, and Search sits at the bottom.
- Title page buttons no longer squash when there are several.

**Fixed**
- The remote stopped responding during film playback.
- After closing a settings page, focus now returns to the item you opened.

## 0.8.0 · 5 Nov 2026

First public beta of **Viewarr**, an Android TV app for live TV from Dispatcharr, plus films and shows from Plex, Stremio addons and Jellyfin/Emby, with TMDB and Trakt.

**Install:** download `Viewarr-0.8.0.apk` below and install it on your Android TV (for example with the Downloader app). Then follow the setup guide. You scan a QR code and set everything up on your phone.

**In this release**
- TV guide with catch-up, channel groups, a channel list while watching, and number-key channel entry
- Recordings on your Dispatcharr server (single programmes and series)
- Movies & Shows rows, detail pages, seasons and episodes, and search
- Plex playback, with automatic conversion by Plex when the TV can't play a file, plus Stremio addon streams
- Watch progress synced to Plex and Trakt
- Phone-based setup, colour themes, and remote-control options

This is an early beta, so please report problems in Issues.
