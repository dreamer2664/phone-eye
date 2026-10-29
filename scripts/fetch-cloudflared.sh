#!/bin/sh
# Fetch the pinned cloudflared binary into app/src/main/jniLibs/arm64-v8a/libcloudflared.so.
#
# You only need this for OFFLINE local builds: the Gradle build downloads and verifies
# the same pinned asset itself (see app/build.gradle.kts, fetchCloudflared) and reuses
# a plausible binary placed by this script. Version + checksum MUST match Gradle's —
# tools/check_docs.py fails the build when they drift.
#
# cloudflared is (c) Cloudflare, Apache-2.0. We bundle the official linux-arm64 build;
# it runs from the app's nativeLibraryDir because Android 10+ forbids exec elsewhere.
set -eu

CLOUDFLARED_VERSION="2026.10.5"
CLOUDFLARED_SHA256="72e0d05a67e09da87cf495e409a28c3cc5994dd23fc66a982eebea6a55923b7d"

cd "$(dirname "$0")/.."
DEST="app/src/main/jniLibs/arm64-v8a/libcloudflared.so"
TMP="${DEST}.download"

mkdir -p "$(dirname "$DEST")"
URL="https://github.com/cloudflare/cloudflared/releases/download/${CLOUDFLARED_VERSION}/cloudflared-linux-arm64"
echo "phone-eye: downloading cloudflared ${CLOUDFLARED_VERSION} (~38 MB) ..."
curl -fL --retry 3 -o "$TMP" "$URL"

# Plausibility first (pc-eye's validate_binary): a proxy error page is not cloudflared.
SIZE=$(wc -c < "$TMP" | tr -d ' ')
if [ "$SIZE" -lt 5000000 ]; then
  echo "phone-eye: download is only ${SIZE} bytes — that is an error page, not cloudflared. Deleting." >&2
  rm -f "$TMP"; exit 1
fi
HEAD=$(head -c 3 "$TMP" | tail -c +2)
if [ "$HEAD" != "EL" ]; then
  echo "phone-eye: download does not start with an ELF header. Deleting." >&2
  rm -f "$TMP"; exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$TMP" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL=$(shasum -a 256 "$TMP" | awk '{print $1}')
else
  echo "phone-eye: need sha256sum or shasum to verify the download" >&2
  rm -f "$TMP"; exit 1
fi
if [ "$ACTUAL" != "$CLOUDFLARED_SHA256" ]; then
  echo "phone-eye: SHA-256 mismatch." >&2
  echo "  expected ${CLOUDFLARED_SHA256}" >&2
  echo "  actual   ${ACTUAL}" >&2
  echo "Refusing to bake an unverified binary into the APK. If Cloudflare re-released" >&2
  echo "the asset, bump the pin in app/build.gradle.kts AND this script together." >&2
  rm -f "$TMP"; exit 1
fi

mv "$TMP" "$DEST"
echo "phone-eye: verified and installed ${DEST} (${SIZE} bytes)"
echo "Note: Gradle strips Go debug info when an NDK is present, so CI APKs are smaller. Behaviour is identical."
