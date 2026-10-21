#!/usr/bin/env bash
# 30-build-astshell.sh — build the AstShell app (Gradle) with the Vosk model,
# plus the AstSetup first-boot wizard.
# Output: astshell/app/build/outputs/apk/release/app-release-unsigned.apk
#         astsetup/app/build/outputs/apk/release/app-release-unsigned.apk
set -e

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO_ROOT/astshell"

# ---- Java 17 (Gradle 8.7 / AGP 8.5 — needed for NewPipeExtractor's
#      Java-21 bytecode) ------------------------------------------------------
if [ -d "$HOME/jdk17" ]; then
    export JAVA_HOME="$HOME/jdk17"
elif [ -n "$JAVA_HOME_17_X64" ] && [ -d "$JAVA_HOME_17_X64" ]; then
    export JAVA_HOME="$JAVA_HOME_17_X64"
else
    echo "Downloading Temurin 17…"
    mkdir -p "$HOME/jdk17tmp"
    curl -sL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" \
        | tar -xz -C "$HOME/jdk17tmp"
    export JAVA_HOME="$(echo $HOME/jdk17tmp/jdk-17*)"
    ln -sfn "$JAVA_HOME" "$HOME/jdk17"
fi
export PATH="$JAVA_HOME/bin:$PATH"
java -version

# ---- Gradle 8.7 ---------------------------------------------------------------
GRADLE_VER="8.7"
GRADLE_HOME="$HOME/gradle-$GRADLE_VER"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
    echo "Downloading Gradle $GRADLE_VER…"
    curl -sL "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip" -o /tmp/gradle.zip
    unzip -q -o /tmp/gradle.zip -d "$HOME"
fi
"$GRADLE_HOME/bin/gradle" --version

# NOTE: the Vosk voice model is NOT bundled anymore — the wizard downloads
# it on the device at the end of setup ("Setup is almost finished, just
# downloading a few more things..."). scripts/60-release.sh self-hosts the
# zip on this repo's releases as the device-side fallback mirror.

# ---- Android SDK bits (compileSdk 34 for AGP 8.5) -----------------------------
export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
if [ -d "$ANDROID_HOME" ]; then
    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
    yes | sdkmanager "platforms;android-34" "build-tools;34.0.0" >/dev/null 2>&1 || \
        yes | sdkmanager --licenses >/dev/null 2>&1 || true
fi

# ---- Build AstShell -------------------------------------------------------------
echo "::group::Gradle assembleRelease (AstShell)"
"$GRADLE_HOME/bin/gradle" --no-daemon -p . :app:assembleRelease
echo "::endgroup::"

APK="app/build/outputs/apk/release/app-release-unsigned.apk"
ls -lh "$APK"
echo "AstShell built OK"

# ---- Build AstSetup (the first-boot wizard) --------------------------------------
echo "::group::Gradle assembleRelease (AstSetup)"
cd "$REPO_ROOT/astsetup"
"$GRADLE_HOME/bin/gradle" --no-daemon -p . :app:assembleRelease
echo "::endgroup::"

APK2="app/build/outputs/apk/release/app-release-unsigned.apk"
ls -lh "$APK2"
echo "AstSetup built OK"
