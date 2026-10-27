# AstOS 3.4 — connectivity and interface follow-up

Install the **3.4 ROM**, **Companion 1.4.0** and this revision's **spicetify/astos.js** together. Mixed versions do not implement the same LAN channel, media fields or background command transport. Back up in TWRP before flashing. The ROM is produced by the normal GitHub Actions build; a successful APK/helper build alone is not a finished ROM.

## Cable-free connection

1. Join the tablet to home Wi-Fi locally during setup. PC and tablet must be on the same LAN; guest/AP isolation or a firewall can block discovery.
2. Companion → Pair → **Wi-Fi**. Leave IP blank for UDP discovery, or enter the tablet's address. For first pairing, enter the six-digit code displayed by AstOS. For a remembered tablet, leave the code blank.
3. After USB/Bluetooth setup, Companion remembers the tablet's Wi-Fi address and attempts a verified LAN handoff automatically. It can discover a changed address and reconnect in the background. USB is optional bootstrap/recovery, not a requirement for normal operation.
4. Close/minimize Companion to hide it to the tray. Use the tray's Open/Reconnect/Quit commands. The background process is still required: it bridges Spotify and the tablet; closing the visible window no longer shuts that bridge down.

LAN uses TCP 58517 and discovery uses UDP 58519. Application frames use per-connection ECDH plus an existing pairing secret and AES-GCM, with sequence checks and bounded frames. The saved token is not sent in plaintext over LAN. The short first-pair code is not a substitute for a trusted local network: pair only on a network you trust and compare the tablet's code yourself. This implementation has automated interoperability tests, not an independent cryptographic audit. No unauthenticated LAN shell/command endpoint or network ADB listener is enabled.

## Bluetooth / Wi-Fi corrections

- Windows uses a native RFCOMM/SPP client first, so auto-detection no longer depends on Windows creating a virtual COM port. Pair the AstOS/AstSetup device in Windows first; keep the tablet's Bluetooth on. Existing outgoing COM selection remains an optional fallback. Close any other program holding the same SPP connection. Automatic native discovery selects paired names containing Ast; a renamed device can still use an explicitly selected outgoing COM port.
- Both privileged apps request `OVERRIDE_WIFI_CONFIG`, allowing shell/setup to update saved networks created by the other app instead of failing Wi-Fi ownership checks.
- Scans wait for the completion broadcast and can use cached results rather than discarding them when a fresh scan cannot start. AP reflection is not mandatory on platforms that lack it.
- Wi-Fi join waits for actual association/DHCP state; it does not treat a second reconnect request as proof of failure. Password bytes are preserved. Supplicant authentication failures and timeout state are reported instead of one generic failure.
- The ROM still requires supported 2.4 GHz WPA/WPA2-Personal hardware/security. This does not create 5 GHz or WPA3 radio support, bypass guest-network isolation, or certify the device's Bluetooth/tethering drivers.

## Interface

- Shared pixel-dialog/keyboard skin uses the selected theme, square borders and bundled Plex Mono fonts. Six themes have measured AA (4.5:1) primary/secondary text contrast on both background and panel surfaces. This is not a claim that every third-party app is themed.
- Manual prompt is a small keyboard-icon button next to the mic, using the same custom button drawing. Card/provider/backdrop/playback utilities live in Settings rather than a second large dock row.
- Responses/errors appear in the existing assistant line. Tap to expand long text. Response text/dialogs clear after approximately 15 seconds; volume/transport acknowledgements no longer produce `PC Spotify: volset` toasts.
- Backdrop presets plus a custom builder persist particle count, speed, pixel size, direction and theme-relative color. Activity/window entrance animations are disabled; backdrop motion is controlled separately.
- A boot property and framework overlay had disabled navigation even when the app requested Back/Home. Both are corrected; navigation uses AstOS pixel icons. Navigation remains available deliberately so calendar/external apps cannot strand the user.

## Music, covers and real levels

Connected PC Spotify is the default destination for music requests unless **Playback target → Tablet** is selected. “Set playback to my PC” and polite song requests are locally routed, not left to an AI reply that only describes an action. A query-less play request needs an existing playable Spotify context. Spotify account/region/search restrictions still apply.

Cover URLs are transferred by the extension and decoded by the tablet. Local resolved tracks use their existing art URLs; media sessions can supply bitmap art. Pixel fallback art is used only when artwork is absent or cannot be loaded.

The generated sine-wave visualizer has been removed. Local AstOS playback uses the actual audio session capture when permitted. For real PC levels, enable **Real PC audio levels** in the Companion dashboard. The bundled Windows helper analyzes the current system output using WASAPI; only nine normalized levels leave the PC, not audio recordings. It measures PC output as a whole, not an isolated Spotify stream. Without valid captured levels, the bars remain flat instead of pretending to be synchronized. Windows device changes/permissions/protected output require physical testing; toggle the analyzer off/on if capture is unavailable after changing an output device.

Spotify commands use network-completion-driven long polling rather than depending solely on background renderer timers. Longer heartbeat/acknowledgement grace avoids declaring a minimized Spotify window disconnected after five seconds. Temporary tablet connection loss does not erase a still-live Spotify session. Real closure/unavailability is still reported; sticky playback pauses after a disconnect grace period rather than at the first transient missed poll.

## Verification

- Static Android/ROM XML and JavaScript/shell/workflow syntax checks.
- Six-theme contrast assertions, dock/dialog integration and navigation boot-property assertions.
- Android release builds (AstShell 3.4/code 8, AstSetup 3.4/code 7).
- Windows helper cross-compilation (.NET 8, NAudio.Wasapi 2.2.1, 32feet 4.2.5).
- Provider mock-server contracts; Java/Node framing and encrypted-frame interoperability; real LAN socket ECDH/GCM/command roundtrip; replay/wrong-key rejection.
- Transport/media/long-poll/command-routing tests; tray lifecycle and background reconnect tests; real companion renderer with fixtures at desktop/narrow widths. Native Windows CI runs the real preload/renderer and builds both installers before the ROM job.

**Not verified here:** a real tablet's radios, local router/hotspot DHCP, microphone, external speakers, real Spotify credentials/minimized-client behavior, Windows Bluetooth adapter, audio capture hardware, Android screenshot/interaction or the final flash. The supplied HTML was inspected as a visual reference; companion renders were inspected, but no physical Android screenshot was available. Do not call this “perfect” or five successful hardware tests.

## Sources

- User-provided `AstOS — Home Assistant UI.html` for pixel design direction.
- Context7: Electron tray/window lifecycle documentation: https://github.com/electron/electron/blob/main/docs/tutorial/tray.md
- Context7: Spicetify Player events: https://github.com/spicetify/docs/blob/main/docs/development/api-wrapper/methods/player.md
- Context7: CosmosAsync: https://github.com/spicetify/docs/blob/main/docs/development/api-wrapper/methods/cosmos-async.md
- Exact API-25 Wi-Fi source (legacy tablet, not modern WifiNetworkSpecifier examples): https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/wifi/java/android/net/wifi/WifiManager.java
- Exact navigation resources: https://android.googlesource.com/platform/frameworks/base/+/android-7.1.2_r39/packages/SystemUI/src/com/android/systemui/statusbar/phone/NavigationBarView.java
- Native RFCOMM without virtual serial ports: https://github.com/inthehand/32feet/wiki/General-Bluetooth-Data-Connections
- WASAPI loopback: https://github.com/naudio/NAudio/blob/master/Docs/WasapiLoopbackCapture.md

Current Android documentation lookup via Context7 was insufficient and direct developer.android.com loads timed out; the legacy Wi-Fi/navigation changes were checked against the API-25 AOSP source instead.
