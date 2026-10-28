#!/usr/bin/env python3
"""Build reversible .astpl installers from signed same-version-code APK updates."""
import argparse,pathlib,json,zipfile,hashlib
p=argparse.ArgumentParser();p.add_argument('--installer-jar',required=True);p.add_argument('--preview',required=True);p.add_argument('--rollback',required=True);p.add_argument('--out',required=True);a=p.parse_args()
mod=pathlib.Path(__file__).resolve().parent;out=pathlib.Path(a.out);out.mkdir(parents=True,exist_ok=True)
for kind,apk in [('preview',a.preview),('rollback',a.rollback)]:
    ident='playback_tools_'+kind;title='Playback tools '+kind
    manifest={'id':ident,'name':title,'version':'0.1.0','author':'AstOS','description':'Native AstShell '+kind+' update. Keeps user data; checks the installed ROM signing certificate before offering an update.','permissions':['ui','system'],'main':'plugin.js'}
    code='var MOD_ID='+json.dumps(ident)+'; var MOD_TITLE='+json.dumps(title)+';\n'+(mod/'installer.js').read_text()
    with zipfile.ZipFile(out/('AstOS-Playback-Tools-'+kind+'.astpl'),'w',zipfile.ZIP_DEFLATED) as z:
        z.write(a.installer_jar,'installer.jar');z.writestr('manifest.json',json.dumps(manifest));z.writestr('plugin.js',code);z.write(apk,'AstShell.apk')
for file in sorted(out.glob('*.astpl')):print(hashlib.sha256(file.read_bytes()).hexdigest(),file.name)
