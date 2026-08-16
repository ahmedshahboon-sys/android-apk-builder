plugins {
    id("com.android.application")
}

android {
    namespace = "com.shahboun.optimizer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shahboun.optimizer"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
