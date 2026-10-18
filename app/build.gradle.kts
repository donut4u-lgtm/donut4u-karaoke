plugins {
    id("com.android.application")
}

val youtubeApiKey = providers.gradleProperty("YOUTUBE_API_KEY")
    .orElse(providers.environmentVariable("YOUTUBE_API_KEY"))
    .getOrElse("")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.donut4u.karaoke"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.donut4u.karaoke"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "YOUTUBE_API_KEY",
            "\"${youtubeApiKey}\""
        )
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")
}
