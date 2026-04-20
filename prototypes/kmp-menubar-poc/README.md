# KMP Menu Bar Prototype

This prototype checks whether Kotlin/Native can own the menu bar shell and a small slice of the presentation-update responsibilities that currently live in StatusBarController.

It is intentionally isolated from the Swift Package so failure or rollback does not affect the production app.

It now also exercises one production-adjacent update path: persisted settings writes via `NSUserDefaultsDidChangeNotification` feeding back into the menu presentation.

The current prototype keeps one Kotlin-owned runtime state store for menu actions, simulated events, and persisted-settings reloads. Notification-driven reload remains as a supplemental validation path rather than the primary mutation path.

Its persisted settings path now uses the same `switchMode`, `darkThresholdLux`, and `lightThresholdLux` UserDefaults contract as the shared KMP settings logic instead of prototype-only keys.

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

To simulate Accessibility denial for manual brightness-key monitoring:

```bash
cd prototypes/kmp-menubar-poc
KMP_MENUBAR_POC_ACCESSIBILITY_DENIED=1 ./build/bin/macosArm64/debugExecutable/kmp-menubar-poc.kexe
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

- single Kotlin-owned runtime state for menu mutations and persisted-settings application
- deferred presentation refresh scheduled in common run loop modes
- runtime-loaded ambient light sensor reads via BezelServices
- lux / appearance / message row updates
- radio-style mode state updates
- threshold visibility only in Auto mode
- separate BrightnessKeyMonitor-like and AutoSwitchEngine-like event inflow
- one production-adjacent settings path backed by `NSUserDefaultsDidChangeNotification`
- production `UserDefaults` key/rawValue contract via shared KMP settings logic
- auto-mode hysteresis with required consecutive samples and cooldown-backed switching
- manual-mode hold-to-light, release-after-max, and Accessibility-permission-required state
- Kotlin/AppKit settings window with mode picker, threshold sliders, current-value capture, and startup toggle wiring
- burst coalescing metrics for mutations per flush
- repeating simulated event timers also run in common modes so menu-open presentation does not stall
- stdout flush logging for headless verification

Non-goals:

- SwiftUI replacement
- Compose Multiplatform adoption

Notes:

- the startup toggle is only enabled when the executable is launched from a bundled `.app`
- the prototype uses its own LaunchAgent label so it does not overwrite the production Swift app's login item during experimentation
