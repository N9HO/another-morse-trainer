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
#   testflight the beta path this script used to be: submit for Beta App
#              Review and hand the build to the previous build's testers.
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

if [ "${SUBMIT_ONLY:-0}" = "1" ] && [ "${DRY_RUN:-0}" = "1" ]; then
  echo "❌ SUBMIT_ONLY and DRY_RUN together do nothing; pick one."; exit 1
fi

ARCHIVE="build/AMT-$(date +%Y%m%d-%H%M%S).xcarchive"
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
  -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
  archive -allowProvisioningUpdates \
  -authenticationKeyPath "$ASC_KEY_PATH" \
  -authenticationKeyID "$ASC_KEY_ID" \
  -authenticationKeyIssuerID "$ASC_ISSUER_ID"

echo "▸ Exporting + uploading to TestFlight…"
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
  UNPACK="$(mktemp -d)"
  unzip -qo "$EXPORT_DIR"/*.ipa -d "$UNPACK"
  # Capture once rather than piping codesign into `grep -q` twice: grep -q exits
  # on its first match, codesign takes SIGPIPE, and `set -o pipefail` then reports
  # the whole pipeline as failed even though the signature was fine.
  SIGINFO=$(codesign -dvvv "$UNPACK"/Payload/*.app 2>&1)
  echo "▸ Signature on the exported app:"
  printf '%s\n' "$SIGINFO" \
    | grep -E '^Authority=|^TeamIdentifier=|^Identifier=' | sed 's/^/    /'
  if printf '%s\n' "$SIGINFO" | grep -q '^Authority=Apple Distribution'; then
    echo "✅ DRY RUN passed: archived, cloud-signed and exported. Nothing uploaded."
  else
    echo "❌ DRY RUN: export is not signed by an Apple Distribution certificate."
    exit 1
  fi
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
    NOTES="tools/whatsnew/whatsnew-en-US"
    if [ ! -s "$NOTES" ]; then
      echo "❌ $NOTES is missing or empty. App Review needs What's New; write it before releasing."
      exit 1
    fi
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
    python3 tools/asc-api.py dist      # assign the new build to the prior build's testers
    python3 tools/asc-api.py submit    # submit for beta review (fast-tracked on an approved train)
    echo "✅ Submitted for beta review and assigned to testers. They'll be emailed once approved."
  fi
else
  echo "ℹ️  SKIP_DISTRIBUTE=1 — submit the build in App Store Connect yourself."
fi
