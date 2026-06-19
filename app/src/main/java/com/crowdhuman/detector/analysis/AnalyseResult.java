package com.crowdhuman.detector.analysis;

import android.graphics.Bitmap;

public class AnalyseResult {
    public final long costTimeMs;
    public final Bitmap resultBitmap;
    public final int detectCount;
    public final int frameWidth;
    public final int frameHeight;
    public final float fps;
    public final int imageWidth;
    public final int imageHeight;

    public AnalyseResult(long costTimeMs, Bitmap resultBitmap, int detectCount,
                         int frameWidth, int frameHeight, float fps) {
        this(costTimeMs, resultBitmap, detectCount, frameWidth, frameHeight, fps, 0, 0);
    }

    public AnalyseResult(long costTimeMs, Bitmap resultBitmap, int detectCount,
                         int frameWidth, int frameHeight, float fps, int imageWidth, int imageHeight) {
        this.costTimeMs = costTimeMs;
        this.resultBitmap = resultBitmap;
        this.detectCount = detectCount;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.fps = fps;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }
}
