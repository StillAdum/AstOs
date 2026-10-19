# Setting up AstOs

After flashing, the tablet boots straight into the **AstOs Setup** wizard
(Wi-Fi → Groq key → Spotify → done). No Android setup screens, no home
chooser — AstShell is the one and only home app. This guide covers
 everything after that.

## 1. Voice control (offline + AI)

Voice works out of the box — tap the terracotta mic button once and allow
microphone access. The Vosk model lives on-device; recognition never leaves
the tablet. If you added a Groq API key during setup, anything that isn't a
built-in command goes to **Ast's AI brain** (Groq) and comes back spoken.

Sample commands:

| Say | What happens |
|---|---|
| "hey spotify, play despacito" | **Spotify instantly plays that song** |
| "play <any song name>" | Same — searched and played on Spotify |
| "what time is it" / "what's the date" | Time / date (spoken + shown) |
| "what's the weather" | Weather card refresh |
| "play music" | Built-in player starts your SD music |
| "pause" / "next" / "previous" | Media transport (Spotify, local player) |
| "volume up" / "volume 40" | Media volume |
| "set a timer for five minutes" | Timer shown next to the clock, beeps at the end |
| "how much time is left" | Timer status |
| "turn on the desk light" | Home Assistant entity toggle |
| "run scene movie" | Home Assistant scene |
| "brightness 50" | Screen brightness |
| "fullscreen on / off" | Kiosk mode toggle |
| "open settings" | AstOs settings |
| anything else (with a Groq key) | **Ast answers with AI** — questions, homework, recipes, anything |
| "help" | Command list |

## 2. Ast's AI brain (Groq — free)

With a Groq key, Ast stops being just a command parser and answers anything:
"how long do I cook pasta", "why is the sky blue", "tell me a bedtime story".

1. Create a free key at [console.groq.com/keys](https://console.groq.com/keys)
   (Google login, 30 seconds).
2. Setup wizard already asked for it — or paste it later in AstShell
   Settings → **AI (Groq)** → *Test & save*.

Answers are spoken and shown on the dashboard.

## 3. Weather

AstShell Settings → **Weather** → type your city → *Save*. Uses Open-Meteo,
no account, no API key. Refreshes every 30 minutes while the dashboard is
visible.

## 4. Home Assistant (real smart-home control)

1. In Home Assistant (any instance on your network): click your profile →
   **Security** → *Long-Lived Access Tokens* → create one.
2. AstShell Settings → **Home Assistant** → enter
   `http://<your-ha-ip>:8123` and paste the token → *Test & load*.
3. Your lights, switches and scenes appear as tappable chips on the
   dashboard, and voice commands like "turn off the kitchen lights" hit
   the matching entity.

Everything stays on your LAN — no Nabu Casa/cloud required.

## 5. Spotify (free tier, voice-played)

AstOs has no Play Store by design — Spotify installs in one tap instead:

1. AstOs Setup (first boot) → **Music** → *Install Spotify (free)* — the
   built-in browser opens the download page; tap **Download APK**, then open
   the downloaded file. AstOs has already allowed installs from the browser
   for you, so no security settings screens are needed.
   (Later installs: AstShell Settings → *MUSIC* → *Install Spotify*.)
2. Open Spotify once and sign in with a free account
   (create one at [spotify.com/signup](https://www.spotify.com/signup)).
3. Say **"play <song name>"** — Spotify opens with that song.

**Instant play (optional):** with Spotify developer keys (free), voice
searches resolve the exact track and it starts playing with zero taps:

1. Log in at [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
   → **Create app** → any name → add `http://localhost` as redirect → copy
   the **Client ID** and **Client secret**.
2. AstShell Settings → **Music** → paste both → *Save keys*.

Without keys, "play <song>" still opens Spotify with the song top of the
search results — one tap. Media controls (play/pause/next on the dashboard)
work either way; the needed notification access is enabled automatically
during setup.

## 6. Local music

Copy MP3s to the `Music` folder on your storage (internal or adopted SD
card), then say "play music". The built-in player shuffles through
everything it finds.

## 7. Your 32 GB SD card — adopt it as internal storage

Android 7.1 supports making an SD card behave like internal storage (apps +
music live there):

1. Insert the SD card.
2. Android Settings → **Storage & USB** → SD card → menu (⋮) →
   **Set up as internal storage** (a.k.a. *Use as internal storage*).
3. Confirm and wait for the migration.

⚠️ The card is then tied to this device/tablet (encrypted). Music copied to
its `Music` folder is picked up by the built-in player automatically.

## 8. Kiosk mode (wall-panel style)

Already on by default: fullscreen, screen stays awake, dashboard reopens at
boot. Tweak in AstShell Settings → Behaviour. Turn any of it off there, or
by voice ("fullscreen off", "show bars").

## 9. Optional: spoken replies

AstOs answers visually by default. For voice replies, install a TTS engine:
F-Droid → **eSpeak NG** (tiny, offline) — then toggle *Spoken replies* in
AstShell settings. (F-Droid APK: [f-droid.org](https://f-droid.org).)

## 10. Root?

Not included. If you want root, flash current Magisk (arm) from TWRP after
first boot — AstOs doesn't fight it.
