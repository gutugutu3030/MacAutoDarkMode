# .github

This directory contains GitHub-specific automation for the repository.

## Workflows

- workflows/ci.yml: runs pull-request and manual validation on a macOS runner with separate `runtime-validation` and `gradle-check` jobs. `runtime-validation` executes `./Scripts/validate.sh`, while `gradle-check` executes `./gradlew check` from the repository root.
- workflows/release.yml: links the Kotlin/Native arm64 executable from the repository root Gradle project, assembles `dist/autoDarkMode.app`, verifies the ad-hoc signature, zips the app bundle, and publishes a GitHub Release when a version tag such as v0.1.1 is pushed.

## Notes

- Both workflows now install Java 21, enable Gradle caching, and cache `~/.konan` so Kotlin/Native checks do not re-download the toolchain on every run.
- The repository Gradle wrapper is pinned to 9.3.0 so local IDE imports can use Java 21 through Java 25 while CI remains on Java 21.
- The CI validation job now treats the root Gradle runtime as the primary implementation: `./Scripts/validate.sh` runs `./gradlew check`, debug executable linking, and app bundle packaging.
- The CI and release workflows resolve an installed developer directory with `Scripts/resolve-xcode-developer-dir.sh` before invoking Gradle or app-bundle scripts, so automation does not depend on a runner-specific fixed Xcode path.
- The release workflow cache key now tracks the root Gradle build files, Kotlin source tree, bundle shell scripts, and `AppResources/Info.plist`, because those files define the shipped artifact.
- Local scripts prefer the current developer directory first and rely on the root Gradle wrapper for builds and tests.
- The current workflow uses ad-hoc signing plus an explicit `codesign --verify --deep --strict` check before packaging, which is enough for attaching artifacts to GitHub Releases.
- Developer ID signing and notarization can be added later with GitHub Actions secrets.