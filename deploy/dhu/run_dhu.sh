#!/usr/bin/env bash
#
# Launch the Android Auto Desktop Head Unit against a USB-connected phone.
#
#   deploy/dhu/run_dhu.sh
#
# This is the only free way to run a Car App Library (templated) app, because
# Android Auto refuses to load templated apps in a REAL car unless they were
# installed from a trusted source (Google Play). That restriction does not
# apply to the DHU, which is the officially supported development path.
#
# Phone setup, once:
#   1. Settings -> Connected devices -> Android Auto
#      (if missing, search "Android Auto" in Settings)
#   2. Scroll to the bottom, tap "Version" 10x -> allow development settings
#   3. Top-right menu -> Developer settings -> enable "Start head unit server"
#   4. Android system Settings -> Developer options -> enable "USB debugging"
#   5. Plug the phone into this machine over USB and accept the adb prompt

set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
DHU_DIR="${ANDROID_HOME}/extras/google/auto"
ADB="${ANDROID_HOME}/platform-tools/adb"
# The DHU links against libc++, which Ubuntu does not ship; borrow the host
# libc++ from the NDK that is already installed system-wide.
NDK_LIB="${NDK_LIB:-/usr/lib/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/lib}"
PORT=5277

# Same package name the Gradle build uses: signing.properties, else the public default.
REPO_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
APP_ID="${APP_ID:-}"
if [ -z "$APP_ID" ] && [ -f "$REPO_DIR/signing.properties" ]; then
    APP_ID=$(sed -n 's/^carvideoApplicationId=//p' "$REPO_DIR/signing.properties" | tail -1)
fi
APP_ID="${APP_ID:-my.kang.carvideo}"

[ -x "${DHU_DIR}/desktop-head-unit" ] || {
    echo "error: DHU not found at ${DHU_DIR}" >&2
    echo "       install it with: sdkmanager --install extras/google/auto" >&2
    exit 1
}
[ -f "${NDK_LIB}/libc++.so.1" ] || {
    echo "error: no host libc++ at ${NDK_LIB}" >&2
    echo "       either install libc++1, or point NDK_LIB at another copy" >&2
    exit 1
}
[ -n "${DISPLAY:-}" ] || {
    echo "error: DISPLAY is unset - the DHU opens a window and needs an X display." >&2
    echo "       Run this from the machine's own desktop session, or export DISPLAY=:0." >&2
    exit 1
}

# 1. phone connected?
devices=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$devices" ]; then
    echo "error: no authorised device. Plug the phone in over USB, enable USB debugging," >&2
    echo "       and accept the prompt on the phone. Current state:" >&2
    "$ADB" devices -l >&2
    exit 1
fi
echo "==> device: $devices"

# 2. is the app installed?
if "$ADB" shell pm list packages 2>/dev/null | grep -qx "package:$APP_ID"; then
    echo "==> $APP_ID is installed"
else
    echo "WARNING: $APP_ID is not installed on the phone."
    echo "         install it with: $ADB install -r app/build/outputs/apk/debug/app-debug.apk"
fi

# 3. forward the head unit server port
"$ADB" forward "tcp:${PORT}" "tcp:${PORT}" >/dev/null
echo "==> forwarded tcp:${PORT}"

# 4. go
echo "==> starting Desktop Head Unit (a window will open on ${DISPLAY})"
echo "    If it exits immediately with a connection error, the head unit server"
echo "    is not running: Android Auto -> Developer settings -> Start head unit server."
cd "$DHU_DIR"
exec env LD_LIBRARY_PATH="${DHU_DIR}:${NDK_LIB}" ./desktop-head-unit "$@"
