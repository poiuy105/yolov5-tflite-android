package com.crowdhuman.detector.analysis;

public interface AnalyseCallback {
    void onResult(AnalyseResult result);
    void onError(String message);
}
