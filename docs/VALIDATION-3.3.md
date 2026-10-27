# AstOS 3.3 verification and remaining acceptance tests

This release addresses reports from the prior worklog and the subsequent tablet/companion feedback. Source fixes are not proof of real-radio, microphone or Spotify-device behavior.

## Five distinct verification passes

1. **Static integration audit** — `python tests/static33.py`, Android XML parsing, JavaScript syntax and shell syntax. Checks all six theme definitions, vertical theme rows, persistent system Back/Home navigation, typed-input/card/provider/transcript wiring, setup keyboard/discoverability and PC metadata hooks.
2. **AI protocol contracts** — `bash scripts/test-ai.sh`: seven provider presets, native Anthropic/Gemini headers and payloads, compatible requests, model discovery filtering, 404 feedback/redaction, ordered opt-in fallback, non-fallback on empty/safety responses, HTTPS/redirect guards. Mock loopback services; no live-provider credentials used.
3. **Protocol/media/session regression** — companion `npm test` covers Java/Node framing, USB/Bluetooth session mocks, PC command acknowledgement/deduplication, authenticated metadata publication, sanitization, Spotify play/search/volume/state and disconnect pause. Local Chromium runs the real renderer with IPC fixtures for saved resume, authoritative setup state, pairing tab guards, explicit disconnect, hostile SSID text, settings retained after scan failure and Spotify token UI.
4. **Final release compilation** — both AstShell and AstSetup `assembleRelease` using Java 17, Gradle 8.7, SDK 34. Packaged manifests inspected for updated versions and provider activity. This checks compilation/resources/packaging, not on-device rendering.
5. **Independent GitHub Actions gate** — both Windows workflows run clean dependency installation, the protocol/provider tests and a real Electron preload/renderer test before producing NSIS/portable installers. The full workflow then builds the ROM. This pass is pending until the actual Actions jobs complete; inspect their status rather than treating dispatch as success.

## What changed

- Six tablet theme options no longer share one over-wide horizontal container; Home tracks its own rendered theme rather than another activity's global theme snapshot.
- Status-only immersive mode preserves system Back/Home across Settings, calendar and external apps. Settings also has an explicit Back button. The shade-disable ROM policy remains. A visible bottom navigation area is intentional, not the old blank status-bar strip.
- Setup starts the pairing service on welcome, offers a built-in touch keyboard, makes Bluetooth discoverability user-requestable when privileged calls fail, and avoids late Wi-Fi scans switching the radio away from hotspot mode.
- Companion uses the device's `setupDone`, resumes remembered pairing, prevents navigation back into Welcome/Pair while connected and supports an outgoing Bluetooth COM port override.
- Tablet PC media includes title, artist, progress, playing state and remote volume. It requires the updated companion and updated Spicetify extension together. This bridge covers Spotify, not arbitrary Windows audio sessions.
- Dashboard chooser persists card visibility; remaining content cards use weighted responsive rows. Clock/weather occupy a separate responsive header.
- Provider-native text requests, model discovery, explicit fallback, visible offline transcript, clearer microphone/model errors and typed prompts.

## Physical acceptance checklist — NOT yet passed here

- Flash on TB3-710F after TWRP backup; test six themes and return from each built-in and external app with Back/Home.
- Confirm no top black inset and that the notification shade cannot be pulled; repeat after reboot, app switching and rotation.
- Run new and existing setup over USB and Windows outgoing Bluetooth COM. Exercise fresh code, saved token, stale token, adapter off/on, dropped connection, cancellation and reboot.
- Check local touch keyboard with spaces/symbols; connect to 2.4 GHz WPA2 with correct/incorrect credentials and hidden SSIDs. This tablet does not gain 5 GHz/WPA3 support from software fixes.
- Start hotspot, verify client DHCP address/gateway and the exact QR URL/port, then switch to home Wi-Fi. Test multiple client devices; tethering remains ROM/driver-dependent.
- Install companion 1.3.0 plus the release's `spicetify/astos.js`, pair the token and play Spotify directly on the PC. Check metadata, play/pause/previous/next/volume, spoken PC commands and sticky disconnect behavior. Spotify/account API behavior still requires a real session.
- Test Bluetooth/USB/jack audio routes with actual speakers. Existing output selector applies to AstOS MusicEngine playback; system-wide third-party routing depends on the OS/device.
- Verify microphone permission, Vosk model download, live partial/final transcript and optional Whisper with a real Groq key. Check model discovery/test using real accounts for all intended providers. Live cloud transcription and paid-provider billing are not validated by mock tests.
- Toggle every dashboard card, including all-off/single-card cases and plugins; confirm reflow on the tablet's actual density/orientation.

No hardware access or live AI/Spotify credentials were available during automated validation. Do not interpret five software passes as five successful physical tablet tests.
