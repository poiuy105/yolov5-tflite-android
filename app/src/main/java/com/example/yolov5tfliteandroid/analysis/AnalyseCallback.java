package com.example.yolov5tfliteandroid.analysis;

public interface AnalyseCallback {
    void onResult(AnalyseResult result);
    void onError(String message);
}
