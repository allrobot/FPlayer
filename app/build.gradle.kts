plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.fplayer.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.fplayer.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("debug").assets.srcDir(
        project(":core:player-mpv").file("src/androidTest/assets"),
    )
}

dependencies {
    implementation(project(":core:player-mpv"))
    implementation(project(":core:index"))
    implementation(project(":feature:feed"))
    implementation(project(":feature:library"))
    implementation(project(":feature:device"))
    implementation(project(":feature:settings"))

    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit4)
}
