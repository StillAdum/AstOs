# Setting up AstOs 2.3

On first boot the **AstOs setup wizard** ALWAYS opens on a fresh flash —
a clean ASCII experience (no CyanogenMod screens): animated logo, WiFi,
your city, your free Groq key, phone pairing, your look, and the final
on-device downloads.

The wizard steps:

1. **Welcome** — animated ASCII logo, tap to begin
2. **WiFi** — tap a network, type the password with the AstOs keyboard
3. **City / Groq** — weather location and your free console.groq.com key
4. **Pair with your phone** — scan the QR code (or open the
   `http://<tablet-address>:8420` page on your phone) and push Home
   Assistant URL + token, city, Groq key, theme and background straight
   from the phone
5. **Look** — theme (12 palettes) + animated background, live preview
6. **Downloads** — "Setup is almost finished, just downloading a few
   more things…" — the offline voice model (~40 MB) downloads HERE,
   never before setup. Retry/skip if you must; AstShell also finishes
   the download automatically when you're on WiFi.

## 1. Music that plays anything — free, no account

Say **"play despacito"**, **"hey spotify play never gonna give you up"**,
or type a song on the MUSIC page. AstOs searches the song like a Discord
music bot does (YouTube Music search on-device) and streams the audio.
You get:

* title, artist, and album cover on the Now Playing screen
* progress bar, play/pause, next/previous, stop, queue
* lockscreen media buttons
* fallback chain: full stream → 30-second preview → local Music folder

No Spotify Premium. No login. If YouTube blocks the network you're on, the
song plays as a 30s preview and the status line tells you so.

Voice commands:

| Say | What happens |
|---|---|
| "play <song name>" | Search + stream that song |
| "hey spotify play <song>" | Same thing — "spotify" is the music intent |
| "play <song> by <artist>" | Better-targeted search |
| "play my music" | Shuffles your SD-card Music folder |
| "pause" / "next" / "previous" / "stop" | Playback controls |
| "what's playing" | Says the current track |

## 2. The AI assistant (Groq)

Get a **free API key** at [console.groq.com](https://console.groq.com)
and paste it in the setup wizard (or SETUP page → GROQ AI). Then ask
anything: "hey astos, how do I fix a leaky tap?", "tell me a joke",
"explain black holes briefly". Replies type out letter by letter and
AstOs speaks them if a TTS engine is installed (F-Droid → eSpeak NG).

Without a key, the offline skills still work: weather, news, date/time,
timers, volume, brightness, music, Home Assistant.

## 3. Alexa-style info cards

On the ASSIST page (or by voice): **"what's the weather"**, **"what's
the news"**, **"what's the date"**, **"what time is it"** — ASCII cards
with animated icons, and the news ticker runs on the home screen.

## 4. Make it yours (customizable background)

SETUP page: cycle **THEME** (Amber CRT, Cream Paper, Ember, Mint
Terminal, Sunset, Mono Slate, Cyan Ghost, Violet Dusk), **BACKGROUND**
(matrix rain, starfield, waves, plasma, scanlines, solid, your own
photo), **FONT size**, ASCII album-art mode, and animation on/off.
The setup wizard's LOOK page has the same controls with a live preview.

## 5. Offline voice control

The voice model is NOT inside the ROM: it downloads at the end of setup
("Setup is almost finished, just downloading a few more things…") or
automatically from the home screen once you're on WiFi. Tap the MIC to
retry or kick it off manually.

Tap the mic (HOME or ASSIST) and allow microphone access. The Vosk model
is on-device; recognition never uploads anything. Commands also include:

| Say | What happens |
|---|---|
| "volume up" / "volume 40" | Media volume |
| "set a timer for five minutes" | Timer, spoken "timer done" |
| "turn on the desk light" | Home Assistant toggle |
| "brightness 50" | Screen brightness |
| "fullscreen on / off" | Kiosk mode |
| "open <app>" | Launch an app by name |
| "help" | Command list |

## 6. Home Assistant (optional)

SETUP page → HOME ASSISTANT → enter `http://YOUR-IP:8123` and a
long-lived access token. Lights, switches, and scenes by voice.

## 7. First-boot setup wizard again

SETUP page → **RUN SETUP WIZARD** replays the AstOs welcome experience.
