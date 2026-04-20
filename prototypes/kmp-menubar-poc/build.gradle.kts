plugins {
    kotlin("multiplatform") version "2.2.21"
}

repositories {
    mavenCentral()
}

kotlin {
    macosArm64 {
        compilations.getByName("main").cinterops.create("ambientlight") {
            defFile(project.file("src/nativeInterop/cinterop/ambientlight.def"))
            compilerOpts("-I/Users/gutugutu3030/github/autoDarkMode/prototypes/kmp-menubar-poc/src/nativeInterop/cinterop")
        }

        binaries.all {
            // linkerOpts(
            //     "-F/System/Library/PrivateFrameworks",
            //     "-framework", "BezelServices",
            // )
        }

        binaries {
            executable {
                baseName = "kmp-menubar-poc"
                entryPoint = "com.github.gutugutu3030.autodarkmode.prototype.main"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("../../kmp/src/commonMain/kotlin")
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }

        val macosArm64Main by getting {
            kotlin.srcDir("../../kmp/src/macosMain/kotlin")
        }

        val macosArm64Test by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
