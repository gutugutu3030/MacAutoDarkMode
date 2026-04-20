import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform") version "2.2.21"
}

val autoDarkModeKmpXCFramework = XCFramework("AutoDarkModeKMP")

repositories {
    mavenCentral()
}

kotlin {
    val macosArm64Target = macosArm64()
    val macosX64Target = macosX64()

    listOf(macosArm64Target, macosX64Target).forEach { target ->
        target.binaries.framework {
            baseName = "AutoDarkModeKMP"
            isStatic = true
            autoDarkModeKmpXCFramework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

}