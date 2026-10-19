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

# ---- Vosk offline model (small en-US, ~40 MB) -------------------------------
MODEL_DIR="app/src/main/assets/model"
MODEL_ZIP="vosk-model-small-en-us-0.15.zip"
mkdir -p app/src/main/assets
if [ ! -f "$MODEL_DIR/conf/model.conf" ]; then
    echo "Downloading Vosk model…"
    rm -rf app/src/main/assets/model app/src/main/assets/vosk-*
    curl -sL "https://alphacephei.com/vosk/models/$MODEL_ZIP" -o "/tmp/$MODEL_ZIP"
    unzip -q "/tmp/$MODEL_ZIP" -d /tmp
    rm -rf "$MODEL_DIR"
    mv "/tmp/vosk-model-small-en-us-0.15" "$MODEL_DIR"
fi
echo "Model: $(du -sh $MODEL_DIR | cut -f1)"

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
