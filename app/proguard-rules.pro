# ProGuard / R8 rules for LocalLLM Android

# Keep JNI native methods across all classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve line numbers and source files for meaningful crash stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep native inference engine wrappers
-keep class org.nehuatl.llamacpp.** { *; }
-dontwarn org.nehuatl.llamacpp.**

-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Keep data models used for Room and JSON serialization
-keep class com.example.model.** { *; }
-keep class com.example.data.local.** { *; }
-keep class com.example.data.crypto.** { *; }

# Keep Room generated implementations
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep Moshi JSON adapters
-keepattributes *JavascriptInterface*
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
