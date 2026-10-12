#!/usr/bin/env bash
# Prints what the catalog needs to know about an APK: package id, version, minSdk, and the SHA-256 of its
# signing certificate(s), measured with the Android SDK's own tools (apksigner / aapt2).
#
#   scripts/measure-apk.sh path/to/app.apk
#
# Use it when adding an app or rotating a publisher's key: download the APK from the publisher's OFFICIAL
# source, run this, and put the digest into app/src/main/assets/catalog/catalog.json under "trust.signers".
# Always confirm the fingerprint out-of-band (publisher's site/repo/announcement) before trusting it.
set -euo pipefail

APK="${1:?usage: $0 path/to/app.apk}"
[ -f "$APK" ] || { echo "not a file: $APK" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "Set ANDROID_HOME to your Android SDK (needs build-tools)." >&2; exit 1; }
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] || { echo "No build-tools found under $SDK" >&2; exit 1; }

echo "== file"
echo "name:   $(basename "$APK")"
echo "size:   $(stat -c %s "$APK" 2>/dev/null || stat -f %z "$APK") bytes"
echo "sha256: $( (sha256sum "$APK" 2>/dev/null || shasum -a 256 "$APK") | cut -d' ' -f1)"

echo
echo "== identity (aapt2)"
"$BT/aapt2" dump badging "$APK" | grep -E "^(package:|minSdkVersion|targetSdkVersion|native-code|application-label:|leanback-launchable-activity)" | cut -c1-200

echo
echo "== signature (apksigner)"
OUT="$("$BT/apksigner" verify --print-certs "$APK" 2>&1 | grep -v '^WARNING' || true)"
echo "$OUT" | grep -E "Verifies|DOES NOT VERIFY|ERROR|certificate DN|certificate SHA-256" || echo "$OUT"

echo
echo "== catalog snippet"
echo "$OUT" | grep -oE "certificate SHA-256 digest: [0-9a-fA-F]{64}" | awk '{print tolower($NF)}' | sort -u | while read -r d; do
  cat <<JSON
{
  "sha256": "$d",
  "label": "<publisher> release key",
  "since": "$(date -u +%Y-%m-%d)",
  "note": "Measured with apksigner on <official source URL>; confirmed against <out-of-band reference>."
}
JSON
done
