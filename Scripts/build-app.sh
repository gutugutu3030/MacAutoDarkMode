#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_NAME="autoDarkMode"
CONFIGURATION="${CONFIGURATION:-release}"
BUILD_DIR="$ROOT_DIR/.build/$CONFIGURATION"
DIST_DIR="$ROOT_DIR/dist"
APP_BUNDLE="$DIST_DIR/$APP_NAME.app"
CONTENTS_DIR="$APP_BUNDLE/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
RESOURCES_DIR="$CONTENTS_DIR/Resources"
INFO_PLIST_TEMPLATE="$ROOT_DIR/AppResources/Info.plist"
EXECUTABLE="$BUILD_DIR/$APP_NAME"

echo "Building $APP_NAME ($CONFIGURATION)..."
swift build -c "$CONFIGURATION"

if [[ ! -x "$EXECUTABLE" ]]; then
  echo "Expected executable not found: $EXECUTABLE" >&2
  exit 1
fi

rm -rf "$APP_BUNDLE"
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR"

cp "$INFO_PLIST_TEMPLATE" "$CONTENTS_DIR/Info.plist"
cp "$EXECUTABLE" "$MACOS_DIR/$APP_NAME"
chmod +x "$MACOS_DIR/$APP_NAME"

if command -v codesign >/dev/null 2>&1; then
  codesign --force --deep --sign - "$APP_BUNDLE" >/dev/null 2>&1 || true
fi

echo "Built app bundle: $APP_BUNDLE"
echo "Open with: open '$APP_BUNDLE'"