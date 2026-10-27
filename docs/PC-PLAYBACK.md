# Spotify playback on your PC

The tablet controls Spotify running on the PC; audio plays through the PC's selected sound device. It does not relay Spotify audio to the tablet or bypass Spotify account, region, or playback restrictions.

## Install

1. Install Spicetify using its official instructions, and ensure Spotify works normally first.
2. Copy `spicetify/astos.js` from this repository into your Spicetify `Extensions` directory (`spicetify config-dir` shows the config directory).
3. Run `spicetify config extensions astos.js` and `spicetify apply`, then restart Spotify.
4. Open AstOS Companion 1.4.0 and pair the tablet over Wi-Fi (or use Bluetooth/USB for initial setup). Closing the Companion window hides it to the tray; use Quit in the tray to stop the bridge. On the dashboard select **show Spotify pairing token**.
5. In Spotify's profile menu, select **AstOS: pair PC companion** and paste that token. Keep both Spotify and Companion open.

The extension contacts only `127.0.0.1:58518`. The companion uses a random token and accepts only Spotify's UI origin. It never opens a LAN control endpoint. Treat the token as a local control credential; do not post it publicly.

## Voice commands

- “Hey Ast, play Never Gonna Give You Up on Spotify in my PC.”
- “Hey Ast, play next song in my PC.”
- “Hey Ast, pause music in my PC.”
- “Hey Ast, play songs through my PC until it is disconnected.”

The last command resumes the PC's current Spotify context and makes subsequent music commands target the PC. Spotify must have an existing playable context for a query-less request. It pauses after the background-safe disconnect grace period (up to roughly 75 seconds after tablet loss while the bridge remains reachable); the tablet returns to local routing after its PC heartbeat expires. An explicit PC command fails visibly if the extension is unavailable rather than silently playing on the tablet.

Direct commands use the local parser. Conversational provider replies do not execute arbitrary code; music/playback requests are handled by the local command router. Playback requests have IDs, acknowledgements, timeouts, bounded queues and per-process duplicate suppression. Do not restart the extension during an outstanding command.

## Tablet speakers

In AstOS Settings choose **Playback device**. Connect/pair your Bluetooth A2DP speaker, attach USB audio, or plug in the audio jack first, then choose the enumerated output. Selection applies to AstOS MusicEngine playback, including the current player; it does not change every third-party app's system route. Unavailable devices fall back to automatic routing. Bluetooth capability and USB DAC support depend on the tablet's drivers. PC Spotify uses the PC's audio settings, not this selector.

## Speech recognition

Settings → **Speech recognition** offers:

- **Offline Vosk:** default, continuous local recognition, downloaded small model.
- **Groq Whisper large v3 turbo:** opt-in; records up to eight seconds, sends the recording to Groq using your saved key, and deletes the temporary file afterward. Tap again to submit sooner. Requires internet and may incur provider usage. It is not always-on wake-word detection.

No larger offline model is forced onto the 1 GB tablet. Real-device recognition quality and Spotify/Spicetify API compatibility still require testing.

## 3.4 update

Update the ROM, Companion and extension together. See [FOLLOWUP-3.4.md](FOLLOWUP-3.4.md) for cable-free pairing, tray operation, artwork, real output levels, privacy and hardware-validation limits.
