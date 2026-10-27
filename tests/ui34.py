from pathlib import Path
import re,xml.etree.ElementTree as ET
r=Path(__file__).resolve().parents[1]
def luminance(value):
 c=[(value>>x&255)/255 for x in (16,8,0)];c=[x/12.92 if x<=.04045 else ((x+.055)/1.055)**2.4 for x in c];return sum(a*b for a,b in zip(c,[.2126,.7152,.0722]))
def ratio(a,b):x,y=sorted([luminance(a),luminance(b)]);return (y+.05)/(x+.05)
s=(r/'astshell/app/src/main/java/com/astos/shell/Ui.java').read_text(encoding='utf-8')
for line in s.splitlines():
 if 'THEMES.put' not in line:continue
 colors=[int(x,16) for x in re.findall('0x([0-9A-F]+)',line)]
 for fg in [2,3]:
  for bg in [0,1]:assert ratio(colors[fg],colors[bg])>=4.5,line
ns='{http://schemas.android.com/apk/res/android}';tree=ET.parse(r/'astshell/app/src/main/res/layout/activity_home.xml');elements={e.get(ns+'id'):e for e in tree.iter() if e.get(ns+'id')}
assert elements['@+id/promptBtn'].tag.endswith('KeyboardButtonView')
assert '@+id/editCardsBtn' not in elements
assert elements['@+id/voiceStatus'].get(ns+'maxLines')=='1'
assert 'Math.sin' not in (r/'astshell/app/src/main/java/com/astos/shell/EqBars.java').read_text(encoding='utf-8')
for p in [r/'astshell/app/src/main/res/values/styles.xml',r/'astsetup/app/src/main/res/values/styles.xml']:assert 'windowAnimationStyle' in p.read_text(encoding='utf-8')
assert 'Toast' not in (r/'astshell/app/src/main/java/com/astos/shell/PcPlayback.java').read_text(encoding='utf-8')
for p in [r/'astshell/app/src/main/AndroidManifest.xml',r/'astsetup/app/src/main/AndroidManifest.xml']:assert 'OVERRIDE_WIFI_CONFIG' in p.read_text(encoding='utf-8')
assert 'setprop qemu.hw.mainkeys 1' in (r/'vendor/astos/configs/astos.rc').read_text(encoding='utf-8')
assert '<bool name="config_showNavigationBar">false</bool>' in (r/'vendor/astos/overlay/frameworks/base/core/res/res/values/config.xml').read_text(encoding='utf-8')
assert 'TYPE_PHONE' in (r/'astshell/app/src/main/java/com/astos/shell/EdgeNavigation.java').read_text(encoding='utf-8')
print('UI/INTEGRATION PASS: all six themes meet 4.5:1 ink/secondary contrast on both surfaces; keyboard icon, no fake EQ/toasts, animation styles, cross-app Wi-Fi grant')
