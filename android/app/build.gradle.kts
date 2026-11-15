plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ua.parentcontrol"
    compileSdk = 34
    defaultConfig { applicationId = "ua.parentcontrol.agent"; minSdk = 26; targetSdk = 34; versionCode = 7; versionName = "1.6" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // Fixed debug-style key committed in keystore/debug.keystore so every build (CI included)
    // is signed the same way. Needed so the in-app updater can install new APKs over the old
    // one without the user having to uninstall first.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }
}
