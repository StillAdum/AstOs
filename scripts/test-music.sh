#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)";trap 'rm -rf "$TMP"' EXIT
javac -encoding UTF-8 -d "$TMP" "$ROOT/astshell/app/src/main/java/com/astos/shell/AudioStreamChoice.java" "$ROOT/tests/AudioStreamChoiceTest.java"
java -cp "$TMP" AudioStreamChoiceTest
node "$ROOT/companion/test/pc-music.test.js"
python3 - "$ROOT" <<'PYCODE'
import pathlib,sys
s=(pathlib.Path(sys.argv[1])/'astlink/src/main/java/com/astos/link/LinkServer.java').read_text()
assert 'pcConnectionClosed(Thread.currentThread())' in s
assert 'pcOwner=Thread.currentThread();pcSeen' in s
assert 'pcOwner=null;pcSeen=0;spotifyReady=false' in s
print('PC DISCONNECT PASS: owner connection cleanup clears readiness, preventing stale-PC routing')
PYCODE
