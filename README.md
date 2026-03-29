# autoDarkMode

Minimal macOS menu bar utility that reads the built-in ambient light sensor and switches the system between Light and Dark appearances.

## Current implementation

- Apple Silicon primary path: IOHIDServiceClient via BezelServices.
- Legacy fallback: AppleLMUController.
- Appearance switching: osascript talking to System Events.
- App shell: Swift Package executable using AppKit and SwiftUI.

## Build

```bash
swift build
```

## Run

```bash
swift run
```

The process launches as an accessory app and adds a menu bar item. Opening the settings window lets you adjust thresholds and force a manual switch.

## Sample the sensor in terminal

```bash
swift run autoDarkMode sample --count 20 --interval 1
```

Use this when calibrating on a real machine. It prints the current ambient light value and the sensor path being used.

For a continuous stream:

```bash
swift run autoDarkMode watch --interval 1
```

Practical calibration flow:

- Run the sampler in a dark room and note the median value.
- Run it again in a bright room or outdoors and note the median value.
- Open the app settings and use the current-value buttons to capture a dark threshold and a light threshold from those environments.

The current code defaults are intentionally outdoor-biased:

- Dark threshold: 3.0 klx
- Light threshold: 12.0 klx
- Consecutive samples: 3

## Caveats

- This uses private APIs. Mac App Store distribution is out of scope.
- Direct distribution with Developer ID signing and notarization is the intended path.
- The first automatic appearance change triggers macOS automation permission prompts for System Events.
- Future macOS releases may break the sensor path.