// The CarPlay session engine Revv's Auto screen hosts (embed/), with its settings, media session,
// foreground connection service and diagnostics. DiPlay / xcertplay, GPL-3.0.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":carplay:shared"))
    implementation(libs.androidx.annotation)
    // Reaches the hidden TetheringManager API that turns the head unit's hotspot on (HotspotSwitch).
    implementation(libs.hiddenapibypass)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
