import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.library)
}

val robolectricSdk by configurations.creating
val stagedRobolectricSdk = layout.buildDirectory.dir("robolectric-sdk")
val stageRobolectricSdk = tasks.register<Sync>("stageRobolectricSdk") {
    from(robolectricSdk)
    into(stagedRobolectricSdk)
}

android {
    namespace = "io.github.fplayer.core.index"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
                arguments["room.incremental"] = "true"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.dependsOn(stageRobolectricSdk)
            it.systemProperty(
                "robolectric.dependency.dir",
                stagedRobolectricSdk.get().asFile.absolutePath,
            )
        }
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.room.runtime)
    implementation(libs.smbj)
    annotationProcessor(libs.room.compiler)
    testAnnotationProcessor(libs.room.compiler)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    robolectricSdk(libs.robolectric.android.sdk)
}
