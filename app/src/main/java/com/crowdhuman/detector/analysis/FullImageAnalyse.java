package com.crowdhuman.detector.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;

import com.crowdhuman.detector.detector.Yolov5TFLiteDetector;
import com.crowdhuman.detector.utils.ImageProcess;
import com.crowdhuman.detector.utils.Recognition;

import java.util.ArrayList;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class FullImageAnalyse implements ImageAnalysis.Analyzer {

    private final PreviewView previewView;
    private final int rotation;
    private final Yolov5TFLiteDetector detector;
    private final ImageProcess imageProcess;
    private final boolean isFrontCamera;
    private AnalyseCallback callback;
    private Disposable currentDisposable;
    private java.util.Set<Integer> enabledLabels;

    // FPS calculation - P1-6 FIX: volatile for thread visibility
    private volatile long lastFrameTime = 0;
    private volatile float currentFps = 0;
    private static final float FPS_ALPHA = 0.9f;

    // Pre-allocated Bitmap for letterbox (avoid per-frame allocation)
    private Bitmap reusedLetterboxBitmap;

    public FullImageAnalyse(Context context, PreviewView previewView, int rotation,
                           Yolov5TFLiteDetector detector, boolean isFrontCamera) {
        this.previewView = previewView;
        this.rotation = rotation;
        this.detector = detector;
        this.imageProcess = new ImageProcess();
        this.isFrontCamera = isFrontCamera;
    }

    public void setCallback(AnalyseCallback callback) {
        this.callback = callback;
    }

    public void setEnabledLabels(java.util.Set<Integer> labels) {
        this.enabledLabels = labels;
    }

    private void ensureLetterboxBitmap(int size) {
        if (reusedLetterboxBitmap == null || reusedLetterboxBitmap.getWidth() != size) {
            if (reusedLetterboxBitmap != null) reusedLetterboxBitmap.recycle();
            reusedLetterboxBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        }
    }

    public void dispose() {
        if (currentDisposable != null && !currentDisposable.isDisposed()) {
            currentDisposable.dispose();
            currentDisposable = null;
        }
        if (reusedLetterboxBitmap != null) {
            reusedLetterboxBitmap.recycle();
            reusedLetterboxBitmap = null;
        }
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        int previewHeight = previewView.getHeight();
        int previewWidth = previewView.getWidth();
        if (previewWidth <= 0 || previewHeight <= 0) { image.close(); return; }

        dispose();

        currentDisposable = Observable.create((ObservableEmitter<AnalyseResult> emitter) -> {
            long start = System.currentTimeMillis();

            // Per-stage timing variables
            long t0, t1, t2, t3, t4, t5;

            try {
                // === Stage 1: CameraX toBitmap ===
                t0 = System.currentTimeMillis();
                Bitmap cameraBitmap = image.toBitmap();
                int imgW = cameraBitmap.getWidth();
                int imgH = cameraBitmap.getHeight();

                // Safety: if CameraX returns an unreasonably large frame, downscale immediately.
                // This prevents CPU bottleneck on rotate/letterbox operations.
                final int MAX_CAMERA_DIM = 960;
                if (imgW > MAX_CAMERA_DIM || imgH > MAX_CAMERA_DIM) {
                    Log.w("FullImageAnalyse", "Camera frame too large: " + imgW + "x" + imgH
                            + ", downscaling to fit " + MAX_CAMERA_DIM + "px");
                    float downScale = Math.min((float) MAX_CAMERA_DIM / imgW, (float) MAX_CAMERA_DIM / imgH);
                    int newW = Math.max(1, (int) (imgW * downScale));
                    int newH = Math.max(1, (int) (imgH * downScale));
                    Bitmap scaled = Bitmap.createScaledBitmap(cameraBitmap, newW, newH, true);
                    cameraBitmap.recycle();
                    cameraBitmap = scaled;
                    imgW = newW;
                    imgH = newH;
                }

                t1 = System.currentTimeMillis();
                long timeToBitmapMs = t1 - t0;

                // === Stage 2+3: Combined rotate + letterbox in ONE drawBitmap ===
                // Instead of: camera -> rotateBitmap -> letterboxBitmap (2 draws)
                // We do: camera -> letterboxBitmap (1 draw with combined matrix)
                int modelSize = detector.getInputSize().getWidth();

                // Camera frame is landscape (e.g. 320x240), screen is portrait
                // We need to: rotate 90° + scale to fit 320x320 + add gray padding
                // Combined transform: rotate around center -> scale -> translate to fit
                float scale = Math.min(
                        modelSize / (float) imgH,  // after rotation, width becomes imgH
                        modelSize / (float) imgW); // after rotation, height becomes imgW
                int scaledW = (int) (imgH * scale);
                int scaledH = (int) (imgW * scale);
                int padX = (modelSize - scaledW) / 2;
                int padY = (modelSize - scaledH) / 2;

                ensureLetterboxBitmap(modelSize);
                Canvas letterCanvas = new Canvas(reusedLetterboxBitmap);
                letterCanvas.drawColor(Color.GRAY);

                // Combined matrix: rotate 90° around camera center, then scale, then translate
                Matrix combinedMatrix = new Matrix();
                // Step 1: move to center for rotation
                combinedMatrix.postTranslate(-imgW / 2f, -imgH / 2f);
                // Step 2: rotate 90°
                combinedMatrix.postRotate(90);
                // Step 3: scale
                combinedMatrix.postScale(scale, scale);
                // Step 4: move to the center of the padded area
                combinedMatrix.postTranslate(padX + scaledW / 2f, padY + scaledH / 2f);

                letterCanvas.drawBitmap(cameraBitmap, combinedMatrix, null);
                t2 = System.currentTimeMillis();
                long timeRotateLetterboxMs = t2 - t1;
                // For display compatibility, report rotate=0, letterbox=combined
                long timeRotateMs = 0;
                long timeLetterboxMs = timeRotateLetterboxMs;

                // === Stage 4: Detect (preprocess + inference + decode + NMS + label) ===
                if (enabledLabels != null) {
                    detector.setEnabledLabels(enabledLabels);
                }

                long[] detectTimings = new long[6];
                ArrayList<Recognition> recognitions = detector.detectZeroCopyWithTimings(reusedLetterboxBitmap, detectTimings);
                long timePreprocessMs = detectTimings[0];
                long timeInferenceMs = detectTimings[1];
                long timeDecodeMs = detectTimings[2];
                long timeNmsMs = detectTimings[3];
                long timeLabelMs = detectTimings[4];
                t3 = System.currentTimeMillis();

                // === Stage 5: Map coordinates ===
                // Step A: letterbox coords -> rotated frame coords (portrait, 240x320)
                // Remove padding, then undo scale
                Matrix modelToRotated = new Matrix();
                modelToRotated.postTranslate(-padX, -padY);
                modelToRotated.postScale(1f / scale, 1f / scale);

                // Step B: rotated frame coords -> preview coords
                // Rotated frame is portrait (rotW x rotH = imgH x imgW)
                int rotW = imgH;  // after 90° rotation, width = original height
                int rotH = imgW;  // after 90° rotation, height = original width
                double previewScale = Math.min(
                        previewHeight / (double) rotH,
                        previewWidth / (double) rotW);
                int renderW = (int) (previewScale * rotW);
                int renderH = (int) (previewScale * rotH);
                int offsetX = (previewWidth - renderW) / 2;
                int offsetY = (previewHeight - renderH) / 2;

                Matrix frameToPreview = new Matrix();
                frameToPreview.postScale((float) previewScale, (float) previewScale);

                // Apply both transforms to each detection box
                for (Recognition r : recognitions) {
                    RectF loc = r.getLocation();
                    modelToRotated.mapRect(loc);
                    frameToPreview.mapRect(loc);
                    r.setLocation(loc);
                }
                t4 = System.currentTimeMillis();
                long timeMapMs = t4 - t3;

                // === Stage 6: Overlay ===
                long timeOverlayMs = 0;

                // Debug info with full pipeline dimensions
                String debugInfo = String.format(
                        "cam=%dx%d rot=%dx%d model=%d scale=%.3f pad=%d,%d preview=%dx%d render=%dx%d offset=%d,%d",
                        imgW, imgH, rotW, rotH, modelSize,
                        scale, padX, padY, previewWidth, previewHeight,
                        renderW, renderH, offsetX, offsetY);
                Log.d("FullImageAnalyse", debugInfo);

                Log.i("PipelineTiming", String.format(
                        "toBitmap=%dms rotate+letterbox=%dms preprocess=%dms inference=%dms decode=%dms nms=%dms label=%dms map=%dms total=%dms",
                        timeToBitmapMs, timeRotateLetterboxMs,
                        timePreprocessMs, timeInferenceMs, timeDecodeMs,
                        timeNmsMs, timeLabelMs, timeMapMs,
                        System.currentTimeMillis() - start));

                RectF firstBox = recognitions.isEmpty() ? null : new RectF(recognitions.get(0).getLocation());

                // Calculate FPS
                long now = System.currentTimeMillis();
                if (lastFrameTime > 0) {
                    float instantFps = 1000f / (now - lastFrameTime);
                    currentFps = FPS_ALPHA * currentFps + (1 - FPS_ALPHA) * instantFps;
                }
                lastFrameTime = now;

                long totalTimeMs = now - start;

                emitter.onNext(new AnalyseResult(
                        totalTimeMs, timeInferenceMs, null, recognitions.size(),
                        previewWidth, previewHeight, currentFps, imgW, imgH, debugInfo, firstBox,
                        recognitions, frameToPreview, isFrontCamera, offsetX, offsetY, renderW, renderH,
                        modelSize,
                        timeToBitmapMs, timeRotateMs, timeLetterboxMs,
                        timePreprocessMs, timeInferenceMs, timeDecodeMs,
                        timeNmsMs, timeLabelMs, timeMapMs, timeOverlayMs));

            } catch (Exception e) {
                Log.e("FullImageAnalyse", "Error: " + e.getMessage(), e);
                emitter.onNext(new AnalyseResult(0, null, 0, previewWidth, previewHeight, currentFps));
            } finally {
                image.close();
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> { if (callback != null) callback.onResult(result); },
                        error -> { Log.e("FullImageAnalyse", "RxJava error: " + error.getMessage(), error); if (callback != null) callback.onError(error.getMessage()); }
                );
    }
}
