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
        versionCode = 14
        versionName = "1.0.0-beta5-k90-shizukucompat"
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
