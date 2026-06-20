package com.crowdhuman.detector.analysis;

import android.graphics.Bitmap;
import android.graphics.RectF;

public class AnalyseResult {
    public final long costTimeMs;
    public final long inferenceTimeMs;
    public final Bitmap resultBitmap;
    public final int detectCount;
    public final int frameWidth;
    public final int frameHeight;
    public final float fps;
    public final int imageWidth;
    public final int imageHeight;

    // Debug info
    public final String debugInfo;
    public final RectF firstBox;

    public AnalyseResult(long costTimeMs, Bitmap resultBitmap, int detectCount,
                         int frameWidth, int frameHeight, float fps) {
        this(costTimeMs, 0, resultBitmap, detectCount, frameWidth, frameHeight, fps, 0, 0, "", null);
    }

    public AnalyseResult(long costTimeMs, long inferenceTimeMs, Bitmap resultBitmap, int detectCount,
                         int frameWidth, int frameHeight, float fps, int imageWidth, int imageHeight) {
        this(costTimeMs, inferenceTimeMs, resultBitmap, detectCount, frameWidth, frameHeight, fps, imageWidth, imageHeight, "", null);
    }

    public AnalyseResult(long costTimeMs, long inferenceTimeMs, Bitmap resultBitmap, int detectCount,
                         int frameWidth, int frameHeight, float fps, int imageWidth, int imageHeight,
                         String debugInfo, RectF firstBox) {
        this.costTimeMs = costTimeMs;
        this.inferenceTimeMs = inferenceTimeMs;
        this.resultBitmap = resultBitmap;
        this.detectCount = detectCount;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.fps = fps;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.debugInfo = debugInfo;
        this.firstBox = firstBox;
    }
}
