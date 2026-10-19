# Flashing AstOs

## Before you start

**You need:**

- Lenovo Tab 3 Essential (TB3-710F) with TWRP flashed — you have this ✅
- Battery charged to at least 50 %
- The `AstOs-1.0-<date>-TB3-710F.zip` from the [Releases](../../releases) page,
  copied to your 32 GB SD card or the tablet's storage
- (Optional) `open_gapps-arm-7.1-nano-<date>.zip` from
  [opengapps.org](https://opengapps.org) (ARM, Android 7.1, **nano**) if you
  want the Play Store

**Strongly recommended:** in TWRP, take a full backup of *Boot*, *System* and
*Data* to your SD card first. If anything goes wrong you can restore and be
back on your old ROM in five minutes.

## Flash steps

1. **Boot into TWRP** (Power + Vol- or however you normally enter recovery).

2. **Back up** — TWRP → Backup → select Boot + System + Data → select your SD
   card as storage → swipe.

3. **Wipe** — Wipe → *Format Data* (this factory-resets internal storage;
   it is required when switching ROMs). Do NOT wipe your SD card if your
   ROM zip lives there — *Format Data* only touches internal storage.

4. **Install** — Install → select `AstOs-1.0-…-TB3-710F.zip` → swipe to flash.

5. *(Optional)* In the same TWRP session, also flash
   `open_gapps-arm-7.1-nano-…zip` if you want Google Play. Skip it for a
   fully clean, cloud-free tablet.

6. **Reboot** → Reboot System. First boot takes 5–10 minutes — the device is
   optimizing apps. Don't panic at the warm boot animation; that's AstOs saying
   hello.

## First boot

1. LineageOS setup: pick Wi-Fi, skip or sign in per your choice.
2. When you press Home the first time, Android asks which home app to use:
   pick **AstOs** and tap **Always**.
3. Follow [SETUP.md](SETUP.md) to configure voice, weather, Home Assistant
   and Spotify.

## Going back

Boot TWRP → Restore → pick your backup → done. (Or flash the stock Lenovo
firmware if you ever need to.)

## Troubleshooting

- **Bootloops:** boot back into TWRP and re-do step 3 (Format Data) then
  re-flash. Dirty flashes over the old ROM are not supported for v1.
- **TWRP asks for a decryption password after flashing:** that's normal with
  a formatted data partition — tap cancel; internal storage is just empty.
- **Voice doesn't hear you:** check that AstShell has microphone permission
  (Android Settings → Apps → AstOs → Permissions).
