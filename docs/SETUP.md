# Viewarr setup guide

This guide takes you from nothing to watching TV. It takes about 10 minutes, and most of it happens on your phone.

## 1. Before you start

Have these ready. Only the things you want to use are needed.

| For | You need | Where to get it |
|---|---|---|
| **Live TV** | Your Dispatcharr address, e.g. `192.168.1.50:9191`, plus a username and password, **or** an API key | Whoever runs your Dispatcharr server |
| **Films & shows** | A free TMDB API key | [themoviedb.org](https://www.themoviedb.org/signup) → Settings → API → request a **Developer** key |
| **Trakt** (watchlist, history) | A Trakt account | [trakt.tv](https://trakt.tv) |
| **Plex** | Your Plex account | You sign in during setup |
| **Stremio addons** | The addon's manifest link (ends in `manifest.json`) | The addon's configure page, e.g. AIOStreams → *Copy manifest URL* |
| **Jellyfin / Emby** | The server address, plus a login (or Quick Connect for Jellyfin) | Whoever runs the server |

**About your Dispatcharr account:**
- It must be a **Standard** or **Admin** user. *Streamer* accounts only work in player apps.
- To record programmes, the account also needs **DVR access: Manage**, set in Dispatcharr → Users → edit user.

## 2. Install the app

1. On the TV, install **Downloader** (by AFTVnews) from the Play Store / App Store.
2. Open Downloader and enter the download link of the latest APK from the [Releases page](../../../releases).
3. When Android asks, allow Downloader to **install unknown apps**, then press **Install**.
4. Open **Viewarr** from your apps. On Google TV it may be under *Apps → See all*.

## 3. Set up on your phone

When Viewarr opens, it shows a **QR code**.

1. Make sure your phone is on the **same Wi-Fi** as the TV.
2. Open your phone's camera, point it at the code, and tap the link.
3. On your phone, tick what you want to set up, then tap **Continue**. Every step has a **Skip** button, and you can add anything later.

### Live TV
- Enter the Dispatcharr address. If you leave out the port, `9191` is tried.
- Sign in with **Username & password**, or switch to **API key**. Your key is in Dispatcharr → Users → edit your user → **API Key** → copy button.
- **Don't press Regenerate** in Dispatcharr: it replaces the key and signs out other apps using it.

### TMDB
Tap **Open TMDB API page**, copy your **API Key** (or the longer *API Read Access Token*), and paste it in.

### Trakt
Copy the code shown, tap **Open Trakt**, paste the code, and approve Viewarr. Your phone moves on by itself once you've approved it.

### Plex
Tap **Sign in with Plex**, sign in, and allow Viewarr. If you have more than one server, pick the one to use.

### Stremio addons
Paste one or more manifest links, one per line. `stremio://` install links work too.

### Jellyfin / Emby
Enter the server address. For **Jellyfin**, the easiest way is **Quick Connect**: tap *Get a Quick Connect code*, open Jellyfin, and enter the code under *Quick Connect*. For **Emby**, use your username and password.

When you're done, tap **Finish on the TV**. The guide loads, which takes a minute the first time while it downloads the TV listings.

**No phone?** Choose **Use the remote instead** on the TV and type everything with the remote.

## 4. Using Viewarr

- **Menu:** press **Back** on the guide, or Left on a row's first poster. It has TV, Movies, Shows, Search, Recordings and Settings.
- **Watch live:** OK on a programme. While watching, **Up/Down** opens the channel list, and **OK** shows the controls (audio, subtitles, aspect, record).
- **Record:** **hold OK** on a programme in the guide. Choose that programme, every episode, or new episodes only. A red dot marks what's set to record.
- **Films & shows:** open a title. **Play on Plex** plays your own copy; **Search addons** finds streams. For shows, use **Episodes**.
- **Settings:**
  - **Accounts & sources:** add or change anything you set up. *Set up on your phone* brings back the QR code.
  - **Appearance:** colours and menu transparency.
  - **Playback:** which player to use, audio and subtitle language, passthrough to a receiver, and Plex quality.
  - **Remote & navigation:** what the buttons do, and which page the app opens on.

## 5. Troubleshooting

**My phone says it can't reach the TV**
- Check the phone and TV are on the same Wi-Fi, and that the phone isn't on mobile data.
- Guest Wi-Fi networks often block devices from seeing each other: use your main network.
- If the TV setup screen was closed, or Viewarr was restarted, scan the new QR code.

**"Streamer account" or "Wrong username or password" for live TV**
- Viewarr needs a Standard or Admin Dispatcharr user, so ask your server admin.
- Too many wrong attempts lock sign-in for a minute.

**Recording says it isn't allowed**
The Dispatcharr account needs **DVR access: Manage** (Users → edit user).

**Sound drops out or stutters on a film**
Some boxes struggle to decode Dolby TrueHD or DTS-HD. If you have a receiver or soundbar, turn on **Settings → Playback → Surround passthrough**. Otherwise pick the AC3 / 5.1 soundtrack (hold OK while playing), or set **Channels** to Stereo.

**A film plays sound but no picture, or won't play**
- From Plex, Viewarr asks Plex to convert the file if the TV can't play it.
- From an addon, go back and pick a different stream. 1080p links and "cached" debrid links work best.

**The Movies / Shows pages are empty**
They need a TMDB key, or at least Plex, Trakt or an addon. Add one in Settings → Accounts & sources.

**The old icon or banner still shows on the home screen**
Some launchers keep the old image. In Projectivy, long-press the tile and refresh its banner. For the Google TV home screen: Settings → Apps → Android TV Home → Clear cache.

Still stuck? Open an [issue](../../../issues) and say what you tried and what you saw.
