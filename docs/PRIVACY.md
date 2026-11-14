# Privacy

Viewarr has no analytics, no ads and no servers of its own. It talks only to the services you connect, plus a few free public services for artwork, sport and intros, listed below.

## What's stored on the TV

- **Your sign-ins** (server addresses, logins, API keys and tokens) are stored on the TV, encrypted with the Android Keystore, and left out of Android backups.
- **Your settings**, for each profile.
- **Watch progress for IPTV films and episodes** (where you stopped, and what you've finished), for each profile, because Dispatcharr and Xtream don't keep it. It stays on the TV: up to 400 entries per profile, the oldest dropped first.
- **Each Live TV source's TV guide** (the three most recent sources keep theirs, so switching is quick). With an IPTV login or playlist, groups you leave out with **Choose groups** aren't stored at all.
- **Artwork** (posters, logos, backdrops) and other caches, and **downloaded updates**.

**Settings → About → Storage** shows what Viewarr keeps and lets you clear artwork and downloaded updates. **Sign out of everything** signs the TV out of all your accounts and sources.

## Your own servers

Viewarr talks to the servers you set up, with the sign-ins you give it:

- **Dispatcharr** (or your IPTV provider, or your M3U and guide links) for channels, the guide, catch-up, recordings and plugins.
- **Plex**, **Jellyfin** and **Emby** for your libraries. Watch progress and watched marks are sent back to them. Signing in to Plex goes through plex.tv, which also gives your Plex watchlist.
- **Navidrome** for music. Plays are counted there (and Navidrome passes them on to ListenBrainz or Last.fm if you've set that up).
- **Seerr**, when you make a request.
- **Addons you add** (for example your own AIOStreams or AIOMetadata) for their rows, title details and streams.

## Outside services, and when they're used

| Service | When | What's sent |
|---|---|---|
| **TMDB** | When you add a TMDB key: Movies & Shows rows, title pages, search, and finding a picture for a programme in the guide when Dispatcharr has none. Images come from TMDB's image server. | Your TMDB key, the titles or programme names being looked up, and title ids. |
| **Trakt** | Only if you sign in to Trakt. | Your watch progress, watched marks, watchlist changes, and requests for your history, recommendations and calendar. |
| **MDBList** | Only if you add an MDBList key. | Your key and the title's id, for its ratings. |
| **ESPN** | Only for **Sports**: when you search in **Settings → Sports**, and to fetch the games of the teams and leagues you follow. | What you type in the search, and the teams and leagues you follow. |
| **TheSportsDB** | For **Live now** and **Sports**: match pictures, and which UK channels show a game the guide doesn't reach yet. | The two team names (and the date). |
| **TheIntroDB / IntroDB** | For **Skip intro** and **Next episode**, when Plex, Jellyfin or Emby haven't marked the intro (addons, IPTV…). Off if **Skip intros** and **Skip credits** are off. | Only the title's id and the episode. |
| **LRCLIB** | Only if you turn on **Settings → Playback → Music → Lyrics from LRCLIB**, for songs your server has no lyrics for. | The song's artist, title, album and length. |
| **YouTube** | For trailers (trailer previews on posters, and the Trailer button). Turn previews off in **Settings → Playback → Trailers**. | Requests for the trailer video. |
| **GitHub** | When Viewarr checks for updates (**Settings → Updates**; automatic checks are at most twice a day and can be turned off). | A request for the list of Viewarr releases. |

Title pages only fetch what you've switched on: anything off in **Settings → Appearance → Title pages** or **Ratings** isn't fetched. Low-memory mode also stops programme pictures and the extra ratings.

## Your setup, saved on Dispatcharr

If you sign in with Dispatcharr, Viewarr can keep a copy of its setup on your Dispatcharr server, for your user: your services, home rows, appearance, playback and remote settings. This is how a new TV, or a reinstall, can restore everything.

- **Sign-ins are encrypted on the TV** before they're saved, with a key made from your Dispatcharr API key. Your Dispatcharr server only ever stores them encrypted.
- Settings that depend on the box (surround sound, frame rate, decoding) stay on each TV.
- Turn it off, or leave sign-ins out, in **Settings → Backup & sync**.

Profiles' settings, locks and limits that an admin sets are kept on that person's Dispatcharr user.

## Setting up on your phone

- The phone setup page runs on the TV itself, only while the setup screen is open.
- It uses a one-time link, and everything you type is encrypted on your phone before it's sent to the TV.
- Your phone needs to be on the same Wi-Fi as the TV. Nothing goes through an outside server.

## The microphone

The microphone is only used while voice search is listening (after you press the mic), and only if you allow it. Speech is turned into text by your TV's own speech service.

## Sharing with friends

**Sign-in details for another house** shows an outside address and that person's API key, for you to send them. Change the key in Dispatcharr at any time to cut them off. Your Trakt and Plex sign-ins are never handed out to other profiles.

## Send a report

**Settings → Troubleshooting → Send a report** is the one thing that sends anything to Viewarr's developer, and only when you press **Send**:

- What's in it: Viewarr's version, the box's model, Android version, memory and screen, which *kinds* of sources you use (Dispatcharr, IPTV login, M3U playlist), and the last part of Viewarr's own log. You see it before sending.
- What's taken out first: passwords, API keys, tokens, server addresses (also server names inside error messages), IP addresses, email addresses, usernames, and the login that IPTV stream addresses carry. The log does include the names of films and episodes you've played recently.
- It's locked on the box with the developer's public key, so only the developer can open it, then uploaded to a free file host ([litterbox.catbox.moe](https://litterbox.catbox.moe)) that deletes it after 72 hours. You get a short code (such as `VR-abc123`) to share. The developer also gets a notification ([ntfy.sh](https://ntfy.sh)) with just that code, the Viewarr version and the box's model, nothing else.
