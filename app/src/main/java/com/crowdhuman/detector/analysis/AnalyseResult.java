package com.crowdhuman.detector.analysis;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;

import com.crowdhuman.detector.utils.Recognition;

import java.util.ArrayList;

public class AnalyseResult {
    public final long costTimeMs;
    public final long inferenceTimeMs;
    public final Bitmap resultBitmap;  // Kept for screenshot compatibility, may be null
    public final int detectCount;
    public final int frameWidth;
    public final int frameHeight;
    public final float fps;
    public final int imageWidth;
    public final int imageHeight;

    // Overlay rendering data
    public final ArrayList<Recognition> recognitions;
    public final Matrix frameToPreviewTransform;
    public final boolean isFrontCamera;
    public final int offsetX;
    public final int offsetY;
    public final int renderWidth;
    public final int renderHeight;

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
        this(costTimeMs, inferenceTimeMs, resultBitmap, detectCount, frameWidth, frameHeight, fps,
                imageWidth, imageHeight, debugInfo, firstBox, null, null, false, 0, 0, 0, 0);
    }

    // Full constructor with overlay data
    public AnalyseResult(long costTimeMs, long inferenceTimeMs, Bitmap resultBitmap, int detectCount,
                         int frameWidth, int frameHeight, float fps, int imageWidth, int imageHeight,
                         String debugInfo, RectF firstBox,
                         ArrayList<Recognition> recognitions, Matrix frameToPreviewTransform,
                         boolean isFrontCamera, int offsetX, int offsetY, int renderWidth, int renderHeight) {
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
        this.recognitions = recognitions;
        this.frameToPreviewTransform = frameToPreviewTransform;
        this.isFrontCamera = isFrontCamera;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
    }
}
