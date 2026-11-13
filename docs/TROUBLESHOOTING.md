# Troubleshooting

Practical fixes for the most common problems. Problems during setup (the phone can't reach the TV, sign-in errors) are covered in the [setup guide](SETUP.md#5-troubleshooting).

## No channels, or the guide is empty

- Use **Settings → Refresh channels & guide** to load the channel list and TV guide again now. The first load can take a minute while it downloads the listings.
- Check **Settings → Live TV → TV guide refresh** isn't set to **Only when I ask** if you expect it to update by itself.
- Check Dispatcharr itself: open its web page and make sure the channels have streams and the guide (EPG) has data for them.
- Check your Dispatcharr user is a **Standard** or **Admin** user. *Streamer* accounts only work in player apps.
- If an admin set **Live TV limits** for your profile, you only see the channel groups they ticked.
- Using an IPTV login or playlist? **Choose groups** may be leaving some groups out.
- **Settings → About** shows each Live TV source and when its guide was last updated.

## A channel won't play, or keeps buffering

- Wait a moment: a channel that stalls is asked for again by itself.
- Try another stream for the channel: **OK** while watching → **Source**. This switches Dispatcharr to another stream or provider. *(Admins.)*
- **Stream info** (in the same controls) shows the source, provider, resolution, codecs, bitrate and viewers. It helps spot a provider that's down or a stream at a much higher bitrate than usual. *(Admins.)*
- Watching several channels in **Multi-view**? Each window counts as a stream on your provider, and you may have run out of connections.
- If you zap a lot, a short **Settings → Remote buttons → Other buttons → Channel change delay** stops passing channels from using up your provider's connections.

## Picture and sound

**Sound drops out or stutters on a film**
Some boxes struggle to decode Dolby TrueHD or DTS-HD. If you have a receiver or soundbar, turn on **Settings → Playback → Surround passthrough**. Otherwise pick an AC3 / 5.1 soundtrack in **Audio & subtitles**, or set **Settings → Playback → Channels** to **Stereo**.

**A film freezes at the start with passthrough on**
Your TV or receiver may not really accept that soundtrack. Viewarr notices and decodes the sound itself after a few seconds. If it keeps happening, turn **Surround passthrough** off.

**A film looks choppy or skips frames**
- Turn on **Settings → Playback → Match frame rate** so the TV switches to the film's own rate (24p films look smoother). Some TVs go black for a second when it switches.
- **Live TV** (or **Both**, with films) does the same for live channels: the TV goes to 50 Hz for UK channels and 59.94 / 60 Hz for American ones, so motion and sport don't judder. It switches a few seconds after a channel starts full screen, once the rate is certain, and only when it differs from what the TV is already on. *(0.28.0 and later.)*
- Switch player from the controls (**OK** while watching), or choose the player in **Settings → Playback → Player**. On weak boxes the built-in player is often the smoothest.
- In mpv, choose **Settings → Playback → mpv decoding → Hardware, direct to screen**, the lightest option. Try software decoding only if the picture glitches or shows colour blocks.

**A file won't play in the built-in player**
Viewarr switches to mpv by itself. You can also switch from the controls, or choose mpv for everything in **Settings → Playback → Player**.

**Sound but no picture**
- Try **Settings → Playback → Video view → Compatible**, for boxes where Standard leaves the picture black.
- From Plex, Viewarr asks Plex to convert the file if the TV can't play it. **Settings → Playback → Plex quality** can force conversion for slow Wi-Fi.
- From an addon, go back and pick a different stream (hold OK on Play). 1080p links and cached debrid links work best.

**Voices out of step with lips**
**Settings → Playback → Lip sync** moves the sound earlier or later (mpv).

**Speech too quiet, explosions too loud**
Try **Night mode**, **Channels** (**Stereo**) or **Volume boost** in **Settings → Playback → Audio**.

**Seeing what's happening**
**Settings → Playback → Show playback info** shows the video and audio format, decoder and dropped frames while playing.

## Viewarr is slow, or closes by itself

On boxes with 1 to 2 GB of memory, turn on **Settings → Troubleshooting → Low-memory mode**. It turns off the heavy extras: box-made seek previews, guide pictures, title logos, wallpaper, title-page artwork, trailer previews, multi-view and the extra ratings. Their settings show as locked until it's off.

Other things that help:

- **Settings → Playback → Seek-bar previews → Server's only** or **Off**.
- **Settings → Playback → Buffer → Small**.
- **Settings → About → Clear artwork** frees space. Posters and logos are fetched again as you browse.
- **Settings → About** shows the box's memory and storage, and warns when it's low on memory.

## Music not showing

- Music has to be turned on: **Settings → Accounts & sources → Music**.
- Check each source is switched on there: Navidrome, and the music libraries on Plex, Jellyfin or Emby.
- If none of your connected servers has a music library, setup says so: add a Navidrome server instead.
- Lyrics, song radio, favourites and artist information need Navidrome. For more lyrics, turn on **Settings → Playback → Music → Lyrics from LRCLIB**.

## The Movies or Shows pages are empty

They need a TMDB key, or at least Plex, Jellyfin, Emby, Trakt or an addon. Add one in **Settings → Accounts & sources**. Also check **Settings → Home page & menu** hasn't got every row switched off.

## Streaming services are missing from the menu

They come from your own addons' rows (for example your own AIOMetadata). They appear once those rows have loaded, and can be hidden in **Edit menus** or **Settings → Menu & navigation → What the menu lists**.

## Something says "admin only"

Source switching, stream info and Dispatcharr plugins are only allowed for Dispatcharr Admin users. Make your user an admin in Dispatcharr (Users → edit user).

## Recording isn't allowed

Your Dispatcharr user needs **DVR access: Manage** (Dispatcharr → Users → edit user), and your profile must be allowed to record (**Users & profiles → What they can do…**).

## Still stuck?

Report it in [GitHub Issues](https://github.com/petebutty/viewarr/issues). Say what you tried, what you saw, your box, and your Viewarr version (**Settings → About**).
