plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.shahboun.multi"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.shahboun.multi"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        manifestPlaceholders["debugActivityEnabled"] = "true"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = false
        buildConfig = true
    }
    buildTypes {
        debug {
            isDebuggable = true
            manifestPlaceholders["debugActivityEnabled"] = "true"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            manifestPlaceholders["debugActivityEnabled"] = "false"
        }
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation(fileTree("libs") { include("*.aar", "*.jar") })
}
