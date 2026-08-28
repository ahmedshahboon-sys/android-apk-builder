plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.shahboun.multi"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.shahboun.multi"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    buildFeatures { viewBinding = false }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation(fileTree("libs") { include("*.aar", "*.jar") })
}
