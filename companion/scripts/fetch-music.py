"""Fetch official standalone tools; no Python/Spotify installation needed on PCs.
Both executables are checked against their publisher's HTTPS SHA-256 manifest.
Never reads the user's bot .env or bundles credentials/cookies.
"""
import hashlib,json,pathlib,re,urllib.request,zipfile,io
OUT=pathlib.Path(__file__).resolve().parents[1]/'resources/music'
OUT.mkdir(parents=True,exist_ok=True)
def get(url):
    with urllib.request.urlopen(url,timeout=120) as r:return r.read()
def verified(data,expected):
    actual=hashlib.sha256(data).hexdigest()
    if actual.lower()!=expected.lower():raise RuntimeError('Music dependency SHA-256 mismatch')
    return actual
# Official standalone contains yt-dlp-ejs; Node 22 executes its YouTube challenges.
tag='2026.10.26'
base=f'https://github.com/yt-dlp/yt-dlp/releases/download/{tag}/'
sums=get(base+'SHA2-256SUMS').decode()
sha=next(line.split()[0] for line in sums.splitlines() if line.split()[-1].lstrip('*')=='yt-dlp.exe')
data=get(base+'yt-dlp.exe');ydlsha=verified(data,sha);(OUT/'yt-dlp.exe').write_bytes(data)
base='https://nodejs.org/dist/latest-v22.x/'
sums=get(base+'SHASUMS256.txt').decode()
sha,name=next(line.split() for line in sums.splitlines() if re.fullmatch(r'node-v22\.\d+\.\d+-win-x64\.zip',line.split()[-1]))
data=get(base+name);nodesha=verified(data,sha)
with zipfile.ZipFile(io.BytesIO(data)) as z:
    (OUT/'node.exe').write_bytes(z.read(name[:-4]+'/node.exe'))
    (OUT/'NODE-LICENSE.txt').write_bytes(z.read(name[:-4]+'/LICENSE'))
(OUT/'YT-DLP-LICENSE.txt').write_bytes(get(f'https://raw.githubusercontent.com/yt-dlp/yt-dlp/{tag}/LICENSE'))
(OUT/'versions.json').write_text(json.dumps({'yt-dlp':tag,'yt-dlp.exe.sha256':ydlsha,'node.archive':name,'node.archive.sha256':nodesha},indent=2)+'\n')
print('Verified music dependencies:',tag,name)

# Browser-native HLS for SoundCloud's segmented AAC audio; MIT licensed.
hls=get('https://cdn.jsdelivr.net/npm/hls.js@1.6.13/dist/hls.min.js')
verified(hls,'7c47cd97d7a6e7b98d9623dd8ed9a6d45af4be4085e0c2001cd7175c2b4cfb07')
(OUT.parents[1]/'src/player/hls.min.js').write_bytes(hls)
(OUT/'HLS-LICENSE.txt').write_bytes(get('https://cdn.jsdelivr.net/npm/hls.js@1.6.13/LICENSE'))
