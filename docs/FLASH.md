# Flashing AstOs 2.0

## Before you start

**You need:**

- Lenovo Tab 3 Essential (TB3-710F) with TWRP flashed — you have this ✅
- Battery charged to at least 50 %
- The `AstOs-2.0-<date>-TB3-710F.zip` from the [Releases](../../releases) page,
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

There is no Android/Lineage setup on this tablet anymore. After the warm
boot animation you land directly in the **AstOs Setup** wizard:

1. **Wi-Fi** — pick your network, type the password (or skip).
2. **Ast's brain** — paste a free Groq API key (from
   [console.groq.com/keys](https://console.groq.com/keys)) so Ast can
   answer anything you ask. Optional.
3. **Music** — one tap installs Spotify (free tier), then sign in inside
   Spotify once.
4. **Done** — the AstOs dashboard opens. It's the only home app on the
   device; there is no chooser and no Android setup screen anywhere.

Follow [SETUP.md](SETUP.md) for voice commands, weather, Home Assistant
and the rest.

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
