import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from keystore.properties (gitignored) or, in CI, from environment
// variables. Nothing secret lives in the repo, and a missing config leaves the release build
// unsigned rather than failing the whole project.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "RECEIPTS_STORE_FILE")
val hasSigningConfig = releaseStoreFile != null &&
    rootProject.file(releaseStoreFile).exists()

android {
    namespace = "cc.rocketscience.receipts"
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.rocketscience.receipts"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "0.1.3"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "RECEIPTS_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RECEIPTS_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RECEIPTS_KEY_PASSWORD")
                // v3 adds key-rotation support; v1 is irrelevant above minSdk 24.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasSigningConfig) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "No release signing config found (keystore.properties missing). " +
                        "The release APK will be unsigned and cannot be installed."
                )
                null
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Tests run in a forked JVM, so a -D on the Gradle command line does not reach them.
        // Forwarded for ArchiveInteropTest's fixture regeneration; see fixtures/interop/README.md.
        unitTests.all {
            it.systemProperty(
                "rsreceipts.writeFixture",
                System.getProperty("rsreceipts.writeFixture") ?: "",
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view) {
        // We only use PreviewView. CameraController's video path drags in androidx.media3,
        // which declares ACCESS_NETWORK_STATE — a permission this app has no business holding.
        exclude(group = "androidx.camera", module = "camera-video")
    }
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.play.services.auth)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
}
