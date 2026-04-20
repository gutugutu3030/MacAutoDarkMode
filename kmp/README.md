# KMP Scaffold

This directory is the standalone Kotlin Multiplatform scaffold for the staged migration plan.

Commit 1 intentionally keeps it isolated from the Swift Package so it can fail independently without affecting the production build.

## Targets

- `macosArm64`
- `macosX64`

## Validation

```bash
cd kmp
./gradlew tasks
./gradlew build
```

At this stage there are no Kotlin sources yet. The goal is only to prove that the KMP toolchain, wrapper, and macOS targets are wired correctly.