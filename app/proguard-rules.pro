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

# sherpa-onnx (ASR + TTS): the JNI resolves Java classes by name
# (Java_com_k2fsa_sherpa_onnx_...), so neither the classes nor their members may
# be renamed or stripped.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# The TTS progress callback is looked up reflectively by exact signature
# invoke([F)Ljava/lang/Integer; — R8 must keep the specialized override that a
# desugared lambda would not have anyway (see LocalTtsEngine.SampleCallback).
-keep class com.localllm.android.voice.LocalTtsEngine$SampleCallback { *; }
-keepclasseswithmembers class * implements kotlin.jvm.functions.Function1 {
    java.lang.Integer invoke(float[]);
}

# Keep data models used for Room and JSON serialization
-keep class com.localllm.android.model.** { *; }
-keep class com.localllm.android.data.local.** { *; }
-keep class com.localllm.android.data.crypto.** { *; }

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
