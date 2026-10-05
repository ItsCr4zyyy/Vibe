plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.vibe.music"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vibe.music"
        minSdk = 26
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0"
    }

    // yt-dlp + Python jsou nativní knihovny; každá ABI zvlášť drasticky zmenší APK.
    // Android Studio při „Run“ automaticky nasadí správný split pro zařízení.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            // Pro osobní použití stačí podepsat debug klíčem; vlastní keystore lze doplnit později.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Media3 označuje část API jako @UnstableApi (ResolvingDataSource apod.) – vědomě používáme
        disable += "UnsafeOptInUsageError"
    }

    packaging {
        // youtubedl-android potřebuje rozbalené .so soubory (extractNativeLibs)
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.cast)
    implementation(libs.appcompat)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.palette)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.reorderable)

    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
}
