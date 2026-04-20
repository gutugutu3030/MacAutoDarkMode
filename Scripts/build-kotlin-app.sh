#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROTOTYPE_DIR="$ROOT_DIR/prototypes/kmp-menubar-poc"
DEVELOPER_DIR="$("$ROOT_DIR/Scripts/resolve-xcode-developer-dir.sh")"
APP_NAME="${APP_NAME:-autoDarkMode}"
EXECUTABLE_BASENAME="${EXECUTABLE_BASENAME:-kmp-menubar-poc}"
CONFIGURATION="${CONFIGURATION:-release}"
INFO_PLIST_TEMPLATE="$ROOT_DIR/AppResources/Info.plist"
DIST_DIR="$ROOT_DIR/dist"
APP_BUNDLE="$DIST_DIR/$APP_NAME.app"
CONTENTS_DIR="$APP_BUNDLE/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
RESOURCES_DIR="$CONTENTS_DIR/Resources"

case "$CONFIGURATION" in
  release|Release)
    GRADLE_TASK="linkReleaseExecutableMacosArm64"
    EXECUTABLE_DIR="releaseExecutable"
    ;;
  debug|Debug)
    GRADLE_TASK="linkDebugExecutableMacosArm64"
    EXECUTABLE_DIR="debugExecutable"
    ;;
  *)
    echo "Unsupported CONFIGURATION: $CONFIGURATION (expected debug or release)" >&2
    exit 1
    ;;
esac

EXECUTABLE="$PROTOTYPE_DIR/build/bin/macosArm64/$EXECUTABLE_DIR/${EXECUTABLE_BASENAME}.kexe"

export DEVELOPER_DIR

echo "Building Kotlin app shell for $APP_NAME ($CONFIGURATION)..."
echo "Using developer directory: $DEVELOPER_DIR"
(
  cd "$PROTOTYPE_DIR"
  ./gradlew "$GRADLE_TASK"
)

if [[ ! -x "$EXECUTABLE" ]]; then
  echo "Expected Kotlin executable not found: $EXECUTABLE" >&2
  exit 1
fi

rm -rf "$APP_BUNDLE"
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR"

cp "$INFO_PLIST_TEMPLATE" "$CONTENTS_DIR/Info.plist"
cp "$EXECUTABLE" "$MACOS_DIR/$APP_NAME"
chmod +x "$MACOS_DIR/$APP_NAME"

codesign --force --deep --sign - "$APP_BUNDLE" >/dev/null
codesign --verify --deep --strict "$APP_BUNDLE"

echo "Built Kotlin app bundle: $APP_BUNDLE"
echo "Open with: open '$APP_BUNDLE'"