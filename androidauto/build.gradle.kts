// The Android Auto stack: a fork of DiAuto (shihabal3amri), itself from Open Headunit and Michael
// Reid's headunit (AGPL-3.0). The AAP protocol, its USB and wireless transports, the video and
// audio decoders, and the session service in their original packages, plus the embed/ package
// Revv's Auto screen hosts it through. DiAuto's own screens, root and Google Nearby paths, the
// libusb transport and the bundled FFmpeg decoder are not carried over; see
// docs/androidauto/THIRD_PARTY_NOTICES.md.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.andrerinas.openheadunit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            ndkBuild {
                arguments += "APP_PLATFORM=android-28"
            }
        }
    }
    // One native file: the read-only probe of a legacy driver's hotspot channel.
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    // The media session Android Auto's playback shows through, and its media-button receiver.
    implementation(libs.androidx.media)
    // The Android Auto protocol's messages.
    implementation(libs.protobuf.java)
    implementation(libs.kotlinx.coroutines.android)
    // Subclasses the hidden tethering callback that turns the head unit's hotspot on before API 30.
    implementation(libs.dexmaker)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
