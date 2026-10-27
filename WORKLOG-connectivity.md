# AstOS 3.2 implementation worklog

## Scope

Reviewed the supplied previous-agent worklog and repository source at `da9e94feff9ba7ff61ff3360d8aceff79ce00a15`. Implemented desktop setup/pairing, shared Wi-Fi handling, hotspot URL/tethering, trusted Java plugins, kiosk flags, improved optional speech recognition, PC Spotify control and tablet output selection. See `docs/FIX-VALIDATION.md` for root causes and hardware acceptance criteria.

## Validation performed locally

- Both release APKs compile with JDK 17, Gradle 8.7, AGP 8.5.2 and Android SDK 34.
- Desktop protocol/chunking tests pass.
- Bluetooth PNP filtering, serial-open ordering, code-required hello, request/response, disconnect and timeout tests pass.
- Spicetify mocked API tests cover search/play, duplicate suppression, allowlisting and disconnect pause.
- Real Java/Node AstLink codec interoperability tests pass.
- Exact requested PC voice-command routing and ordinary-song regressions pass.
- Spotify HTTP bridge authentication, origin restrictions, tablet lease, acknowledgements, errors and disconnect tests pass.
- Real renderer loaded in headless Chromium with an IPC fixture: existing/new-tablet routing, Wi-Fi row selection/rescan, malicious SSID rendered as text, and Spotify pairing token controls pass.
- JavaScript syntax checks and release shell-script syntax checks pass.
- Native serial module loads on Linux, but port enumeration cannot run in this sandbox because `udevadm` is absent. Windows Bluetooth/USB enumeration is a required hardware test.
- Electron's real-preload smoke test is wired into both Windows build workflows. Local Electron execution is blocked by missing `libgtk-3.so.0`; Chromium renderer verification is not a substitute for the native Windows test.

## Not verified locally

No physical tablet, Bluetooth adapter, Windows PC, Spotify login, speakers or Groq key is connected to this session. Consequently Wi-Fi association, MTK hotspot DHCP/tethering, bar/shade suppression, recognition accuracy, Spotify API playback and actual audio routing are implementation changes requiring hardware acceptance, not claimed verified fixes. The full ROM is built by the repository's existing GitHub Actions workflow after merge; APK success is not a flash/boot test.

## Distribution

Version 3.2 APKs remain unsigned until the existing ROM pipeline re-signs them with its platform key. Do not replace a platform-signed system app with a locally signed APK. The release pipeline includes `astos.js`, PC setup instructions and the validation checklist. All unrestricted plugins must be explicitly re-enabled/trusted.

## 3.3 follow-up: setup recovery, navigation, providers and media sync

Implemented tablet theme visibility/per-activity refresh, persistent Back/Home, explicit Settings Back, standalone touch keyboard, typed prompt and transcript UI, editable/reflowing dashboard cards, provider-native APIs and opt-in fallback, model discovery, metadata sync/remote PC volume, setup discoverability/radio sequencing, companion saved-pairing resume and authoritative setup routing. Added Java mock-server provider contracts, static integration assertions and expanded media/renderer regressions. Updated release versions to ROM/apps 3.3 and companion 1.3.0. Full Actions removes only the already-merged `fix/setup-connectivity-pc-playback` branch and performs clean Windows validation before the ROM job.

See `docs/AI-PROVIDERS.md` for official sources and configuration, and `docs/VALIDATION-3.3.md` for five independent software verification passes and explicit unverified physical acceptance tests. Actual hardware connectivity, microphone accuracy, external audio and live-provider/Spotify accounts cannot be certified by these tests. ROM/installer Actions status must be checked separately after pushing main.


## 3.4 follow-up — physical reports and structural corrections

Added native RFCOMM (32feet) to remove COM-port dependency; direct challenge/secret-authenticated ECDH/GCM LAN transport with discovery, Wi-Fi handoff and background reconnect; tray lifecycle; heartbeat/long-poll media recovery; cross-app Wi-Fi configuration grant and scan/association diagnostics. Corrected the ROM's hidden navigation contradiction (`config_showNavigationBar=false`, `qemu.hw.mainkeys=1`) and supplied AstOS pixel navigation assets. Replaced generated EQ motion with captured local/opt-in PC audio levels; added cover loading, PC-first routing and target selection. Restyled new controls/dialogs/keyboard, removed large prompt utilities from Home, added compact keyboard icon and timed expandable response line, improved six-theme text contrast, disabled window entrances and added persisted custom backdrop controls. See `docs/FOLLOWUP-3.4.md` for exact tests, sources, privacy and unresolved hardware acceptance tests. No zero-defect or physical connectivity claim is made.
