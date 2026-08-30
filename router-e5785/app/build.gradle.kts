plugins { id("com.android.application") }
android {
    namespace = "com.shahboun.e5785"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.shahboun.e5785"
        minSdk = 24
        targetSdk = 35
        versionCode = 10
        versionName = "1.7.0"
    }
    buildFeatures { buildConfig = true }
    buildTypes { release { isMinifyEnabled = false } }
}
