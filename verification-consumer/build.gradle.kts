plugins {
    kotlin("multiplatform") version "2.2.21-1.0.0"
    id("com.android.library") version "8.10.1"
}
val soundVersion = providers.gradleProperty("soundVersion").orElse("0.1.6").get()
val verifyKuiklyNative = soundVersion !in setOf("0.1.1", "0.1.2", "0.1.3", "0.1.4", "0.1.5")
val kuiklyRenderFrameworkDir = providers.gradleProperty("kuiklyRenderFrameworkDir").orNull
kotlin {
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    iosArm64()
    iosX64 { binaries.framework {
        baseName = "SoundConsumer"
        if (verifyKuiklyNative) kuiklyRenderFrameworkDir?.let { linkerOpts("-F$it", "-framework", "OpenKuiklyIOSRender") }
        export("com.github.gycrosskit.sound:sound-core:$soundVersion")
        if (verifyKuiklyNative) export("com.github.gycrosskit.sound:sound-kuikly:$soundVersion")
    } }
    iosSimulatorArm64 { binaries.framework {
        baseName = "SoundConsumer"
        if (verifyKuiklyNative) kuiklyRenderFrameworkDir?.let { linkerOpts("-F$it", "-framework", "OpenKuiklyIOSRender") }
        export("com.github.gycrosskit.sound:sound-core:$soundVersion")
        if (verifyKuiklyNative) export("com.github.gycrosskit.sound:sound-kuikly:$soundVersion")
    } }
    ohosArm64()
    sourceSets {
        commonMain.dependencies { api("com.github.gycrosskit.sound:sound-core:$soundVersion") }
        if (verifyKuiklyNative) {
            commonMain.get().kotlin.srcDir("src/kuiklyNativeMain/kotlin")
            androidMain.get().kotlin.srcDir("src/kuiklyNativeAndroidMain/kotlin")
            iosMain.get().kotlin.srcDir("src/kuiklyNativeIosMain/kotlin")
            commonMain.dependencies { api("com.github.gycrosskit.sound:sound-kuikly:$soundVersion") }
        } else {
            ohosArm64Main.dependencies { implementation("com.github.gycrosskit.sound:sound-kuikly:$soundVersion") }
        }
    }
}
android {
    namespace = "io.github.gycrosskit.sound.consumer"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
