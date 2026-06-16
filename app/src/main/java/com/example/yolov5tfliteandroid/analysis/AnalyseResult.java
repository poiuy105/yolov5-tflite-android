package com.example.yolov5tfliteandroid.analysis;

import android.graphics.Bitmap;

public class AnalyseResult {
    public final long costTimeMs;
    public final Bitmap resultBitmap;
    public final int detectCount;
    public final int frameWidth;
    public final int frameHeight;

    public AnalyseResult(long costTimeMs, Bitmap resultBitmap, int detectCount, int frameWidth, int frameHeight) {
        this.costTimeMs = costTimeMs;
        this.resultBitmap = resultBitmap;
        this.detectCount = detectCount;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
    }
}
