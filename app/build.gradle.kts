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
    signingConfigs {
        create("release") {
            // Leggiamo le variabili d'ambiente di GitHub
            val ksFile = System.getenv("KEYSTORE_FILE")
            val ksPassword = System.getenv("KEYSTORE_PASSWORD")
            val ksAlias = System.getenv("KEY_ALIAS")
            val ksKeyPassword = System.getenv("KEY_PASSWORD")

            // Applichiamo la configurazione solo se le variabili sono presenti (cioè in CI)
            if (ksFile != null && ksPassword != null && ksAlias != null) {
                storeFile = file(ksFile)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Aggiungi shrinkResources come da specifiche
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Collega la configurazione di firma
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    /** Abilita la classe BuildConfig per questo modulo */
    buildFeatures {
        buildConfig = true
    }
}

dependencies {

    implementation(project(":core"))
    implementation(project(":feature_auth"))
    implementation(project(":feature_notes"))
    implementation(project(":feature_vault"))
    implementation(project(":backup_worker"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.work.runtime)
    //implementation(libs.activity)
    //implementation(libs.constraintlayout)

    //Autenticazione biometrica
    //implementation("androidx.biometric:biometric:1.1.0")
    //Jetpack security (EncryptedSharedPreferences, EncryptedFile)
    //implementation("androidx.security:security-crypto:1.0.0")
    // WorkManager (libreria per scheduling di lavori in background)
    //implementation("androidx.work:work-runtime:2.10.2")
    // Jetpack Security (per crittografia AES con Android Keystore)
    //implementation("androidx.security:security-crypto:1.0.0")

    //Test
    /*
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)*/
}