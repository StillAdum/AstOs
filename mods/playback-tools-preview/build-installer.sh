#!/usr/bin/env bash
set -euo pipefail
MOD="$(cd "$(dirname "$0")" && pwd)"; OUT="${1:?output installer.jar}"; WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/dex"
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 8 -cp "$ANDROID_HOME/platforms/android-34/android.jar" -d "$WORK/classes" "$MOD/native/ModInstaller.java"
(cd "$WORK/classes" && "$JAVA_HOME/bin/jar" cf "$WORK/input.jar" .)
"$ANDROID_HOME/build-tools/34.0.0/d8" --lib "$ANDROID_HOME/platforms/android-34/android.jar" --min-api 24 --output "$WORK/dex" "$WORK/input.jar"
(cd "$WORK/dex" && "$JAVA_HOME/bin/jar" cf "$OUT" classes.dex)
