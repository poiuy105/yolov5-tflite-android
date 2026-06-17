# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep class org.tensorflow.lite.nnapi.** { *; }

# CameraX
-keep class androidx.camera.** { *; }

# RxJava
-keep class io.reactivex.rxjava3.** { *; }

# Keep model classes (updated package name)
-keep class com.crowdhuman.detector.detector.** { *; }
-keep class com.crowdhuman.detector.utils.** { *; }
-keep class com.crowdhuman.detector.analysis.** { *; }

# Keep Recognition data class (updated package name)
-keepclassmembers class com.crowdhuman.detector.utils.Recognition {
    <fields>;
    <methods>;
}

# Preserve line number information for debugging
-keepattributes SourceFile,LineNumberTable
