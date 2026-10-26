// battery-guard.astpl — a card + a spoken warning at 15%.
// Shows the ui + voice permissions working together.

var LAST_SEEN = 'last_seen_pct';
var LOW_LIMIT = 15;

function onLoad() {
  astos.ui.card({
    id: 'bat',
    title: 'BATTERY',
    value: 'reading...',
    onTap: function () {
      astos.voice.say('battery is at ' + astos.battery.pct + ' percent');
    }
  });
  refresh();
}

function onTick() {
  refresh();
}

function refresh() {
  var bat = astos.battery;
  var p = bat.pct;
  astos.ui.card({
    id: 'bat',
    title: 'BATTERY',
    value: p + '%',
    accent: (p >= 0 && p <= LOW_LIMIT) ? 'red' : ''
  });
  if (p >= 0 && p <= LOW_LIMIT && !bat.charging) {
    if (astos.store.get(LAST_SEEN, '100') !== String(p)) {
      astos.store.put(LAST_SEEN, String(p));
      astos.voice.say('battery is low. ' + p + ' percent left.');
      astos.ui.toast('battery low: ' + p + '% — plug the tablet in');
    }
  }
  // persist a tiny history the companion could read via files
  if (p >= 0) {
    astos.files.write('history.txt',
      new Date().toISOString() + ' ' + p + '%\n' + (astos.files.read('history.txt') || ''));
  }
}
