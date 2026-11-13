# Controlling Viewarr from outside

Viewarr can be told what to do by other apps on the TV, by ADB from a computer or server, or by home automation
(Home Assistant, a web page on your server, Tasker and so on). Each command is a `viewarr://` link sent as an
Android "view" intent. Every command does what the remote's own button would, so a profile's limits still apply
(for example, a profile that may not record can't record this way either).

Available from **Viewarr 0.27.0**.

## Sending a command with ADB

Connect once (your TV's address, with *Network debugging* / *ADB debugging* switched on in the TV's developer
options):

```sh
adb connect 192.168.1.50:5555
```

Then send any link:

```sh
adb shell am start -a android.intent.action.VIEW -d "viewarr://channel?number=101"
```

Quote the link, as shown, so the shell doesn't split it at `?` or `&`.

## The commands

### Live TV

| Link | What it does |
|---|---|
| `viewarr://channel?uuid=<UUID>` | Plays that channel full screen. The UUID is the Dispatcharr channel UUID (the unambiguous way). |
| `viewarr://channel?number=<number>` | Plays the channel with that number, e.g. `number=101`. |
| `viewarr://channel?step=up` | Next channel (from the one playing, or the first channel if nothing is). |
| `viewarr://channel?step=down` | Previous channel. |
| `viewarr://record?uuid=<UUID>` | Records what's on now on that channel (or `?number=<number>`; with neither, the channel playing). |
| `viewarr://guide` | Opens the TV guide (the channel playing carries on in the top-left window). |

- **Cold start:** if Viewarr isn't running yet, it waits until the guide has its channels, then tunes. Nothing
  sent early is lost.
- **Already watching:** it switches straight to the channel (closing the menu, search, the channel list or a film).
- **Same channel again:** it reconnects the stream, which helps when a picture has frozen.
- **A channel Viewarr doesn't know yet** (added on Dispatcharr since it last fetched the channels): Viewarr fetches
  the channel list again (quick: the channels only, not the whole guide) and then tunes. If it still isn't there,
  it shows *"That channel isn't in Viewarr's guide"*. *(0.27.2 and later.)*

### Everything else

| Link | What it does |
|---|---|
| `viewarr://stop` | Stops whatever is playing: live TV, a film or episode, or music. |
| `viewarr://page?name=movies` | Opens a page: `movies`, `shows`, `music`, `recordings`, `sports` or `guide`. |
| `viewarr://refresh` | Fetches the channel list again (quick; the guide's programmes follow with its next fetch). `viewarr://refresh?guide=1` fetches the whole guide. *(0.27.2 and later.)* |
| `viewarr://search?q=<words>` | Opens search with those words filled in (encode spaces as `%20`). |
| `viewarr://title?tmdb=<id>&type=movie` | Opens a film's page from its TMDB id (`type=show` for a TV show). |

### Play, pause, skip

These are the remote's media buttons, and work as ADB key events:

```sh
adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE
adb shell input keyevent KEYCODE_MEDIA_FAST_FORWARD
adb shell input keyevent KEYCODE_MEDIA_REWIND
adb shell input keyevent KEYCODE_MEDIA_STOP
adb shell input keyevent KEYCODE_MEDIA_NEXT       # music: next song
adb shell input keyevent KEYCODE_MEDIA_PREVIOUS   # music: previous song
```

## Finding a channel's UUID or number

In Dispatcharr, open **Channels**: the number is in the list, and the UUID is in the channel's details (or from
Dispatcharr's API, `GET /api/channels/channels/`, as `uuid`).

## Home Assistant example

With the [Android Debug Bridge](https://www.home-assistant.io/integrations/androidtv/) integration set up for your
TV, a script that puts BBC One on:

```yaml
script:
  tv_bbc_one:
    sequence:
      - action: androidtv.adb_command
        target:
          entity_id: media_player.living_room_tv
        data:
          command: 'am start -a android.intent.action.VIEW -d "viewarr://channel?number=101"'
```

## Not available (on purpose)

Changing settings, accounts or profiles, and deleting anything (recordings and so on), can't be done this way:
any app on the TV can send these links, so they're kept to what the remote can do in the moment.
