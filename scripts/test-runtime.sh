#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
"${JAVA_HOME}/bin/javac" -encoding UTF-8 -d "$WORK" "$ROOT/astshell/app/src/main/java/com/astos/shell/SpeechGate.java" "$ROOT/astshell/app/src/main/java/com/astos/shell/PluginArchive.java" "$ROOT/tests/RuntimeTest.java"
"${JAVA_HOME}/bin/java" -cp "$WORK" RuntimeTest
curl -fLsS https://repo.maven.apache.org/maven2/org/mozilla/rhino/1.8.1/rhino-1.8.1.jar -o "$WORK/rhino.jar"
echo "98a06d42bdd69da15bf9c2a7812b4cfe648ca9e64fe8baaeb502a32b0ed2c8ac  $WORK/rhino.jar" | sha256sum -c -
"${JAVA_HOME}/bin/javac" -encoding UTF-8 -cp "$WORK/rhino.jar" -d "$WORK" "$ROOT/tests/jvm/dalvik/system/DexClassLoader.java" "$ROOT/tests/RhinoInteropTest.java"
CP="$WORK:$WORK/rhino.jar"
case "${OSTYPE:-}" in msys*|cygwin*) CP="$(cygpath -w "$WORK");$(cygpath -w "$WORK/rhino.jar")" ;; esac
"${JAVA_HOME}/bin/java" -cp "$CP" RhinoInteropTest
