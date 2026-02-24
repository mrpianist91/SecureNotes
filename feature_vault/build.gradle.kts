plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.securenotes.feature_vault"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    // Necessario per generare FragmentVaultBinding e ItemVaultFileBinding usati nel codice Java
    buildFeatures {
        viewBinding = true
    }

}

dependencies {

    // Modulo Core: Contiene DB, Entità (VaultFile) e Utility
    implementation(project(":core"))

    // UI Components & Material Design 3
    implementation(libs.material)
    implementation(libs.androidx.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.androidx.core) // Utility di base (ContextCompat, ecc.)

    // Architecture Components (MVVM)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)

    // Navigation Component (per navigare dentro/fuori dal Vault)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    // Gestione file cifrati (EncryptedFile)
    implementation(libs.androidx.security.crypto)
    // Autenticazione biometrica (BiometricPrompt) per il Gatekeeper
    implementation(libs.androidx.biometric)
}