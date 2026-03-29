# AI Customization Guide

This directory contains repository-level AI customization files for autoDarkMode.

## Structure

- [copilot-instructions.md](./copilot-instructions.md): always-on repository rules for the Swift Package, macOS menu bar app flow, and validation commands.
- [instructions/swift-development.instructions.md](./instructions/swift-development.instructions.md): Swift implementation rules for MainActor boundaries, logic changes, tests, and Markdown synchronization.
- [instructions/als-bridge.instructions.md](./instructions/als-bridge.instructions.md): Objective-C bridge rules for private framework access and ambient light sensor compatibility paths.
- [skills/repo-change-workflow/SKILL.md](./skills/repo-change-workflow/SKILL.md): reusable workflow skill for implementation tasks.

## Why One Skill

This repository intentionally uses a single implementation workflow skill instead of separate skills for each phase.

- Typical work crosses Swift app logic, the ALS bridge, build scripts, and user-facing documentation.
- The validation command depends on the touched area, but the decision belongs in one workflow.
- A single skill reduces the chance that code, tests, and docs drift out of sync.

If future work needs a clearly different workflow, add a separate skill for that distinct task instead of splitting the current implementation flow into artificial phases.