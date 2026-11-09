plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mlapps.mlstems"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mlapps.mlstems"
        minSdk = 29
        targetSdk = 34
        versionCode = 6
        versionName = "0.6"

        // Solo telefoni e tablet a 64 bit: toglie le librerie x86 e a 32 bit di ONNX Runtime (~50 MB)
        ndk { abiFilters += "arm64-v8a" }
    }

    // "standard" = app normale; "npu" = build sperimentale con Qualcomm QNN (installabile accanto alla normale)
    flavorDimensions += "ep"
    productFlavors {
        create("standard") { dimension = "ep" }
        create("npu") {
            dimension = "ep"
            applicationIdSuffix = ".npu"
            versionNameSuffix = "-npu"
        }
    }
    // Le librerie Hexagon devono essere file veri su disco: solo per la build NPU (-PlegacyJni=true)
    packaging { jniLibs { useLegacyPackaging = providers.gradleProperty("legacyJni").isPresent } }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(project(":core"))
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.github.NorthernCaptain:TAndroidLame:1.1")
    implementation("com.github.marco90-cyber:MLTheme:1.0.0")
    "standardImplementation"("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    "npuImplementation"("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.24.1")
}
