plugins { kotlin("multiplatform"); `maven-publish` }
kotlin {
    ohosArm64()
    sourceSets.commonMain.dependencies {
        api(project(":sound-core"))
        implementation("com.tencent.kuikly-open:core:2.28.0-2.0.21-ohos")
    }
}
publishing { repositories.maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("maven")) } }
