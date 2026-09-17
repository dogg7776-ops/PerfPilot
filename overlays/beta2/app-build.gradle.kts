plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.oai.perfpilot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oai.perfpilot"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "2.0.0-alpha1-original-compatible"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
