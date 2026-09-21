plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "fi.roadweather.app"
    // compileSdk matches targetSdk deliberately. Compiling against a newer
    // platform than we target buys nothing for a templated app and only widens
    // the gap to the car, which runs API 33.
    compileSdk = 35

    defaultConfig {
        // Permanent. Play treats a change here as a different app entirely.
        applicationId = "fi.roadweather.app"
        // Every AAOS car running the Car App Library is API 29 or higher.
        minSdk = 29
        // Play's target-API rule carves out automotive: new Wear OS and AAOS
        // apps need 35, where ordinary new apps need 36.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += listOf("en", "fi", "sv")

        // The app's own hostname, routed to the shared backend by Traefik the
        // same way weather.tallimedia.com is. Compiled in, so changing it after
        // release needs an app update — settle it before publishing.
        buildConfigField("String", "API_BASE", "\"https://roadweather.tallimedia.com\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // BuildConfig is off by default in AGP 8; the About screen shows the version.
    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.car.app.automotive)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
}
