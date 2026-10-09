plugins { kotlin("multiplatform"); id("com.android.library"); `maven-publish` }
kotlin {
    androidTarget { publishLibraryVariants("release"); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    iosArm64(); iosSimulatorArm64(); iosX64(); ohosArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":sound-core"))
            api("com.tencent.kuikly-open:core:2.28.0-2.0.21-ohos")
        }
        androidMain.dependencies {
            api("com.tencent.kuikly-open:core-render-android:2.28.0-2.0.21-ohos")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2-1.0.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2-1.0.0")
        }
        androidUnitTest.dependencies { implementation("org.robolectric:robolectric:4.16.1") }
    }
}
android {
    namespace = "io.github.gycrosskit.sound.kuikly"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
