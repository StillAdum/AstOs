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
| 🎤 **Offline voice control** | Vosk speech recognition runs entirely on-device. No Google account, no cloud. |
| 🏠 **Home Assistant, for real** | Lights, switches and scenes over the HA REST API — local network only. |
| 🎵 **Spotify free + local music** | Transport controls (play/pause/next) work with free Spotify via media sessions. Built-in offline player for SD-card music. |
| 🌤️ **Weather & clock** | Open-Meteo (no API key). |
| ⏱️ **Voice timers** | "Set a timer for five minutes." |
| 🧠 **1 GB RAM tuning** | zram swap + low-RAM profile keep Android 7.1 smooth. |
| 🎨 **Warm, minimal UI** | Cream / terracotta / sage. Deliberately zero blue. |
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
