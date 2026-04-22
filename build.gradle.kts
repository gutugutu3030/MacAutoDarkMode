plugins {
    kotlin("multiplatform") version "2.2.21"
    id("com.diffplug.spotless") version "6.25.0"
}

repositories {
    mavenCentral()
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint().editorConfigOverride(
            mapOf("ktlint_standard_property-naming" to "disabled"),
        )
    }

    kotlinGradle {
        target("*.gradle.kts")
        ktlint().editorConfigOverride(
            mapOf("ktlint_standard_property-naming" to "disabled"),
        )
    }
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}

kotlin {
    macosArm64 {
        compilations.getByName("main").cinterops.create("ambientlight") {
            defFile(project.file("src/nativeInterop/cinterop/ambientlight.def"))
            compilerOpts("-I$projectDir/src/nativeInterop/cinterop")
        }

        binaries {
            executable {
                baseName = "autoDarkMode"
                entryPoint = "com.github.gutugutu3030.autodarkmode.app.main"
            }
        }
    }

    macosX64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val macosArm64Test by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
