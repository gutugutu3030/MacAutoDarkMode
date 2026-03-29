# .github

This directory contains GitHub-specific automation for the repository.

## Workflows

- workflows/release.yml: builds dist/autoDarkMode.app on a macOS runner when a version tag such as v0.1.1 is pushed, zips the app bundle, and publishes a GitHub Release with the artifact attached.

## Notes

- The current workflow uses ad-hoc signing from the build script, which is enough for attaching artifacts to GitHub Releases.
- Developer ID signing and notarization can be added later with GitHub Actions secrets.