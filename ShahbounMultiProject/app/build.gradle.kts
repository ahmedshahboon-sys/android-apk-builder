plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.shahboun.multi"
    compileSdk = 36

    val stableStore = System.getenv("SHAHBOUN_KEYSTORE")
    val stableStorePassword = System.getenv("SHAHBOUN_STORE_PASSWORD")
    val stableKeyAlias = System.getenv("SHAHBOUN_KEY_ALIAS")
    val stableKeyPassword = System.getenv("SHAHBOUN_KEY_PASSWORD")
    val hasStableSigning = !stableStore.isNullOrBlank() && !stableStorePassword.isNullOrBlank() && !stableKeyAlias.isNullOrBlank() && !stableKeyPassword.isNullOrBlank()

    defaultConfig {
        applicationId = "com.shahboun.multi"
        minSdk = 29
        targetSdk = 36
        versionCode = 26
        versionName = "0.6.1-deepdiag"
        manifestPlaceholders["debugActivityEnabled"] = "true"
    }

    if (hasStableSigning) {
        signingConfigs {
            create("shahbounStable") {
                storeFile = file(stableStore!!)
                storePassword = stableStorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
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
            if (hasStableSigning) signingConfig = signingConfigs.getByName("shahbounStable")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            manifestPlaceholders["debugActivityEnabled"] = "true"
            if (hasStableSigning) signingConfig = signingConfigs.getByName("shahbounStable")
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
