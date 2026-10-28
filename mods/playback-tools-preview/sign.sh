#!/usr/bin/env bash
# Public LineageOS cm-14.1 DEVELOPMENT platform keys, as used by this ROM's default build.
# Installer refuses to update a ROM signed with a different certificate.
set -euo pipefail
INPUT="$1"; OUTPUT="$2"; KEYS="$(mktemp -d)"; trap 'rm -rf "$KEYS"' EXIT
for name in platform.pk8 platform.x509.pem; do
 curl -fLsS "https://raw.githubusercontent.com/LineageOS/android_build/cm-14.1/target/product/security/$name" -o "$KEYS/$name"
done
printf '%s  %s\n' 1ad8ef556870edb70f69a9d3c112544c07de5162ba440d84d33f8bb0c5962875 "$KEYS/platform.pk8" 9837de028f460c35cc8d3fa45f14eecce30f6fbfe4b93d399aef1acb80c20d14 "$KEYS/platform.x509.pem" | sha256sum -c -
BT="${ANDROID_HOME:?}/build-tools/34.0.0"
"$BT/zipalign" -f 4 "$INPUT" "$KEYS/aligned.apk"
"$BT/apksigner" sign --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" --min-sdk-version 24 --out "$OUTPUT" "$KEYS/aligned.apk"
"$BT/apksigner" verify --verbose --print-certs "$OUTPUT"
