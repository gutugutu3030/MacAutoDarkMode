# autoDarkMode

Minimal macOS menu bar utility that reads the built-in ambient light sensor and switches the system between Light and Dark appearances.

## Current implementation

- Ambient light sampling: IOHIDServiceClient via BezelServices.
- Appearance switching: osascript talking to System Events.
- Packaged app shell: Kotlin/Native AppKit executable built from the root Gradle project.
- The repository no longer carries the old Swift runtime or split prototype/KMP subprojects.

## Local development requirements

- The repository scripts prefer the current developer directory when it already supports the requested command.
- Use Java 21 or newer for Gradle-based development. IntelliJ import uses the configured Gradle JVM, so set it to a supported JDK under Settings > Build, Execution, Deployment > Build Tools > Gradle.
- Full Xcode is still recommended locally because it guarantees Kotlin/Native Apple target compilation.
- The packaged app bundle is produced from the root Gradle project via `Scripts/build-kotlin-app.sh`.
- `./Scripts/validate.sh` now validates the root Gradle project by running `./gradlew check`, debug executable linking, and bundle packaging.

The Gradle wrapper is pinned to 9.4.1 so the project can be imported with current IntelliJ builds using Java 21 through Java 25. CI continues to validate on Java 21.

Recommended validation commands:

```bash
./Scripts/validate.sh
./Scripts/validate.sh --build-only
./gradlew spotlessApply
./gradlew check
```

`./gradlew spotlessApply` で Kotlin ソースと Gradle Kotlin DSL を整形し、`./gradlew check` で整形チェックを含む検証を実行できます。

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

`build-app.sh` is the stable entrypoint and now forwards to `Scripts/build-kotlin-app.sh`, which links the Kotlin/Native macOS arm64 executable, assembles `dist/autoDarkMode.app`, and ad-hoc signs the result.

If you need a differently named bundle for local packaging, you can override `APP_NAME`; the generated bundle name, executable name, and corresponding `Info.plist` display/executable fields stay in sync.

```bash
APP_NAME="autoDarkMode Dev" ./Scripts/build-app.sh
```

## Tag-based release

Pushing a tag such as v0.1.1 now triggers GitHub Actions to:

- link the Kotlin/Native arm64 executable and build the macOS app bundle on a macOS runner
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
./gradlew linkDebugExecutableMacosArm64
./build/bin/macosArm64/debugExecutable/autoDarkMode.kexe
```

## Sample the sensor in terminal

```bash
./gradlew linkDebugExecutableMacosArm64
./build/bin/macosArm64/debugExecutable/autoDarkMode.kexe sample --count 20 --interval 1
```

Use this when calibrating on a real machine. It prints the current ambient light value and the sensor path being used.

For a continuous stream:

```bash
./gradlew linkDebugExecutableMacosArm64
./build/bin/macosArm64/debugExecutable/autoDarkMode.kexe watch --interval 1
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