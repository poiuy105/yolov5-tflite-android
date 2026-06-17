package com.crowdhuman.detector.detector;

public interface DetectorCallback {
    void onModelLoaded(String modelFile);
    void onModelError(String message);
}
