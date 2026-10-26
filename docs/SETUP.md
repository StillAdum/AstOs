# Setting up AstOs 3.1

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
   * **2 · SCAN** — scan the QR code. The address is a clean
     `http://…` now (no port), and on most phones the setup page opens
     BY ITSELF: join the hotspot and the "sign in to network" sheet pops
     straight into the form. If it doesn't, scan the QR or type the
     address shown under it
   * **3 · SEND** — fill the short form on your phone (styled like the
     tablet): your home WiFi (picked from the tablet's live network scan,
     password included), city, Groq key, Home Assistant, one of the six
     pixel themes and the 24-hour clock toggle — then hit **SEND TO TABLET**
3. **Connect** — the tablet leaves the setup hotspot, joins your home
   WiFi and locates your city (pulses while it works)
4. **Downloads** — "Setup is almost finished, just downloading a few
   more things…" — the offline voice model (~40 MB) downloads HERE,
   never before setup. Retry/skip if you must; AstShell also finishes
   the download automatically when you're on WiFi.
5. **Done** — *AstOs is ready.* Tap **ENTER ASTOS**.

**No phone handy?** The pair screen has a *"no phone? set up wifi here"*
pill — tap it and pick your network on the tablet itself (password prompt
included). **Every button in the wizard is tappable now** (they were
re-built in v3.1 — an inverted hit-rectangle bug made them dead in 3.0).
Everything else — city, Groq key, theme, Home Assistant — can be changed
any time later in **Settings** (System -> Run setup wizard re-runs the
whole wizard too).

**Rather use your PC?** Install the **AstOS Companion** app (Windows
installer from the release) and pick **set up from a PC** — pair over
Bluetooth with the 6-digit code shown on the tablet (or just plug in USB),
then the whole setup form runs on the PC. See COMPANION.md.

**Setup page won't load on the phone?** The phone's browser sometimes
refuses to talk to a network that has no internet. Fix: turn **OFF mobile
data** for a minute (the setup page says so too), or use the PC app
instead. The hotspot name is **AstSetup_xxxx — never in quotes**: if your
phone shows quotes around the name, that's a stale 3.0 build — flash 3.1.

**Hotspot can't start?** (only happens on side-loaded dev builds) the
wizard falls back to LAN pairing: join the same WiFi as the tablet, then
scan the same QR.

## 1. Music that plays anything — free, no account

Say **"play despacito"**, **"hey spotify play never gonna give you up"**,
or tap the voice line under the clock and type a song. AstOs searches the
song like a Discord music bot does (YouTube Music search on-device) and
streams the audio. You get, on the home media card:

* pixel album art (generated per track), title, artist and source tag
* segmented progress bar with times, play/pause, next/previous, volume
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
and paste it during phone setup (or Settings -> Groq AI). Then ask
anything: "hey astos, how do I fix a leaky tap?", "tell me a joke",
"explain black holes briefly". Replies type out letter by letter and
AstOs speaks them if a TTS engine is installed (F-Droid → eSpeak NG).

Without a key, the offline skills still work: weather, news, date/time,
timers, volume, brightness, music, Home Assistant.

## 3. Alexa-style info cards

By voice (or the mic): **"what's the weather"**, **"what's the news"**,
**"what's the date"**, **"what time is it"** — replies type out in the
voice line under the clock, spoken too when a TTS engine is installed
(F-Droid -> eSpeak NG). The home header shows the live pixel weather.

## 4. Make it yours (six pixel themes)

**Settings -> Display -> Theme**: Dune (warm paper default), Forest,
Rust, Mono, Ember and Pine (dark) — color-chip swatches, one tap and the
whole OS re-skins live (home, keyboard, setup wizard, backdrop). Same
for the 24-hour clock and the animate-backdrop (drifting motes) switches.

## 5. Offline voice control

The voice model is NOT inside the ROM: it downloads at the end of setup
("Setup is almost finished, just downloading a few more things…") or
automatically from the home screen once you're on WiFi. Tap the MIC to
retry or kick it off manually.
