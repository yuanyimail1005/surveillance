# Keep Android components
-keep public class android.** { *; }
-keep public class androidx.** { *; }

# Keep app code
-keep class com.example.pisurveillance.** { *; }

# Keep model classes for JSON serialization
-keep class com.example.pisurveillance.models.** { *; }

# Keep API service interface
-keep interface com.example.pisurveillance.api.SurveillanceService { *; }

# Gson configuration
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# WebSocket
-keep class com.neovisionaries.** { *; }
-dontwarn com.neovisionaries.**

# Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Timber
-keep class timber.log.** { *; }
-dontwarn timber.log.**

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Optimization options
-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
