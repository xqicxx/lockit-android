#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_APK="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
LOCAL_APK="${1:-$DEFAULT_APK}"
REMOTE_TAG="${2:-}"
TMP_DIR="${TMPDIR:-/tmp}/lockit-release-parity"

find_apksigner() {
  local candidates=()
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    candidates+=("$ANDROID_SDK_ROOT")
  fi
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    candidates+=("$ANDROID_HOME")
  fi
  candidates+=("$HOME/Library/Android/sdk")

  local sdk_root
  for sdk_root in "${candidates[@]}"; do
    [[ -d "$sdk_root/build-tools" ]] || continue
    find "$sdk_root/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -n 1
    return 0
  done

  return 1
}

print_apk_summary() {
  local label="$1"
  local apk="$2"
  local apksigner="$3"

  echo "== $label =="
  echo "APK: $apk"
  shasum -a 256 "$apk"
  "$apksigner" verify --print-certs "$apk" | sed -n '1,4p'
  echo
}

if [[ ! -f "$LOCAL_APK" ]]; then
  echo "Local APK not found: $LOCAL_APK" >&2
  echo "Build it first with: ./gradlew assembleRelease" >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required for remote release checks." >&2
  exit 1
fi

APKSIGNER_BIN="$(find_apksigner || true)"
if [[ -z "$APKSIGNER_BIN" ]]; then
  echo "Unable to find apksigner under ANDROID_SDK_ROOT/ANDROID_HOME/$HOME/Library/Android/sdk." >&2
  exit 1
fi

mkdir -p "$TMP_DIR"
print_apk_summary "Local release" "$LOCAL_APK" "$APKSIGNER_BIN"

if [[ -n "$REMOTE_TAG" ]]; then
  REMOTE_APK="$TMP_DIR/${REMOTE_TAG}-app-release.apk"
  REMOTE_URL="$(gh api "repos/xqicxx/lockit-android/releases/tags/$REMOTE_TAG" --jq '.assets[] | select(.name=="app-release.apk") | .browser_download_url')"
  if [[ -z "$REMOTE_URL" ]]; then
    echo "Unable to resolve app-release.apk for tag $REMOTE_TAG." >&2
    exit 1
  fi

  curl -L --fail --silent --show-error "$REMOTE_URL" -o "$REMOTE_APK"

  print_apk_summary "GitHub release $REMOTE_TAG" "$REMOTE_APK" "$APKSIGNER_BIN"

  local_signer="$("$APKSIGNER_BIN" verify --print-certs "$LOCAL_APK" | awk -F': ' '/SHA-256 digest/ {print $2; exit}')"
  remote_signer="$("$APKSIGNER_BIN" verify --print-certs "$REMOTE_APK" | awk -F': ' '/SHA-256 digest/ {print $2; exit}')"

  if [[ "$local_signer" != "$remote_signer" ]]; then
    echo "Signer mismatch: local=$local_signer remote=$remote_signer" >&2
    exit 1
  fi

  echo "Signer match confirmed for local APK and $REMOTE_TAG."
fi
