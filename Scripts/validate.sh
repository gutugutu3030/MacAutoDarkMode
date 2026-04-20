#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_BUILD=true
RUN_TEST=true

# 利用可能なオプションと、このスクリプトが担う検証範囲を表示する。
usage() {
  cat <<EOF
Usage: ./Scripts/validate.sh [--build-only | --test-only]

Runs Kotlin runtime validation for the repository.

- test path: root Gradle checks + debug executable link
- build path: Kotlin app bundle packaging via Scripts/build-app.sh
EOF
}

if [ "$#" -gt 1 ]; then
  usage >&2
  exit 1
fi

if [ "$#" -eq 1 ]; then
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

DEVELOPER_DIR="$(${ROOT_DIR}/Scripts/resolve-xcode-developer-dir.sh)"
export DEVELOPER_DIR

echo "Using developer directory: $DEVELOPER_DIR"
if xcrun -f xcodebuild >/dev/null 2>&1; then
  xcrun xcodebuild -version
else
  echo "xcodebuild unavailable for selected developer directory"
fi

if command -v java >/dev/null 2>&1; then
  java -version
fi

if [ "$RUN_TEST" = true ]; then
  echo "Running Gradle checks"
  (
    cd "$ROOT_DIR"
    ./gradlew check linkDebugExecutableMacosArm64
  )
fi

if [ "$RUN_BUILD" = true ]; then
  echo "Building Kotlin app bundle"
  "$ROOT_DIR/Scripts/build-app.sh"
fi