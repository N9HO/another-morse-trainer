#!/bin/bash
# One-command iOS release: archive the Release build, upload it to App Store
# Connect using the App Store Connect API key, then submit it.
#
# Where it goes after the upload is RELEASE_CHANNEL:
#   appstore   (default) attach the build to the App Store version named by
#              MARKETING_VERSION, set What's New from
#              tools/whatsnew/whatsnew-en-US, apply the listing metadata in
#              tools/store-metadata.json, and submit to App Review with
#              release-after-approval. This is the production path.
#   testflight the testing path: set What to Test from the same
#              tools/whatsnew/whatsnew-en-US, submit for Beta App Review and
#              hand the build to the previous build's testers.
# Every upload lands in TestFlight regardless, so internal testers still get
# a production build before Apple has finished reviewing it.
#
# Credentials come from tools/asc-auth.sh (gitignored). Bump the build number
# (CURRENT_PROJECT_VERSION) in the project before running, or App Store Connect
# will reject a duplicate build. For an App Store release, MARKETING_VERSION
# must be higher than the version that is live.
#
# Usage:  ./tools/upload-testflight.sh
#         DRY_RUN=1 ./tools/upload-testflight.sh               # build + sign, no upload
#         SKIP_DISTRIBUTE=1 ./tools/upload-testflight.sh       # upload, don't submit
#         SUBMIT_ONLY=1 ./tools/upload-testflight.sh           # no build: submit the build
#                                                              # already uploaded for this
#                                                              # CURRENT_PROJECT_VERSION
#         RELEASE_CHANNEL=testflight ./tools/upload-testflight.sh  # beta, not App Review
#         RELEASE_PLATFORM=maccatalyst RELEASE_CHANNEL=testflight ./tools/upload-testflight.sh
#                                                              # the Mac build, not the iPhone one
#
# RELEASE_PLATFORM picks which build of the one MorseTrainer target ships:
#   ios          (default) the iPhone build, archived for generic/platform=iOS
#                and exported as an .ipa. Tags and every existing caller get this.
#   maccatalyst  the Mac build (#264), archived for Mac Catalyst and exported as
#                a signed .pkg for the Mac App Store. Same bundle ID and the
#                same App Store Connect record, but App Store Connect keeps
#                builds per platform: a Mac build numbered 37 and an iPhone
#                build numbered 37 are two builds, and asc-api.py is told which
#                one to act on (ASC_PLATFORM=MAC_OS). TestFlight channel only
#                for now; the Mac App Store listing (screenshots, review notes)
#                is not wired up, so `appstore` is refused rather than guessed.
#
# SUBMIT_ONLY exists because the upload and the submission are separate acts
# with separate failure modes: once a build is in App Store Connect its number
# is spent, so a submission that fails on metadata must be retried without
# archiving again. It is a plain re-run of the submission half.
#
# This script is the single code path for iOS releases: `ios-release.yml`
# runs this exact file rather than reimplementing the steps in YAML, so CI and a
# local run cannot drift apart. CI supplies the credentials by writing the same
# gitignored tools/asc-auth.sh this reads locally. (The file name predates the
# App Store path and is kept so muscle memory and old notes still work.)
set -euo pipefail
cd "$(dirname "$0")/.."

AUTH="tools/asc-auth.sh"
[ -f "$AUTH" ] || { echo "Missing $AUTH (API credentials). See tools/asc-auth.sh.example."; exit 1; }
# shellcheck disable=SC1090
source "$AUTH"

CHANNEL="${RELEASE_CHANNEL:-appstore}"
case "$CHANNEL" in
  appstore|testflight) ;;
  *) echo "❌ RELEASE_CHANNEL must be 'appstore' or 'testflight', not '$CHANNEL'."; exit 1 ;;
esac

PLATFORM="${RELEASE_PLATFORM:-ios}"
case "$PLATFORM" in
  ios)
    DESTINATION='generic/platform=iOS'
    PACKAGE_EXT=ipa
    export ASC_PLATFORM=IOS ;;
  maccatalyst)
    DESTINATION='generic/platform=macOS,variant=Mac Catalyst'
    PACKAGE_EXT=pkg
    export ASC_PLATFORM=MAC_OS
    # A dry run submits nothing, so the channel does not matter to it.
    if [ "$CHANNEL" != "testflight" ] && [ "${DRY_RUN:-0}" != "1" ]; then
      echo "❌ The Mac build ships through TestFlight only for now (RELEASE_CHANNEL=testflight)."
      echo "   The Mac App Store version (screenshots, review notes) is set up in App Store Connect first."
      exit 1
    fi ;;
  *) echo "❌ RELEASE_PLATFORM must be 'ios' or 'maccatalyst', not '$PLATFORM'."; exit 1 ;;
esac
echo "▸ Platform: $PLATFORM ($ASC_PLATFORM), channel: $CHANNEL"

if [ "${SUBMIT_ONLY:-0}" = "1" ] && [ "${DRY_RUN:-0}" = "1" ]; then
  echo "❌ SUBMIT_ONLY and DRY_RUN together do nothing; pick one."; exit 1
fi

# Both channels ship notes: What's New on the App Store version, What to Test
# on the TestFlight build. Checked before archiving, because once the upload
# lands the build number is spent and a missing file would strand it.
NOTES="tools/whatsnew/whatsnew-en-US"
# The Mac build's testers are told what is Mac-specific when there is a file
# for it; otherwise they read the same notes as the iPhone build.
if [ "$PLATFORM" = "maccatalyst" ] && [ -s tools/whatsnew/whatsnew-mac-en-US ]; then
  NOTES="tools/whatsnew/whatsnew-mac-en-US"
fi
if [ "${DRY_RUN:-0}" != "1" ] && [ ! -s "$NOTES" ]; then
  echo "❌ $NOTES is missing or empty. Write the notes for this build before releasing."
  exit 1
fi

ARCHIVE="build/AMT-$PLATFORM-$(date +%Y%m%d-%H%M%S).xcarchive"
EXPORT_DIR="build/export"
rm -rf "$EXPORT_DIR"

# DRY_RUN=1 exercises the whole pipeline — archive, cloud-sign, export — but
# writes the .ipa to disk instead of uploading it. Nothing reaches TestFlight
# and no build number is consumed, which makes it safe to run against a build
# number that already shipped. CI uses this to prove the plumbing; it's equally
# useful locally for checking a signing change without burning a build.
EXPORT_PLIST="tools/ExportOptions.plist"
if [ "${DRY_RUN:-0}" = "1" ]; then
  EXPORT_PLIST="$(mktemp -d)/ExportOptions-dryrun.plist"
  cp tools/ExportOptions.plist "$EXPORT_PLIST"
  plutil -replace destination -string export "$EXPORT_PLIST"
  echo "▸ DRY RUN: exporting to disk, not uploading."
fi

if [ "${SUBMIT_ONLY:-0}" = "1" ]; then
  echo "▸ SUBMIT_ONLY: skipping archive and upload; submitting the build already in App Store Connect."
else
echo "▸ Archiving (Release, unsigned)…"
# Archive unsigned and let `-exportArchive` do all the signing. Cloud signing
# re-signs at export anyway, so a signed archive buys nothing — and it costs
# something real: on a machine with no signing identity in its keychain (i.e.
# every CI runner, which starts clean), automatic signing silently provisions a
# fresh Apple Development certificate. That accumulates against the account's
# certificate cap, one per release, until releases start failing. Verified
# 2026-08-31: with these flags the development-certificate count is unchanged
# across a full archive+export, and the exported .ipa is still signed by
# "Apple Distribution: JUSTIN KEITH ROGERS (F6ASU3CH5M)".
#
# Trade-off: the .xcarchive itself is unsigned, so it can't be re-exported from
# Xcode's Organizer for ad-hoc/enterprise distribution without signing it first.
# We only ever ship it to the App Store, so that costs us nothing.
xcodebuild -project MorseTrainer.xcodeproj -scheme MorseTrainer -configuration Release \
  -destination "$DESTINATION" -archivePath "$ARCHIVE" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
  archive -allowProvisioningUpdates \
  -authenticationKeyPath "$ASC_KEY_PATH" \
  -authenticationKeyID "$ASC_KEY_ID" \
  -authenticationKeyIssuerID "$ASC_ISSUER_ID"

# The export signs the app with the entitlements the ARCHIVED app claims,
# filtered by the provisioning profile. An unsigned archive claims none, so
# the export came out with only the profile's identifiers. On the Mac that
# meant no App Sandbox (which the Mac App Store refuses), no microphone, no
# network; on iOS it meant no App Attest, so every leaderboard post failed
# on device. Both seen 2026-09-30, the iOS one by the dry run's entitlement
# check. Ad-hoc signing needs no certificate, so it records the entitlements
# on the archived app without provisioning anything, and the cloud-signed
# export keeps them.
ENTITLEMENTS=Config/MorseTrainer.entitlements
[ "$PLATFORM" = "maccatalyst" ] && ENTITLEMENTS=Config/MorseTrainer-macCatalyst.entitlements
echo "▸ Recording $ENTITLEMENTS on the archived app (ad-hoc)…"
codesign --force --sign - --generate-entitlement-der \
  --entitlements "$ENTITLEMENTS" \
  "$ARCHIVE/Products/Applications/MorseTrainer.app"

echo "▸ Exporting + uploading to TestFlight ($PLATFORM)…"
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE" \
  -exportOptionsPlist "$EXPORT_PLIST" \
  -exportPath "$EXPORT_DIR" \
  -allowProvisioningUpdates \
  -authenticationKeyPath "$ASC_KEY_PATH" \
  -authenticationKeyID "$ASC_KEY_ID" \
  -authenticationKeyIssuerID "$ASC_ISSUER_ID"

if [ "${DRY_RUN:-0}" = "1" ]; then
  # Prove the export is genuinely App Store distribution-signed. Cloud signing
  # keeps the private key on Apple's side, so the only local evidence that it
  # worked is the signature on the .ipa itself.
  PACKAGE=$(ls "$EXPORT_DIR"/*."$PACKAGE_EXT" 2>/dev/null | head -1)
  if [ -z "$PACKAGE" ]; then
    echo "❌ DRY RUN: no .$PACKAGE_EXT in $EXPORT_DIR:"; ls -la "$EXPORT_DIR"; exit 1
  fi
  UNPACK="$(mktemp -d)"
  if [ "$PACKAGE_EXT" = "ipa" ]; then
    unzip -qo "$PACKAGE" -d "$UNPACK"
    APP=$(ls -d "$UNPACK"/Payload/*.app | head -1)
  else
    # The Mac App Store takes a flat installer package signed by the team's
    # installer certificate, wrapping an app signed like the iPhone one.
    PKGSIG=$(pkgutil --check-signature "$PACKAGE" 2>&1 || true)
    echo "▸ Signature on the exported installer package:"
    printf '%s\n' "$PKGSIG" | sed 's/^/    /'
    if ! printf '%s\n' "$PKGSIG" | grep -qE 'Installer Distribution|3rd Party Mac Developer Installer'; then
      echo "❌ DRY RUN: the .pkg is not signed by a Mac installer distribution certificate."
      exit 1
    fi
    pkgutil --expand-full "$PACKAGE" "$UNPACK/pkg"
    APP=$(find "$UNPACK/pkg" -name '*.app' -type d -prune | head -1)
  fi
  # Capture once rather than piping codesign into `grep -q` twice: grep -q exits
  # on its first match, codesign takes SIGPIPE, and `set -o pipefail` then reports
  # the whole pipeline as failed even though the signature was fine.
  SIGINFO=$(codesign -dvvv "$APP" 2>&1)
  echo "▸ Signature on the exported app:"
  printf '%s\n' "$SIGINFO" \
    | grep -E '^Authority=|^TeamIdentifier=|^Identifier=' | sed 's/^/    /'
  if ! printf '%s\n' "$SIGINFO" | grep -qE '^Authority=(Apple Distribution|3rd Party Mac Developer Application)'; then
    echo "❌ DRY RUN: export is not signed by an Apple Distribution certificate."
    exit 1
  fi
  ENTS=$(codesign -d --entitlements - --xml "$APP" 2>/dev/null | plutil -convert json -o - - 2>/dev/null || true)
  echo "▸ Entitlements on the exported app: $ENTS"
  if [ "$PLATFORM" = "maccatalyst" ]; then
    if ! printf '%s' "$ENTS" | grep -q '"com.apple.security.app-sandbox":true'; then
      echo "❌ DRY RUN: the Mac app is not sandboxed; the Mac App Store would refuse it."
      exit 1
    fi
  fi
  # The leaderboard and buddy streaks depend on App Attest; without this
  # entitlement every attestation fails on device and nothing can be posted.
  if ! printf '%s' "$ENTS" | grep -q '"com.apple.developer.devicecheck.appattest-environment":"production"'; then
    echo "❌ DRY RUN: the exported app has no App Attest entitlement; leaderboard posts would fail."
    exit 1
  fi
  # Ask App Store Connect whether it would take this package, without
  # uploading it: this is where a missing platform on the app record, a
  # duplicate build number or an icon/Info.plist problem shows up, and a
  # rejected validation spends no build number. Reported, not enforced,
  # because a dry run is often of a build number that has already shipped,
  # which validation rightly calls a duplicate.
  echo "▸ Validating with App Store Connect (nothing is uploaded)…"
  ALTOOL_PLATFORM=ios; [ "$PLATFORM" = "maccatalyst" ] && ALTOOL_PLATFORM=macos
  # altool can print ERROR and still exit 0 (seen 2026-09-30 with "Cannot
  # determine the Apple ID from Bundle ID … and platform 'MAC_OS'", the app
  # record lacking the macOS platform), so read its output, not its status.
  VALIDATION=$(API_PRIVATE_KEYS_DIR="$(dirname "$ASC_KEY_PATH")" xcrun altool --validate-app "$PACKAGE" \
       --platform "$ALTOOL_PLATFORM" --api-key "$ASC_KEY_ID" --api-issuer "$ASC_ISSUER_ID" 2>&1) \
    && VALID_RC=0 || VALID_RC=$?
  printf '%s\n' "$VALIDATION"
  if [ "$VALID_RC" = "0" ] && ! grep -qE 'ERROR|error:' <<<"$VALIDATION"; then
    echo "▸ Validation passed."
  else
    echo "⚠️  Validation reported problems (above). A duplicate build number is expected when"
    echo "   re-running an already-uploaded number; anything else would fail the real upload."
  fi
  echo "✅ DRY RUN passed: archived, cloud-signed and exported ($PLATFORM). Nothing uploaded."
  exit 0
fi

echo "✅ Uploaded."
fi  # SUBMIT_ONLY

echo "▸ Waiting for processing, then submitting ($CHANNEL)…"

# The upload only puts the build in App Store Connect. Nobody outside the team
# sees it until it has (a) finished processing and (b) been submitted: to App
# Review on the App Store version for the production path, or to Beta App
# Review and assigned to testers for the TestFlight path. Poll until the build
# is VALID, then do that. (Skip by setting SKIP_DISTRIBUTE=1 to handle it in
# the ASC UI.)
if [ "${SKIP_DISTRIBUTE:-0}" != "1" ]; then
  # Wait for THIS build (by version) to finish processing — not just any VALID
  # build, or the submission would act on the previous one while this bakes.
  VER=$(grep -m1 'CURRENT_PROJECT_VERSION' MorseTrainer.xcodeproj/project.pbxproj | grep -oE '[0-9]+')
  MARKETING=$(grep -m1 'MARKETING_VERSION' MorseTrainer.xcodeproj/project.pbxproj | grep -oE '[0-9]+(\.[0-9]+)*')
  echo "  waiting for build $VER to finish processing…"
  python3 tools/asc-api.py wait "$VER"
  if [ "$CHANNEL" = "appstore" ]; then
    # Three passes, because App Review refuses a version whose age rating,
    # categories or listing are missing, and those records only settle once
    # the version exists with a build on it: attach without submitting,
    # apply the checked-in store metadata, then submit. The review contact
    # comes from ASC_REVIEW_FIRST_NAME/LAST_NAME/PHONE/EMAIL when set (CI
    # passes them from secrets); an existing contact is kept otherwise.
    ASC_NO_SUBMIT=1 python3 tools/asc-api.py appstore "$MARKETING" "$VER" "$NOTES"
    python3 tools/asc-api.py prepare "$MARKETING" tools/store-metadata.json
    python3 tools/asc-api.py appstore "$MARKETING" "$VER" "$NOTES"
    echo "✅ $MARKETING ($VER) is submitted to App Review and will release automatically once approved."
  else
    # What to Test goes on before the build reaches testers or Beta App
    # Review, so neither sees it blank.
    python3 tools/asc-api.py whatsnew "$VER" "$NOTES"
    python3 tools/asc-api.py dist      # assign the new build to the prior build's testers
    python3 tools/asc-api.py submit    # submit for beta review (fast-tracked on an approved train)
    echo "✅ Submitted for beta review and assigned to testers. They'll be emailed once approved."
  fi
else
  echo "ℹ️  SKIP_DISTRIBUTE=1 — submit the build in App Store Connect yourself."
fi
