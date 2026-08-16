plugins { id("com.android.application") }
android {
    namespace = "com.shahboun.routeroptimizer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.shahboun.routeroptimizer"
        minSdk = 24
        targetSdk = 35
        versionCode = 14
        versionName = "0.11.2"
    }
    buildTypes { release { isMinifyEnabled = false } }
}
