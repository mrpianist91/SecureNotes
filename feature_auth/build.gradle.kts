plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "com.securennotes.feature.auth" // Assicurarsi che il namespace sia univoco
    compileSdk = 34
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        /*L'abilitazione del ViewBinding è una scelta moderna e sicura per interagire con le view
         definite in XML. Elimina la necessità di findViewById, prevenendo NullPointerException
         e garantendo la type safety in fase di compilazione.*/
        viewBinding = true // Abilita ViewBinding per semplificare l'accesso alle view
    }
}
dependencies {
// Dipendenza verso il modulo :core per accedere a utility condivise
    implementation(project(":core"))
// Librerie AndroidX essenziali
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.constraintlayout)
// Componenti di Material Design per la UI
    implementation(libs.material)
// Jetpack Lifecycle per MVVM (ViewModel e LiveData)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
// Jetpack Navigation per la gestione dei fragment
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
// Jetpack Security per BiometricPrompt
    implementation(libs.androidx.biometric)
// Dipendenze per i test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}