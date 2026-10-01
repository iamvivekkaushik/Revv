plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from the environment (the release workflow's secrets). Without it the
// release build is left unsigned, so local builds need no keystore.
val releaseKeystore = System.getenv("REVV_KEYSTORE_PATH")?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "com.vivekkaushik.revv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.vivekkaushik.revv"
        minSdk = 28
        targetSdk = 37
        // The release workflow sets these from the pushed tag, e.g. v1.2.3 is 1.2.3 and 1002003.
        versionCode = providers.gradleProperty("revv.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("revv.versionName").orNull ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("REVV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("REVV_KEY_ALIAS")
                keyPassword = System.getenv("REVV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.maplibre.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
