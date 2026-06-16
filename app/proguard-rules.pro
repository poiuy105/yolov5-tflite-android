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

# Keep model classes
-keep class com.example.yolov5tfliteandroid.detector.** { *; }
-keep class com.example.yolov5tfliteandroid.utils.** { *; }
-keep class com.example.yolov5tfliteandroid.analysis.** { *; }

# Keep Recognition data class
-keepclassmembers class com.example.yolov5tfliteandroid.utils.Recognition {
    <fields>;
    <methods>;
}

# Preserve line number information for debugging
-keepattributes SourceFile,LineNumberTable
