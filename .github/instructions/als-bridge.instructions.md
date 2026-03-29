---
name: ALS Bridge Rules
description: Objective-C bridge changes must keep private API access isolated to Sources/ALSBridge and preserve ambient light sensor compatibility paths.
applyTo: "Sources/ALSBridge/**/*.{h,m}"
---

For Objective-C bridge files under `Sources/ALSBridge`, follow these rules whenever you add or modify code.

- Keep BezelServices, IOKit, and other private or low-level integration details isolated to the bridge instead of spreading them into Swift call sites.
- Preserve both compatibility paths when practical: IOHIDServiceClient plus BezelServices as the primary path, and `AppleLMUController` as the legacy fallback.
- Keep the exported C and Objective-C surface minimal and stable for the Swift target.
- When memory ownership, CoreFoundation bridging, or runtime symbol lookup becomes non-obvious, add concise comments near the relevant block.
- If a bridge change affects user-visible behavior or sensor availability, update the relevant Markdown documentation in the same change.
- Validate bridge changes with `swift build`, and also run `./Scripts/build-app.sh` when the change can affect app bundling or runtime loading behavior.