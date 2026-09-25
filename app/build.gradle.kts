plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") version "2.3.10"
}

android {
    namespace = "com.marksy.os"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.marksy.os"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Non-secret endpoint configuration only. Integration credentials are provisioned
        // at runtime and encrypted with Android Keystore.
        val apiBaseUrl = providers.environmentVariable("MARKSY_API_BASE_URL").orNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "https://marksy.indoulia.com/api/v1"
        buildConfigField("String", "MARKSY_API_BASE_URL", "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    testImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // QR scanning for gateway provisioning (self-contained capture activity).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    // EPIC-019: Gemini Nano through Android AICore; runs on-device, no model file ships in the APK.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    // Upstox market-data WebSocket (the platform has no WebSocket client).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json implementation for local unit tests (the android.jar stub throws "not mocked").
    testImplementation("org.json:json:20240303")
    // Robolectric provides real android.os.Bundle / ComponentName for local unit tests.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
