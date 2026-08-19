plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.fplayer.core.script"
    compileSdk = 37

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:device"))
    api(project(":core:player-api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}
