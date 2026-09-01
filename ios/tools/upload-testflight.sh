#!/bin/bash
# One-command TestFlight upload: archive the Release build and upload it to
# App Store Connect using the App Store Connect API key.
#
# Credentials come from tools/asc-auth.sh (gitignored). Bump the build number
# (CURRENT_PROJECT_VERSION) in the project before running, or App Store Connect
# will reject a duplicate build.
#
# Usage:  ./tools/upload-testflight.sh
#         DRY_RUN=1 ./tools/upload-testflight.sh        # build + sign, no upload
#         SKIP_DISTRIBUTE=1 ./tools/upload-testflight.sh # upload, don't submit
#
# This script is the single code path for TestFlight releases: `ios-release.yml`
# runs this exact file rather than reimplementing the steps in YAML, so CI and a
# local run cannot drift apart. CI supplies the credentials by writing the same
# gitignored tools/asc-auth.sh this reads locally.
set -euo pipefail
cd "$(dirname "$0")/.."

AUTH="tools/asc-auth.sh"
[ -f "$AUTH" ] || { echo "Missing $AUTH (API credentials). See tools/asc-auth.sh.example."; exit 1; }
# shellcheck disable=SC1090
source "$AUTH"

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

echo "✅ Uploaded. Waiting for processing, then submitting for beta review + notifying testers…"

# The upload only puts the build in App Store Connect — external testers won't
# see it until it's (a) finished processing, (b) submitted for Beta App Review,
# and (c) assigned to them. Poll until the build is VALID, then do both. (Skip
# by setting SKIP_DISTRIBUTE=1 if you want to handle it in the ASC UI.)
if [ "${SKIP_DISTRIBUTE:-0}" != "1" ]; then
  # Wait for THIS build (by version) to finish processing — not just any VALID
  # build, or dist/submit would act on the previous one while this still bakes.
  VER=$(grep -m1 'CURRENT_PROJECT_VERSION' MorseTrainer.xcodeproj/project.pbxproj | grep -oE '[0-9]+')
  echo "  waiting for build $VER to finish processing…"
  python3 tools/asc-api.py wait "$VER"
  python3 tools/asc-api.py dist      # assign the new build to the prior build's testers
  python3 tools/asc-api.py submit    # submit for beta review (fast-tracked on an approved train)
  echo "✅ Submitted for beta review and assigned to testers. They'll be emailed once approved."
else
  echo "ℹ️  SKIP_DISTRIBUTE=1 — submit for beta review + add testers in App Store Connect yourself."
fi
