#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
curl -fLsS https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$WORK/json.jar"
echo '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'"  $WORK/json.jar" | sha256sum -c - >/dev/null
"${JAVA_HOME}/bin/javac" --add-modules jdk.httpserver -encoding UTF-8 -cp "$WORK/json.jar" -d "$WORK" \
 "$ROOT/astshell/app/src/main/java/com/astos/shell/AiProtocol.java" "$ROOT/tests/AiProtocolTest.java"
CP="$WORK:$WORK/json.jar"
case "${OSTYPE:-}" in msys*|cygwin*) CP="$(cygpath -w "$WORK");$(cygpath -w "$WORK/json.jar")" ;; esac
"${JAVA_HOME}/bin/java" --add-modules jdk.httpserver -cp "$CP" AiProtocolTest
