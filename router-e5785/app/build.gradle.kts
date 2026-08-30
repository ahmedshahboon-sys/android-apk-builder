plugins { id("com.android.application") }
android {
    namespace = "com.shahboun.e5785"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.shahboun.e5785"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.6.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
}
