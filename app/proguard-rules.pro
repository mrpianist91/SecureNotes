# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# proguard-rules.pro
-keep class com.scottyab.roobeer.** { *; }

# Suppress warnings for missing annotation classes referenced by Tink/Guava
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# App-level classes: Activity, SessionObserver, SettingsFragment
-keep class com.example.securenotes.MainActivity { *; }
-keep class com.example.securenotes.SessionObserver { *; }
-keep class com.example.securenotes.SettingsFragment { *; }

# SQLCipher: la libreria nativa (JNI) accede a campi Java per nome (es. mNativeHandle).
# R8 non deve rinominarli.
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }