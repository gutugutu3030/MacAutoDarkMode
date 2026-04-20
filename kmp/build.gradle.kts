plugins {
    kotlin("multiplatform") version "2.2.21"
}

repositories {
    mavenCentral()
}

kotlin {
    macosArm64()
    macosX64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

}