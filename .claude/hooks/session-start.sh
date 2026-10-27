#!/bin/bash
# Prepares a Claude Code cloud session to build/test this Android project:
# Android SDK (compileSdk 36), local.properties, 7z for ArchiveTest, Gradle deps.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

SDK="${ANDROID_HOME:-/opt/android-sdk}"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  tmp="$(mktemp -d)"
  curl -sSfLo "$tmp/clt.zip" https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

if [ ! -d "$SDK/platforms/android-36" ] || [ ! -d "$SDK/build-tools/36.0.0" ] || [ ! -x "$SDK/platform-tools/adb" ]; then
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" "platforms;android-36" "build-tools;36.0.0" "platform-tools" >/dev/null
fi

echo "export ANDROID_HOME=$SDK" >> "${CLAUDE_ENV_FILE:-/dev/null}"
echo "export PATH=\$PATH:$SDK/platform-tools:$SDK/build-tools/36.0.0" >> "${CLAUDE_ENV_FILE:-/dev/null}"

cd "$CLAUDE_PROJECT_DIR"
[ -f local.properties ] || echo "sdk.dir=$SDK" > local.properties

# ArchiveTest's 7-Zip compatibility tests are skipped without 7z.
if ! command -v 7z >/dev/null; then
  (apt-get install -y -qq p7zip-full >/dev/null 2>&1 || (apt-get update -qq && apt-get install -y -qq p7zip-full >/dev/null 2>&1)) || true
fi

# Pre-fetch Gradle dependencies. Maven Central sometimes answers 429, so retry.
for i in 1 2 3 4; do
  if ./gradlew --console=plain --max-workers=2 -q compileDebugUnitTestKotlin >/dev/null 2>&1; then break; fi
  sleep $((i * 15))
done
