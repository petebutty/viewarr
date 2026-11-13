# Music

Music is optional. Turn it on in **Settings → Accounts & sources → Music**, or during setup. It can be set up from your phone too, which is easier for typing a login.

## Where your music comes from

- **Navidrome** (or another Subsonic server). Navidrome is found on your network automatically.
- The music libraries on your **Plex**, **Jellyfin** or **Emby** servers.

Viewarr joins them into one library: an album on two servers shows once. Each source can be switched on or off.

Plays count on the server they came from. Navidrome scrobbles, so ListenBrainz and Last.fm get them too if you've set that up there.

> **Lyrics, song radio, favourites and artist information need Navidrome.** With only Plex, Jellyfin or Emby, you can still browse and play everything.

## Finding music

**Music** in the menu has:

- a short **Music home** (with **Shuffle my library** and **Recently added**),
- **Albums A–Z**, **Artists**, **Playlists**, **Genres**, **Favourites** and **Recently added**.

Each has search and voice search. Music also shows up in Viewarr's main search.

### Hold OK for more

Hold OK on a song, album, playlist or artist:

| On | Options |
|---|---|
| A song | **Play next**, **Add to queue**, **Song radio**, **Add to favourites** / **Remove from favourites** |
| An album or playlist | **Play next**, **Add to queue** (albums: favourites too) |
| An artist | **Add to favourites** / **Remove from favourites** |

Favourites (★) sync to Navidrome.

### Artist pages

An artist's page has their biography, **Top songs** and **Similar artists**, and a **Radio** button that plays a mix based on them. An album's page has **Artist ›** to jump to the artist.

## Now Playing

Now Playing shows the artwork, the song, a visualiser, and these buttons:

- **Previous**, **Play / Pause**, **Next**
- **Shuffle** and **Repeat**
- **Big visualiser**: the visualiser fills the screen (press again for less)
- **Queue**: see what's coming up. Play a song next, move it up or down, or remove it.
- **Song radio**: a mix based on the song playing
- **Add to favourites**
- **Sleep timer**: stop after a number of minutes, at the **End of this song**, or the **End of this album**
- **Stop**

**Lyrics** show on Now Playing. Timed lyrics follow the song.

A now-playing bar keeps the music going while you browse the rest of Viewarr. **Hold Back** brings Now Playing back.

### Screensaver

With music playing and no button pressed for a while, Now Playing comes up big with the artwork and lyrics. Any button puts things back.

## Music settings

These are in **Settings → Playback → Music**.

| Setting | Choices | What it does |
|---|---|---|
| **Lyrics** | On, Off | The words on Now Playing (from your Navidrome). |
| **Lyrics from LRCLIB** | Off, On | When your server has no lyrics for a song, look on LRCLIB, the free lyrics library. Only the song's artist, title, album and length are sent. Off unless you turn it on. |
| **Artwork** | Square cover, Spinning vinyl record, Spinning CD, Round cover (turning slowly), Cover with a reflection | How Now Playing shows the album. Records and CDs turn while the music plays. |
| **Visualiser** | Bars, Mirrored bars, Wave, Dots, Off | How the music is drawn on Now Playing and the now-playing bar. |
| **Screensaver** | After 2, 5 or 10 minutes, Off | When the big Now Playing screensaver comes up. |

## Several people

Each profile can have its own Navidrome user, so their plays, favourites and playlists are their own. A Dispatcharr admin sets this in **Settings → Users & profiles** → a person → **Music…**. See [Profiles](PROFILES.md).

## Music not showing?

See [Troubleshooting](TROUBLESHOOTING.md#music-not-showing).
