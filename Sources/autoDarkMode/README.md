# Legacy Swift Runtime Snapshot

This directory keeps the pre-cutover Swift runtime as a rollback and code archaeology reference.

- The production runtime path now lives in `prototypes/kmp-menubar-poc`.
- `Package.swift` no longer builds this directory into a release executable.
- `Scripts/validate.sh` and GitHub Actions validate the Kotlin runtime path instead.