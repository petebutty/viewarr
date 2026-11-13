# Profiles and sharing

Everyone on the TV can have their own profile. A profile is a user on your Dispatcharr server, so profiles need Dispatcharr (not an IPTV login or a playlist).

## What a profile keeps

Each person's profile has their own:

- settings,
- sign-ins (Plex, Trakt and so on),
- library views and rows.

## Switching profile

- **Settings → Switch profile** shows the profiles on this TV (when there's more than one).
- **Settings → Ask who's watching** (off by default) shows **Who's watching?** each time Viewarr opens. Otherwise Viewarr opens on the last profile used.
- Hold OK on someone in the list to remove them from this TV. Their Dispatcharr user stays.

### Profile PIN

**Settings → Profile PIN** sets an optional PIN of 4 to 8 digits. It's asked for when someone switches to your profile on this TV. Handy on a TV shared with others in your house. Choose **Remove PIN** to take it off.

## Users & profiles (admins)

Dispatcharr admins get **Settings → Users & profiles**. It lists the profiles on this TV, and every user on your Dispatcharr server.

- **Add someone…**: a new user with a name and password, at home or in another house, with what they get and what they can do, in one go.
- **Away addresses…**: how TVs in other houses reach your servers (see below).

Choose a user for:

| Option | What it does |
|---|---|
| **Set up their Viewarr…** | Tick which of your services they get from this TV (Plex, Jellyfin, Emby, addons and so on). Live TV is theirs already, through their own user. |
| **What they can do…** | Allow or lock each of these (see the next table). |
| **Music…** | Their own Navidrome login, so their plays, favourites and playlists are their own. |
| **Sign-in details for another house…** | The outside address and their API key, to send to them. |
| **Add to this TV** | Add them as a profile on this TV. |
| **Live TV limits…** | **Streams at once**, and which channel groups they see (none ticked: all). |
| **Reset password…** | A new password for them. |
| **Delete user…** | Remove their Dispatcharr user. |

Your Trakt and Plex sign-ins are never handed out: they can link their own.

### What they can do

| Switch | What it allows |
|---|---|
| **Request on Seerr** | Hold OK → Request on Seerr |
| **Record programmes** | Recording and series recordings |
| **Catch-up** | Replaying past programmes |
| **IPTV films & series** | Your provider's films and series |
| **Change accounts & sources** | **Settings → Accounts & sources** |
| **Switch Live TV source** | Changing between saved Live TV sources |
| **Change settings** | Appearance, Home page & menu, Remote buttons, Navigation, Playback, Sports and Watchlist |
| **Sign out & backup** | **Sign out of everything** and **Backup & sync** |

Recording, catch-up and IPTV films & series are enforced by Dispatcharr too.

A new Standard user starts with requests, accounts & sources, source switching and signing out locked. Admins can always do everything.

## Friends in another house

You can share your Viewarr with someone who lives elsewhere. Your Dispatcharr login page never needs to be open to the internet.

1. Set each server's outside address once in **Users & profiles → Away addresses…** (for example `tv.example.com`). TVs at home keep using the home address. Away from home, Viewarr switches to the outside address by itself.
2. Choose the person, then **Sign-in details for another house…**. It shows the outside address and that person's API key, with a QR code to copy them to your phone. Send them by text or email.
3. On their TV, they install Viewarr and choose **Someone gave me sign-in details**. They type the address and paste the key (scanning the code with their phone makes pasting easy).
4. The setup you prepared for them, and their limits, arrive by themselves.

To cut someone off, change their API key in Dispatcharr.

## Your setup, saved on Dispatcharr

Viewarr keeps a copy of its setup on your Dispatcharr server, for your user: your services, home rows, appearance, playback and remote settings.

- Sign in to the same Dispatcharr user on another TV, or after a reinstall, and Viewarr offers to restore it all (**Restore your Viewarr setup?**). Choose **Not now** to keep the TV's own settings.
- TVs with it switched on pick up each other's changes when they start.
- Sign-ins are encrypted on the TV before they're saved, so the server never sees them in plain form.
- Settings that depend on the box (surround sound, frame rate, decoding, picture size, buffer) stay on each TV.

Turn it off, or leave sign-ins out, in **Settings → Backup & sync** (**Save settings to Dispatcharr**, **Include sign-ins**). **Restore from Dispatcharr** checks for a saved setup now.

This needs Dispatcharr 0.33 or newer.
