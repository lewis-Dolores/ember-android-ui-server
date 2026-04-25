plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ember.server"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ember.server"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
}
