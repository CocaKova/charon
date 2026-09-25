#!/usr/bin/env bash
# sign-release.sh — sign a release APK with the release key, on THIS machine.
#
# A build host never sees the release key: tools/remote-gradle.sh builds the release APK there
# (signed with the host's throwaway debug key) and brings it back; this re-signs it with the key
# local.properties names (charon.keystore / .password / key.alias / key.password — the same
# properties a local Gradle build uses) and writes dist/charon-<version>[+obol].apk.
#
#   tools/sign-release.sh [foss|obol]      (default foss)
#
# apksigner replaces any existing signature. Needs apksigner on PATH, ANDROID_HOME, or the
# Android env script at $CHARON_ANDROID_ENV (default ~/android-buildenv/env.sh).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$REPO_ROOT/local.properties"
FLAVOR="${1:-foss}"
IN="$REPO_ROOT/app/build/outputs/apk/$FLAVOR/release/app-$FLAVOR-release.apk"

prop() { grep -E "^$1=" "$PROPS" 2>/dev/null | head -1 | cut -d= -f2-; }
KS="$(prop charon.keystore)"
[ -n "$KS" ] || { echo "no charon.keystore in local.properties — nothing to sign with" >&2; exit 2; }
[ -f "$KS" ] || { echo "keystore not found: $KS" >&2; exit 2; }
[ -f "$IN" ] || { echo "no release APK at $IN" >&2; exit 2; }

if ! command -v apksigner >/dev/null; then
  ENV_SH="${CHARON_ANDROID_ENV:-$HOME/android-buildenv/env.sh}"
  # shellcheck disable=SC1090
  [ -f "$ENV_SH" ] && source "$ENV_SH"
  if ! command -v apksigner >/dev/null && [ -n "${ANDROID_HOME:-}" ]; then
    BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
    PATH="$BT:$PATH"
  fi
fi
command -v apksigner >/dev/null || { echo "apksigner not found" >&2; exit 2; }

VERSION="$(grep -E '^\s*versionName\s*=' "$REPO_ROOT/app/build.gradle.kts" | head -1 | sed -E 's/.*"(.*)".*/\1/')"
[ "$FLAVOR" = obol ] && VERSION="$VERSION+obol"
mkdir -p "$REPO_ROOT/dist"
OUT="$REPO_ROOT/dist/charon-$VERSION.apk"

KS_PASS="$(prop charon.keystore.password)" KEY_PASS="$(prop charon.key.password)" \
  apksigner sign --ks "$KS" --ks-key-alias "$(prop charon.key.alias)" \
    --ks-pass env:KS_PASS --key-pass env:KEY_PASS --out "$OUT" "$IN"
rm -f "$OUT.idsig"

apksigner verify --print-certs "$OUT" | grep -E "Signer #1 certificate (DN|SHA-256)"
echo "signed: $OUT"
