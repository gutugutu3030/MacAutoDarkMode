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
}
