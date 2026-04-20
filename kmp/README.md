# KMP Scaffold

This directory is the standalone Kotlin Multiplatform scaffold for the staged migration plan.

Commit 1 intentionally kept it isolated from the Swift Package so it could fail independently without affecting the production build.

Commit 2 adds the first shared logic slice in commonMain:

- SwitchMode rawValue compatibility for persisted settings
- KeyValueStore abstraction for future UserDefaults bridging
- SettingsStoreLogic and SettingsStoreState for threshold clamping and legacy-key migration

## Targets

- `macosArm64`
- `macosX64`

The scaffold targets macOS 13+ by repository policy, but it does not currently force a deployment target flag during Kotlin/Native linking.

## Validation

```bash
cd kmp
./gradlew tasks
./gradlew compileKotlinMacosArm64
./gradlew macosArm64Test
./gradlew build
```

At this stage the project still builds independently from the Swift Package, but it now contains the first shared settings logic and matching commonTest coverage that will later be bridged back into the production app.