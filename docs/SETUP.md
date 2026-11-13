# Viewarr setup guide

This guide takes you from nothing to watching TV. It takes about 10 minutes, and the easiest way is to do most of it on your phone.

## 1. Before you start

Have these ready. You only need the things you want to use, and anything you skip can be added later in **Settings → Accounts & sources**.

Viewarr shows the same three groups on its "Before you set up" pages, so you'll know what's coming.

### Live TV (one of these)

| Option | You need | Where to get it |
|---|---|---|
| **Dispatcharr** | Your server's address, e.g. `http://192.168.1.10:9191`, plus your username and password, **or** an API key | Whoever runs your Dispatcharr server |
| **IPTV (Xtream Codes)** | The server address, username and password | Your provider's welcome email |
| **M3U playlist** | The playlist link, and the guide (EPG / XMLTV) link if it has one | Your provider |

**About your Dispatcharr account:**
- It must be a **Standard** or **Admin** user. *Streamer* accounts only work in player apps.
- Use your Dispatcharr website login, not the XC password your IPTV apps use.
- To record programmes, the account also needs **DVR access: Manage**, set in Dispatcharr → Users → edit user.
- Recordings, profiles and Dispatcharr plugins need Dispatcharr. An IPTV login gives channels, the guide, catch-up and the provider's films and series.

### Your libraries (optional)

| For | You need |
|---|---|
| **Plex** | Nothing to type: a code appears, and you approve it on your phone |
| **Jellyfin** | The server address and your login, or Quick Connect (no password) |
| **Emby** | The server address and your login |
| **Navidrome** | For music: the address and your login |

### Extras (optional)

| For | You need | Where to get it |
|---|---|---|
| **Addons** | AIOStreams, AIOMetadata or other Stremio addon links (they end in `manifest.json`): streams and the streaming services' rows | The addon's configure page, e.g. AIOStreams → *Copy manifest URL* |
| **Trakt** | A code to approve: your watch history and watchlist | A [trakt.tv](https://trakt.tv) account |
| **TMDB** | A free API key, for artwork, details and discovery on the Movies & Shows pages | [themoviedb.org](https://www.themoviedb.org/signup) → Settings → API → request a **Developer** key |
| **MDBList** | A free API key, for IMDb, Rotten Tomatoes, Metacritic and Letterboxd ratings on every title | [mdblist.com](https://mdblist.com) → Preferences → API access |
| **Seerr** | Its address and API key, to request films and shows | Seerr → Settings → General |

## 2. Install the app

1. On the TV, install **Downloader** (by AFTVnews) from the Play Store / App Store.
2. Open Downloader and enter the code **7623981**, or `https://github.com/petebutty/viewarr/releases/latest/download/viewarr.apk` (it always gets the newest version; the [Releases page](https://github.com/petebutty/viewarr/releases) has every version).
3. When Android asks, allow Downloader to **install unknown apps**, then press **Install**.
4. Open **Viewarr** from your apps. On Google TV it may be under *Apps → See all*.

## 3. The first run

A new install takes you through four short steps.

### Who's setting it up?

Viewarr asks **How are you setting it up?**

- **I run my own server**: set up Viewarr with your Dispatcharr (found on your network) and your other services. Carry on below.
- **Someone gave me sign-in details**: a friend or family member shares their Viewarr with you. Scan the code with your phone and paste the server address and API key they sent you (or type them on the TV). Their setup for you arrives by itself, so you can skip the rest of this guide. See [Profiles](PROFILES.md#friends-in-another-house).

### The tour

Next, a short tour shows how Viewarr works, with drawn screens of the menu, the TV guide, channel groups, live TV, films and services, and title pages. Press **Right** to go on. You can see it again any time from **Settings → About → Show the tour**.

### Before you set up

Three pages list what setup will ask for, so you can have it to hand:

1. **Live TV**: a Dispatcharr address and login, or an Xtream login, or an M3U playlist and guide link.
2. **Your libraries (optional)**: a Plex code, a Jellyfin or Emby address and login (or Jellyfin Quick Connect), and Navidrome for music.
3. **Extras (optional)**: addon links (e.g. AIOStreams, AIOMetadata), a Trakt code, TMDB and MDBList keys, and Seerr.

They match the tables in [Before you start](#1-before-you-start).

### The setup wizard

Then the setup itself opens, with a **QR code**. You can set up on your phone (easiest) or with the remote.

Setup finds **Dispatcharr, Jellyfin, Emby and Seerr** on your home network by itself, Jellyfin signs in with **Quick Connect** (no password), and you can tick **several Plex servers**.

## 4. Set up on your phone

1. Make sure your phone is on the **same Wi-Fi** as the TV.
2. Open your phone's camera, point it at the code, and tap the link.
3. On your phone, under **What do you want to set up?**, tick what you want, then tap **Continue**. Every step has a **Skip** button, and you can add anything later.

Everything you type is encrypted on your phone before it's sent to the TV.

### Live TV
- **Dispatcharr**: Viewarr looks for it on your network. Otherwise enter the address you open it at in a browser. If you leave out the port, `9191` is tried.
- Sign in with **Username & password**, or switch to **API key**. Your key is in Dispatcharr → Users → edit your user → **API Key** → copy button.
- **Don't press Regenerate** in Dispatcharr: it replaces the key and signs out other apps using it.
- **No Dispatcharr?** Choose **Xtream Codes** and enter your provider's server address, username and password. Or choose **M3U playlist** and paste the **Playlist link**, plus the **TV guide link (XMLTV, optional)**. Leave the guide empty if the playlist includes its own.

### IPTV films & series
With Dispatcharr, you can add your provider's films and series (served through Dispatcharr). An IPTV login includes them already.

### Plex
Tap **Sign in with Plex**, sign in, and allow Viewarr. If you have more than one server, untick any you don't want Viewarr to use.

### Jellyfin / Emby
Enter the server address (if you leave out the port, `8096` is tried). For **Jellyfin**, the easiest way is **Quick Connect**: no password is needed, because you approve Viewarr from Jellyfin, where you're already signed in. For **Emby**, use your username and password (only a sign-in token is kept on the TV).

### Music
Viewarr lists the music libraries on your connected Plex, Jellyfin and Emby servers. You can also add a **Navidrome / Subsonic** server with its address and login.

### Addons
Paste one or more manifest links, one per line. `stremio://` install links work too.

### Trakt
Copy the code shown, tap **Open Trakt**, paste the code, and approve Viewarr. Your phone moves on by itself once you've approved it.

### TMDB
Tap **Open TMDB API page**, copy your **API Key** (or the longer *API Read Access Token*), and paste it in.

### MDBList
Tap the button to open MDBList's preferences, sign in, copy your API key and paste it in.

### Seerr
Paste the address you open Seerr at, and its API key (Seerr → **Settings → General**).

When you're done, tap **Finish on the TV**. The guide loads, which takes a minute the first time while it downloads the TV listings.

### No phone?

Choose **Back: set up with the remote** on the TV. The **Set up Viewarr** screen lists each service: pick one, connect it, and come back for the next. Each step has its own QR code in case typing gets too long: for Dispatcharr, one opens its Users page on your phone (for your API key), another lets you type your login on your phone. When you're done, choose **Start watching**.

You can bring the phone setup back any time from **Settings → Accounts & sources → Set up on your phone**.

## What next

- [Getting around](GETTING-AROUND.md): the menu, the top-left window and the info bar.
- [Live TV](LIVE-TV.md), [Films & shows](FILMS-AND-SHOWS.md) and [Music](MUSIC.md).
- [Remote control](REMOTE.md) and [Settings](SETTINGS.md).
- [Profiles](PROFILES.md): everyone in the house, and friends in another one.

## 5. Troubleshooting

**My phone says it can't reach the TV**
- Check the phone and TV are on the same Wi-Fi, and that the phone isn't on mobile data.
- Guest Wi-Fi networks often block devices from seeing each other: use your main network.
- If the TV setup screen was closed, or Viewarr was restarted, scan the new QR code.

**"Streamer account" or "Wrong username or password" for live TV**
- Viewarr needs a Standard or Admin Dispatcharr user, so ask your server admin.
- Use your Dispatcharr website login, not your IPTV app password.
- Too many wrong attempts lock sign-in for a minute.

**Recording says it isn't allowed**
The Dispatcharr account needs **DVR access: Manage** (Users → edit user).

**Source / Stream info / Plugins say "admin only"**
Dispatcharr only allows these for Admin users. Make your user an admin in Dispatcharr (Users → edit user).

**The Movies / Shows pages are empty**
They need a TMDB key, or at least Plex, Jellyfin, Emby, Trakt or an addon. Add one in **Settings → Accounts & sources**.

**The old icon or banner still shows on the home screen**
Some launchers keep the old image. In Projectivy, long-press the tile and refresh its banner. For the Google TV home screen: Settings → Apps → Android TV Home → Clear cache.

For problems with playback, channels or a slow box, see [Troubleshooting](TROUBLESHOOTING.md).

Still stuck? Open an [issue](https://github.com/petebutty/viewarr/issues) and say what you tried and what you saw.
