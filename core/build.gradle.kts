plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.securenotes.core"
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
}

dependencies {
    //Per room usiamo "api" in modo che i moduli che dipendono da :core (come :feature_notes) possano vedere anche le classi di Room
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.lifecycle.livedata)
    annotationProcessor(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.security.crypto)

    // Dipendenze per Test Strumentali (AndroidTest)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Truth rende le asserzioni più leggibili (es. assertThat(...).doesNotContain(...))
    androidTestImplementation("com.google.truth:truth:1.1.3")
}