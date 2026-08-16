plugins { id("com.android.application") }
android {
    namespace = "com.shahboun.routeroptimizer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.shahboun.routeroptimizer"
        minSdk = 24
        targetSdk = 35
        versionCode = 16
        versionName = "0.11.4"
    }
    buildTypes { release { isMinifyEnabled = false } }
}
