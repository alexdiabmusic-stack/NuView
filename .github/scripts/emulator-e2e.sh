#!/usr/bin/env bash
# Runs INSIDE the emulator session (reactivecircus/android-emulator-runner). Builds and installs the debug APK + the
# instrumentation APK, runs every on-device test (remote-control behaviour and the live install flows), and collects
# screenshots, logcat and result XML into ci-out/.
set -uo pipefail
mkdir -p ci-out/shots
adb logcat -c || true

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.liveInstall=true \
  --console=plain 2>&1 | tee ci-out/build.log
STATUS=${PIPESTATUS[0]}

adb pull /sdcard/Download/. ci-out/shots/ >/dev/null 2>&1 || true
rm -f ci-out/shots/.nomedia
adb logcat -d -s TVEssentials:I AndroidRuntime:E ActivityManager:W PackageInstaller:W > ci-out/logcat.txt 2>&1 || true
mkdir -p ci-out/results && cp -r app/build/outputs/androidTest-results/connected/. ci-out/results/ 2>/dev/null || true
exit "$STATUS"
