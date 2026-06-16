package com.example.yolov5tfliteandroid.detector;

public interface DetectorCallback {
    void onModelLoaded(String modelFile);
    void onModelError(String message);
}
