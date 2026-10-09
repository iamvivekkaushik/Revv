// The CarPlay protocol stack from DiPlay / xcertplay (GPL-3.0): iAP2, AirPlay, MFi authentication,
// the USB and wireless transports, media decoding and the BYD cluster outputs. Pure library; the
// app and :carplay:common build the screens on it.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.shilapi.xcertplay.shared"
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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The I2C bridge to an MFi coprocessor and the hotspot radio probe are the only native code.
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.bouncycastle)
    implementation(libs.jmdns)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
