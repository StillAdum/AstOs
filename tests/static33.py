from pathlib import Path
import xml.etree.ElementTree as ET
import json
r=Path(__file__).resolve().parents[1]
for base in ['astshell/app/src/main','astsetup/app/src/main']:
 for p in (r/base).rglob('*.xml'):ET.parse(p)
ns='{http://schemas.android.com/apk/res/android}'
tree=ET.parse(r/'astshell/app/src/main/res/layout/activity_settings.xml')
row=next(e for e in tree.iter() if e.get(ns+'id')=='@+id/themeRow');assert row.get(ns+'orientation')=='vertical'
for name in ['dune','forest','rust','mono','ember','pine']:assert name in (r/'companion/src/ui/app.js').read_text(encoding='utf-8')
java='\n'.join(p.read_text(encoding='utf-8') for base in ['astshell/app/src/main/java','astsetup/app/src/main/java'] for p in (r/base).rglob('*.java'))
assert 'HIDE_NAVIGATION' not in java and 'immersive.full' not in java
home=(r/'astshell/app/src/main/java/com/astos/shell/HomeActivity.java').read_text(encoding='utf-8')
for symbol in ['promptBtn','mRenderedTheme','Hearing: ','PcPlayback.state()','mediaAction("volset", pct)']:assert symbol in home,symbol
setup=(r/'astsetup/app/src/main/java/com/astos/setup/SetupActivity.java').read_text(encoding='utf-8');assert 'TextEntry.show' in setup and 'ACTION_REQUEST_DISCOVERABLE' in setup
assert 'pcPlaybackState()' in (r/'astlink/src/main/java/com/astos/link/LinkServer.java').read_text(encoding='utf-8')
assert json.loads((r/'companion/package.json').read_text(encoding='utf-8'))['version']=='1.5.1'
print('STATIC PASS: all Android XML, six themes, Back/Home retained, typed input, cards, transcripts, PC state and setup keyboard hooks')
