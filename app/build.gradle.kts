plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

val youtubeApiKey = providers.gradleProperty("YOUTUBE_API_KEY")
    .orElse(providers.environmentVariable("YOUTUBE_API_KEY"))
    .getOrElse("")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

val keystorePath = System.getenv("DONUT4U_KEYSTORE_PATH") ?: ""
val keystorePassword = System.getenv("DONUT4U_KEYSTORE_PASSWORD") ?: ""
val keyAliasValue = System.getenv("DONUT4U_KEY_ALIAS") ?: ""
val keyPasswordValue = System.getenv("DONUT4U_KEY_PASSWORD") ?: ""

android {
    namespace = "com.donut4u.karaoke"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.donut4u.karaoke"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "YOUTUBE_API_KEY",
            "\"${youtubeApiKey}\""
        )
    }

    signingConfigs {
        create("release") {
            if (keystorePath.isNotBlank()) {
                storeFile = file(keystorePath)
            }
            storePassword = keystorePassword
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")

    implementation(
        platform("com.google.firebase:firebase-bom:35.0.2")
    )
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")

    // Google AdMob SDK
    implementation("com.google.android.gms:play-services-ads:25.5.0")
}
