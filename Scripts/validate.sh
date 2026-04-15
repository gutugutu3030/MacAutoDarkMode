#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_BUILD=true
RUN_TEST=true

usage() {
  cat <<EOF
Usage: ./Scripts/validate.sh [--build-only | --test-only]

Runs repository validation using the current developer directory when possible,
and falls back to full Xcode when swift test needs the Testing module.
EOF
}

if (( $# > 1 )); then
  usage >&2
  exit 1
fi

if (( $# == 1 )); then
  case "$1" in
    --build-only)
      RUN_TEST=false
      ;;
    --test-only)
      RUN_BUILD=false
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 1
      ;;
  esac
fi

cd "$ROOT_DIR"

typeset -a resolve_args
if [[ "$RUN_TEST" == true ]]; then
  resolve_args+=(--require-testing)
fi

DEVELOPER_DIR="$($ROOT_DIR/Scripts/resolve-xcode-developer-dir.sh "${resolve_args[@]}")"
export DEVELOPER_DIR

SWIFT_BIN="$(xcrun -f swift)"

echo "Using developer directory: $DEVELOPER_DIR"
if xcrun -f xcodebuild >/dev/null 2>&1; then
  xcrun xcodebuild -version
else
  echo "xcodebuild unavailable for selected developer directory"
fi
"$SWIFT_BIN" --version

if [[ "$RUN_BUILD" == true ]]; then
  echo "Running swift build"
  "$SWIFT_BIN" build
fi

if [[ "$RUN_TEST" == true ]]; then
  echo "Running swift test"
  "$SWIFT_BIN" test
fi