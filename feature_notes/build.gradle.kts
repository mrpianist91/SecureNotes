plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.securenotes.feature_notes"
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
    buildFeatures {
        /*L'abilitazione del ViewBinding è una scelta moderna e sicura per interagire con le view
         definite in XML. Elimina la necessità di findViewById, prevenendo NullPointerException
         e garantendo la type safety in fase di compilazione.*/
        viewBinding = true // Abilita ViewBinding per semplificare l'accesso alle view
    }
}

dependencies {

    implementation(project(":core"))
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.fragment)
    implementation(libs.navigation.fragment)
    /*
    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)*/
}