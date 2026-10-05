plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0"
    id("com.android.library") version "8.10.1"
}
val soundVersion = providers.gradleProperty("soundVersion").orElse("0.1.3").get()
kotlin {
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    iosArm64()
    iosX64 { binaries.framework {
        baseName = "SoundConsumer"
        export("com.github.gycrosskit.sound:sound-core:$soundVersion")
    } }
    iosSimulatorArm64 { binaries.framework {
        baseName = "SoundConsumer"
        export("com.github.gycrosskit.sound:sound-core:$soundVersion")
    } }
    ohosArm64()
    sourceSets {
        commonMain.dependencies { api("com.github.gycrosskit.sound:sound-core:$soundVersion") }
        ohosArm64Main.dependencies { implementation("com.github.gycrosskit.sound:sound-kuikly:$soundVersion") }
    }
}
android {
    namespace = "io.github.gycrosskit.sound.consumer"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
