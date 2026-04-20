# .github

This directory contains GitHub-specific automation for the repository.

## Workflows

- workflows/ci.yml: runs pull-request and manual validation on a macOS runner with separate `swift-test` and `kmp-test` jobs. `swift-test` executes `./Scripts/validate.sh`, while `kmp-test` executes `cd kmp && ./gradlew check`.
- workflows/release.yml: builds dist/autoDarkMode.app on a macOS runner when a version tag such as v0.1.1 is pushed, zips the app bundle, and publishes a GitHub Release with the artifact attached.

## Notes

- Both workflows now install Java 21, enable Gradle caching, and cache `~/.konan` so Kotlin/Native checks do not re-download the toolchain on every run.
- The release workflow explicitly selects Xcode 26.2 before invoking the build script, so CI does not depend on the runner's default Command Line Tools selection.
- Local scripts prefer the current developer directory first and fall back to an installed Xcode when a command needs capabilities such as the Testing module.
- The current workflow uses ad-hoc signing from the build script, which is enough for attaching artifacts to GitHub Releases.
- Developer ID signing and notarization can be added later with GitHub Actions secrets.