# Home Assistant

Viewarr can work with [Home Assistant](https://www.home-assistant.io/), if you use it. It's optional: nothing
happens and nothing shows until you set it up. Viewarr talks straight to your Home Assistant; nothing goes through
Dispatcharr or the Viewarr plugin, and nothing needs installing in Home Assistant.

It does three things:

1. **Tells Home Assistant what's playing**, so your lights (or anything else) can follow what you watch.
2. **Shows pop-ups Home Assistant sends**: a message, a picture or a live camera, over whatever's playing.
3. **Takes commands from Home Assistant**: change channel, pause, play, stop, open the guide. With Alexa or Google
   linked to Home Assistant, that's voice control too.

## Setting it up

1. In Home Assistant, open your profile → **Security** → **Long-lived access tokens** → **Create token**. Copy it.
2. In Viewarr: **Settings → This TV → Home Assistant**.
3. **Address**: your Home Assistant's address with its port, e.g. `http://192.168.1.10:8123`.
4. **Access token**: paste or type the token. Viewarr tests the connection straight away and shows
   *Connected to Home Assistant 2026.x*, or what went wrong.
5. **Send what's playing** and **Pop-ups from Home Assistant** can each be switched off.

The token stays on that TV: it isn't in settings codes, backups or reports. **Forget Home Assistant** removes it.
Each TV is set up on its own, and events carry the TV's name (shown on the same page) so automations can tell
TVs apart.

Typing a long token with a remote is slow: a phone keyboard app for Android TV, or `adb shell input text "<token>"`
with the box open, is quicker.

## 1. What's playing: `viewarr_playback`

Viewarr fires the event `viewarr_playback` when something starts, pauses, resumes or stops, and when you change
channel while watching live TV full screen. A pause is only sent once it's lasted 2 seconds (so buffering doesn't
count).

| Field | Example | Notes |
|---|---|---|
| `tv` | `Living room` | The TV's name in Android TV's settings |
| `state` | `playing`, `paused`, `stopped` | |
| `kind` | `film`, `episode`, `recording`, `live`, `music` | Trailers aren't sent |
| `title` | `Shetland` | |
| `show`, `season`, `episode` | `Shetland`, `11`, `2` | Episodes |
| `channel` | `BBC One` | Live TV |
| `artist`, `album` | | Music |
| `full_screen` | `true` | `false` while a film is in the corner |
| `position`, `duration` | seconds | When known |

Example: movie lighting in the evening, back to normal on pause (with a wait, so the next episode starting doesn't
flash the lights).

```yaml
automation:
  - alias: "Viewarr: movie lighting"
    mode: restart            # a new event cancels a waiting "lights back"
    triggers:
      - trigger: event
        event_type: viewarr_playback
        event_data: { tv: "Living room" }
    actions:
      - choose:
          - conditions:
              - "{{ trigger.event.data.state == 'playing' and trigger.event.data.kind in ['film','episode','recording','live'] }}"
              - condition: state
                entity_id: sun.sun
                state: below_horizon
            sequence:
              - delay: 5
              - action: scene.turn_on
                target: { entity_id: scene.movie_time }
          - conditions: "{{ trigger.event.data.state in ['paused','stopped'] }}"
            sequence:
              - delay: "{{ 15 if trigger.event.data.state == 'paused' else 60 }}"
              - action: scene.turn_on
                target: { entity_id: scene.living_room_normal }
```

## 2. Pop-ups: `viewarr_notify`

While Viewarr is open, fire the event `viewarr_notify` and a card appears in the corner, over films and full-screen
live TV too. It never takes the remote, and closes on its own.

| Field | What it does |
|---|---|
| `title`, `message` | The card's text |
| `image` | A picture: a full address, or a Home Assistant path such as `/local/door.jpg` |
| `camera` | A camera entity (`camera.front_door`): shown live, refreshed about once a second |
| `duration` | Seconds on screen (default 10, up to 120) |
| `position` | `top_right` (default), `top_left`, `bottom_right`, `bottom_left`, `centre` |
| `tv` | Only the TV with that name shows it |

```yaml
action: event.fire           # or, in a script: - event: viewarr_notify
data:
  event_type: viewarr_notify
  event_data:
    title: "Dryer"
    message: "The dryer's finished"
    duration: 15
```

## 3. Commands: `viewarr_command`

Fire `viewarr_command` and Viewarr does what the matching [`viewarr://` link](AUTOMATION.md) does, as if pressed on
the remote:

| `command` | Other fields | What it does |
|---|---|---|
| `channel` | `number` or `uuid` | Plays that channel full screen |
| `channel_up`, `channel_down` | | Next / previous channel |
| `pause`, `play`, `play_pause` | | As the remote's media buttons |
| `stop` | | Stops whatever's playing |
| `guide` | | Opens the TV guide |
| `page` | `name`: `movies`, `shows`, `music`, `recordings`, `sports` | Opens a page |
| `record` | `number` or `uuid` (else the channel playing) | Records what's on now |
| `search` | `query` | Opens search with those words |
| `refresh` | | Fetches the channel list again |
| `key` | `name`: `up`, `down`, `left`, `right`, `ok`, `back`, `menu`, `info`, `rewind`, `ffwd`, `record`, `red`, `green`, `yellow`, `blue`, `chup`, `chdown`, `0`–`9` | Presses that remote button *(0.29.8 and later)*: build a full remote on a dashboard |
| `link` | `link`: any `viewarr://` link | |

Add `tv` to send it to one TV only. A script per command makes dashboard buttons and voice commands easy:

```yaml
script:
  tv_bbc_one:
    alias: "BBC One"
    sequence:
      - event: viewarr_command
        event_data: { tv: "Living room", command: channel, number: 101 }
  tv_pause:
    alias: "TV pause"
    sequence:
      - event: viewarr_command
        event_data: { tv: "Living room", command: pause }
```

**Voice:** expose those scripts to Alexa or Google in Home Assistant (Settings → Voice assistants → Expose), then
"Alexa, turn on BBC One" or "Alexa, turn on TV pause". An Alexa routine can give them any phrase you like.
