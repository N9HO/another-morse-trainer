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

echo "✅ Uploaded. It will appear in App Store Connect → TestFlight after processing."
