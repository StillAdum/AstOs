# Connectivity and PC playback changes

## Root causes addressed

- AstShell lacked Wi-Fi modification, location and tether permissions used by the shared companion server. ROM default runtime grants now cover setup and shell.
- Station scan/join ran against a hotspot radio, before the radio was ready, with stale saved configurations and incomplete SSID/password escaping. Both apps now share one worker-thread station/join path. Correct SSID, network ID and DHCP completion are required before success.
- Phone-posted passwords were trimmed. Whitespace and quoted passwords are now preserved.
- The QR path ignored the actual HTTP server port and always advertised port 80. It now uses the successfully bound port (including 8420 fallback).
- Enabling legacy SoftAP alone did not explicitly request interface tethering/DHCP. Setup now requests tethering and waits for a real AP address; it no longer advertises a guessed address or an unrelated interface.
- BootReceiver started AstShell's pairing endpoints even during first-boot setup, competing with AstSetup. Endpoint ownership now follows setup state; re-running the wizard stops shell endpoints and finishing setup releases wizard endpoints before launching the shell.
- Bluetooth server startup raced asynchronous radio enable. The listener retries; setup requests discoverability for five minutes. Windows discovery checks Bluetooth labels and BTHENUM PNP identity, not unrelated COM equipment.
- Companion lost pairing tokens between launches and reused dead sessions; it now persists encrypted pairing data when OS secure storage is available and guards concurrent scans.
- Desktop plugin/install/update calls had an extra `rpc` argument. Corrected. Only idempotent reads are retried automatically.
- Immersive policy used a space between filters, which is invalid. It now uses `immersive.full=*`; layout flags cover the former bar area and status/quick-settings disable flags are reasserted.
- The two stock-system navigation buttons and base-platform label are removed from AstOS Settings.
- The song parser stripped `play` then rejected its own result, sending ordinary song requests to the AI fallback. Fixed with regression coverage.

## Trusted Java plugins

Plugins now have Rhino Java interop (`Packages`, `java`, `android`) and a `context` object with AstShell's process permissions. This is not a security sandbox and does not grant root or bypass OS SELinux. All installs/updates start disabled; previous installs require fresh trust. Enabling from tablet/desktop presents a trust warning. Manifest permission lists are descriptive, not security boundaries. Exception/watchdog handling remains for stability, but cannot safely terminate blocking native/Java calls. Only install code you trust.

## Automated validation

Commands: `npm ci && npm test` in `companion`; `gradle :app:assembleRelease` in both app projects (JDK 17, Gradle 8.7, SDK/build-tools 34). `electron test/renderer-smoke.js` exercises the real preload and new setup controls on Windows CI. Tests cover protocol/chunking, Java/Node codec interoperability, example voice intents and Spotify bridge auth/origin/lease/ack/error/disconnect behavior.

## Required hardware checks before calling this verified

1. Clean-flash backup-safe test on TB3-710F; no black status region, no shade with edge swipes, including after dialogs, screen wake and SystemUI restart.
2. Pair Windows Bluetooth, verify outgoing serial service discovery, code auth, reconnect and remembered token; repeat over USB.
3. New-tablet setup: scan after hotspot shutdown, rescan, hidden SSID, open/WPA2 network, whitespace/quote/backslash password and invalid password. Use 2.4 GHz WPA2-Personal; this hardware does not support 5 GHz/WPA3-only networks. WEP and enterprise provisioning are not implemented.
4. Phone joins setup AP, gets DHCP lease, loads QR URL on the actual bound port, submits configuration, and tablet completes association/DHCP. Test port-80 bind failure fallback explicitly. Tether API/MTK behavior is hardware-dependent.
5. Test Spicetify installation, song search, next/pause, account restrictions, acknowledgements and disconnect lease. Test Bluetooth, USB and wired speaker outputs physically.
6. Test offline Vosk and opt-in Whisper with a real key, including no network/rate limit, microphone permission denial, tap-to-submit and lifecycle cancellation.
7. Flash only after the full ROM Actions build succeeds; APK compilation alone is not ROM/boot validation.
