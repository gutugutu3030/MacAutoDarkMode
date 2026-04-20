# .github

This directory contains GitHub-specific automation for the repository.

## Workflows

- workflows/ci.yml: runs pull-request and manual validation on a macOS runner with separate `swift-test` and `kmp-test` jobs. `swift-test` executes `./Scripts/validate.sh`, while `kmp-test` executes `cd kmp && ./gradlew check`.
- workflows/release.yml: links the Kotlin/Native arm64 executable from `prototypes/kmp-menubar-poc`, assembles `dist/autoDarkMode.app`, verifies the ad-hoc signature, zips the app bundle, and publishes a GitHub Release when a version tag such as v0.1.1 is pushed.

## Notes

- Both workflows now install Java 21, enable Gradle caching, and cache `~/.konan` so Kotlin/Native checks do not re-download the toolchain on every run.
- The release workflow explicitly selects Xcode 26.2 before invoking `Scripts/build-kotlin-app.sh`, so CI does not depend on the runner's default Command Line Tools selection.
- The release workflow cache key now tracks the prototype Gradle build files, bundle shell scripts, and `AppResources/Info.plist`, because those files define the shipped artifact.
- Local scripts prefer the current developer directory first and fall back to an installed Xcode when a command needs capabilities such as the Testing module.
- The current workflow uses ad-hoc signing plus an explicit `codesign --verify --deep --strict` check before packaging, which is enough for attaching artifacts to GitHub Releases.
- Developer ID signing and notarization can be added later with GitHub Actions secrets.