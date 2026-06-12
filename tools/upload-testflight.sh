#!/bin/bash
# One-command TestFlight upload: archive the Release build and upload it to
# App Store Connect using the App Store Connect API key.
#
# Credentials come from tools/asc-auth.sh (gitignored). Bump the build number
# (CURRENT_PROJECT_VERSION) in the project before running, or App Store Connect
# will reject a duplicate build.
#
# Usage:  ./tools/upload-testflight.sh
set -euo pipefail
cd "$(dirname "$0")/.."

AUTH="tools/asc-auth.sh"
[ -f "$AUTH" ] || { echo "Missing $AUTH (API credentials). See tools/asc-auth.sh.example."; exit 1; }
# shellcheck disable=SC1090
source "$AUTH"

ARCHIVE="build/AMT-$(date +%Y%m%d-%H%M%S).xcarchive"
EXPORT_DIR="build/export"
rm -rf "$EXPORT_DIR"

echo "▸ Archiving (Release)…"
xcodebuild -project MorseTrainer.xcodeproj -scheme MorseTrainer -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" \
  archive -allowProvisioningUpdates \
  -authenticationKeyPath "$ASC_KEY_PATH" \
  -authenticationKeyID "$ASC_KEY_ID" \
  -authenticationKeyIssuerID "$ASC_ISSUER_ID"

echo "▸ Exporting + uploading to TestFlight…"
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE" \
  -exportOptionsPlist tools/ExportOptions.plist \
  -exportPath "$EXPORT_DIR" \
  -allowProvisioningUpdates \
  -authenticationKeyPath "$ASC_KEY_PATH" \
  -authenticationKeyID "$ASC_KEY_ID" \
  -authenticationKeyIssuerID "$ASC_ISSUER_ID"

echo "✅ Uploaded. Waiting for processing, then submitting for beta review + notifying testers…"

# The upload only puts the build in App Store Connect — external testers won't
# see it until it's (a) finished processing, (b) submitted for Beta App Review,
# and (c) assigned to them. Poll until the build is VALID, then do both. (Skip
# by setting SKIP_DISTRIBUTE=1 if you want to handle it in the ASC UI.)
if [ "${SKIP_DISTRIBUTE:-0}" != "1" ]; then
  for _ in $(seq 1 40); do
    if python3 tools/asc-api.py builds | grep -q "VALID"; then break; fi
    echo "  …still processing; checking again in 30s"
    sleep 30
  done
  python3 tools/asc-api.py dist      # assign the new build to the prior build's testers
  python3 tools/asc-api.py submit    # submit for beta review (fast-tracked on an approved train)
  echo "✅ Submitted for beta review and assigned to testers. They'll be emailed once approved."
else
  echo "ℹ️  SKIP_DISTRIBUTE=1 — submit for beta review + add testers in App Store Connect yourself."
fi
