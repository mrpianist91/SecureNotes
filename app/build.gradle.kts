plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.securenotes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.securenotes"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    //Autenticazione biometrica
    implementation("androidx.biometric:biometric:1.1.0")
    //Jetpack security (EncryptedSharedPreferences, EncryptedFile)
    implementation("androidx.security:security-crypto:1.0.0")
    // WorkManager (libreria per scheduling di lavori in background)
    implementation("androidx.work:work-runtime:2.10.2")
    // Jetpack Security (per crittografia AES con Android Keystore)
    implementation("androidx.security:security-crypto:1.0.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}