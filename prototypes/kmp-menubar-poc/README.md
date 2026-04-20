# KMP Menu Bar Prototype

This prototype checks whether Kotlin/Native can own the menu bar shell and a small slice of the presentation-update responsibilities that currently live in StatusBarController.

It is intentionally isolated from the Swift Package so failure or rollback does not affect the production app.

It now also exercises one production-adjacent update path: persisted settings writes via `NSUserDefaultsDidChangeNotification` feeding back into the menu presentation.

## Build

```bash
cd prototypes/kmp-menubar-poc
./gradlew linkDebugExecutableMacosArm64
```

## Run

```bash
cd prototypes/kmp-menubar-poc
./build/bin/macosArm64/debugExecutable/kmp-menubar-poc.kexe
```

To validate the persisted-settings notification path without manual menu interaction:

```bash
cd prototypes/kmp-menubar-poc
KMP_MENUBAR_POC_VALIDATE_DEFAULTS=1 ./build/bin/macosArm64/debugExecutable/kmp-menubar-poc.kexe
```

## Quick smoke check

In another terminal:

```bash
pgrep -fl 'kmp-menubar-poc\\.kexe|kmp-menubar-poc'
```

Success criteria:

- build a native macOS executable from Kotlin/Native
- show a menu bar item with icon and tooltip updates
- open a menu whose items change with simulated state
- switch Off/Auto/Manual states from Kotlin menu actions
- hide and show the threshold row based on mode
- reflect persisted settings changes without restarting the process
- quit cleanly from a `Quit` menu item

Current verified behaviors:

- deferred presentation refresh via a zero-delay timer
- runtime-loaded ambient light sensor reads via BezelServices
- lux / appearance / message row updates
- radio-style mode state updates
- threshold visibility only in Auto mode
- separate BrightnessKeyMonitor-like and AutoSwitchEngine-like event inflow
- one production-adjacent settings path backed by `NSUserDefaultsDidChangeNotification`
- burst coalescing metrics for mutations per flush
- stdout flush logging for headless verification

Non-goals:

- SwiftUI replacement
- Compose Multiplatform adoption
- settings persistence
- launch-at-login
