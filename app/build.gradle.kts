plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from the environment (the release workflow's secrets). Without it the
// release build is left unsigned, so local builds need no keystore.
val releaseKeystore = System.getenv("REVV_KEYSTORE_PATH")?.let(::file)?.takeIf { it.exists() }

// The developer's own CarPlay accessory identity in the gitignored .private/auth/offline-mfi/
// (identity.pk8 and certificate.p7b). Debug builds bundle it when it is there, so personal test
// installs need no import. Release builds never do: anyone with the APK could extract the key.
val identityFiles = listOf("identity.pk8", "certificate.p7b")
val debugIdentityAssets = rootProject.file(".private/auth").canonicalFile.takeIf { dir ->
    identityFiles.all { name -> dir.resolve("offline-mfi/$name").let { it.isFile && it.length() > 0 } }
}

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
            // R8 shrinks and obfuscates with the default keep rules. Play deobfuscates crashes with
            // the mapping AGP puts in the app bundle.
            optimization {
                enable = true
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
    // Licensed recordings, such as the engine's start-up, are kept out of the public repo in
    // .private/assets and built in when they're there.
    sourceSets.getByName("main").assets.directories.add(rootProject.file(".private/assets").path)
    debugIdentityAssets?.let { sourceSets.getByName("debug").assets.directories.add(it.path) }
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
    // CarPlay: the session engine the Auto screen hosts, and DiPlay's own screens behind it.
    implementation(project(":carplay:common"))
    // Android Auto: the session the Auto screen hosts, from DiAuto's stack.
    implementation(project(":androidauto"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// No credential file reaches an APK's assets but the debug identity above: a CarPlay identity in a
// published APK would be extractable, and so would a signing key or certificate left in a folder.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key", "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
tasks.register("rejectBundledCredentials") {
    group = "verification"
    description = "Reject credential files in APK assets, other than the debug build's own CarPlay identity."
    val filesToCheck = credentialAssets
    val allowed = debugIdentityAssets?.let { dir -> identityFiles.map { dir.resolve("offline-mfi/$it").canonicalFile } }.orEmpty().toSet()
    inputs.files(filesToCheck)
    doLast {
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets: ${unexpected.joinToString()}" }
    }
}
tasks.named("preBuild") { dependsOn("rejectBundledCredentials") }
