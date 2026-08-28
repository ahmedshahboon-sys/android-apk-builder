#!/usr/bin/env bash
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  SDKMANAGER=$(find "$ANDROID_HOME" -type f -name sdkmanager | head -1)
fi
yes | "$SDKMANAGER" --licenses >/dev/null || true
"$SDKMANAGER" "platform-tools" "build-tools;35.0.0" "platforms;android-35" "ndk;29.0.13846066"

rm -rf /tmp/ShahbounMultiEngine
git clone --depth 1 https://github.com/Black00Z/Blacks-BlackBox.git /tmp/ShahbounMultiEngine
chmod +x /tmp/ShahbounMultiEngine/gradlew
mkdir -p /tmp/ShahbounMultiEngine/app/src/main/res/font
curl -L --fail --retry 3 -o /tmp/ShahbounMultiEngine/app/src/main/res/font/cairo_regular.ttf https://github.com/google/fonts/raw/main/ofl/cairo/Cairo%5Bslnt%2Cwght%5D.ttf
python3 "$GITHUB_WORKSPACE/scripts/patch_shahboun_modern.py"
python3 "$GITHUB_WORKSPACE/scripts/add_shahboun_colors.py"

cd /tmp/ShahbounMultiEngine
./gradlew --no-daemon :app:assembleDebug
APK=$(find app/build/outputs/apk/debug -type f -name '*universal*.apk' | head -1)
if [ -z "$APK" ]; then APK=$(find app/build/outputs/apk/debug -type f -name '*.apk' | head -1); fi
test -n "$APK"
test -s "$APK"
cp "$APK" "$GITHUB_WORKSPACE/ShahbounMulti-Modern.apk"
