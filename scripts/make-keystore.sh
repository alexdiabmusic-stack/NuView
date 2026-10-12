#!/usr/bin/env bash
# Creates the release signing key for TV Essentials ONCE. Keep the resulting files OUT of git (they are git-ignored).
#
#   scripts/make-keystore.sh [output-dir]
#
# It writes tve-release.jks and keystore.properties (used by Gradle), and prints the base64 you can paste into the
# GitHub Actions secret TVE_KEYSTORE_BASE64. Losing this key means installed copies can never be updated in place.
set -euo pipefail

OUT="${1:-.}"
mkdir -p "$OUT"
JKS="$OUT/tve-release.jks"
[ ! -e "$JKS" ] || { echo "$JKS already exists; refusing to overwrite a signing key." >&2; exit 1; }

ALIAS="tveessentials"
read -r -s -p "Choose a keystore password (min 8 chars): " PASS; echo
[ "${#PASS}" -ge 8 ] || { echo "Password too short." >&2; exit 1; }

keytool -genkeypair -v -keystore "$JKS" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=TV Essentials, O=TV Essentials"

cat > "$OUT/keystore.properties" <<PROPS
storeFile=$(cd "$OUT" && pwd)/tve-release.jks
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
PROPS
chmod 600 "$JKS" "$OUT/keystore.properties"

echo
echo "Created: $JKS and $OUT/keystore.properties (both git-ignored; back them up somewhere safe)."
echo "Certificate SHA-256 (publish this so users can verify your APK):"
keytool -list -v -keystore "$JKS" -alias "$ALIAS" -storepass "$PASS" | grep "SHA256:" | head -1
echo
echo "For GitHub Actions, add these repository secrets:"
echo "  TVE_KEYSTORE_BASE64   = $(base64 -w0 "$JKS" 2>/dev/null || base64 "$JKS" | tr -d '\n')" | cut -c1-80 | sed 's/$/…  (run: base64 -w0 tve-release.jks)/'
echo "  TVE_KEYSTORE_PASSWORD = (the password you just chose)"
echo "  TVE_KEY_ALIAS         = $ALIAS"
echo "  TVE_KEY_PASSWORD      = (the same password)"
