# Setting up AstOs

After flashing and picking **AstOs** as your home app, the dashboard is
already running. This guide wires up the smart parts.

## 1. Voice control (offline)

Voice works out of the box — tap the terracotta mic button once and allow
microphone access. The Vosk model lives on-device; nothing is uploaded.

Sample commands:

| Say | What happens |
|---|---|
| "what time is it" / "what's the date" | Time / date (spoken + shown) |
| "what's the weather" | Weather card refresh |
| "play music" | Built-in player starts your SD music |
| "open spotify" | Launches Spotify (if installed) |
| "pause" / "next" / "previous" | Media transport (Spotify, local player) |
| "volume up" / "volume 40" | Media volume |
| "set a timer for five minutes" | Timer shown next to the clock, beeps at the end |
| "how much time is left" | Timer status |
| "turn on the desk light" | Home Assistant entity toggle |
| "run scene movie" | Home Assistant scene |
| "brightness 50" | Screen brightness |
| "fullscreen on / off" | Kiosk mode toggle |
| "open settings" | AstOs settings |
| "help" | Command list |

Note: recognition is a compact offline model — speak clearly, one command at
a time. It's a helper, not a chatbot, by design.

## 2. Weather

AstShell Settings → **Weather** → type your city → *Save*. Uses Open-Meteo,
no account, no API key. Refreshes every 30 minutes while the dashboard is
visible.

## 3. Home Assistant (real smart-home control)

1. In Home Assistant (any instance on your network): click your profile →
   **Security** → *Long-Lived Access Tokens* → create one.
2. AstShell Settings → **Home Assistant** → enter
   `http://<your-ha-ip>:8123` and paste the token → *Test & load*.
3. Your lights, switches and scenes appear as tappable chips on the
   dashboard, and voice commands like "turn off the kitchen lights" hit
   the matching entity.

Everything stays on your LAN — no Nabu Casa/cloud required.

## 4. Spotify (works on free accounts)

- **With OpenGapps/Play:** just install Spotify normally and log in.
- **Without Google:** install the Spotify APK from
  [APKMirror](https://www.apkmirror.com/apk/spotify/spotify-music/) (armv7
  build), log in once.
- Start playback in Spotify (or launch it via "open spotify"), and AstOs's
  **MEDIA** card shows now-playing info with play/pause/next controls.

For this to work, enable notification access once:
AstShell Settings → **System** → *Enable media control (notification
access)* → flip **AstOs** on. Free-tier Spotify fully supports
play/pause/skip via media sessions — no Premium needed.

## 5. Local music

Copy MP3s to the `Music` folder on your storage (internal or adopted SD
card), then say "play music". The built-in player shuffles through
everything it finds.

## 6. Your 32 GB SD card — adopt it as internal storage

Android 7.1 supports making an SD card behave like internal storage (apps +
music live there):

1. Insert the SD card.
2. Android Settings → **Storage & USB** → SD card → menu (⋮) →
   **Set up as internal storage** (a.k.a. *Use as internal storage*).
3. Confirm and wait for the migration.

⚠️ The card is then tied to this device/tablet (encrypted). Music copied to
its `Music` folder is picked up by the built-in player automatically.

## 7. Kiosk mode (wall-panel style)

Already on by default: fullscreen, screen stays awake, dashboard reopens at
boot. Tweak in AstShell Settings → Behaviour. Turn any of it off there, or
by voice ("fullscreen off", "show bars").

## 8. Optional: spoken replies

AstOs answers visually by default. For voice replies, install a TTS engine:
F-Droid → **eSpeak NG** (tiny, offline) — then toggle *Spoken replies* in
AstShell settings. (F-Droid APK: [f-droid.org](https://f-droid.org).)

## 9. Root?

Not included. If you want root, flash current Magisk (arm) from TWRP after
first boot — AstOs doesn't fight it.
