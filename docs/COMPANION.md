# AstOS Companion — the PC app

AstOS Companion pairs your **Windows PC** with your AstOS tablet over
**Bluetooth or USB**, and from there everything is one click away:
first-boot setup, day-to-day management, **.astpl mods**, and full **ROM
updates** using the same TWRP-flashable zips.

Two transports, one protocol (AstLink):

| transport | pairing | speed | use it for |
|-----------|---------|-------|------------|
| **USB** (adb, bundled) | plug the cable — the app finds the tablet | ~8-12 MB/s | ROM updates, big files |
| **Bluetooth** (serial SPP) | pair the tablet once in Windows Bluetooth settings, then enter the 6-digit code shown on the tablet | ~20-50 KB/s | setup, settings, mods, small transfers |

Everything is local. No accounts, no cloud, no data leaves your network.

## Install

1. Download **`AstOS-Companion-Setup-1.1.0.exe`** from the newest
   [AstOs release](../../releases) (a portable `.exe` ships too).
2. Run the installer. Windows may warn about an unsigned app —
   "More info -> Run anyway" (self-built hobby software, no EV cert).
3. Launch **AstOS Companion**.

## Pair the tablet

### During first-boot setup (the wizard)

The wizard turns the tablet's Bluetooth on by itself and shows a 6-digit
code on the PAIR step (and under *"set up from a PC"*):

1. In the app open the **pair** tab, press **scan bluetooth**.
2. Type the code the tablet shows when asked.
3. The dashboard opens — you're paired. The tablet writes the token, and
   this PC is remembered; no code again unless you wipe the tablet.

Tip: **USB needs no code at all** — plug in the cable during setup and
press **scan usb**.

### After setup (normal life)

The tablet listens on Bluetooth + USB whenever it's on. If the tablet's
Bluetooth is off, enable it in **AstOs Settings → Bluetooth** — or just
plug in USB.

**Bluetooth pairing (one time, in Windows):** Windows Settings →
Bluetooth & devices → Add device → the tablet appears as **AstSetup_xxxx**
during setup, or under its device name afterwards. Pair it — Windows
creates the serial COM port the app probes. Then press **scan bluetooth**
in the app.

## The screens

* **welcome** — the short tour.
* **pair** — find + authenticate the tablet (USB or Bluetooth, sonar
  animation included).
* **dashboard** — name, AstOS version, battery (+charging), wifi,
  free storage, uptime. Refresh any time.
* **setup** — the same form the phone page shows, but on your PC:
  pick the home wifi from the tablet's own scan list, type the password,
  set city / Groq key / Home Assistant / theme / clock / kiosk toggles,
  then **send to tablet + join wifi**. The tablet drops its setup hotspot
  and joins your network, and the wizard finishes on the glass.
* **mods** — the .astpl plugin manager: install mods from your PC,
  enable/disable/remove them on the tablet. See PLUGINS.md.
* **update** — ROM updates: pick the AstOs `.zip` (the TWRP-flashable one
  from the release), press **send + flash via recovery**. The app:
  1. transfers the zip to `/sdcard/AstOs/updates/` (progress bar, resume
     if interrupted, sha256 checked end-to-end),
  2. stages `/cache/recovery/openrecoveryscript` on the tablet,
  3. reboots the tablet into TWRP, which flashes the ROM and boots the
     new AstOs — the exact zip you would have flashed by hand, no SD-card
     juggling. **Always take a TWRP backup first.**
  USB takes a minute or two for the full ROM. Bluetooth works too (it
  resumes), but expect hours — use a cable when you can.
* **tools** — power actions (reboot / recovery / power off / wake the
  screen), the kiosk/screen/voice/boot toggles, and the **system
  console** — a shell into the tablet (getprop, pm list, input keyevent,
  am start …) that uses the platform-signed system grant. Needs
  **Settings → Mods → system mods ON** on the tablet.

## Troubleshooting

* **scan bluetooth finds nothing** — is the tablet's Bluetooth on? Is the
  tablet paired in *Windows* Bluetooth settings (that's what creates the
  COM port)? Re-pair, then scan again.
* **scan usb finds nothing** — is USB debugging on? (AstOS ships it on by
  default; if it was turned off: AstOs Settings → Android settings →
  developer options → USB debugging). Also accept the debugging prompt on
  the tablet if it asks.
* **wrong code** — the code rotates after every successful pairing and is
  shown on the tablet: AstOs Settings → Mods shows the current one, and
  during setup it's on the PAIR screen.
* **connection lost mid-transfer** — re-pair and press send again: the
  transfer resumes from where it stopped.
