package com.crowdhuman.detector.analysis;

import android.graphics.Bitmap;

public class AnalyseResult {
    public final long costTimeMs;
    public final Bitmap resultBitmap;
    public final int detectCount;
    public final int frameWidth;
    public final int frameHeight;
    public final float fps;

    public AnalyseResult(long costTimeMs, Bitmap resultBitmap, int detectCount,
                         int frameWidth, int frameHeight, float fps) {
        this.costTimeMs = costTimeMs;
        this.resultBitmap = resultBitmap;
        this.detectCount = detectCount;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.fps = fps;
    }
}
