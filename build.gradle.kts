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
            compilerOpts("-I${projectDir}/src/nativeInterop/cinterop")
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
