#!/usr/bin/env bash
set -euo pipefail

# Android's standard debug signing configuration reads this location. Store a
# single debug keystore in an Actions secret; never use a release signing key.
if [[ -z "${DEBUG_KEYSTORE_BASE64:-}" ]]; then
  echo "::warning::DEBUG_KEYSTORE_BASE64 is not configured. This APK uses a temporary debug key and may not update an installed app. See README.md."
  exit 0
fi

umask 077
mkdir -p "$HOME/.android"
keystore_tmp=$(mktemp "$HOME/.android/debug.keystore.XXXXXX")
trap 'rm -f "$keystore_tmp"' EXIT
printf '%s' "$DEBUG_KEYSTORE_BASE64" | base64 --decode > "$keystore_tmp"
keytool -list -keystore "$keystore_tmp" -storepass android -alias androiddebugkey > /dev/null
mv "$keystore_tmp" "$HOME/.android/debug.keystore"
echo "Restored persistent debug signing key."
