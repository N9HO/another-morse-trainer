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

# ---- Rotation must not restart the activity ----
#
# MainActivity declares android:configChanges for orientation and screen size,
# so a rotation has to reconfigure in place rather than recreate. Recreating
# throws away every screen's `remember` state, and mid-quiz that is the
# session's tally — it never reaches Stats.record, which only runs from the
# explicit exits. A compile cannot see any of that, so assert it here.
#
# The signal is the framework's own lifecycle event in the `events` buffer.
# Asserting only "the tag did not appear after rotating" would pass for the
# wrong reason on an image that never emits it, so confirm first that the launch
# above DID emit it. Without that, say so and skip rather than bank a pass we
# did not earn.
CREATE_TAG=wm_on_create_called

if adb logcat -b events -d | grep -q "${CREATE_TAG}.*MainActivity"; then
  signal_ok=yes
else
  signal_ok=no
  echo "::warning::${CREATE_TAG} was not emitted for MainActivity on this image;" \
       "rotation is exercised below but recreation cannot be asserted"
fi

adb logcat -b events -c
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1   # 1 = 90°, landscape
sleep 5
adb exec-out screencap -p > landscape.png || true

if [ -z "$(pid_of)" ]; then
  echo "::error::$PKG died on rotation"
  adb logcat -d | tail -200
  exit 1
fi

if adb logcat -d | grep -qE "$PAT"; then
  echo "::error::crash on rotation"
  adb logcat -d | grep -B 5 -A 40 -E "$PAT" | head -150
  exit 1
fi

if [ "$signal_ok" = yes ] && adb logcat -b events -d | grep -q "${CREATE_TAG}.*MainActivity"; then
  echo "::error::MainActivity was recreated by a rotation — android:configChanges is not covering it"
  adb logcat -b events -d | grep "$CREATE_TAG" | head -20
  exit 1
fi

# Leave the device as we found it, so a later step reads an upright screen.
adb shell settings put system user_rotation 0

if [ "$signal_ok" = yes ]; then
  echo "✅ installed, launched, alive with no fatal exception, and survived a"
  echo "   rotation without the activity being recreated"
else
  echo "✅ installed, launched, alive with no fatal exception, and survived a"
  echo "   rotation (recreation not asserted — see the warning above)"
fi
