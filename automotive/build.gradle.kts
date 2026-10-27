import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The upload key lives outside the repo and is never committed. Play App
// Signing holds the real signing key; this one only proves uploads come from
// us. If the properties file is absent the release build is simply unsigned,
// which is better than failing the whole configuration phase on a machine that
// has no business signing releases.
val keystoreProps = Properties().apply {
    val f = File(System.getProperty("user.home"), "Documents/keystores/firoadweather-keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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
        versionCode = 15
        versionName = "0.8.0"
        resourceConfigurations += listOf("en", "fi", "sv")

        // The app's own hostname, routed to the shared backend by Traefik the
        // same way weather.tallimedia.com is. Compiled in, so changing it after
        // release needs an app update — settle it before publishing.
        buildConfigField("String", "API_BASE", "\"https://roadweather.tallimedia.com\"")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("upload") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
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
