plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.fplayer.core.recommendation"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:index"))
    testImplementation(libs.junit4)
}
