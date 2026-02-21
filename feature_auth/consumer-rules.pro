# Fragments – Navigation Component instantiates by class name via reflection
-keep class com.example.securenotes.feature_auth.SplashFragment { *; }
-keep class com.example.securenotes.feature_auth.OnBoardingFragment { *; }
-keep class com.example.securenotes.feature_auth.LoginFragment { *; }
-keep class com.example.securenotes.feature_auth.CreatePinFragment { *; }

# ViewModel – instantiated by ViewModelProvider (uses class as key)
#-keep class com.example.securenotes.feature_auth.AuthViewModel { *; }

# BiometricHelper singleton and all inner types (Operation, BiometricAuthListener, etc.)
#-keep class com.example.securenotes.feature_auth.BiometricHelper { *; }
#-keep class com.example.securenotes.feature_auth.BiometricHelper$* { *; }

# AuthListener – interface implemented by MainActivity
#-keep interface com.example.securenotes.feature_auth.AuthListener { *; }
