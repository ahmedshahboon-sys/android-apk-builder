plugins { id("com.android.application") }
android {
    namespace = "com.shahboun.e5785"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.shahboun.e5785"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.5.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
}
