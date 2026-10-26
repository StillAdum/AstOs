# AstOS mods — the .astpl plugin system

`.astpl` files are how you **mod the whole OS**: JavaScript mods with a
permission system, sandboxed and crash-isolated, that can add cards to
the home dashboard, react to your voice, control Home Assistant, and —
with the `system` grant — run shell commands, set system properties,
write secure settings and inject input. That's real whole-OS access on a
platform-signed ROM.

## Install

Three ways:

* **From the PC app**: Companion → **mods** → *install .astpl from pc…*
* **From the tablet**: drop `something.astpl` into
  `/sdcard/AstOs/plugins/`, then **AstOs Settings → Mods → scan + install**
* **Sideload**: push the file anywhere and use the Companion's transfer.

Manage them in **AstOs Settings → Mods**: enable/disable each mod with
the pixel switch, remove with `x`, and the **system mods master switch**
gates whole-OS access.

## The file

An `.astpl` is a ZIP with:

```
manifest.json    {"id","name","version","author","description",
                  "permissions":[...],"main":"plugin.js","tickSeconds":30}
plugin.js        the mod itself (JavaScript, ES6-ish)
assets/…         optional extra files, extracted next to the plugin
```

* `id`: `a-z 0-9 _`, 2–32 chars — it scopes the mod's storage.
* `tickSeconds`: minimum 5, how often `onTick()` fires.

Example manifests ship in `examples/plugins/` in the repo and in every
release (uptime-card, battery-guard, sys-mod).

## Permissions

| grant | gives the script | gate |
|-------|------------------|------|
| `ui` | `astos.ui.card / toast` | none |
| `settings` | `astos.settings.get / set` (astos_* keys) | none |
| `ha` | `astos.ha.entities / call` | none |
| `media` | `astos.media.play/pause/next/prev/volume…` | none |
| `voice` | `astos.voice.say` + `onVoice(text)` events | none |
| `network` | `astos.http.get / postJson` | none |
| `files` | `astos.files.read/write/list/delete` (own folder) | none |
| `system` | `astos.system.shell/setting/prop/input/intent` | **manifest + master switch + starts disabled** |

Always available (no grant): `astos.store`, `astos.battery`,
`astos.device`.

`astos.system.shell` runs commands with the app's platform-signed rights
(getprop, setprop, pm, am, input, dumpsys …). `astos.system.setting` can
write global/secure/system settings; `astos.system.input` injects
keyevents/taps/swipes; `astos.system.intent` starts any component.

## The API (property shapes!)

```js
// cards on the home dashboard (HOME card, PLUGINS row)
astos.ui.card({ id: 'bat', title: 'BATTERY', value: '87%',
                accent: '', onTap: function () { … } });
astos.ui.removeCard('bat');
astos.ui.toast('hi');                      // short toast

// per-mod storage (survives reboots)
astos.store.get('key', 'default');
astos.store.put('key', 'value');

// device state (no permission needed)
astos.battery.pct;         // 87
astos.battery.charging;    // false
astos.device.uptimeMs;      // 12345678
astos.device.model;         // "Lenovo TB3-710F"
astos.device.astosVersion;  // "3.1"

// settings (the astos_* shared keys)
astos.settings.get('astos_theme');
astos.settings.set('astos_h24', '1');

// home assistant
astos.ha.entities();                       // [{id,name,domain,on,scene}…]
astos.ha.call('light', 'toggle', 'light.desk');

// media
astos.media.play(); astos.media.pause();
astos.media.next(); astos.media.prev();
astos.media.volumeUp(); astos.media.volumeDown();

// voice (grant: voice)
astos.voice.say('battery is low');

// network (grant: network)
astos.http.get('http://192.168.1.50:8123/api/');
astos.http.postJson(url, '{"a":1}');

// files (grant: files) — scoped to /sdcard/AstOs/plugin-data/<id>/
astos.files.write('history.txt', '…');
astos.files.read('history.txt');
astos.files.list();
astos.files.delete('history.txt');

// system (grant: system + master switch ON)
astos.system.shell('getprop ro.build.id');       // "exit 0\n<output>"
astos.system.prop('persist.sys.ime');             // read a prop
astos.system.setting('global', 'airplane_mode_on');          // read
astos.system.setting('secure', 'default_input_method', '…');  // write
astos.system.input('keyevent KEYCODE_POWER');
astos.system.intent('com.example.app/.MainActivity');
```

## Events

Define any of these top-level functions in plugin.js:

```js
function onLoad() { … }                    // when enabled
function onUnload() { … }                  // when disabled/removed
function onTick() { … }                    // every tickSeconds
function onVoice(text) { … }               // every utterance the mic hears
function onCommand(name) { … }             // future extension points
```

Card `onTap` callbacks fire when the card is tapped on the dashboard.

## Safety model

* **Sandbox**: scripts run on Rhino in interpreted mode with a
  ClassShutter that exposes ONLY the `astos` object — no `java.*`, no
  `Packages`, no reflection.
* **Watchdog**: each dispatch has a deadline; a stuck or looping script is
  killed and disabled, the dashboard keeps running.
* **Crash isolation**: a throwing mod is disabled with its error shown in
  the Mods list — AstShell itself never goes down with it.
* **Whole-OS opt-in**: `system`-grade mods install DISABLED and need the
  master switch; the console + companion sys.* commands check the same
  switch.

## Writing your own

1. Make a folder: `my-mod/manifest.json` + `my-mod/plugin.js`.
2. Zip it as `my-mod.astpl` (manifest.json at the zip root!).
3. Install it (Companion → mods, or /sdcard/AstOs/plugins + scan).

Start from `examples/plugins/uptime-card` — it's ~30 lines and shows the
whole shape: card + tick + toast + store.
