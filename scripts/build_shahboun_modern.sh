#!/usr/bin/env bash
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  SDKMANAGER=$(find "$ANDROID_HOME" -type f -name sdkmanager | head -1)
fi
yes | "$SDKMANAGER" --licenses >/dev/null || true
"$SDKMANAGER" "platform-tools" "build-tools;36.0.0" "platforms;android-36"

if grep -R -n -E 'BlackBox|Black00Z|Dobby|VirtualApp|VirtualXposed|niunaijun' "$GITHUB_WORKSPACE/ShahbounMultiProject" --exclude-dir=build; then
  echo 'External clone engine reference found'
  exit 1
fi

if find "$GITHUB_WORKSPACE/ShahbounMultiProject/app" -type f \( -name '*.so' -o -name '*.aar' -o -name '*.jar' -o -name '*.dex' \) | grep -q .; then
  echo 'Prebuilt engine binary found in Shahboun project'
  exit 1
fi

cd "$GITHUB_WORKSPACE/ShahbounMultiProject"
gradle :app:assembleDebug --stacktrace
APK=$(find app/build/outputs/apk/debug -type f -name '*.apk' | head -1)
test -n "$APK" && test -s "$APK"
cp "$APK" "$GITHUB_WORKSPACE/ShahbounMulti-Modern.apk"
