plugins { id("com.android.application") }
android {
    namespace = "com.shahboun.e5785"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.shahboun.e5785"
        minSdk = 24
        targetSdk = 35
        versionCode = 11
        versionName = "1.8.0"
    }
    buildFeatures { buildConfig = true }
    buildTypes { release { isMinifyEnabled = false } }
}
