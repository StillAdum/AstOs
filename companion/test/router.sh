#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
"${JAVA_HOME}/bin/javac" -encoding UTF-8 -d "$WORK" \
  "$ROOT/astshell/app/src/main/java/com/astos/shell/CommandRouter.java" \
  "$ROOT/companion/test/CommandRouterTest.java"
"${JAVA_HOME}/bin/java" -cp "$WORK" CommandRouterTest
