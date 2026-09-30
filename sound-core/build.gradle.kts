plugins { kotlin("multiplatform"); id("com.android.library"); `maven-publish` }
kotlin {
    androidTarget { publishLibraryVariants("release"); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    ohosArm64()
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2-1.0.0")
            implementation("io.ktor:ktor-http:3.3.3-1.1.0-04")
        }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.3.3-1.1.0-04") }
        commonTest.dependencies { implementation(kotlin("test")) }
        iosTest.dependencies {
            implementation("io.ktor:ktor-client-mock:3.3.3-1.1.0-04")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2-1.0.0")
        }
    }
}
android {
    namespace = "io.github.gycrosskit.sound"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
