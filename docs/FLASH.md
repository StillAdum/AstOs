# Flashing AstOs 2.1

## Before you start

**You need:**

- Lenovo Tab 3 Essential (TB3-710F) with TWRP flashed — you have this ✅
- Battery charged to at least 50 %
- The `AstOs-<version>-<date>-TB3-710F.zip` from the [Releases](../../releases)
  page, copied to your 32 GB SD card or the tablet's storage
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

4. **Install** — Install → select `AstOs-…-TB3-710F.zip` (the newest one from
   the Releases page) → swipe to flash.

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

### TWRP: "Updater process ended with ERROR: N"

Every zip's `update-binary` exits with a code the recovery shows:

- **ERROR 6** — the zip's updater-script failed to parse. **Nothing was
  flashed.** Either the download is corrupted, or the zip is from the broken
  releases (every AstOs zip published on 2026-10-19 between 12:42 and 18:09,
  v1.0-1242 / v2.0-1602 / v2.0-1809 — their device-assert line had unbalanced
  parentheses). Re-download the newest release and verify it (see below).
- **ERROR 7** — the script *ran* and aborted. Read the last line above the
  error:
  - `E3004: This package is for device: …` — zip/device mismatch.
  - `Can't install this package on top of incompatible data…` — you skipped
    step 3 (*Format Data*). Do it and re-flash.
- **ERROR 20/21/22** — TWRP itself could not open or verify the zip file —
  the copy on the tablet is corrupt. Re-copy it.
- For the exact cause, pull the log: `adb pull /tmp/recovery.log` while
  still in TWRP (or TWRP → Advanced → File Manager → `/tmp/recovery.log`).

**Verify the zip before flashing.** Every release page lists the sha256:
`sha256sum AstOs-…zip` on Linux/macOS, `certutil -hashfile … SHA256` on
Windows. Also prefer `adb push` over MTP drag-and-drop — MTP corrupts
large files sometimes.

### Frozen at the Lenovo logo (no bootanimation ever starts)

The bootanimation only plays after the new kernel **and** the Android
framework come up. A logo that never moves means the boot died at the
kernel stage — almost always the **boot partition** didn't get the new
kernel (the zip writes it via `/dev/bootimg`, which not every TWRP build
maps; the install then aborts at the very last step, after the system files
are already in place). TWRP still works and `/system` is populated — only
the kernel needs re-flashing:

1. Download `AstOs-…-TB3-710F-boot.img` from the same release (or extract
   `boot.img` from the ROM zip — identical file). Verify its sha256 against
   the one listed on the release page.
2. Copy it to the tablet (SD card, or `adb push boot.img /sdcard/` in TWRP).
3. TWRP → **Install → Install Image** → select the boot image → target
   partition **Boot** → flash. Reboot System.

If Install Image errors out, write it from TWRP → Advanced → Terminal:
`ls -l /dev/block/platform/*/by-name/` to find the `bootimg` entry, then
`dd if=/sdcard/boot.img of=/dev/block/platform/<...>/by-name/bootimg`.

To check what's actually on the boot partition: in TWRP terminal run
`head -c <size> /dev/bootimg > /sdcard/bootdump.bin` (size = the listed
boot.img size) and compare `sha256sum /sdcard/bootdump.bin` with the
release page. If it already matches and the tablet STILL freezes, capture
evidence for a bug report: `ls /sys/fs/pstore/`,
`cat /sys/fs/pstore/console-ramoops*` (previous boot's kernel log) and
`adb pull /tmp/recovery.log`.

### Other issues

- **Bootloops:** boot back into TWRP and re-do step 3 (Format Data) then
  re-flash. Dirty flashes over the old ROM are not supported for v1.
- **TWRP asks for a decryption password after flashing:** that's normal with
  a formatted data partition — tap cancel; internal storage is just empty.
- **Voice doesn't hear you:** check that AstShell has microphone permission
  (Android Settings → Apps → AstOs → Permissions).
