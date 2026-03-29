---
name: Swift Development Rules
description: Swift source changes for autoDarkMode must preserve MainActor UI boundaries, keep automation behavior consistent, and add tests for new logic.
applyTo: "**/*.swift"
---

For Swift source files in this repository, follow these rules whenever you add or modify code.

Architecture requirements:

- Keep AppKit, SwiftUI, timers, status bar state, and window-controller state on the main actor when the surrounding code already relies on main-thread execution.
- Keep ambient light sampling, settings persistence, automatic switching, and UI presentation aligned with the existing flow: monitor -> settings -> AutoSwitchEngine -> UI.
- Prefer small changes inside the relevant area instead of broad cross-cutting refactors.

Documentation and comments:

- Add concise doc comments to added or modified types or functions when the behavior is non-obvious, externally consumed, or coordinates with private APIs or system automation.
- For complex logic, add brief Japanese step-by-step comments immediately before the relevant block.
- Do not add comments for obvious single-line code.

Testing requirements:

- When you add new logic or branching behavior, add or extend Swift Package tests under `Tests/`.
- Cover at least one normal path and one failure or edge path when the behavior can be exercised.
- If no test target exists for the touched area, create the smallest useful test target instead of skipping tests without explanation.

Documentation requirements:

- If the change affects behavior, commands, settings, permissions, launch-at-login behavior, or build and release flow, update the related Markdown in the same change.