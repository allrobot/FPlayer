import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.library)
}

val nativeRoot = rootProject.file("native-build/out-arm64")
val stagedJniLibs = layout.projectDirectory.dir("src/main/jniLibs")
val stageNativeLibs = tasks.register<Sync>("stageNativeLibs") {
    from(nativeRoot.resolve("lib")) {
        include("*.so")
    }
    into(stagedJniLibs.dir("arm64-v8a"))
}

android {
    namespace = "io.github.fplayer.player.mpv"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                arguments += "-DFPLAYER_NATIVE_ROOT=${nativeRoot.absolutePath}"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild").configure { dependsOn(stageNativeLibs) }

dependencies {
    api(project(":core:player-api"))
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
