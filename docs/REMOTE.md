# Remote control

Viewarr is made for a TV remote. This page lists what the buttons do out of the box, and how to change any of them.

## What the buttons do

These are the **Viewarr** buttons (the default). Where the **TiviMate** preset differs, it says so.

| Where | Button | What it does |
|---|---|---|
| TV guide | OK | Watch live, or replay a past programme. On a programme still to come: show its details. |
| TV guide | Hold OK | Record options (this programme, or the series), and more |
| TV guide | Left (tap / hold) | Earlier programmes and catch-up / scroll fast. TiviMate: back to now, then the channel groups. |
| TV guide | Hold Back | The menu, on the channel groups (Left again: the main menu) |
| TV guide | Back | Jumps to now, then asks to exit (press Back again). TiviMate: back to the player. |
| TV guide | Stop | Stop the channel in the preview |
| TV guide | Number keys | Type a channel number |
| Watching live TV | Back | The guide, the channel gliding into its preview (twice quickly: exit) |
| Watching live TV | Up / Down | Channel list (Left from there: channel groups). TiviMate: change channel. |
| Watching live TV | Left / Right | Rewind ("Rewind?" first) / skip ahead. TiviMate: channel list / the channel watched before. |
| Watching live TV | Stop | Stop, back to the guide |
| Watching live TV | Channel + / − | Change channel |
| Watching live TV | OK | The info bar with the controls (TV guide, stop, audio, subtitles, aspect, source, stream info, record…) |
| Watching live TV | Record | Record what's on, straight away |
| Films & shows | Left at the start of a row, or Back | The menu |
| Film player | OK | The info bar with the controls. Left from the first button: the menu, over the film. |
| Film player | Down, then Left / Right, OK | Seek bar with previews: move, then jump there |
| Film player | Down (shows) | The next episodes, with their release dates |
| Film player | Back | Close what's open (controls, details), then keep playing in the window top left while you browse |
| Film player | Stop (or the Stop button) | Stop (your place is saved) |
| Film player | Info | The poster, episode and synopsis |
| Music | Hold Back | Bring Now Playing back |
| Anywhere | Hold Menu (≡) | Search |
| Anywhere, nothing playing | Hold Back | The menu |

Remotes with **Guide** and **Record** buttons open the TV guide and record what's on.

## Changing the buttons

**Settings → Remote buttons** works the way TiviMate does: every button on the remote has its own entry.

### Presets

At the top, a preset sets every button at once. You can then change any you like.

- **Use Viewarr buttons**: live TV: Up / Down the channel list, OK the controls, Left / Right rewind and skip. The guide: Left goes into catch-up and opens the menu at the start; Back jumps to now, then asks to exit.
- **Use TiviMate buttons**: as TiviMate comes. Live TV: Up / Down change channel, OK the controls, Left the channel list, Right the channel before, Menu the main menu. The guide: Left back to now, then the menu; Back back to the player.

### Every button, in every part of Viewarr

Below the presets, each button has a section: **OK**, **Long OK**, **Back**, **Long Back**, **Left**, **Long Left**, **Right**, **Long Right**, **Up**, **Long Up**, **Down**, **Long Down**, **Ch +**, **Ch −**, **Menu**, **Long Menu**, **Play / pause**, **Long Play / pause**, **Stop**, **Rewind**, **Fast forward**, **Record**, **Info**, and the colour buttons **Red**, **Green**, **Yellow** and **Blue**.

Each section lists what the button does in each place, with its own choice:

- **In the TV guide**
- **On live TV**
- **In films & shows**
- **In menus & pages** (and **In the menu** for Back)

Only the places where a button can be changed are listed. For example, OK and the arrows always move around the guide and pages.

Some of the actions you can choose:

| Place | Examples |
|---|---|
| In the TV guide | Watch, or details for later · Watch in the preview · Record options · Record (straight away) · Start over · Jump to now · Page up / down the channels · Earlier / Later programmes · Back / Forward a day · Channel groups (menu) · Main menu |
| On live TV | Open the controls · Info banner · Channel list · Next / Previous channel · **Next / Previous channel in the info bar** · The channel watched before · Skip back / forward · Go live · Start over · Audio & subtitles · Record · Multi-view · Transparency · TV guide |
| In films & shows | Open the controls · Time and title · The seek bar · Next episodes row · Skip back / forward · Audio & subtitles · Details · Media info · Switch player |
| Everywhere | Search · Movies · Shows · Recordings · Music · Settings · Exit Viewarr · Nothing |

**Next / Previous channel in the info bar** lets you browse channels while the picture stays put. See [Live TV](LIVE-TV.md#browsing-channels-in-the-info-bar).

**Reset buttons** puts every button back.

### Other buttons

The **Other buttons** section has:

- **Skip size**: how far each skip goes (catch-up, paused live TV, films and recordings).
- **Channel change delay**: how long a channel has to stay chosen before Viewarr asks for its stream, so zapping quickly doesn't use up your provider's connections. No wait, up to 2 seconds.
- **Search button**: the remote's search button or hold Menu (≡), a colour button, the Info button, **A button you choose** (press it next), or off (search from the menu only).
- **Extra buttons**: give one of these an extra button, such as a colour button or one your remote has spare: **Play / pause**, **Skip back**, **Skip forward**, **Stop**, **Info**, **Audio & subtitles**, **Channel up**, **Channel down**, **TV guide** and **Record**. Press OK on one, then press the button you want. OK, Back, the arrows and the number keys can't be used.

### Button hints

A small pill at the bottom of the screen says what the buttons do where you are. Choose **First few times**, **Always** or **Off** in **Settings → Navigation → Button hints**.

### Navigation

**Settings → Navigation** covers the rest of how you get around: where the app opens, the side menu, and poster rows. See [Settings](SETTINGS.md#navigation).

## Controlling Viewarr from outside

Home automation, a web page on your server, or ADB can change channel, stop, record, search and more with `viewarr://` links, for example:

```sh
adb shell am start -a android.intent.action.VIEW -d "viewarr://channel?number=101"
```

Play / pause, rewind, fast-forward and stop also work as ADB key events (`input keyevent KEYCODE_MEDIA_PLAY_PAUSE`…).

Every command, with examples (and one for Home Assistant), is in [Controlling Viewarr from outside](AUTOMATION.md).
