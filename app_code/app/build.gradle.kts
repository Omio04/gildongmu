plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.drivepet.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.drivepet.app"
        minSdk = 28
        targetSdk = 34   // Pleos Connect emulator image is API 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // the Pleos SDK classes are loaded by name at runtime: no shrinking
            optimization { enable = false }
            // prototype build: signed with the debug key so it installs as is
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")   // turn signal, steering angle, brake pedal, gear, odometer (read only)
    implementation("ai.pleos.playground:NaviHelper:3.0.5") // next turn, remaining distance and time (read only)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
}
