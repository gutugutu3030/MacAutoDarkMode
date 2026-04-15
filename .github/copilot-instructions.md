# autoDarkMode Copilot instructions

This repository is a Swift Package for a macOS menu bar app that reads the built-in ambient light sensor and switches the system between Light and Dark appearances. The main areas are `AmbientLight`, `Appearance`, `Application`, `UI`, the Objective-C `ALSBridge` target, `Scripts`, and GitHub workflows.

Prefer minimal, targeted changes in the relevant area instead of broad refactors across the package.

Write pull requests in Japanese. Use Japanese for the PR title, summary, validation notes, and review comments unless a file format requires another language.

If a request has a small ambiguity that can be resolved quickly, ask a concise question with explicit options instead of making assumptions.

For code changes in this repository:

- Keep AppKit, SwiftUI, timer-driven state, and observable UI state on the main actor when the surrounding code already assumes it.
- Keep private framework access isolated to `Sources/ALSBridge` or tightly scoped bridge code. Do not spread BezelServices or low-level sensor integration across Swift call sites without a clear need.
- Preserve the existing flow between ambient light sampling, settings persistence, automatic switching, and menu/settings UI. If thresholds, cooldown, automation toggles, or manual overrides change, update the affected surfaces together.
- When behavior, commands, settings, permissions, launch-at-login behavior, app resources, or release automation change, update the related Markdown in the same change. Update the root `README.md` for user-facing behavior and `.github/README.md` for GitHub automation changes.
- When you add new logic, add or extend Swift Package tests under `Tests/` where practical. Cover both success paths and failure or edge paths. If no suitable test target exists, create the smallest useful one instead of silently skipping tests.
- Do not delete, weaken, or rewrite existing tests unless the test is wrong or the user explicitly approved a breaking change.

Use the repository change workflow skill for implementation tasks that span code, tests, and documentation: [repo-change-workflow](./skills/repo-change-workflow/SKILL.md).

Always run relevant validation before finishing. Use the repository root unless a narrower command is enough.

- Source or package changes: `./Scripts/validate.sh --build-only`
- Logic changes with tests: `./Scripts/validate.sh`
- App bundle, resources, launch behavior, or packaging changes: `./Scripts/build-app.sh`
- Workflow or release-documentation changes: review `.github/workflows/` and keep `.github/README.md` synchronized

Target environment is Swift 6.2 on macOS 13 or newer. Apple Silicon is the primary path, using BezelServices plus IOHID for ambient light readings with `AppleLMUController` kept as a legacy fallback.

For local validation, prefer the repository scripts over calling `swift` directly. They use the current developer directory when it is sufficient and fall back to an installed Xcode when commands such as `swift test` need the Testing module.

Trust these instructions first and only search for more context when the affected area or task is not covered here.