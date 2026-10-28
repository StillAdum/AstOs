#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)";trap 'rm -rf "$TMP"' EXIT
javac -encoding UTF-8 -d "$TMP" "$ROOT/astshell/app/src/main/java/com/astos/shell/AudioStreamChoice.java" "$ROOT/tests/AudioStreamChoiceTest.java"
java -cp "$TMP" AudioStreamChoiceTest
node "$ROOT/companion/test/pc-music.test.js"
