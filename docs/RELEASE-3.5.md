# AstOS 3.5.1: standalone tablet and Spotify-free PC music

Use **AstOS ROM 3.5.1 (AstShell version code 10) + Companion 1.5.1**. Mixing the old preview Companion/extension with new tablet code is not supported. Back up in TWRP before flashing. System navigation buttons remain disabled; in-app Back and invisible edge gestures remain available. Window entrance animations remain disabled.

## Music repair in 3.5.1

- Plain “play run it back” does not depend on an AI model selecting a tool. The direct parser routes it to the selected player. Case-sensitive song links are preserved rather than lowercased/punctuation-stripped.
- **PC connected:** generic song names use bundled yt-dlp YouTube search with SoundCloud fallback, and a hidden Electron audio player on the PC's default audio output. Spotify, Spicetify, Python and a separately installed media player are not required. Minimize/close Companion to its tray; quitting Companion stops its own player. Explicit Spotify links still use the optional Spotify extension.
- **No PC, or tablet output selected:** on-device NewPipe YouTube/SoundCloud extraction plays full audio through the tablet and its selected Bluetooth/USB/jack output. The PC is not the tablet's resolver. The previous bitrate score could reject every otherwise playable stream (kbps compared with 128000 and an initial -1); it is corrected and regression-tested. AAC HLS fallback is supported as well as progressive streams.
- Search/play/control/state AI tools now work on the standalone tablet too. Playback success is returned after player preparation/start, not merely after searching. Search and source errors remain visible in the prompt/status line. Failed sources are not silently called success or substituted with a 30-second preview.
- Pause and resume are explicit operations instead of both toggling playback. Newer/cancelled requests invalidate stale search callbacks. A failed network stream gets one bounded refresh attempt instead of skipping silently to an unrelated local song.
- PC audio timeline comes from the actual media element while hidden, not Spotify window samples. Live SoundCloud AAC/HLS decoding and advancing timeline were checked locally. YouTube's live request from the test host was rejected by its sign-in/bot check; no credentials/cookies were extracted or challenges bypassed. This is **not** proof that every song/site or tablet hardware playback will work.
- Bundled yt-dlp and Node executables are fetched from official publishers and checksum-verified during CI. HLS.js is version/hash-pinned. No Discord token or supplied .env credentials are used or included. This follows the supplied bot's search/fallback pattern, not its Discord voice transport.

## Earlier 3.5 changes retained

- Playback preview is now production source, not an overlay patch: PC Spotify search, track-name/URI/link playback, playback controls/state, structured results, and native AI tool calls for OpenAI-compatible, Claude and Gemini providers.
- AI tools add/list/cancel persistent one-shot reminders, alarms and timers, and dismiss ringing alerts. Time-sensitive calls include the current timezone; the assistant should ask about ambiguous times. No arbitrary shell/file tool is exposed to the AI.
- Uploaded mods are staged in AstShell private storage with directory creation and write errors checked. A new file picker imports `.astpl` from internal storage or removable SD. Legacy folder scanning requests storage access and no longer deletes the original packages. Invalid archives are rejected in staging before replacing an installed mod.
- Older completed-setup ROMs also get a compatibility upload fallback in Companion: if their shared inbox fails, the authenticated management channel writes the package to AstShell’s own private inbox and verifies SHA-256. It does not require USB, root or disabling storage security.
- Mod reloads now call old unload hooks; dashboard listeners detach/re-attach correctly. Interrupted replacement restores the old package or keeps the completed new one.
- Corrected `Packages.dalvik.system.DexClassLoader` in the native mod installer; compatibility aliases also let old installed scripts refer to `dalvik` and `android` after updating AstShell. No Rhino-generated JavaAdapter receiver is used.
- Each PC has a stable ID and its own saved tablet credential; pairing another PC does not intentionally revoke existing PCs. Companion encrypts its credentials using OS storage. The initial setup credential survives the wizard-to-shell handoff.
- Companion retries remembered Wi-Fi/Bluetooth up to 40 attempts, scheduled four seconds apart, then waits. The tablet sends 40 presence announcements four seconds apart and continues listening afterward; a Companion opened later actively looks for it. Announcements are discovery hints, not authentication. Windows firewall/router client isolation can still block discovery; enter the tablet IP when necessary. No USB fallback for routine reconnects.
- Settings → **Connect / pair new PC** opens a five-minute pairing window and shows the Wi-Fi pairing code. Existing trusted PCs do not need the code. Companion → **Find nearby tablets** initiates native Windows Bluetooth pairing; confirm matching numbers on PC and tablet. Windows may show its security confirmation dialog, but visiting Windows Settings first is not required for the new picker. Never approve mismatching numbers.
- Setup-only gating also checks which app owns the endpoint: other Companion tabs remain locked until the wizard actually hands off to AstShell. The wizard closes old client connections during handoff; the Companion reconnects using the same saved identity.
- Ordinary Spotify commands no longer enable pause-on-disconnect. An optional Spicetify menu item retains explicit opt-in behavior. Background progress is interpolated with a monotonic clock; an estimated cursor is labelled `~` rather than falsely called exact. Search/play failures are returned to the assistant instead of silently reporting success.
- Custom particles use elapsed seconds, not elapsed milliseconds modulo the frame interval (which could make them almost stationary). Size changes rescale particles; hidden/detached views stop their animation callbacks.
- Speech remains offline/private by default. Settings offers opt-in **Whisper large v3 (accuracy)** or **turbo (speed)** using your Groq API key. Cloud capture now uses 16 kHz PCM/WAV, silence endpointing, a 30-second cap and optional live Vosk captions when the offline model is installed. Audio is sent to Groq only after opting in; network and provider usage charges may apply. Temporary recordings are deleted after completion/cancel.

## Install/update

1. Prefer the full ROM to update both apps and system permissions together. Use the correct TB3-710F build and follow FLASH.md; do not flash it on another model.
2. Install Companion 1.5.1 on Windows, replacing older builds. Close/minimize leaves it in the tray; Quit stops the bridge.
3. Optional Spotify integration only: replace the old Spicetify `Extensions/astos.js` with the one from this release. Run `spicetify config extensions astos.js`, then `spicetify apply`. Restart Spotify. In Spotify's menu choose **AstOS: pair PC companion** and paste the Spotify token shown by Companion if it is not already paired. This token is distinct from the tablet's six-digit first-pairing code.
4. Spotify is optional. Song-name PC playback uses Companion audio and the PC default speaker output. Only explicit Spotify track links/Spotify controls need Spotify running with its extension.
5. Optional **AstOS-3.5-App-Repair.astpl** updates AstShell only, with explicit install consent and a signing-certificate check. It is useful for an existing completed setup but is not a substitute for the ROM's updated AstSetup/system permissions. The separate signed APKs are also release assets. A ROM using custom platform signing keys cannot use these default-development-key app updates.
6. If old Companion/ROM versions already overwrote a saved credential, it cannot be recovered automatically: pair that PC once using the new Settings button, then it should be remembered. Do not keep re-enabling the old preview/rollback mod; its APK version is older than 3.5.

## Validation and honest limits

Local checks: both Android release APKs compile; Windows native helper cross-publishes; Node protocol, real-socket encrypted LAN, Java/Node crypto interoperability, lifecycle, 40-attempt reconnection, Spotify bridge and extension fixtures pass. Native AI tool loops and schedule schemas are covered in JVM tests. PCM endpointing and archive extraction run behavioral tests. Renderer tests verify authoritative setup-only gating and safe SSID rendering. Theme contrast and navigation/window-animation invariants are checked separately.

These are not physical device certification. Bluetooth pairing/radio firmware, actual router association/DHCP, removable SD providers, microphone quality, real Spotify account/catalog restrictions, alarm wake-up after reboot, and actual installed-device behavior still require testing on the TB3-710F and Windows PC. Spotify background progress can be an estimate when the client stops publishing real samples; the UI says so. No claim of “zero possible issues” is justified without those tests.

## Music validation

Java bitrate/format regression tests, exact “play run it back” parser tests for both destinations, resolver fallback/URL checks, player acknowledgement/error/cancellation fixtures, and existing lifecycle/reconnect tests pass locally. CI additionally runs a real Electron PCM decoding + hidden-window timeline/pause/resume/volume smoke test. These tests do not replace physical TB3-710F/Windows speaker testing. Site anti-bot checks, regional availability, removed songs and DRM can still prevent playback; source errors are surfaced rather than hidden.

## API references

- Spicetify Player: https://spicetify.app/docs/development/api-wrapper/methods/player.html
- Spicetify native PlayerAPI fallback: https://spicetify.app/docs/development/api-wrapper/methods/platform
- Spicetify authenticated Spotify requests: https://spicetify.app/docs/development/api-wrapper/methods/cosmos-async
- Windows BluetoothAuthenticateDeviceEx: https://learn.microsoft.com/en-us/windows/win32/api/bluetoothapis/nf-bluetoothapis-bluetoothauthenticatedeviceex
- Current 32feet source (API shape checked against source rather than legacy examples): https://github.com/inthehand/32feet
- Groq speech documentation: https://console.groq.com/docs/speech-to-text
- Rhino Java package access: https://rhino.github.io/tutorials/scripting_java/

- yt-dlp options/runtime documentation: https://github.com/yt-dlp/yt-dlp#usage-and-options
- Electron hidden-window/background behavior: https://www.electronjs.org/docs/latest/api/browser-window
- Electron request headers/session interception: https://www.electronjs.org/docs/latest/api/web-request
- HLS.js: https://github.com/video-dev/hls.js
