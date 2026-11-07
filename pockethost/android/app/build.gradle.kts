plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.pockethost"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.pockethost"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0"
        // Versions of the bundled engines (passed by CI, see pockethost/native/build.sh)
        buildConfigField("String", "OLLAMA_VERSION", "\"${project.findProperty("ollamaVersion") ?: "dev"}\"")
        buildConfigField("String", "CLOUDFLARED_VERSION", "\"${project.findProperty("cloudflaredVersion") ?: "dev"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so CI builds install directly.
            // Use your own keystore before publishing to the Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // The engines (libollama.so, libcloudflared.so) are executables placed in
    // src/main/jniLibs/arm64-v8a by pockethost/native/build.sh. They must be
    // extracted to the native lib dir, the only place Android lets apps exec from.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
