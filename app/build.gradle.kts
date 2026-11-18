plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.chessreviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.chessreviewer"
        minSdk = 26
        targetSdk = 34
        // Goes up with every GitHub build so new releases install over old ones.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.3"
    }

    // Release signing: the workflow supplies these from GitHub secrets.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    // Only used if your folder is still misspelled "scr" instead of "src".
    if (file("scr/main/AndroidManifest.xml").exists()) {
        sourceSets {
            getByName("main") {
                manifest.srcFile("scr/main/AndroidManifest.xml")
                java.srcDirs("scr/main/java")
                aidl.srcDirs("scr/main/aidl")
                res.srcDirs("scr/main/res")
            }
        }
    }

    buildFeatures {
        // The Shizuku user service talks to the app over AIDL.
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shizuku: runs the screenshot code in a shell-privileged process on a phone without root.
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
}
