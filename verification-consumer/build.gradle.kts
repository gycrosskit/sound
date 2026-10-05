plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0"
    id("com.android.library") version "8.10.1"
}
kotlin {
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    iosArm64()
    iosX64()
    iosSimulatorArm64 { binaries.framework {
        baseName = "SoundConsumer"
        export("com.github.gycrosskit.sound:sound-core:0.1.2")
    } }
    ohosArm64()
    sourceSets {
        commonMain.dependencies { api("com.github.gycrosskit.sound:sound-core:0.1.2") }
        ohosArm64Main.dependencies { implementation("com.github.gycrosskit.sound:sound-kuikly:0.1.2") }
    }
}
android {
    namespace = "io.github.gycrosskit.sound.consumer"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
