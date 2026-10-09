plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)

    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "info.dourok.voicebot"
    compileSdk = 35

    defaultConfig {
        // DB-Robot's own id, so it installs beside (not over) both the stock aiboxplus app and an
        // upstream xiaozhi-android build. `namespace` above stays as it is: the Opus JNI symbols
        // are bound to the info.dourok.voicebot class names.
        applicationId = "vn.dbrobot.r1"
        minSdk = 22
        targetSdk = 35
        // One number per release, said in both places and by the git tag. Left at 1 / "1.0" for
        // months, which is how a fix measured on one build gets reported as still broken on
        // another -- nothing on the device could say which binary was running.
        versionCode = 5
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags  += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true              // R8: tối ưu + thu gọn code
            isShrinkResources = false           // tắt -> tránh xén nhầm res (Compose)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Ký bằng debug key -> cài đè (pm install -r) lên bản .dev hiện tại được, khỏi gỡ.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        prefab = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.okhttp)
    implementation(libs.opus.v131)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)

    implementation(libs.paho.mqtt.android)

    // Embedded HTTP server for the on-device control panel (EQ / settings / chat).
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Standard TFLite runtime for the "Mai ơi" wake engine's classifier (no TFLite Micro needed —
    // the trained model runs fine as a standard streaming/stateful TFLite graph).
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    testImplementation(kotlin("test"))
    testImplementation(libs.json)
}
kapt {
    correctErrorTypes = true
}