#!/usr/bin/env bash
#
# Runtime smoke test for the R8-minified release build, run inside
# reactivecircus/android-emulator-runner from android-ci.yml.
#
# Lives in a file rather than inline in the workflow because that action runs
# its `script:` input under /usr/bin/sh (dash), where `set -o pipefail` is a
# syntax error — and because a file can be checked with `bash -n` before it
# reaches CI.
#
# Expects: a booted emulator on adb, and the release APK unpacked under
# artifact/ by the download-artifact step.

set -uo pipefail

PKG=app.anothermorsetrainer
ACTIVITY="$PKG/.MainActivity"

pid_of() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r\n'; }

APK=$(find artifact -name 'app-release.apk' | head -1)
if [ -z "$APK" ]; then
  echo "::error::no app-release.apk found under artifact/"
  find artifact -type f | head -20
  exit 1
fi
echo "installing $APK"
adb install -r "$APK"

adb logcat -c
adb shell am start -n "$ACTIVITY"

# Taken as early as possible on purpose: android:windowBackground from Theme.AMT
# is what is on screen before Compose draws its first frame, so this is where a
# dropped @color/brand_navy would show.
adb exec-out screencap -p > launch.png || true

pid=""
for _ in $(seq 1 30); do
  pid=$(pid_of)
  [ -n "$pid" ] && break
  sleep 1
done
if [ -z "$pid" ]; then
  echo "::error::$PKG never started"
  adb logcat -d | tail -200
  exit 1
fi
echo "started, pid $pid"

sleep 10
adb exec-out screencap -p > home.png || true

if [ -z "$(pid_of)" ]; then
  echo "::error::$PKG died within 10s of launch"
  adb logcat -d | tail -200
  exit 1
fi

# Theme.AMT is the application theme, so a colour resource the shrinker removed
# out from under it throws during inflation rather than rendering wrong.
PAT='FATAL EXCEPTION|Resources\$NotFoundException|ClassNotFoundException|NoSuchMethodError|NoClassDefFoundError'
if adb logcat -d | grep -qE "$PAT"; then
  echo "::error::crash or missing symbol in logcat — most likely an R8 keep rule"
  adb logcat -d | grep -B 5 -A 40 -E "$PAT" | head -150
  exit 1
fi

echo "✅ installed, launched, and alive 10s later with no fatal exception"
