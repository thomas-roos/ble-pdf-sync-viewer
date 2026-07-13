#!/usr/bin/env bash
# Builds the app + snippet APKs, installs them on every connected device and
# runs the multi-device BLE sync tests. Needs at least two devices (or
# emulators with Bluetooth emulation) visible to adb.
#
# Usage: ./run_tests.sh [--skip-build]
set -euo pipefail
cd "$(dirname "$0")"

APP_APK=../android-app/app/build/outputs/apk/debug/app-debug.apk
TEST_APK=../android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

if [ "${1:-}" != "--skip-build" ]; then
    (cd ../android-app && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest)
fi

SERIALS=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
COUNT=$(printf '%s\n' "$SERIALS" | grep -c . || true)
if [ "$COUNT" -lt 2 ]; then
    echo "ERROR: need at least 2 connected devices, found $COUNT" >&2
    exit 1
fi

for serial in $SERIALS; do
    echo "Installing APKs on $serial"
    # -g grants all runtime permissions (Bluetooth, location) at install time
    adb -s "$serial" install -r -g "$APP_APK"
    adb -s "$serial" install -r -g "$TEST_APK"
done

python3 ble_sync_test.py -c testbed.yml