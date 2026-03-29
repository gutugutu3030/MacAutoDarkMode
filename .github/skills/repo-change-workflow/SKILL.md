---
name: repo-change-workflow
description: Repository workflow for implementing changes in autoDarkMode. Use this when a task modifies Swift app logic, ALSBridge integration, build scripts, tests, or user-facing documentation.
argument-hint: Describe the requested change, touched area, and whether commands, settings, or automation behavior change.
---

# Repo Change Workflow

Use this skill when the task requires code changes, test updates, and documentation synchronization.

This repository uses one workflow skill instead of phase-specific skills because the required phases are tightly coupled. In typical implementation tasks, scope confirmation, code changes, test coverage, documentation updates, and validation must be handled together across Swift app code, the Objective-C bridge, and packaging.

## When To Use

- Add or modify Swift production code.
- Add or modify ALSBridge integration or Objective-C bridge code.
- Add or modify test logic.
- Change behavior, commands, settings, permissions, launch behavior, packaging, or GitHub automation documentation.

## Workflow

1. Confirm the scope.
   - Identify the touched area and the affected behavior.
   - If a small ambiguity remains, ask a concise question with explicit options instead of guessing.
2. Inspect documentation impact.
   - Decide whether the change affects the root `README.md`, `.github/README.md`, or nearby documentation.
   - Update the nearest relevant Markdown file in the same change.
3. Implement the code change.
   - Keep UI-related state changes aligned with existing `@MainActor` usage.
   - Keep private framework integration isolated to `Sources/ALSBridge` where practical.
   - Prefer small, targeted edits over package-wide refactors.
4. Add or update tests.
   - Add or extend Swift Package tests for new logic.
   - Cover both normal cases and failure or edge cases where the behavior can be exercised.
   - If no suitable test target exists, create the smallest useful one or report the concrete blocker.
5. Validate before finishing.
   - Run `swift build` for source, bridge, or package changes.
   - Run `swift test` when logic or tests changed.
   - Run `./Scripts/build-app.sh` when app bundle, resources, or launch behavior changed.
   - Finish only after the relevant validation passes, or report the exact blocker.

## Repository-Specific Checks

- Root `README.md` owns user-facing behavior, commands, calibration flow, and launch-at-login notes.
- `.github/README.md` owns GitHub workflow and release automation notes.
- Private APIs and osascript-based automation can require a real macOS runtime; if local validation cannot fully exercise them, call out the limitation explicitly.
- Keep pull request summaries and validation notes in Japanese.

## References

- [Repository Instructions](../../copilot-instructions.md)
- [Swift Development Rules](../../instructions/swift-development.instructions.md)
- [ALS Bridge Rules](../../instructions/als-bridge.instructions.md)