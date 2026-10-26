// sys-mod.astpl — the WHOLE-OS demo. The "system" permission grants shell
// commands, system properties, secure/global settings and input injection.
// Mods carrying "system" start DISABLED; you flip them on in
// Settings -> Mods (with the master switch on) — that is the opt-in.

function onLoad() {
  var build = firstLine(astos.system.shell('getprop ro.build.display_id'));
  var ast = firstLine(astos.system.shell('getprop ro.astos.version'));
  astos.ui.card({
    id: 'sysinfo',
    title: 'SYSTEM',
    value: 'AstOS ' + ast,
    onTap: function () {
      // every tap re-reads the live OS state and swaps the card
      var ip = firstLine(astos.system.shell('ip route')).split(' ').pop();
      astos.ui.card({
        id: 'sysinfo',
        title: 'SYSTEM',
        value: ip
      });
      astos.ui.toast('build ' + build);
    }
  });
}

function onTick() {
  // example: keep the screen awake-adjacent global readable on the card
  var mode = astos.system.setting('global', 'airplane_mode_on', null) || '0';
  astos.ui.card({
    id: 'sysinfo',
    title: 'SYSTEM',
    value: 'airplane ' + (mode === '1' ? 'on' : 'off')
  });
}

function onVoice(text) {
  // "lock the tablet" -> inject the power menu key
  if (/lock (the )?(tablet|screen)/.test(text)) {
    astos.system.input('keyevent KEYCODE_POWER');
    astos.voice.say('locking');
  }
}

function firstLine(s) {
  s = String(s || '');
  var lines = s.split('\n');
  for (var i = 0; i < lines.length; i++) {
    if (lines[i].indexOf('exit ') === 0) continue;
    if (lines[i].trim()) return lines[i].trim();
  }
  return '';
}
