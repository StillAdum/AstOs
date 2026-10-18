#!/usr/bin/env bash
# 30-build-astshell.sh — build the AstShell app (Gradle) with the Vosk model.
# Output: astshell/app/build/outputs/apk/release/app-release-unsigned.apk
set -e

REPO_ROOT="${REPO_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO_ROOT/astshell"

# ---- Java 17 (Gradle 7.6 / AGP 7.4) --------------------------------------
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

# ---- Gradle 7.6.4 ----------------------------------------------------------
GRADLE_VER="7.6.4"
GRADLE_HOME="$HOME/gradle-$GRADLE_VER"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
    echo "Downloading Gradle $GRADLE_VER…"
    curl -sL "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip" -o /tmp/gradle.zip
    unzip -q /tmp/gradle.zip -d "$HOME"
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

# ---- Android SDK bits --------------------------------------------------------
export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
if [ -d "$ANDROID_HOME" ]; then
    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
    yes | sdkmanager "platforms;android-33" "build-tools;33.0.2" >/dev/null 2>&1 || \
        yes | sdkmanager --licenses >/dev/null 2>&1 || true
fi

# ---- Build -------------------------------------------------------------------
echo "::group::Gradle assembleRelease"
"$GRADLE_HOME/bin/gradle" --no-daemon -p . :app:assembleRelease
echo "::endgroup::"

APK="app/build/outputs/apk/release/app-release-unsigned.apk"
ls -lh "$APK"
echo "AstShell built OK"
