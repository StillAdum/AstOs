#!/usr/bin/env bash
# interop.sh — the tablet codec (Java LinkJson) and the PC codec
# (companion protocol.js) exchange golden frames and verify each other.
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
ASTLINK="$HERE/../../astlink/src/main/java"
JDK="${JDK:-$HOME/jdk17}"
JAVAC="$JDK/bin/javac"
JAVA="$JDK/bin/java"
WORK="$(mktemp -d)"

# compile the REAL LinkJson + the driver
"$JAVAC" -d "$WORK" "$ASTLINK/com/astos/link/LinkJson.java" "$HERE/JavaDriver.java"

# 1) Java writes golden frames; node parses each with the PC codec
"$JAVA" -cp "$WORK" JavaDriver write "$WORK/java-frames.txt"

# 2) node writes frames for Java to verify
node "$HERE/write-frames.js" "$WORK/node-frames.txt"

# 3) Java verifies the node frames
"$JAVA" -cp "$WORK" JavaDriver verify "$WORK/node-frames.txt"

# 4) node verifies the java frames + asserts semantics
node "$HERE/verify-frames.js" "$WORK/java-frames.txt"

echo "ASTLINK INTEROP OK — java and node codecs agree byte-for-byte"
