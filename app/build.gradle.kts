import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// AAOS "android.car" API stubs ship inside the SDK (platforms/<api>/optional/android.car.jar).
// The real classes come from the Pleos Connect system image at runtime.
val sdkDir: String = Properties().apply {
    rootProject.file("local.properties").inputStream().use { load(it) }
}.getProperty("sdk.dir")

android {
    namespace = "com.gildongmu.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.gildongmu.app"
        minSdk = 28
        targetSdk = 34   // Pleos Connect emulator image is API 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // no shrinking: the Pleos SDK classes are loaded by name at runtime
            optimization {
                enable = false
            }
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
    compileOnly(files("$sdkDir/platforms/android-37.0/optional/android.car.jar"))
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}