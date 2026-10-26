// uptime-card.astpl — the hello-world of AstOS mods.
// Adds one card to the home dashboard that ticks once a minute.

function onLoad() {
  astos.ui.card({
    id: 'uptime',
    title: 'UPTIME',
    value: fmt(),
    onTap: function () {
      astos.ui.toast('up for ' + fmt());
    }
  });
}

function onTick() {
  astos.ui.card({
    id: 'uptime',
    title: 'UPTIME',
    value: fmt()
  });
}

function fmt() {
  var s = Math.floor(astos.device.uptimeMs / 1000);
  var h = Math.floor(s / 3600);
  var m = Math.floor((s % 3600) / 60);
  return h > 0 ? h + 'h ' + m + 'm' : m + 'm';
}
