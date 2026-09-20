import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aripd.norda"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aripd.norda"
        minSdk = 26          // Android 8.0 — foreground service requirements
        // Android 15. From this level on, edge-to-edge drawing is mandatory;
        // the app leaves the window insets itself (MainActivity.applyInsets).
        targetSdk = 35
        // versionCode = MAJOR×10000 + MINOR×100 + PATCH (docs/MVP.md, 15.1)
        versionCode = 10800
        versionName = "1.8.0"
    }

    /**
     * Optional: a release APK signed with your own key.
     *
     * The values can come from two places. Locally, `keystore.properties` in
     * the root directory is read. In CI that file does not exist (neither the
     * key nor the passwords go into the repository); environment variables are
     * used there. If neither exists the block is skipped silently: the release
     * APK comes out unsigned, the debug APK is still built.
     *
     * The reason CI reads environment variables instead of a file is the
     * `.properties` format itself: a backslash is an escape character there,
     * and a colon or an equals sign terminates the key. A password containing
     * one of those silently turns into a different password when written to
     * the file, and signing breaks with "wrong password". An environment
     * variable involves no such interpretation.
     */
    val signingValues: Map<String, String>? = run {
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.exists()) {
            val props = Properties().apply { propsFile.inputStream().use { load(it) } }
            props.getProperty("storeFile")?.let { store ->
                mapOf(
                    "storeFile" to store,
                    "storePassword" to props.getProperty("storePassword").orEmpty(),
                    "keyAlias" to props.getProperty("keyAlias").orEmpty(),
                    "keyPassword" to props.getProperty("keyPassword").orEmpty()
                )
            }
        } else {
            System.getenv("NORDA_KEYSTORE_FILE")?.let { store ->
                mapOf(
                    "storeFile" to store,
                    "storePassword" to System.getenv("NORDA_KEYSTORE_PASSWORD").orEmpty(),
                    "keyAlias" to System.getenv("NORDA_KEY_ALIAS").orEmpty(),
                    "keyPassword" to System.getenv("NORDA_KEY_PASSWORD").orEmpty()
                )
            }
        }
    }

    if (signingValues != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(signingValues["storeFile"]!!)
                storePassword = signingValues["storePassword"]
                keyAlias = signingValues["keyAlias"]
                keyPassword = signingValues["keyPassword"]
                // Sign with all three schemes: some OEM ROMs reject APKs signed
                // with v2 only, saying "App not installed".
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8: drops unused code and resources and shrinks the rest.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingValues != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        // In AGP 8 BuildConfig generation is off by default; enabled only to
        // write the version name into the GPX report (F-6, MapActivity.buildReport).
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Deliberately no external dependencies on the app side: only the Android
// SDK + Kotlin stdlib. The one below is used only in tests and never enters the APK.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
