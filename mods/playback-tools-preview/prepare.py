#!/usr/bin/env python3
"""Materialize experimental sources without editing the ROM source checkout."""
import argparse,hashlib,json,pathlib,shutil,subprocess
p=argparse.ArgumentParser();p.add_argument('--output',required=True);args=p.parse_args()
mod=pathlib.Path(__file__).resolve().parent;root=mod.parents[1];out=pathlib.Path(args.output).resolve()
if out==root or root in out.parents:raise SystemExit('Output must be outside the source checkout')
for rel,digest in json.loads((mod/'baseline.json').read_text()).items():
    f=root/rel
    actual=hashlib.sha256(f.read_bytes().replace(b'\r\n',b'\n')).hexdigest() if f.is_file() else None
    if actual!=digest:raise SystemExit('Baseline changed; rebase preview before building: '+rel)
if out.exists():raise SystemExit('Output already exists; choose a clean directory')
shutil.copytree(root,out,ignore=shutil.ignore_patterns('.git','node_modules','build','.gradle','dist','__pycache__'))
for rel in json.loads((mod/'baseline.json').read_text()):
    f=out/rel
    if f.is_file():f.write_bytes(f.read_bytes().replace(b'\r\n',b'\n'))
for source in sorted((mod/'patches').glob('*.patch')):
    patch=out/source.relative_to(root)
    patch.write_bytes(source.read_bytes().replace(b'\r\n',b'\n'))
    subprocess.run(['git','apply','--check',str(patch)],cwd=out,check=True)
    subprocess.run(['git','apply',str(patch)],cwd=out,check=True)
print('Preview materialized:',out)
