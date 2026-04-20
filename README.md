# autoDarkMode

Minimal macOS menu bar utility that reads the built-in ambient light sensor and switches the system between Light and Dark appearances.

## Current implementation

- Apple Silicon primary path: IOHIDServiceClient via BezelServices.
- Legacy fallback: AppleLMUController.
- Appearance switching: osascript talking to System Events.
- App shell: Swift Package executable using AppKit and SwiftUI.

## Local development requirements

- The repository scripts prefer the current developer directory when it already supports the requested command.
- When `swift test` needs the `Testing` module and the current toolchain cannot provide it, the scripts fall back to an installed Xcode in `/Applications`.
- Full Xcode is still recommended locally because it guarantees `swift test` support, but `build-app.sh` can continue to use Command Line Tools when they are sufficient.
- The repository scripts assemble the local `AutoDarkModeKMP.xcframework` before invoking SwiftPM.

Recommended validation commands:

```bash
./Scripts/validate.sh
./Scripts/validate.sh --build-only
```

## Switching modes

The app supports three switching modes, selectable from the menu bar or the settings window:

| Mode | Behavior |
|------|----------|
| **Off** | Appearance switching is disabled. |
| **Auto** | Switches automatically based on the ambient light sensor. Uses configurable dark/light thresholds with hysteresis. |
| **Manual** | Switches based on display brightness setting. When brightness drops below the near-maximum threshold → Dark mode. Without Accessibility permission, reaching the near-maximum threshold still switches to Light mode. With Accessibility permission, reaching the threshold while Brightness Up is still held only arms Light mode after you release the key once, then hold Brightness Up again while already at or near maximum. |

The selected mode is persisted across app launches.

## Build a bundle

```bash
./Scripts/build-app.sh
```

This script uses the current developer directory when it can build the package and falls back to an installed Xcode only when needed.

It also assembles the local KMP XCFramework before building the Swift app.

## Tag-based release

Pushing a tag such as v0.1.1 now triggers GitHub Actions to:

- build the macOS app bundle on a macOS runner
- package dist/autoDarkMode.app as a zip
- create or publish a GitHub Release for that tag
- attach the zip and a sha256 file

Example:

```bash
git tag v0.1.1
git push origin v0.1.1
```

This workflow currently builds a releasable artifact, but it does not notarize the app. If you want notarization later, you can extend the workflow with Developer ID and notary credentials stored as GitHub secrets.

## Run

```bash
open dist/autoDarkMode.app
```

The bundled app launches as an accessory app and adds a menu bar item. Opening the settings window lets you adjust thresholds, force a manual switch, and enable launch at login.

If you still want the raw executable during development:

```bash
cd kmp && ./gradlew assembleAutoDarkModeKMPReleaseXCFramework
DEVELOPER_DIR="$(./Scripts/resolve-xcode-developer-dir.sh)" swift run
```

## Sample the sensor in terminal

```bash
cd kmp && ./gradlew assembleAutoDarkModeKMPReleaseXCFramework
DEVELOPER_DIR="$(./Scripts/resolve-xcode-developer-dir.sh)" swift run autoDarkMode sample --count 20 --interval 1
```

Use this when calibrating on a real machine. It prints the current ambient light value and the sensor path being used.

For a continuous stream:

```bash
cd kmp && ./gradlew assembleAutoDarkModeKMPReleaseXCFramework
DEVELOPER_DIR="$(./Scripts/resolve-xcode-developer-dir.sh)" swift run autoDarkMode watch --interval 1
```

Practical calibration flow:

- Run the sampler in a dark room and note the median value.
- Run it again in a bright room or outdoors and note the median value.
- Open the app settings and use the current-value buttons to capture a dark threshold and a light threshold from those environments.

The current code defaults are intentionally outdoor-biased:

- Dark threshold: 3.0 klx
- Light threshold: 12.0 klx
- Consecutive samples: 3

## Launch at login

The settings window includes a Launch automatically at login checkbox.

- It is only enabled when the app is running from dist/autoDarkMode.app or another .app bundle.
- It writes a per-user LaunchAgent in ~/Library/LaunchAgents.
- The change takes effect on the next login.

## Caveats

- This uses private APIs. Mac App Store distribution is out of scope.
- Direct distribution with Developer ID signing and notarization is the intended path.
- The first automatic appearance change triggers macOS automation permission prompts for System Events.
- The optional brightness-key shortcut assist in Manual mode requires macOS Accessibility permission.
- Future macOS releases may break the sensor path.