#!/usr/bin/env bash
set -uo pipefail

# Exercise the phone-first layout at a deliberate compact 360 dp width (540x1200 px at 240 dpi).
adb shell wm size 540x1200
adb shell wm density 240
adb shell wm size
adb shell wm density
mkdir -p composeApp/build/reports/compact-phone-smoke

# Preserve the actual instrumentation exit status, then collect optional visual evidence.
set +e
bash gradlew :composeApp:connectedDebugAndroidTest --stacktrace
test_result=$?

# Pull deterministic browser-state frames captured by instrumentation before the app is reset.
# A missing optional visual directory must not mask the instrumentation result.
adb pull /sdcard/Android/data/dev.veyra.browser/files/compact-phone-smoke/. \
  composeApp/build/reports/compact-phone-smoke/ >/dev/null 2>&1
visual_pull_result=$?
if [ "$visual_pull_result" -ne 0 ]; then
  echo "WARNING: instrumentation browser-state screenshots were not available to pull."
fi

# connectedDebugAndroidTest may leave the target app absent on some emulator runs.
# Reinstall the already-built debug APK before launching and capturing the home frame.
adb shell am force-stop dev.veyra.browser
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
install_result=$?
capture_result=$install_result

if [ "$install_result" -eq 0 ]; then
  adb shell monkey -p dev.veyra.browser 1
  launch_result=$?
  capture_result=$launch_result
  if [ "$launch_result" -eq 0 ]; then
    sleep 5
    adb exec-out screencap -p > composeApp/build/reports/compact-phone-smoke/home.png
    capture_result=$?
  fi
fi

set -e
if [ "$capture_result" -ne 0 ]; then
  echo "WARNING: compact-phone screenshot capture failed (exit $capture_result); instrumentation result remains $test_result."
fi
exit "$test_result"
