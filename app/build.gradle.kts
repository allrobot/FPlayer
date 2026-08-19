plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Release inputs are deliberately provider-backed so secrets never enter the
// project files or the configuration cache as literal defaults.
val releaseInput = fun(name: String): Provider<String> =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name))

val releaseStoreFile = releaseInput("FPLAYER_RELEASE_STORE_FILE")
val releaseStorePassword = releaseInput("FPLAYER_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseInput("FPLAYER_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseInput("FPLAYER_RELEASE_KEY_PASSWORD")
val releaseApplicationId = releaseInput("FPLAYER_APPLICATION_ID")
val releaseVersionCode = releaseInput("FPLAYER_VERSION_CODE")
val releaseVersionName = releaseInput("FPLAYER_VERSION_NAME")

val developmentApplicationId = "io.github.fplayer.android"
val developmentVersionCode = 1
val developmentVersionName = "0.1.0-dev"

android {
    namespace = "io.github.fplayer.android"
    compileSdk = 37

    defaultConfig {
        applicationId = developmentApplicationId
        minSdk = 26
        targetSdk = 37
        versionCode = developmentVersionCode
        versionName = developmentVersionName
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile.isPresent) storeFile = project.file(releaseStoreFile.get())
            if (releaseStorePassword.isPresent) storePassword = releaseStorePassword.get()
            if (releaseKeyAlias.isPresent) keyAlias = releaseKeyAlias.get()
            if (releaseKeyPassword.isPresent) keyPassword = releaseKeyPassword.get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.applicationId.set(releaseApplicationId.orElse(developmentApplicationId))
        variant.outputs.forEach { output ->
            output.versionCode.set(
                releaseVersionCode.map { it.toInt() }.orElse(developmentVersionCode),
            )
            output.versionName.set(releaseVersionName.orElse(developmentVersionName))
        }
    }
}

val validateReleaseConfiguration = tasks.register("validateReleaseConfiguration") {
    group = "verification"
    description = "Fails unless explicit production identity and signing inputs are present."
    doLast {
        val missing = buildList {
            if (!releaseApplicationId.isPresent) add("FPLAYER_APPLICATION_ID")
            if (!releaseVersionCode.isPresent) add("FPLAYER_VERSION_CODE")
            if (!releaseVersionName.isPresent) add("FPLAYER_VERSION_NAME")
            if (!releaseStoreFile.isPresent) add("FPLAYER_RELEASE_STORE_FILE")
            if (!releaseStorePassword.isPresent) add("FPLAYER_RELEASE_STORE_PASSWORD")
            if (!releaseKeyAlias.isPresent) add("FPLAYER_RELEASE_KEY_ALIAS")
            if (!releaseKeyPassword.isPresent) add("FPLAYER_RELEASE_KEY_PASSWORD")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release build requires explicit production identity/signing inputs: ${missing.joinToString(", ")}. " +
                    "Provide them through Gradle properties or environment; debug signing is never used for release.",
            )
        }

        val applicationId = releaseApplicationId.get()
        require(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(applicationId)) {
            "FPLAYER_APPLICATION_ID is not a valid application id"
        }
        val versionCode = releaseVersionCode.get().toIntOrNull()
        require(versionCode != null && versionCode > 0) {
            "FPLAYER_VERSION_CODE must be a positive integer"
        }
        require(releaseVersionName.get().isNotBlank() &&
            !releaseVersionName.get().contains("dev", ignoreCase = true)) {
            "FPLAYER_VERSION_NAME must be a non-development release value"
        }
        val keyFile = project.file(releaseStoreFile.get())
        require(keyFile.isFile) {
            "FPLAYER_RELEASE_STORE_FILE must point to an existing keystore outside generated build output"
        }
    }
}

tasks.configureEach {
    val producesReleaseArtifact = name.matches(
        Regex("^(assemble|bundle|package|sign|install|publish).*Release.*$", RegexOption.IGNORE_CASE),
    )
    if ((name == "preReleaseBuild" || producesReleaseArtifact) &&
        name != validateReleaseConfiguration.name) {
        dependsOn(validateReleaseConfiguration)
    }
}

dependencies {
    implementation(project(":core:player-mpv"))
    implementation(project(":core:index"))
    implementation(project(":core:script"))
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
