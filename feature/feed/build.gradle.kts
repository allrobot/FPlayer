plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.fplayer.feature.feed"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:player-api"))
    implementation(project(":core:script"))
    implementation(project(":core:device"))
    implementation(project(":core:index"))
    testImplementation(libs.junit4)
}
