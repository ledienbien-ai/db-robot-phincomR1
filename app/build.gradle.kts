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
        versionCode = 13
        versionName = "1.5.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags  += "-std=c++17"
            }
        }
    }

    // One fixed key for every build, wherever it is built. The default debug key is generated per
    // machine, and a CI runner is a new machine each time: every APK came out signed differently,
    // so `pm install -r` over the previous one failed with INSTALL_FAILED_UPDATE_INCOMPATIBLE and
    // the only way forward was an uninstall that wiped the settings and the device's identity.
    // This key is committed on purpose and is NOT a secret -- it only keeps updates installable.
    signingConfigs {
        create("dbrobot") {
            storeFile = file("dbrobot.keystore")
            storePassword = "android"
            keyAlias = "dbrobot"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dbrobot")
        }
        release {
            isMinifyEnabled = true              // R8: tối ưu + thu gọn code
            isShrinkResources = false           // tắt -> tránh xén nhầm res (Compose)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Cùng một khoá với debug -> bản nào cũng cài đè (pm install -r) lên bản nào được.
            signingConfig = signingConfigs.getByName("dbrobot")
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