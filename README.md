<div align="center">

# AstOs

**A warm, voice-first home-assistant ROM for the Lenovo Tab 3 Essential (TB3-710F).**

Built on LineageOS 14.1 · Compiled on GitHub Actions · No cloud required

</div>

---

## What is AstOs?

AstOs turns a 2016 budget tablet into a calm home dashboard — think Alexa Show /
Google Nest Hub energy, but private, offline-capable, and warm. It is a **real
custom ROM**: a full LineageOS 14.1 (Android 7.1.2) build for the TB3-710F with
an AstOs layer baked into `/system`.

```
┌────────────────────────────────────────────────┐
│  09:41            ☀ 21°                       │
│  Saturday, 18 October      Partly · 14°/23°    │
│                                                │
│  ┌──────────────────────────────────────────┐  │
│  │ MEDIA   Playlist #1 — lofi beats         │  │
│  │         ⏮   ▶   ⏭            🔉 🔊       │  │
│  └──────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────┐  │
│  │ HOME   [Living room ●] [Desk ○] [TV ●]   │  │
│  └──────────────────────────────────────────┘  │
│                       ( 🎤 )                   │
└────────────────────────────────────────────────┘
```

## Highlights

| | |
|---|---|
| 🧭 **Phone-driven first-boot setup** | The tablet opens its own hotspot **`AstSetup_xxxx`** — connect your phone and the setup form usually opens BY ITSELF (v3.1 answers the OS captive-portal probes). Or scan the QR, or use the PC app. The tablet then joins your WiFi and finishes on its own. |
| 🖥️ **AstOS Companion (PC app)** | **v3.1**: a Windows installer ships with every release — pair over **Bluetooth or USB** and run the whole setup from your PC, manage the tablet (battery, wifi, toggles, power), install mods, and apply **ROM updates from the TWRP-flashable zips** straight from the app (it stages the recovery flash for you). Styled like the OS, with the same pixel animations. |
| 🧩 **.astpl mods — mod the WHOLE OS** | **v3.1**: JavaScript plugins with a permission system. `ui/ha/media/voice/network/files/settings` grants for normal mods, and the `system` grant for REAL whole-OS access: shell, system properties, secure settings, input injection — sandboxed, watchdogged, opt-in per mod. Ship in `/sdcard/AstOs/plugins` or install from the PC app. |
| 🔒 **Pure kiosk** | Landscape-locked. No lock screen, no notification shade, no nav bar, no edge-swipe surprises — power-on goes straight to the dashboard, and a persistent foreground service holds the shade closed with a 20 s heartbeat. Nobody guesses it's Android. |
| 🖥️ **Pixel-shell UI** | The approved pixel design, 99% point-for-point: VT323 clock + IBM Plex Mono, corner-tagged cards with ink borders and hard shadows, dotted-paper backdrop with drifting motes, segmented bars, EQ visualizer, six themes (Dune/Forest/Rust/Mono/Ember/Pine). 100% native Views — no WebView. ASCII art lives only in the boot animation. |
| 🏷️ **Boot screen never lies again** | **v3.1**: the boot animation caption is stamped from `scripts/VERSION` at every ROM build — the boot screen always shows the version you actually flashed. |
| ⌨️ **AstOs keyboard** | Custom terminal IME — chunky monospace keys, digits row, [DEL]/[HIDE]/[GO], hold-DEL repeat, themed with the active palette. Selected automatically. |
| 🎤 **Offline voice control** | Vosk speech recognition runs entirely on-device. No Google account, no cloud. Model downloads after setup over your WiFi. |
| 🎵 **Discord-bot music** | "Play despacito" — on-device YouTube Music search + free streaming. Local SD music too. |
| 🤖 **Groq AI assistant** | Ask anything; paste a free key in setup or the SETUP page. |
| 🏠 **Home Assistant, for real** | Lights, switches and scenes over the HA REST API — pair it from your phone. |
| 🌤️ **Weather, news & timers** | Open-Meteo (no API key), headlines, voice timers. |
| 🎨 **12 theme palettes + 7 background FX** | Phosphor, Amber CRT, Ember Gold, Ice Cavern, Vaporwave and more — all switchable live. |
| 🔘 **Bigger touch targets** | 2-row tap areas + larger default font (16/20/24 px) — the whole UI is finger-friendly now. |
| 🔌 **Redesigned power + volume** | SHUT DOWN / REBOOT only, chunky warm rows on an espresso dialog (no more white box); warm espresso volume panel with terracotta sliders. |
| 🧠 **1 GB RAM tuning + CI caching** | zram swap + low-RAM profile; ccache-cached builds make rebuilds much faster. |
| 🔏 **GApps-free by default** | Nothing phones home. Flash OpenGApps if you want Play. |

## Device support

| Device | SoC | RAM | Status |
|---|---|---|---|
| Lenovo Tab 3 Essential (TB3-710F, Wi-Fi) | MT8127 | 1 GB | Primary target |

Known device quirks (inherited from the 14.1 device tree): GPS,
video recording and FM radio don't work; camera stills, Wi-Fi,
audio and Bluetooth are fine. For a wall dashboard none of that matters.

## Building

The ROM compiles **100% on GitHub Actions**:

1. Fork or clone this repo.
2. **Actions → Build AstOs ROM → Run workflow**.
3. ~4–6 hours later, grab the ROM zip from **Releases**.

No local build machine needed. Manual build steps are in
[`scripts/`](scripts/) — same scripts the CI runs.

## Flashing

See **[docs/FLASH.md](docs/FLASH.md)** — you need TWRP (which you already
have) and ~10 minutes. Then configure AstShell via
**[docs/SETUP.md](docs/SETUP.md)**.

## Credits & licenses

AstOs stands on giant shoulders:

- **LineageOS** and the CyanogenMod lineage — the base OS.
- **andrew264**, **szazs89**, **EoflaOE** and the Cyanogenmod-for-Lenovo
  community — the TB3-710F device tree, kernel and vendor blobs.
- **Vosk** (alphacephei) — offline speech recognition, Apache-2.0.
- **Open-Meteo** — free weather API.
- **Home Assistant** — the best open-source smart home hub.

AstOs code (AstShell, `vendor/astos`, scripts) is Apache-2.0. The ROM
itself inherits the LineageOS / Android licensing of its sources. No
Google proprietary code is included.
