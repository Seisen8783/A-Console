plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noreal.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.noreal.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets.named("main") {
        java.srcDir("../../core/kotlin")
        assets.srcDir("../../core/assets")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.2.0")
}
