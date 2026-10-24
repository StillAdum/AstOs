# Setting up AstOs 2.4

On first boot the **AstOs setup wizard** ALWAYS opens on a fresh flash.
Setup is **fully phone-driven**: the tablet opens its own setup hotspot,
you connect your phone to it, scan the QR code, and push the entire
configuration (home WiFi credentials, city, AI key, theme) from your
phone's browser. The tablet then joins your WiFi and finishes on its own.

The wizard steps:

1. **Welcome** — the pixel-block AstOs logo types itself in, tap to begin
2. **Pair** — the tablet opens its setup WiFi hotspot
   **`AstSetup_xxxx`** (xxxx = the unique id of this device, e.g.
   `AstSetup_9f3e` — an open network, no password). The screen shows
   three clean cards:
   * **1 · CONNECT** — join `AstSetup_xxxx` on your phone
   * **2 · SCAN** — scan the QR code (or type the `http://…:8420`
     address shown under it) to open the setup page
   * **3 · SEND** — fill the short form on your phone: your home WiFi
     (picked from the tablet's live network scan, password included),
     city, Groq key, Home Assistant, theme — then hit **SEND TO TABLET**
3. **Connect** — the tablet leaves the setup hotspot, joins your home
   WiFi and locates your city (pulses while it works)
4. **Downloads** — "Setup is almost finished, just downloading a few
   more things…" — the offline voice model (~40 MB) downloads HERE,
   never before setup. Retry/skip if you must; AstShell also finishes
   the download automatically when you're on WiFi.
5. **Done** — *AstOs is ready.* Tap **ENTER ASTOS**.

**No phone handy?** The pair screen has a *"no phone? set up wifi on the
tablet"* link: pick your network on the tablet itself (password prompt
included). Everything else — city, Groq key, theme, Home Assistant — can
be changed any time later on the **SETUP** page of the shell.

**Hotspot can't start?** (only happens on side-loaded dev builds) the
wizard falls back to LAN pairing: join the same WiFi as the tablet, then
scan the same QR.

## 1. Music that plays anything — free, no account

Say **"play despacito"**, **"hey spotify play never gonna give you up"**,
or type a song on the MUSIC page. AstOs searches the song like a Discord
music bot does (YouTube Music search on-device) and streams the audio.
You get:

* title, artist, and album cover on the Now Playing screen (optional
  pixelated "pixel kinda" cover mode in SETUP)
* progress bar, play/pause, next/previous, stop, queue
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
and paste it during phone setup (or SETUP page → GROQ AI). Then ask
anything: "hey astos, how do I fix a leaky tap?", "tell me a joke",
"explain black holes briefly". Replies type out letter by letter and
AstOs speaks them if a TTS engine is installed (F-Droid → eSpeak NG).

Without a key, the offline skills still work: weather, news, date/time,
timers, volume, brightness, music, Home Assistant.

## 3. Alexa-style info cards

On the ASSIST page (or by voice): **"what's the weather"**, **"what's
the news"**, **"what's the date"**, **"what time is it"** — warm cards
with pixel-art icons, and the news ticker runs on the home screen.

## 4. Make it yours (customizable background)

SETUP page: cycle **THEME** (12 warm palettes), **BACKGROUND** (pixel
rain, starfield, waves, plasma, scanlines, solid, your own photo),
**TEXT SIZE**, pixel album-art mode, and animation on/off. Everything
applies live.

## 5. Offline voice control

The voice model is NOT inside the ROM: it downloads at the end of setup
("Setup is almost finished, just downloading a few more things…") or
automatically from the home screen once you're on WiFi. Tap the MIC to
retry or kick it off manually.
