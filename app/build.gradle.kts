
plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.donut4u.karaoke"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.donut4u.karaoke"
        minSdk = 24
        targetSdk = 35

        versionCode = 1
        versionName = "1.0"

        val youtubeApiKey =
            (project.findProperty("YOUTUBE_API_KEY") as String?)
                ?: System.getenv("YOUTUBE_API_KEY")
                ?: ""

        buildConfigField(
            "String",
            "YOUTUBE_API_KEY",
            "\"${youtubeApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                System.getenv("DONUT4U_KEYSTORE_PATH")
                    ?: error("DONUT4U_KEYSTORE_PATH is missing")
            )

            storePassword =
                System.getenv("DONUT4U_KEYSTORE_PASSWORD")
                    ?: error("DONUT4U_KEYSTORE_PASSWORD is missing")

            keyAlias =
                System.getenv("DONUT4U_KEY_ALIAS")
                    ?: error("DONUT4U_KEY_ALIAS is missing")

            keyPassword =
                System.getenv("DONUT4U_KEY_PASSWORD")
                    ?: error("DONUT4U_KEY_PASSWORD is missing")

            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation(platform("com.google.firebase:firebase-bom:35.0.2"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")

    implementation("com.google.android.gms:play-services-ads:25.5.0")

    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")
}
