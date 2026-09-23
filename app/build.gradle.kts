plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hostu404.trilliontracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hostu404.trilliontracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            // R8 shrinking/obfuscation was off, meaning every release build
            // shipped full debug-level class/method names and no dead-code
            // removal — pure APK-size waste for a release artifact, and the
            // proguard-rules.pro already in this project (the kotlinx.serialization
            // keep rules) was written for exactly this being on. okhttp and
            // coil both ship their own R8 consumer rules, so no extra keep
            // rules are needed for either. Turning this on changes nothing
            // about how the app looks or behaves — only its release binary —
            // but it hasn't been verified against a real compiler in this
            // pass, so build and smoke-test one release APK before shipping
            // it, in case something needs an additional keep rule.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // So the header can show the real versionName above via
        // BuildConfig.VERSION_NAME instead of a second, easy-to-forget
        // hardcoded copy of it — one source of truth, in this same file.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.ui.tooling)
}
