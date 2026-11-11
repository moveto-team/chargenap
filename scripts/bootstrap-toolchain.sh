#!/usr/bin/env bash
# 차지냅 개발 툴체인을 사용자 홈에 설치한다 (sudo 불필요).
#   JDK 17 (Temurin)      -> ~/.local/jdks/jdk-17
#   Gradle 8.11.1         -> ~/.local/gradle/gradle-8.11.1   (wrapper 생성용)
#   Android SDK           -> ~/Android/Sdk
# 사용: scripts/bootstrap-toolchain.sh && source scripts/env.sh
set -euo pipefail

JDK_DIR="$HOME/.local/jdks/jdk-17"
GRADLE_VERSION="8.11.1"
GRADLE_DIR="$HOME/.local/gradle/gradle-$GRADLE_VERSION"
SDK_DIR="$HOME/Android/Sdk"
CMDLINE_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ ! -x "$JDK_DIR/bin/java" ]; then
  echo "[bootstrap] JDK 17 설치"
  mkdir -p "$JDK_DIR"
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" -o "$TMP/jdk.tar.gz"
  tar -xzf "$TMP/jdk.tar.gz" -C "$JDK_DIR" --strip-components=1
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
  echo "[bootstrap] Gradle $GRADLE_VERSION 설치"
  mkdir -p "$(dirname "$GRADLE_DIR")"
  curl -fsSL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$TMP/gradle.zip"
  unzip -q "$TMP/gradle.zip" -d "$(dirname "$GRADLE_DIR")"
fi

if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "[bootstrap] Android cmdline-tools 설치"
  mkdir -p "$SDK_DIR/cmdline-tools"
  curl -fsSL "$CMDLINE_ZIP_URL" -o "$TMP/cmdline.zip"
  unzip -q "$TMP/cmdline.zip" -d "$TMP/cmdline"
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  mv "$TMP/cmdline/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
fi

SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
echo "[bootstrap] Android SDK 패키지 설치"
yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$SDK_DIR" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator" 2>&1 | tr "\r" "\n" | grep -E "^(Installing|Warning|Error)" || true

echo "[bootstrap] 완료"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
ls "$SDK_DIR"
